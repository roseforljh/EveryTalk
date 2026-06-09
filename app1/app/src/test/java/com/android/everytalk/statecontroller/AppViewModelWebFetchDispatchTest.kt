package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
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
    fun `history click reloads same stable conversation after save reorders list`() {
        val clickedConversation = listOf(
            Message(id = "user-clicked", text = "旧会话", sender = Sender.User),
            Message(id = "ai-clicked", text = "旧回答", sender = Sender.AI),
        )
        val currentConversation = listOf(
            Message(id = "user-current", text = "问题 a", sender = Sender.User),
            Message(id = "ai-current", text = "回答 b", sender = Sender.AI),
        )
        val beforeSave = listOf(clickedConversation)
        val afterSave = listOf(currentConversation, clickedConversation)

        val resolvedIndex = resolveHistoryIndexAfterSave(
            requestedIndex = 0,
            historyBeforeSave = beforeSave,
            historyAfterSave = afterSave,
        )

        assertEquals(1, resolvedIndex)
    }

    @Test
    fun `history click prefers completed conversation over stale user only duplicate`() {
        val completedConversation = listOf(
            Message(id = "actual-user-message-id", text = "你好", sender = Sender.User),
            Message(id = "ai-reply", text = "你好！请问有什么我可以帮你的吗？", sender = Sender.AI),
        )
        val staleUserOnlyConversation = listOf(
            Message(id = "temp-conversation-id", text = "你好", sender = Sender.User),
        )
        val history = listOf(completedConversation, staleUserOnlyConversation)

        val resolvedIndex = resolveHistoryIndexAfterSave(
            requestedIndex = 1,
            historyBeforeSave = history,
            historyAfterSave = history,
        )

        assertEquals(0, resolvedIndex)
    }

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
        assertEquals(listOf<String?>(null), statusUpdates)
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
        assertEquals(listOf<String?>(null), statusUpdates)
    }
}
