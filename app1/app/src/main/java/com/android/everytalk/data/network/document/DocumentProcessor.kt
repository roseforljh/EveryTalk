package com.android.everytalk.data.network

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Writer
import java.util.zip.ZipInputStream

/**
 * 文档处理模块
 * 用于在直连模式下提取文档内容
 *
 * 目前支持：
 * - 纯文本文件 (txt, md, json, xml, html, etc.)
 * - PDF (via PdfBox-Android)
 * - DOCX (via XML parsing)
 * - XLSX (via XML parsing, partial support)
 */
object DocumentProcessor {
    private const val TAG = "DocumentProcessor"
    internal const val MAX_INPUT_BYTES = 20L * 1024L * 1024L
    internal const val MAX_OUTPUT_CHARS = 200_000
    private const val MAX_ZIP_ENTRY_BYTES = 8L * 1024L * 1024L
    private const val MAX_ZIP_ENTRIES = 1_000
    @Volatile
    private var isPdfBoxInitialized = false

    // 支持的纯文本 MIME 类型
    private val TEXT_MIME_TYPES = setOf(
        "text/plain", "text/html", "text/csv", "text/markdown", "text/x-markdown",
        "application/json", "text/xml", "application/xml", "text/rtf", "application/rtf",
        "application/x-javascript", "text/javascript", "text/css", "application/x-python",
        "text/x-python", "application/x-yaml", "text/yaml", "text/x-yaml",
        "application/toml", "text/x-toml", "application/x-sh", "text/x-shellscript",
        "text/x-java-source", "text/x-c", "text/x-c++", "text/x-csharp"
    )

    /**
     * 提取文档内容
     */
    suspend fun extractText(
        context: Context,
        uri: Uri,
        mimeType: String?
    ): String? {
        val effectiveMime = mimeType?.lowercase() ?: "application/octet-stream"
        
        Log.i(TAG, "尝试提取文档内容: $uri, mime=$effectiveMime")

        return withContext(Dispatchers.IO) {
            try {
                when {
                    isTextMime(effectiveMime) -> extractFromPlainText(context, uri)
                    effectiveMime == "application/pdf" -> extractFromPdf(context, uri)
                    effectiveMime.contains("wordprocessingml") -> extractFromDocx(context, uri)
                    effectiveMime.contains("spreadsheetml") -> extractFromExcel(context, uri)
                    else -> {
                        Log.w(TAG, "不支持的文档类型: $effectiveMime")
                        null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "文档提取失败", e)
                null
            }
        }
    }

    private fun isTextMime(mime: String): Boolean {
        return mime.startsWith("text/") || TEXT_MIME_TYPES.contains(mime)
    }

    private suspend fun extractFromPlainText(context: Context, uri: Uri): String? {
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            extractPlainText(inputStream)
        }
    }

