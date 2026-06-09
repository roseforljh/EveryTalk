package com.android.everytalk.util.storage

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LimitedByteReaderTest {
    @Test
    fun `readAtMost returns bytes when stream fits limit`() {
        val bytes = byteArrayOf(1, 2, 3, 4)

        val result = readAtMost(ByteArrayInputStream(bytes), maxBytes = 4)

        assertArrayEquals(bytes, result)
    }

    @Test
    fun `readAtMost rejects streams that exceed limit`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        assertThrows(IllegalArgumentException::class.java) {
            readAtMost(ByteArrayInputStream(bytes), maxBytes = 4)
        }
    }
}
