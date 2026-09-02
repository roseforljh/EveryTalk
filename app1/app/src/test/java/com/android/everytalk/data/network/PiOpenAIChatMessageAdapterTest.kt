package com.android.everytalk.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class PiOpenAIChatMessageAdapterTest {
    @Test
    fun `Responses管道ID转换结果与Pi金标一致`() {
        val id = "call.id|fc/item/with/very/very/very/very/very/very/long/value"

        assertEquals(
            "call_id_1rj47g21",
            PiOpenAIChatMessageAdapter.normalizeToolCallId(id, provider = "openai"),
        )
    }

    @Test
    fun `OpenAI普通ID限制40字符而兼容Provider保持原ID`() {
        val id = "x".repeat(50)

        assertEquals("x".repeat(40), PiOpenAIChatMessageAdapter.normalizeToolCallId(id, "openai"))
        assertEquals(id, PiOpenAIChatMessageAdapter.normalizeToolCallId(id, "custom"))
    }
}
