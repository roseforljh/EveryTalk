package com.android.everytalk.data.network

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.json.*

private const val MAX_FILE_UPLOAD_RESPONSE_BYTES = 1L * 1024L * 1024L

internal class StreamingContentAggregator {
    private val buffer = StringBuilder()
    private var pendingShortHeading = false
    private var lastEmitAt = System.currentTimeMillis()
    private val maxWaitMs = 450L
    private val maxPendingChars = 40

    fun append(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        buffer.append(delta)
        updatePendingShortHeadingFlag()
        val out = mutableListOf<String>()
        while (true) {
            val flushLen = findFlushablePrefixLength(buffer.toString())
            if (flushLen <= 0) break
            out += buffer.substring(0, flushLen)
            buffer.delete(0, flushLen)
            lastEmitAt = System.currentTimeMillis()
            updatePendingShortHeadingFlag()
        }
        return out
    }

    fun flushRemaining(): String {
        val remaining = buffer.toString()
        buffer.setLength(0)
        pendingShortHeading = false
        lastEmitAt = System.currentTimeMillis()
        return remaining
    }

    private fun findFlushablePrefixLength(text: String): Int {
        if (text.isEmpty()) return -1

        val now = System.currentTimeMillis()
        val waitedTooLong = now - lastEmitAt >= maxWaitMs
        val bufferedTooMuch = text.length >= maxPendingChars

        if ((waitedTooLong || bufferedTooMuch) && !isInsideUnclosedFence(text)) {
            val forcedCut = findForcedFlushCut(text)
            if (forcedCut > 0) return forcedCut
        }

        if (text.length < 12 && !text.contains('\n')) return -1

        val shortHeadingCut = findShortHeadingWithBodyBoundary(text)
        if (shortHeadingCut > 0) return shortHeadingCut

        val lastLineStart = text.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
        val lastLine = text.substring(lastLineStart).trimStart()
        if (pendingShortHeading) {
            if (isHeadingBodyArrived(text)) {
                val pendingCut = flushPendingShortHeadingWithBody(text)
                if (pendingCut > 0) return pendingCut
            }
            if (!waitedTooLong && !bufferedTooMuch) {
                return -1
            }
        }
        if (isHeadingLikeLine(lastLine) && !text.endsWith("\n") && !text.contains("\n\n")) {
            return -1
        }
        if (isListLikeLine(lastLine) && !text.endsWith("\n") && !text.contains("\n\n")) {
            return -1
        }

        val fenceCut = findFenceLineBoundary(text)
        if (fenceCut > 0) return fenceCut

        val codeLineCut = findCodeLineBoundary(text)
        if (codeLineCut > 0) return codeLineCut

        val headingCut = findHeadingLineBoundary(text)
        if (headingCut > 0) return headingCut

        val listCut = findListItemBoundary(text)
        if (listCut > 0) return listCut

        if (text.length >= 96) return findSafeCut(text, 96)

        val doubleNewline = text.lastIndexOf("\n\n")
        if (doubleNewline >= 0) {
            val cut = doubleNewline + 2
            return if (isSafePrefix(text.substring(0, cut))) cut else -1
        }

        val singleNewline = text.lastIndexOf('\n')
        if (singleNewline >= 0) {
            val cut = singleNewline + 1
            return if (isSafePrefix(text.substring(0, cut))) cut else -1
        }

        val punctuationCut = findLastPunctuationBoundary(text)
        if (punctuationCut > 0) {
            return if (isSafePrefix(text.substring(0, punctuationCut))) punctuationCut else -1
        }

        val whitespaceCut = text.lastIndexOf(' ')
        if (whitespaceCut > 0) {
            val cut = whitespaceCut + 1
            return if (isSafePrefix(text.substring(0, cut))) cut else -1
        }

        return -1
    }

