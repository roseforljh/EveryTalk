package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ComputerBoundedOutputReaderTest {
    @Test
    fun `bounded output keeps head and tail when truncated`() {
        val result = ComputerBoundedOutputReader.read(
            ByteArrayInputStream("0123456789abcdefghij".toByteArray()),
            maxBytes = 10,
        )

        assertEquals("01234fghij", result.bytes.toString(Charsets.UTF_8))
        assertTrue(result.truncated)
    }

    @Test
    fun `bounded output preserves complete small output`() {
        val result = ComputerBoundedOutputReader.read(ByteArrayInputStream("中文abc".toByteArray()), 32)

        assertEquals("中文abc", result.bytes.toString(Charsets.UTF_8))
        assertFalse(result.truncated)
    }
}
