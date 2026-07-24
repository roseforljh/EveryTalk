package com.android.everytalk.data.network.direct

import com.android.everytalk.data.network.ResponseBodyTooLargeException
import kotlinx.serialization.json.*

internal class BoundedJsonObjectByteBuffer(private val maxBytes: Long) {
    init {
        require(maxBytes in 1..Int.MAX_VALUE.toLong()) { "JSON 缓冲区上限无效" }
    }

    private var bytes = ByteArray(minOf(maxBytes.toInt(), 8192))
    private var size = 0

    internal val bufferedByteCount: Int
        get() = size

    fun append(source: ByteArray, length: Int): List<String> {
        require(length in 0..source.size) { "追加字节长度无效" }
        val requiredSize = size.toLong() + length
        if (requiredSize > maxBytes) {
            throw ResponseBodyTooLargeException(maxBytes)
        }
        ensureCapacity(requiredSize.toInt())
        source.copyInto(bytes, destinationOffset = size, startIndex = 0, endIndex = length)
        size = requiredSize.toInt()
        return drainCompleteObjects()
    }

    private fun ensureCapacity(requiredSize: Int) {
        if (requiredSize <= bytes.size) return
        bytes = bytes.copyOf(minOf(maxBytes.toInt(), maxOf(requiredSize, bytes.size * 2)))
    }

    private fun drainCompleteObjects(): List<String> {
        val objects = mutableListOf<String>()
        var consumedBytes = 0
        var braceCount = 0
        var inString = false
        var escaped = false
        var jsonStart = -1

        for (index in 0 until size) {
            when (val value = bytes[index].toInt() and 0xff) {
                '\\'.code -> if (inString) escaped = !escaped
                '"'.code -> {
                    if (!escaped) inString = !inString
                    escaped = false
                }
                else -> {
                    if (escaped) {
                        escaped = false
                    } else if (!inString) {
                        when (value) {
                            '{'.code -> {
                                if (braceCount == 0) jsonStart = index
                                braceCount++
                            }
                            '}'.code -> if (braceCount > 0) {
                                braceCount--
                                if (braceCount == 0 && jsonStart >= 0) {
                                    objects += String(
                                        bytes,
                                        jsonStart,
                                        index - jsonStart + 1,
                                        Charsets.UTF_8,
                                    )
                                    consumedBytes = index + 1
                                    jsonStart = -1
                                }
                            }
                        }
                    }
                }
            }
        }

        if (consumedBytes > 0) {
            bytes.copyInto(bytes, startIndex = consumedBytes, endIndex = size)
            size -= consumedBytes
        }
        return objects
    }
}

internal class AliyunTtsApiException(
    errorCode: String,
    errorMessage: String?,
) : Exception("Aliyun TTS Error: $errorMessage ($errorCode)")

internal fun parseAliyunTtsJsonOrNull(rawText: String): JsonObject? {
    val json = try {
        Json.parseToJsonElement(rawText).jsonObject
    } catch (_: Exception) {
        return null
    }
    val errorCode = json["code"]?.jsonPrimitive?.contentOrNull ?: return json
    val errorMessage = json["message"]?.jsonPrimitive?.contentOrNull
    throw AliyunTtsApiException(errorCode, errorMessage)
}

/**
 * Helper function to convert hex string to byte array
 */
internal fun TtsDirectClient.hexStringToByteArray(s: String): ByteArray {
    val len = s.length
    require(len % 2 == 0) { "音频十六进制数据长度必须为偶数" }
    if (len.toLong() / 2L > MAX_NON_STREAMING_AUDIO_BYTES) {
        throw ResponseBodyTooLargeException(MAX_NON_STREAMING_AUDIO_BYTES)
    }
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        val high = Character.digit(s[i], 16)
        val low = Character.digit(s[i + 1], 16)
        require(high >= 0 && low >= 0) { "音频十六进制数据包含非法字符" }
        data[i / 2] = ((high shl 4) + low).toByte()
        i += 2
    }
    return data
}

internal fun TtsDirectClient.addAudioBytes(currentBytes: Long, additionalBytes: Int): Long {
    if (currentBytes > MAX_NON_STREAMING_AUDIO_BYTES - additionalBytes) {
        throw ResponseBodyTooLargeException(MAX_NON_STREAMING_AUDIO_BYTES)
    }
    return currentBytes + additionalBytes
}

internal fun TtsDirectClient.ensureEncodedAudioWithinLimit(encoded: String) {
    val encodedLength = encoded.count { !it.isWhitespace() }.toLong()
    val estimatedBytes = (encodedLength / 4L) * 3L + when (encodedLength % 4L) {
        2L -> 1L
        3L -> 2L
        else -> 0L
    }
    if (estimatedBytes > MAX_NON_STREAMING_AUDIO_BYTES) {
        throw ResponseBodyTooLargeException(MAX_NON_STREAMING_AUDIO_BYTES)
    }
}
