package com.android.everytalk.util.storage

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

internal fun readAtMost(inputStream: InputStream, maxBytes: Long): ByteArray {
    require(maxBytes >= 0) { "maxBytes must be non-negative" }
    val output = ByteArrayOutputStream()
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
