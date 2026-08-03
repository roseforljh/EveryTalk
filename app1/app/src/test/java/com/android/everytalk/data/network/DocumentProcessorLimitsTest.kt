package com.android.everytalk.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class DocumentProcessorLimitsTest {
    @Test
    fun `超限HTML文本保留可分析的前缀`() = runBlocking {
        val html = "<html><body>附件内容</body></html>"

        val result = DocumentProcessor.extractPlainText(
            ByteArrayInputStream(html.toByteArray()),
            maxInputBytes = 100,
            maxOutputChars = 16,
        )

        assertEquals(html.take(16), result)
    }

    @Test
    fun `超限HTML文本可按游标连续读取且无内容丢失`() = runBlocking {
        val html = "<html><body>第一页内容，第二页内容</body></html>"

        val first = DocumentProcessor.extractPlainTextPage(
            inputStream = ByteArrayInputStream(html.toByteArray()),
            offsetChars = 0,
            maxOutputChars = 18,
        )
        val second = DocumentProcessor.extractPlainTextPage(
            inputStream = ByteArrayInputStream(html.toByteArray()),
            offsetChars = checkNotNull(first.nextOffset),
            maxOutputChars = 100,
        )

        assertTrue(first.truncated)
        assertEquals(html, first.content + second.content)
        assertEquals(null, second.nextOffset)
    }


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
