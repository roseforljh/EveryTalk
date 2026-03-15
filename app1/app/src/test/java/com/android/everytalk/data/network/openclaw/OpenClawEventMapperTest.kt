package com.android.everytalk.data.network.openclaw

import com.android.everytalk.data.network.AppStreamEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawEventMapperTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `maps text delta event to content`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "event": "chat",
              "data": {
                "type": "content.delta",
                "text": "hello"
              }
            }
            """.trimIndent(),
            json
        )

        assertTrue(event is AppStreamEvent.Content)
        assertEquals("hello", (event as AppStreamEvent.Content).text)
    }

    @Test
    fun `maps completed event to finish`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "event": "chat",
              "data": {
                "type": "run.completed"
              }
            }
            """.trimIndent(),
            json
        )

        assertTrue(event is AppStreamEvent.Finish)
        assertEquals("completed", (event as AppStreamEvent.Finish).reason)
    }

    @Test
    fun `maps error event to error`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "event": "chat",
              "data": {
                "type": "error",
                "message": "gateway failed"
              }
            }
            """.trimIndent(),
            json
        )

        assertTrue(event is AppStreamEvent.Error)
        assertEquals("gateway failed", (event as AppStreamEvent.Error).message)
    }

    @Test
    fun `returns null for unknown chat event`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "event": "chat",
              "data": {
                "type": "unknown.event"
              }
            }
            """.trimIndent(),
            json
        )

        assertNull(event)
    }
}
