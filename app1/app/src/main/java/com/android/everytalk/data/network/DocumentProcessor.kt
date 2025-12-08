package com.android.everytalk.data.network

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStreamReader
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
            } catch (e: Exception) {
                Log.e(TAG, "文档提取失败", e)
                null
            }
        }
    }

    private fun isTextMime(mime: String): Boolean {
        return mime.startsWith("text/") || TEXT_MIME_TYPES.contains(mime)
    }

    private fun extractFromPlainText(context: Context, uri: Uri): String? {
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.readText()
            }
        }
    }

    private fun initPdfBox(context: Context) {
        if (!isPdfBoxInitialized) {
            try {
                PDFBoxResourceLoader.init(context.applicationContext)
                isPdfBoxInitialized = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init PDFBox", e)
            }
        }
    }

    private fun extractFromPdf(context: Context, uri: Uri): String? {
        initPdfBox(context)
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            try {
                PDDocument.load(inputStream).use { document ->
                    val stripper = PDFTextStripper()
                    stripper.sortByPosition = true
                    stripper.getText(document)
                }
            } catch (e: Exception) {
                Log.e(TAG, "PDF parsing error", e)
                null
            }
        }
    }

    private fun extractFromDocx(context: Context, uri: Uri): String? {
        // DOCX is a ZIP containing word/document.xml
        return extractXmlTextFromZip(context, uri, "word/document.xml")
    }

    private fun extractFromExcel(context: Context, uri: Uri): String? {
        // XLSX is a ZIP. Text is usually in xl/sharedStrings.xml
        return extractXmlTextFromZip(context, uri, "xl/sharedStrings.xml")
    }

    private fun extractXmlTextFromZip(context: Context, uri: Uri, targetEntry: String): String? {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == targetEntry) {
                        return parseXmlText(zip)
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return null
    }

    private fun parseXmlText(inputStream: java.io.InputStream): String {
        val sb = StringBuilder()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val xpp = factory.newPullParser()
            xpp.setInput(inputStream, "UTF-8")
            
            var eventType = xpp.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.TEXT) {
                    val text = xpp.text
                    if (!text.isNullOrBlank()) {
                        sb.append(text).append(" ")
                    }
                }
                eventType = xpp.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "XML parsing error", e)
        }
        return sb.toString().trim()
    }
}