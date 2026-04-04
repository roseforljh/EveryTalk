package com.android.everytalk.statecontroller

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelWebFetchDispatchTest {

    @Test
    fun `webfetch tool is dispatched locally`() = runTest {
        val arguments = buildJsonObject {
            put("url", JsonPrimitive("https://example.com"))
        }
        val localResult = buildJsonObject {
            put("ok", JsonPrimitive(true))
        }
        var fallbackCalled = false
        val statusUpdates = mutableListOf<String?>()

        val result = executeSharedToolCall(
            toolName = BUILT_IN_WEBFETCH_TOOL_NAME,
            arguments = arguments,
            updateStatus = { statusUpdates.add(it) },
            localWebFetchExecutor = { localResult },
            fallbackExecutor = { _, _ ->
                fallbackCalled = true
                JsonObject(emptyMap())
            }
        )

        assertSame(localResult, result)
        assertTrue(!fallbackCalled)
        assertEquals(listOf("正在分析链接", null), statusUpdates)
    }

    @Test
    fun `non webfetch tools keep MCP fallback unchanged`() = runTest {
        val arguments = buildJsonObject {
            put("query", JsonPrimitive("hello"))
        }
        var fallbackToolName: String? = null
        var fallbackArguments: JsonObject? = null
        val fallbackResult = buildJsonObject {
            put("source", JsonPrimitive("mcp"))
        }
        val statusUpdates = mutableListOf<String?>()

        val result = executeSharedToolCall(
            toolName = "search_docs",
            arguments = arguments,
            updateStatus = { statusUpdates.add(it) },
            localWebFetchExecutor = {
                error("local webfetch executor should not run")
            },
            fallbackExecutor = { toolName, jsonArguments ->
                fallbackToolName = toolName
                fallbackArguments = jsonArguments
                fallbackResult
            }
        )

        assertSame(fallbackResult, result)
        assertEquals("search_docs", fallbackToolName)
        assertEquals(arguments, fallbackArguments)
        assertTrue(statusUpdates.isEmpty())
    }

    @Test
    fun `current time tool is dispatched locally`() = runTest {
        val localResult = buildJsonObject {
            put("now", JsonPrimitive("2026-04-04T12:00:00+08:00"))
        }
        var fallbackCalled = false
        val statusUpdates = mutableListOf<String?>()

        val result = executeSharedToolCall(
            toolName = BUILT_IN_CURRENT_TIME_TOOL_NAME,
            arguments = buildJsonObject {},
            updateStatus = { statusUpdates.add(it) },
            localWebFetchExecutor = { error("webfetch executor should not run") },
            localCurrentTimeExecutor = { localResult },
            fallbackExecutor = { _, _ ->
                fallbackCalled = true
                JsonObject(emptyMap())
            }
        )

        assertSame(localResult, result)
        assertTrue(!fallbackCalled)
        assertEquals(listOf("正在获取当前时间", null), statusUpdates)
    }
}
