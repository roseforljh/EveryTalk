package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.ExecutionStepType
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.database.Converters
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionStepTest {
    @Test
    fun `搜索工具保留真实查询并合并同一调用`() {
        val event = AppStreamEvent.ToolCall(
            id = "search-1",
            name = "web_search_exa",
            argumentsObj = buildJsonObject {
                put("query", JsonPrimitive("EveryTalk timeline"))
            },
        )
        val step = executionStepForToolCall(event)

        assertEquals(ExecutionStepType.Search, step.type)
        assertEquals("搜索网页", step.title)
        assertEquals(listOf("EveryTalk timeline"), step.labels)
        assertEquals(listOf(step), mergeExecutionStep(emptyList(), step))
        assertEquals(
            1,
            mergeExecutionStep(listOf(step.copy(completed = true)), step).size,
        )
        assertTrue(mergeExecutionStep(listOf(step.copy(completed = true)), step).single().completed)
    }

    @Test
    fun `网页读取和普通工具生成对应胶囊内容`() {
        val webStep = executionStepForToolCall(
            AppStreamEvent.ToolCall(
                id = "fetch-1",
                name = "webfetch",
                argumentsObj = buildJsonObject {
                    put("url", JsonPrimitive("https://developer.android.com/compose"))
                },
            )
        )
        val toolStep = executionStepForToolCall(
            AppStreamEvent.ToolCall(
                id = "tool-1",
                name = "local_clock",
                argumentsObj = buildJsonObject {},
            )
        )

        assertEquals(ExecutionStepType.Web, webStep.type)
        assertEquals(listOf("https://developer.android.com/compose"), webStep.labels)
        assertEquals(ExecutionStepType.Tool, toolStep.type)
        assertEquals(listOf("local_clock"), toolStep.labels)

        val converters = Converters()
        assertEquals(
            listOf(webStep, toolStep),
            converters.toExecutionStepList(converters.fromExecutionStepList(listOf(webStep, toolStep))),
        )
    }
}