    private fun findForcedFlushCut(text: String): Int {
        val newlineCut = text.lastIndexOf('\n').takeIf { it >= 0 }?.plus(1) ?: -1
        if (newlineCut > 0 && isSafePrefix(text.substring(0, newlineCut))) return newlineCut

        val punctuationCut = findLastPunctuationBoundary(text)
        if (punctuationCut > 0 && isSafePrefix(text.substring(0, punctuationCut))) return punctuationCut

        val whitespaceCut = text.lastIndexOf(' ').takeIf { it >= 0 }?.plus(1) ?: -1
        if (whitespaceCut > 0 && isSafePrefix(text.substring(0, whitespaceCut))) return whitespaceCut

        return if (text.length >= 12 && isSafePrefix(text)) text.length else -1
    }

    private fun findShortHeadingWithBodyBoundary(text: String): Int {
        val lines = text.split('\n')
        if (lines.size < 2) return -1

        val headingLine = lines[0].trimEnd()
        if (!isHeadingLikeLine(headingLine.trimStart())) return -1

        val headingText = headingLine.trimStart().replaceFirst(Regex("^#{1,6}\\s+"), "")
        if (headingText.length > 8) return -1

        var consumedChars = headingLine.length
        var bodyCollected = StringBuilder()
        var sawMeaningfulBody = false

        for (i in 1 until lines.size) {
            consumedChars += 1 // newline
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                if (sawMeaningfulBody) {
                    consumedChars += line.length
                    return if (consumedChars <= text.length && isSafePrefix(text.substring(0, consumedChars))) consumedChars else -1
                }
                consumedChars += line.length
                continue
            }

            if (isHeadingLikeLine(trimmed) || isListLikeLine(trimmed)) {
                return if (sawMeaningfulBody && consumedChars <= text.length && isSafePrefix(text.substring(0, consumedChars))) consumedChars else -1
            }

            bodyCollected.append(trimmed).append('\n')
            consumedChars += line.length
            sawMeaningfulBody = true

            val bodyText = bodyCollected.toString().trim()
            val bodyLooksComplete = bodyText.endsWith("。") || bodyText.endsWith("！") || bodyText.endsWith("？") ||
                bodyText.endsWith("：") || bodyText.endsWith(":") || bodyText.length >= 24
            if (bodyLooksComplete) {
                return if (consumedChars <= text.length && isSafePrefix(text.substring(0, consumedChars))) consumedChars else -1
            }
        }

