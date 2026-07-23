package com.android.everytalk.data.network.direct

import com.android.everytalk.data.network.ResponseBodyTooLargeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsDirectClientBufferTest {

    @Test
    fun `json byte buffer preserves utf8 split across network chunks`() {
        val json = "{\"message\":\"中文\",\"literal\":\"}{\"}"
        val payload = "data: $json".toByteArray(Charsets.UTF_8)
        val chineseStart = payload.indexOfFirst { (it.toInt() and 0xff) == 0xe4 }
        val split = chineseStart + 1
        val buffer = BoundedJsonObjectByteBuffer(128)

        assertTrue(buffer.append(payload.copyOfRange(0, split), split).isEmpty())
        assertEquals(split, buffer.bufferedByteCount)

        val remaining = payload.copyOfRange(split, payload.size)
        assertEquals(listOf(json), buffer.append(remaining, remaining.size))
        assertEquals(0, buffer.bufferedByteCount)
    }

    @Test
    fun `json byte buffer enforces exact unconsumed byte limit`() {
        val buffer = BoundedJsonObjectByteBuffer(8)
        val first = "data: {".toByteArray()
        buffer.append(first, first.size)

        assertThrows(ResponseBodyTooLargeException::class.java) {
            val overflow = "中".toByteArray(Charsets.UTF_8)
            buffer.append(overflow, overflow.size)
        }
    }

    @Test
    fun `bare aliyun json error is propagated`() {
        val error = assertThrows(AliyunTtsApiException::class.java) {
            parseAliyunTtsJsonOrNull(
                """{"code":"InvalidParameter","message":"voice not found"}""",
            )
        }

        assertEquals("Aliyun TTS Error: voice not found (InvalidParameter)", error.message)
    }
}
