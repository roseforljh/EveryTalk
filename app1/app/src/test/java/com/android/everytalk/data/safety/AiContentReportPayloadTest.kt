package com.android.everytalk.data.safety

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiContentReportPayloadTest {

    @Test
    fun `report payload is bounded and excludes generated image locations`() {
        val message = Message(
            id = "message-1",
            text = "a".repeat(3_999) + '\u0000' + "b".repeat(200),
            sender = Sender.AI,
            imageUrls = listOf("https://example.com/private.png?token=secret"),
            modelName = "m".repeat(300),
            providerName = "provider",
        )

        val payload = createAiContentReportPayload(
            message = message,
            category = AiContentReportCategory.OTHER,
            details = "d".repeat(700),
            isImageGeneration = true,
            reportId = "report-1",
            createdAtEpochMillis = 123L,
        )

        assertEquals("report-1", payload.reportId)
        assertEquals(4_000, payload.messageText.length)
        assertFalse(payload.messageText.contains('\u0000'))
        assertEquals(500, payload.details.length)
        assertEquals(1, payload.imageCount)
        assertEquals(200, payload.modelName?.length)
        assertTrue(payload.isImageGeneration)
        assertFalse(payload.toString().contains("private.png"))
        assertFalse(payload.toString().contains("secret"))
    }
}