        return -1
    }

    private fun updatePendingShortHeadingFlag() {
        val text = buffer.toString()
        val lines = text.split('\n')
        if (lines.isEmpty()) {
            pendingShortHeading = false
            return
        }

        val headingLine = lines.first().trimEnd()
        val headingTrimmed = headingLine.trimStart()
        if (!isHeadingLikeLine(headingTrimmed)) {
            pendingShortHeading = false
            return
        }

        val headingText = headingTrimmed.replaceFirst(Regex("^#{1,6}\\s+"), "")
        pendingShortHeading = headingText.length <= 8
    }

    private fun isHeadingBodyArrived(text: String): Boolean {
        val lines = text.split('\n')
        if (lines.size < 2) return false
        val bodyLines = lines.drop(1)
        return bodyLines.any { it.trim().isNotEmpty() }
    }

    private fun flushPendingShortHeadingWithBody(text: String): Int {
        val lines = text.split('\n')
        if (lines.size < 2) return -1

        val headingLine = lines[0].trimEnd()
        val headingTrimmed = headingLine.trimStart()
        if (!isHeadingLikeLine(headingTrimmed)) return -1

        val bodyLines = mutableListOf<String>()
        var consumedChars = headingLine.length
        var bodyLength = 0

        for (i in 1 until lines.size) {
            consumedChars += 1 // newline
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                if (bodyLines.isNotEmpty()) {
                    consumedChars += line.length
                    return if (consumedChars <= text.length && isSafePrefix(text.substring(0, consumedChars))) consumedChars else -1
                }
                consumedChars += line.length
                continue
            }

            if (isHeadingLikeLine(trimmed) || isListLikeLine(trimmed)) {
                return if (bodyLines.isNotEmpty() && consumedChars <= text.length && isSafePrefix(text.substring(0, consumedChars))) consumedChars else -1
            }

            bodyLines += line
            consumedChars += line.length
            bodyLength += trimmed.length

            val bodyJoined = bodyLines.joinToString("\n").trim()
            val bodyLooksComplete = bodyJoined.endsWith("。") || bodyJoined.endsWith("！") || bodyJoined.endsWith("？") ||
                bodyJoined.endsWith("：") || bodyJoined.endsWith(":") || bodyLength >= 20
            if (bodyLooksComplete) {
                return if (consumedChars <= text.length && isSafePrefix(text.substring(0, consumedChars))) consumedChars else -1
            }
        }

        return -1
    }

    private fun findHeadingLineBoundary(text: String): Int {
        val lineStart = text.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
        val line = text.substring(lineStart)
        val trimmed = line.trimStart()
        if (!isHeadingLikeLine(trimmed)) return -1

        val headingText = trimmed.replaceFirst(Regex("^#{1,6}\\s+"), "")
        if (headingText.length <= 8) return -1

        val hasLineTerminator = text.endsWith("\n") || text.contains("\n\n")
        if (!hasLineTerminator) return -1

        return if (isSafePrefix(text)) text.length else -1
    }

    private fun findListItemBoundary(text: String): Int {
        val lineStart = text.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
        val line = text.substring(lineStart)
        val trimmed = line.trimStart()
        if (!isListLikeLine(trimmed)) return -1

        val hasLineTerminator = text.endsWith("\n") || text.contains("\n\n")
        val hasSentenceEnd = line.endsWith("。") || line.endsWith("！") || line.endsWith("？") ||
            line.endsWith("；") || line.endsWith(":") || line.endsWith("：")
        val bodyLength = trimmed.length

        if (!hasLineTerminator && !hasSentenceEnd) return -1
        if (bodyLength < 12) return -1

        return if (isSafePrefix(text)) text.length else -1
    }

    private fun isHeadingLikeLine(trimmed: String): Boolean {
        if (!trimmed.startsWith("#")) return false
        val marker = trimmed.takeWhile { it == '#' }
        if (marker.isEmpty() || marker.length > 6) return false
        if (trimmed.length <= marker.length) return false
        if (trimmed[marker.length] != ' ') return false
        return true
    }

    private fun isListLikeLine(trimmed: String): Boolean {
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) return true
        if (Regex("^\\d+\\.\\s+").containsMatchIn(trimmed)) return true
        return false
    }

    private fun findFenceLineBoundary(text: String): Int {
        val trimmed = text.trimEnd()
        if (trimmed.endsWith("```") || Regex("```[A-Za-z0-9_-]+$").containsMatchIn(trimmed)) {
            return if (isSafePrefix(text)) text.length else -1
        }
        return -1
    }

    private fun findCodeLineBoundary(text: String): Int {
        if (!isInsideUnclosedFence(text)) return -1
        val newline = text.lastIndexOf('\n')
        if (newline <= 0) return -1
        val lineStart = text.substring(0, newline).lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
        val candidate = text.substring(0, newline + 1)
        val line = candidate.substring(lineStart, candidate.length - 1)
        if (line.isBlank()) return candidate.length
        if (line.length < 12 && !line.trimEnd().endsWith("{") && !line.trimEnd().endsWith(";") && !line.trimEnd().endsWith(">")) {
            return -1
        }
        return candidate.length
    }

    private fun findSafeCut(text: String, preferred: Int): Int {
        val safeRegion = text.take(preferred)
        val punctuationCut = findLastPunctuationBoundary(safeRegion)
        if (punctuationCut > 0 && isSafePrefix(text.substring(0, punctuationCut))) return punctuationCut

        val newlineCut = safeRegion.lastIndexOf('\n').takeIf { it >= 0 }?.plus(1) ?: -1
        if (newlineCut > 0 && isSafePrefix(text.substring(0, newlineCut))) return newlineCut

        val whitespaceCut = safeRegion.lastIndexOf(' ').takeIf { it >= 0 }?.plus(1) ?: -1
        if (whitespaceCut > 0 && isSafePrefix(text.substring(0, whitespaceCut))) return whitespaceCut

        return if (isSafePrefix(safeRegion)) safeRegion.length else -1
    }

    private fun findLastPunctuationBoundary(text: String): Int {
        for (i in text.indices.reversed()) {
            val ch = text[i]
            if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == ':' || ch == '：' || ch == ',' || ch == '，') {
                return i + 1
            }
        }
        return -1
    }

    private fun isSafePrefix(prefix: String): Boolean {
        return !endsWithDangerousFragment(prefix) && !isInsideUnclosedFence(prefix)
    }

    private fun endsWithDangerousFragment(text: String): Boolean {
        val line = text.substringAfterLast('\n')
        val trimmed = line.trimEnd()
        val startTrimmed = trimmed.trimStart()

        if (startTrimmed == "#" || startTrimmed == "##" || startTrimmed == "###" ||
            startTrimmed == "####" || startTrimmed == "#####" || startTrimmed == "######") return true
        if (startTrimmed == "-" || startTrimmed == "+" || startTrimmed == "*" || startTrimmed == ">") return true
        if (Regex("^\\d+\\.$").matches(startTrimmed)) return true
        if (trimmed == "`" || trimmed == "``" || trimmed == "```") return true
        if (trimmed == "**") return true
        if (trimmed == "'" || trimmed == "\"") return true
        if (trimmed.endsWith("</") || trimmed.endsWith("<")) return true
        if (trimmed.endsWith("|") && trimmed.count { it == '|' } >= 2) return true
        return false
    }

    private fun isInsideUnclosedFence(text: String): Boolean {
        var idx = 0
        var count = 0
        while (true) {
            val pos = text.indexOf("```", idx)
            if (pos < 0) break
            count++
            idx = pos + 3
        }
        return (count % 2) == 1
    }
}


