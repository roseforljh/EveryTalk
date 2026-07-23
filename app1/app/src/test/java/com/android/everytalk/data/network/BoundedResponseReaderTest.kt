package com.android.everytalk.data.network

import com.android.everytalk.data.network.direct.TtsDirectClient
import io.ktor.utils.io.ByteReadChannel
import io.ktor.http.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoundedResponseReaderTest {

    @Test
    fun `bounded channel reader rejects oversized payload`() = runBlocking {
        val channel = ByteReadChannel(ByteArray(9))
        try {
            channel.readBytesAtMost(8)
            fail("应拒绝超过响应字节上限的数据")
        } catch (_: ResponseBodyTooLargeException) {
            assertTrue(channel.isClosedForRead)
        }
    }

    @Test
    fun `bounded channel reader preserves cancellation`() = runBlocking {
        val cancelledJob = Job().apply { cancel() }
        try {
            withContext(cancelledJob) {
                ByteReadChannel(ByteArray(8)).readBytesAtMost(8)
            }
            fail("应继续抛出取消异常")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `image and audio callers use finite limits`() {
        assertEquals(32L * 1024L * 1024L, ImageGenerationDirectClient.MAX_GENERATED_IMAGE_BYTES)
        assertEquals(64L * 1024L * 1024L, TtsDirectClient.MAX_NON_STREAMING_AUDIO_BYTES)
        assertTrue(ImageGenerationDirectClient.MAX_GENERATED_IMAGE_BYTES < TtsDirectClient.MAX_NON_STREAMING_AUDIO_BYTES)
    }

    @Test
    fun `generated image base64 gate accepts exact decoded limit and unpadded input`() {
        ImageGenerationDirectClient.ensureGeneratedImageBase64WithinLimit("QUJDRA==", maxBytes = 4)
        ImageGenerationDirectClient.ensureGeneratedImageBase64WithinLimit("QUJDRA", maxBytes = 4)
        val dataUri = "data:image/png;base64,QUJDRA=="
        ImageGenerationDirectClient.ensureGeneratedImageBase64WithinLimit(
            dataUri,
            maxBytes = 4,
            startIndex = dataUri.indexOf(',') + 1,
        )
    }

    @Test
    fun `generated image base64 gate rejects oversized decoded payload`() {
        assertThrows(ResponseBodyTooLargeException::class.java) {
            ImageGenerationDirectClient.ensureGeneratedImageBase64WithinLimit("QUJDRA==", maxBytes = 3)
        }
    }

    @Test
    fun `generated image base64 gate rejects malformed padding and characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageGenerationDirectClient.ensureGeneratedImageBase64WithinLimit("QU=J")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageGenerationDirectClient.ensureGeneratedImageBase64WithinLimit("QUJD!")
        }
    }

    @Test
    fun `response text decoder honors content type charset and defaults to utf8`() {
        val latin1 = "é".toByteArray(Charsets.ISO_8859_1)
        val utf8 = "中文".toByteArray(Charsets.UTF_8)

        assertEquals("é", decodeResponseText(latin1, ContentType.parse("text/plain; charset=ISO-8859-1")))
        assertEquals("中文", decodeResponseText(utf8, null))
    }

    @Test
    fun `sse event limit uses channel byte delta`() {
        ensureSseEventWithinLimit(eventStartBytes = 100, currentBytes = 108, maxEventBytes = 8)
        assertThrows(ResponseBodyTooLargeException::class.java) {
            ensureSseEventWithinLimit(eventStartBytes = 100, currentBytes = 109, maxEventBytes = 8)
        }
    }

    @Test
    fun `sse line reader flushes final event without trailing blank line`() = runBlocking {
        val reader = BoundedSseLineReader(
            ByteReadChannel("data:{\"done\":true}".toByteArray()),
            maxEventBytes = 64,
            maxStreamBytes = 64,
        )

        assertEquals("data:{\"done\":true}", reader.readLine())
        assertEquals("", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun `sse line reader handles crlf without phantom lines`() = runBlocking {
        val reader = BoundedSseLineReader(
            ByteReadChannel("data:a\r\n\r\n".toByteArray()),
            maxEventBytes = 64,
            maxStreamBytes = 64,
        )

        assertEquals("data:a", reader.readLine())
        assertEquals("", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun `sse line reader enforces utf8 byte limit before decoding`() = runBlocking {
        val reader = BoundedSseLineReader(
            ByteReadChannel("data:中文\n\n".toByteArray()),
            maxEventBytes = 10,
            maxStreamBytes = 64,
        )

        try {
            reader.readLine()
            fail("应按 UTF-8 原始字节拒绝超限事件")
        } catch (_: ResponseBodyTooLargeException) {
        }
    }
}
