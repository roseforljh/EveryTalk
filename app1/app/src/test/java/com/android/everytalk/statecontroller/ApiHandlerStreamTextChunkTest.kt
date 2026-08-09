package com.android.everytalk.statecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiHandlerStreamTextChunkTest {

    @Test
    fun `stream chunk filter ignores empty spaces and tabs`() {
        listOf<String?>(null, "", "   ", "\t\t").forEach { chunk ->
            assertFalse(shouldAppendStreamTextChunk(chunk))
        }
    }

    @Test
    fun `stream chunk filter preserves line breaks`() {
        listOf("\n", "\r", "\r\n", "\n\n", " \n ").forEach { chunk ->
            assertTrue(shouldAppendStreamTextChunk(chunk))
        }
    }

    @Test
    fun `stream chunk filter preserves visible content`() {
        assertTrue(shouldAppendStreamTextChunk("正文"))
        assertTrue(shouldAppendStreamTextChunk("正文\n"))
    }

    @Test
    fun `stream chunks keep markdown heading boundary`() {
        val result = listOf("邮件通知。", "\n\n", "### 重要说明")
            .filter { chunk -> shouldAppendStreamTextChunk(chunk) }
            .joinToString("")

        assertEquals("邮件通知。\n\n### 重要说明", result)
    }

    @Test
    fun `stream chunks keep markdown list boundary`() {
        val result = listOf("描述。", "\n", "- **建议**")
            .filter { chunk -> shouldAppendStreamTextChunk(chunk) }
            .joinToString("")

        assertEquals("描述。\n- **建议**", result)
    }
}
