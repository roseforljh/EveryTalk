package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCachePolicyTest {

    @Test
    fun `tool order and nested map order should normalize identically`() {
        val first = listOf(
            tool("zeta", mapOf("type" to "object", "properties" to mapOf("b" to 2, "a" to 1))),
            tool("alpha", mapOf("required" to listOf("x"), "type" to "object")),
        )
        val second = listOf(
            tool("alpha", mapOf("type" to "object", "required" to listOf("x"))),
            tool("zeta", mapOf("properties" to mapOf("a" to 1, "b" to 2), "type" to "object")),
        )

        assertEquals(PromptCachePolicy.normalizeTools(first), PromptCachePolicy.normalizeTools(second))
        assertEquals(PromptCachePolicy.toolSchemaHash(first), PromptCachePolicy.toolSchemaHash(second))
    }

    @Test
    fun `schema change should change tool hash`() {
        val first = listOf(tool("search", mapOf("type" to "object")))
        val second = listOf(tool("search", mapOf("type" to "array")))

        assertNotEquals(PromptCachePolicy.toolSchemaHash(first), PromptCachePolicy.toolSchemaHash(second))
    }

    @Test
    fun `cache key should be stable and official endpoint only`() {
        val messages = listOf(
            SimpleTextApiMessage(role = "system", content = "stable"),
            SimpleTextApiMessage(role = "user", content = "hello"),
        )
        val tools = listOf(tool("search", mapOf("type" to "object")))

        val first = PromptCachePolicy.buildOpenAICacheKey(
            apiAddress = "https://api.openai.com/v1",
            model = "gpt-5.6",
            messages = messages,
            tools = tools,
        )
        val second = PromptCachePolicy.buildOpenAICacheKey(
            apiAddress = "https://api.openai.com/v1/chat/completions",
            model = "gpt-5.6",
            messages = messages,
            tools = tools.reversed(),
        )

        assertEquals(first, second)
        assertTrue(first.orEmpty().startsWith("et-cap-v1-"))
        assertNull(
            PromptCachePolicy.buildOpenAICacheKey(
                apiAddress = "https://example.com/v1",
                model = "gpt-5.6",
                messages = messages,
                tools = tools,
            ),
        )
    }

    @Test
    fun `cache family should ignore user text and change with system prompt`() {
        fun key(system: String, user: String): String? = PromptCachePolicy.buildOpenAICacheKey(
            apiAddress = "https://api.openai.com",
            model = "gpt-5.6",
            messages = listOf(
                SimpleTextApiMessage(role = "system", content = system),
                SimpleTextApiMessage(role = "user", content = user),
            ),
            tools = null,
        )

        assertEquals(key("stable", "first"), key("stable", "second"))
        assertNotEquals(key("stable", "first"), key("changed", "first"))
    }

    @Test
    fun `model capability gates should follow cache generations`() {
        assertTrue(PromptCachePolicy.supportsPromptCaching("gpt-4o-mini"))
        assertFalse(PromptCachePolicy.supportsPromptCaching("gpt-3.5-turbo"))
        assertTrue(PromptCachePolicy.supportsPromptCaching("gpt-5.6"))
        assertTrue(PromptCachePolicy.supportsPromptCaching("gpt-6"))
    }

    @Test
    fun `tool profiles stay finite and cache key changes when profile changes`() {
        val timeTool = listOf(tool("get_current_datetime", emptyMap()))
        val searchTool = listOf(tool("web_search", emptyMap()))

        assertEquals(PromptCachePolicy.ToolProfile.CURRENT_TIME, PromptCachePolicy.toolProfile(timeTool))
        assertEquals(PromptCachePolicy.ToolProfile.WEB_SEARCH, PromptCachePolicy.toolProfile(searchTool))
        assertNotEquals(
            PromptCachePolicy.buildOpenAICacheKey("https://api.openai.com", "gpt-5.6", emptyList(), timeTool),
            PromptCachePolicy.buildOpenAICacheKey("https://api.openai.com", "gpt-5.6", emptyList(), searchTool),
        )
    }

    @Test
    fun `system fingerprint ignores user text and changes with stable system`() {
        val first = listOf(
            SimpleTextApiMessage(role = "system", content = "stable"),
            SimpleTextApiMessage(role = "user", content = "first"),
        )
        val second = first.map { message ->
            if (message.role == "user") message.copy(content = "second") else message
        }

        assertEquals(PromptCachePolicy.systemFingerprint(first), PromptCachePolicy.systemFingerprint(second))
        assertNotEquals(
            PromptCachePolicy.systemFingerprint(first),
            PromptCachePolicy.systemFingerprint(listOf(SimpleTextApiMessage(role = "system", content = "changed"))),
        )
    }

    private fun tool(name: String, parameters: Map<String, Any>): Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to name,
            "description" to name,
            "parameters" to parameters,
        ),
    )
}
