package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRingBufferTest {
    @Test
    fun `ring buffer reports dropped output and stable cursors`() {
        val buffer = TerminalRingBuffer(5)
        buffer.append("abc".toCharArray(), 3)
        val first = buffer.read(0)
        assertEquals("abc", first.text)
        assertEquals(3L, first.nextCursor)
        assertFalse(first.dropped)

        buffer.append("defg".toCharArray(), 4)
        val stale = buffer.read(0)
        assertEquals("cdefg", stale.text)
        assertEquals(7L, stale.nextCursor)
        assertTrue(stale.dropped)

        val current = buffer.read(3)
        assertEquals("defg", current.text)
        assertFalse(current.dropped)
    }
}