    internal suspend fun extractPlainText(
        inputStream: InputStream,
        maxInputBytes: Long = MAX_INPUT_BYTES,
        maxOutputChars: Int = MAX_OUTPUT_CHARS,
    ): String {
        val result = StringBuilder(minOf(maxOutputChars, 8192))
        InputStreamReader(BoundedInputStream(inputStream, maxInputBytes), Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4096)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = reader.read(buffer)
                if (read < 0) break
                val remaining = maxOutputChars - result.length
                if (remaining > 0) result.append(buffer, 0, minOf(read, remaining))
            }
        }
        return result.toString()
    }

    private fun initPdfBox(context: Context) {
        if (isPdfBoxInitialized) return
        synchronized(this) {
            if (isPdfBoxInitialized) return
            try {
                PDFBoxResourceLoader.init(context.applicationContext)
                isPdfBoxInitialized = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init PDFBox", e)
            }
        }
    }

    private suspend fun extractFromPdf(context: Context, uri: Uri): String? {
        initPdfBox(context)
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            PDDocument.load(BoundedInputStream(inputStream, MAX_INPUT_BYTES)).use { document ->
                currentCoroutineContext().ensureActive()
                val writer = LimitedStringWriter(MAX_OUTPUT_CHARS)
                PDFTextStripper().apply { sortByPosition = true }.writeText(document, writer)
                currentCoroutineContext().ensureActive()
                writer.toString()
            }
        }
    }

    private suspend fun extractFromDocx(context: Context, uri: Uri): String? {
        // DOCX is a ZIP containing word/document.xml
        return extractXmlTextFromZip(context, uri, "word/document.xml")
    }

    private suspend fun extractFromExcel(context: Context, uri: Uri): String? {
        // XLSX is a ZIP. Text is usually in xl/sharedStrings.xml
        return extractXmlTextFromZip(context, uri, "xl/sharedStrings.xml")
    }

    private suspend fun extractXmlTextFromZip(context: Context, uri: Uri, targetEntry: String): String? {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(BoundedInputStream(inputStream, MAX_INPUT_BYTES)).use { zip ->
                var entry = zip.nextEntry
                var entryCount = 0
                while (entry != null) {
                    currentCoroutineContext().ensureActive()
                    entryCount++
                    if (entryCount > MAX_ZIP_ENTRIES) throw IOException("ZIP 条目数量超过上限")
                    if (entry.name == targetEntry) {
                        if (entry.size > MAX_ZIP_ENTRY_BYTES) throw IOException("ZIP 条目超过大小上限")
                        return parseXmlText(BoundedInputStream(zip, MAX_ZIP_ENTRY_BYTES))
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return null
    }

    private suspend fun parseXmlText(inputStream: InputStream): String {
        val sb = StringBuilder()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val xpp = factory.newPullParser()
        xpp.setInput(inputStream, "UTF-8")

        var eventType = xpp.eventType
        while (eventType != XmlPullParser.END_DOCUMENT && sb.length < MAX_OUTPUT_CHARS) {
            currentCoroutineContext().ensureActive()
            if (eventType == XmlPullParser.TEXT) {
                val text = xpp.text
                if (!text.isNullOrBlank()) {
                    val remaining = MAX_OUTPUT_CHARS - sb.length
                    sb.append(text, 0, minOf(text.length, remaining))
                    if (sb.length < MAX_OUTPUT_CHARS) sb.append(' ')
                }
            }
            eventType = xpp.next()
        }
        return sb.toString().trim()
    }

    internal class InputLimitExceededException(maxBytes: Long) : IOException("文档超过 ${maxBytes} 字节上限")

    internal class BoundedInputStream(
        input: InputStream,
        private val maxBytes: Long,
    ) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            if (count >= maxBytes) {
                if (super.read() < 0) return -1
                throw InputLimitExceededException(maxBytes)
            }
            return super.read().also { if (it >= 0) count++ }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (count >= maxBytes) {
                if (super.read() < 0) return -1
                throw InputLimitExceededException(maxBytes)
            }
            val allowed = minOf(length.toLong(), maxBytes - count).toInt()
            return super.read(buffer, offset, allowed).also { if (it > 0) count += it }
        }

        override fun skip(byteCount: Long): Long {
            if (byteCount <= 0) return 0
            if (count >= maxBytes) {
                if (super.read() < 0) return 0
                throw InputLimitExceededException(maxBytes)
            }
            val allowed = minOf(byteCount, maxBytes - count)
            return super.skip(allowed).also { skipped -> count += skipped }
        }
    }

    private class LimitedStringWriter(private val maxChars: Int) : Writer() {
        private val content = StringBuilder(minOf(maxChars, 8192))

        override fun write(buffer: CharArray, offset: Int, length: Int) {
            val writable = minOf(length, maxChars - content.length)
            if (writable > 0) content.append(buffer, offset, writable)
        }

        override fun flush() = Unit
        override fun close() = Unit
        override fun toString(): String = content.toString()
    }
}
