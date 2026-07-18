package com.android.everytalk.ui.components.streamdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamdownEntryPointTest {
    @Test
    fun `entry point enum covers every production message render path`() {
        val names = StreamdownEntryPoint.entries.map { it.name }.toSet()

        assertEquals(
            setOf(
                "StreamingMessageStateManager",
                "ChatMessagesList",
                "ContentCoordinator",
                "TableAwareText",
                "ImageGenerationMessagesList",
                "BubbleContentTypes",
            ),
            names,
        )
    }

    @Test
    fun `telemetry event records entry point lengths and fallback reason`() {
        val event = StreamdownTelemetry.Event(
            entryPoint = StreamdownEntryPoint.ChatMessagesList,
            rawLength = 12,
            displayLength = 18,
            fallbackReason = "markwon-fallback",
        )

        assertEquals(StreamdownEntryPoint.ChatMessagesList, event.entryPoint)
        assertEquals(12, event.rawLength)
        assertEquals(18, event.displayLength)
        assertEquals("markwon-fallback", event.fallbackReason)

        val sink = StreamdownTelemetry.InMemorySink()
        StreamdownTelemetry.record(event, sink)
        assertTrue(sink.events.contains(event))
    }
}
