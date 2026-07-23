package com.android.everytalk.util.storage

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

internal class CappedByteArrayOutputStream(
    private val maxBytes: Long,
) : ByteArrayOutputStream(minOf(maxBytes, 8192L).toInt()) {
    init {
        require(maxBytes >= 0L) { "maxBytes must be non-negative" }
    }

    private fun ensureCapacity(incomingBytes: Int) {
        if (incomingBytes < 0 || count.toLong() + incomingBytes > maxBytes) {
            throw IOException("Output exceeds maximum size: $maxBytes bytes")
        }
    }

    override fun write(value: Int) {
        ensureCapacity(1)
        super.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        ensureCapacity(length)
        super.write(buffer, offset, length)
    }
}

internal fun readAtMost(inputStream: InputStream, maxBytes: Long): ByteArray {
    require(maxBytes >= 0) { "maxBytes must be non-negative" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192L).toInt())
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L

    while (true) {
        val read = inputStream.read(buffer)
        if (read == -1) break
        total += read
        if (total > maxBytes) {
            throw IllegalArgumentException("Input exceeds maximum size: $maxBytes bytes")
        }
        output.write(buffer, 0, read)
    }

    return output.toByteArray()
}

internal fun File.readAtMost(maxBytes: Long): ByteArray {
    if (length() > maxBytes) {
        throw IllegalArgumentException("File exceeds maximum size: $maxBytes bytes")
    }
    return inputStream().use { readAtMost(it, maxBytes) }
}
