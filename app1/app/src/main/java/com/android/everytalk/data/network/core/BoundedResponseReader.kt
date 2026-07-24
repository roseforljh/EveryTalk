package com.android.everytalk.data.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.charset
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.IOException

class ResponseBodyTooLargeException(maxBytes: Long) : IOException("响应超过 ${maxBytes} 字节上限")

internal const val MAX_ERROR_RESPONSE_BYTES = 64L * 1024L
internal const val MAX_SSE_EVENT_BYTES = 4L * 1024L * 1024L
internal const val MAX_STREAM_TEXT_BYTES = 16L * 1024L * 1024L
internal const val MAX_MCP_EVENT_BYTES = 16L * 1024L * 1024L
internal const val MAX_WEBSOCKET_FRAME_BYTES = 16L * 1024L * 1024L

suspend fun HttpResponse.readBytesAtMost(maxBytes: Long): ByteArray {
    val channel = bodyAsChannel()
    headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { declaredLength ->
        if (declaredLength > maxBytes) {
            val error = ResponseBodyTooLargeException(maxBytes)
            channel.cancel(error)
            throw error
        }
    }
    return channel.readBytesAtMost(maxBytes)
}

suspend fun HttpResponse.readTextAtMost(maxBytes: Long): String {
    val responseContentType = runCatching { contentType() }.getOrNull()
    return decodeResponseText(readBytesAtMost(maxBytes), responseContentType)
}

suspend fun HttpResponse.readErrorTextAtMost(): String? = try {
    readTextAtMost(MAX_ERROR_RESPONSE_BYTES)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

internal fun decodeResponseText(bytes: ByteArray, contentType: ContentType?): String {
    val charset = runCatching { contentType?.charset() }.getOrNull() ?: Charsets.UTF_8
    return bytes.toString(charset)
}

internal fun ensureSseEventWithinLimit(
    eventStartBytes: Long,
    currentBytes: Long,
    maxEventBytes: Long,
) {
    require(currentBytes >= eventStartBytes) { "SSE 通道字节计数无效" }
    if (currentBytes - eventStartBytes > maxEventBytes) {
        throw ResponseBodyTooLargeException(maxEventBytes)
    }
}

internal class BoundedSseLineReader(
    source: ByteReadChannel,
    private val maxEventBytes: Long = MAX_SSE_EVENT_BYTES,
    private val maxStreamBytes: Long = MAX_STREAM_TEXT_BYTES,
) {
    init {
        require(maxEventBytes in 1..Int.MAX_VALUE.toLong()) { "SSE 事件上限无效" }
        require(maxStreamBytes >= maxEventBytes) { "SSE 流上限不得小于单事件上限" }
    }

    private val lines = BoundedByteLineReader(source, maxEventBytes)
    private var eventStartBytes = 0L
    private var eventHasFields = false
    private var eofFlushed = false

    val totalBytesRead: Long
        get() = lines.totalBytesRead

    suspend fun readLine(): String? {
        if (eofFlushed) return null

        val eventLimit = eventStartBytes + maxEventBytes
        val absoluteLimit = minOf(eventLimit, maxStreamBytes)
        val reportedLimit = if (absoluteLimit == eventLimit) maxEventBytes else maxStreamBytes
        val line = lines.readLine(absoluteLimit, reportedLimit)
        if (line == null) {
            if (!eventHasFields) return null
            eofFlushed = true
            eventHasFields = false
            return ""
        }

        if (line.isEmpty()) {
            eventStartBytes = lines.totalBytesRead
            eventHasFields = false
        } else if (!line.startsWith(':')) {
            eventHasFields = true
        }
        return line
    }
}

internal class BoundedByteLineReader(
    private val source: ByteReadChannel,
    private val maxLineBytes: Long,
) {
    init {
        require(maxLineBytes in 1..Int.MAX_VALUE.toLong()) { "行长度上限无效" }
    }

    private val readBuffer = ByteArray(8192)
    private var readOffset = 0
    private var readLimit = 0
    private var skipLfAfterCr = false

    var totalBytesRead: Long = 0L
        private set

    suspend fun readLine(absoluteByteLimit: Long, reportedLimit: Long): String? {
        var line = ByteArray(minOf(maxLineBytes, 8192L).toInt())
        var lineSize = 0

        while (true) {
            val value = readNextByte() ?: run {
                skipLfAfterCr = false
                return line.takeIf { lineSize > 0 }?.let {
                    String(it, 0, lineSize, Charsets.UTF_8)
                }
            }

            totalBytesRead++
            if (totalBytesRead > absoluteByteLimit) {
                val error = ResponseBodyTooLargeException(reportedLimit)
                source.cancel(error)
                throw error
            }

            if (skipLfAfterCr) {
                skipLfAfterCr = false
                if (value == '\n'.code) continue
            }

            when (value) {
                '\n'.code -> return String(line, 0, lineSize, Charsets.UTF_8)
                '\r'.code -> {
                    skipLfAfterCr = true
                    return String(line, 0, lineSize, Charsets.UTF_8)
                }
                else -> {
                    if (lineSize.toLong() >= maxLineBytes) {
                        val error = ResponseBodyTooLargeException(maxLineBytes)
                        source.cancel(error)
                        throw error
                    }
                    if (lineSize == line.size) {
                        line = line.copyOf(minOf(maxLineBytes.toInt(), line.size * 2))
                    }
                    line[lineSize++] = value.toByte()
                }
            }
        }
    }

    private suspend fun readNextByte(): Int? {
        while (readOffset >= readLimit) {
            currentCoroutineContext().ensureActive()
            val read = source.readAvailable(readBuffer)
            if (read < 0) return null
            if (read == 0) continue
            readOffset = 0
            readLimit = read
        }
        return readBuffer[readOffset++].toInt() and 0xff
    }
}

internal suspend fun ByteReadChannel.readBytesAtMost(maxBytes: Long): ByteArray {
    require(maxBytes in 1..Int.MAX_VALUE.toLong()) { "响应大小上限无效" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192L).toInt())
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = readAvailable(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) {
            val error = ResponseBodyTooLargeException(maxBytes)
            cancel(error)
            throw error
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