internal data class OpenAiToolCallInfo(
    val id: String,
    val name: String,
    val arguments: String
)

internal data class OpenAIParseResult(
    val hasToolCalls: Boolean,
    val fullText: String,
    val reasoningContent: String = ""
)


internal fun defaultGeminiReasoningEffort(model: String): String {
    return when {
        model.contains("flash", ignoreCase = true) -> "low"
        model.contains("pro", ignoreCase = true) -> "medium"
        else -> "high"
    }
}

internal fun mapToJsonElement(map: Map<String, Any>): JsonElement {
    return buildJsonObject {
        map.forEach { (key, value) ->
            put(key, anyToJsonElement(value))
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun anyToJsonElement(value: Any?): JsonElement {
    return when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> mapToJsonElement(value as Map<String, Any>)
        is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }
}


internal suspend fun uploadFileToDashScope(
    client: HttpClient,
    apiKey: String,
    fileName: String,
    fileBytes: ByteArray
): String {
    // https://dashscope.aliyuncs.com/compatible-mode/v1/files
    return client.preparePost("https://dashscope.aliyuncs.com/compatible-mode/v1/files") {
        setBody(MultiPartFormDataContent(formData {
            append("file", fileBytes, Headers.build {
                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                val mimeType = when (fileName.substringAfterLast('.', "").lowercase()) {
                    "txt" -> "text/plain"
                    "pdf" -> "application/pdf"
                    "doc", "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    else -> "application/octet-stream"
                }
                append(HttpHeaders.ContentType, mimeType)
            })
            append("purpose", "file-extract")
        }))
        header(HttpHeaders.Authorization, "Bearer $apiKey")
    }.execute { response ->
        if (!response.status.isSuccess()) {
            throw Exception("Upload failed: ${response.status}")
        }

        val json = Json.parseToJsonElement(
            response.readTextAtMost(MAX_FILE_UPLOAD_RESPONSE_BYTES)
        ).jsonObject
        json["id"]?.jsonPrimitive?.content ?: throw Exception("No file id in response")
    }
}


internal fun shouldFallbackToResponses(errorBody: String?): Boolean {
    if (errorBody.isNullOrBlank()) return false
    val lower = errorBody.lowercase()
    // HTML 页面（Cloudflare 拦截）或非 JSON 响应
    return lower.contains("<html") ||
        lower.contains("cloudflare") ||
        lower.contains("<!doctype") ||
        (lower.contains("403 forbidden") && !lower.startsWith("{"))
}
