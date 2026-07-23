package com.android.everytalk.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class DocumentProcessorLimitsTest {

    @Test
    fun `plain text rejects oversized input`() = runBlocking {
        try {
            DocumentProcessor.extractPlainText(ByteArrayInputStream(ByteArray(9)), maxInputBytes = 8, maxOutputChars = 20)
            fail("应拒绝超过原始字节上限的文本")
        } catch (_: DocumentProcessor.InputLimitExceededException) {
        }
    }

    @Test
    fun `plain text preserves cancellation`() = runBlocking {
        val cancelledJob = Job().apply { cancel() }
        try {
            withContext(cancelledJob) {
                DocumentProcessor.extractPlainText(ByteArrayInputStream("text".toByteArray()))
            }
            fail("应继续抛出取消异常")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `plain text truncates extracted output`() = runBlocking {
        val result = DocumentProcessor.extractPlainText(
            ByteArrayInputStream("abcdefghijklmnop".toByteArray()),
            maxInputBytes = 32,
            maxOutputChars = 8,
        )
        assertEquals("abcdefgh", result)
    }

    @Test
    fun `bounded input counts skipped bytes against the same limit`() {
        val input = DocumentProcessor.BoundedInputStream(
            ByteArrayInputStream(ByteArray(9)),
            maxBytes = 8,
        )

        assertEquals(8L, input.skip(9))
        assertThrows(DocumentProcessor.InputLimitExceededException::class.java) {
            input.skip(1)
        }
    }
}
