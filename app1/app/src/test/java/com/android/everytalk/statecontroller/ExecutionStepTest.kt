package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.ExecutionStepType
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.computer.ComputerToolNames
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.database.Converters
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionStepTest {
    @Test
    fun `真实流事件严格保留思考与工具的交错顺序`() {
        val events = listOf(
            AppStreamEvent.Reasoning("先读取系统配置。"),
            AppStreamEvent.ToolCall(
                id = "call-1",
                name = ComputerToolNames.EXEC,
                argumentsObj = buildJsonObject { put("command", JsonPrimitive("uname -a")) },
            ),
            AppStreamEvent.ExecutionStatusUpdate(
                status = null,
                toolCallId = "call-1",
                executionId = "execution-1",
            ),
            AppStreamEvent.Reasoning("再检查磁盘占用。"),
            AppStreamEvent.ToolCall(
                id = "call-2",
                name = ComputerToolNames.EXEC,
                argumentsObj = buildJsonObject { put("command", JsonPrimitive("df -h")) },
            ),
            AppStreamEvent.Finish("stop"),
        )

        val trace = events.fold(emptyList<ExecutionTraceEvent>()) { current, event ->
            reduceExecutionTrace(current, event)
        }

        assertEquals(
            listOf("先读取系统配置。", "uname -a", "再检查磁盘占用。", "df -h"),
            trace.map { event ->
                when (event) {
                    is ExecutionTraceEvent.Content -> event.text
                    is ExecutionTraceEvent.Reasoning -> event.text
                    is ExecutionTraceEvent.Tool -> event.step.labels.single()
                }
            },
        )
        assertTrue(trace.filterIsInstance<ExecutionTraceEvent.Tool>().all { it.step.completed })
    }

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

    @Test
    fun `旧版执行步骤缺少顺序字段时仍能解析`() {
        val converters = Converters()

        val steps = converters.toExecutionStepList(
            """[{"id":"legacy","type":"Tool","title":"调用工具","labels":["clock"]}]"""
        )

        assertEquals(1, steps.size)
        assertEquals(null, steps.single().reasoningBefore)
    }

    @Test
    fun `有序执行链可序列化并恢复`() {
        val converters = Converters()
        val trace = listOf(
            ExecutionTraceEvent.Content("先说明目标。"),
            ExecutionTraceEvent.Reasoning("先检查系统。"),
            ExecutionTraceEvent.Tool(
                executionStepForToolCall(
                    AppStreamEvent.ToolCall(
                        id = "call-1",
                        name = ComputerToolNames.EXEC,
                        argumentsObj = buildJsonObject {
                            put("command", JsonPrimitive("uname -a"))
                        },
                    )
                )
            ),
        )

        assertEquals(trace, converters.toExecutionTrace(converters.fromExecutionTrace(trace)))
    }

    @Test
    fun `前导正文后 Agent 工具仍保持完整运行状态`() {
        val message = Message(
            id = "agent-loop",
            text = "我来检查一下服务器",
            sender = Sender.AI,
            contentStarted = true,
        )
        val toolCall = AppStreamEvent.ToolCall(
            id = "call-1",
            name = ComputerToolNames.EXEC,
            argumentsObj = buildJsonObject {
                put("command", JsonPrimitive("uname -a"))
            },
        )

        val pending = applyToolCallEventToMessage(message, toolCall)
        assertEquals("运行 Agent · exec", pending.currentWebSearchStage)
        assertEquals(listOf("uname -a"), pending.executionSteps.single().labels)
        assertFalse(pending.executionSteps.single().completed)

        val running = applyExecutionStatusEventToMessage(
            pending,
            AppStreamEvent.ExecutionStatusUpdate("执行服务器命令"),
        )
        assertEquals("执行服务器命令", running.executionStatus)
        assertFalse(running.executionSteps.single().completed)

        val continuing = applyExecutionStatusEventToMessage(
            running,
            AppStreamEvent.ExecutionStatusUpdate(null),
        )
        assertEquals(AGENT_LOOP_CONTINUING_STATUS, continuing.executionStatus)
        assertTrue(continuing.executionSteps.single().completed)
    }

    @Test
    fun `同一轮多个工具按调用顺序完成`() {
        val firstCall = AppStreamEvent.ToolCall(
            id = "call-1",
            name = ComputerToolNames.EXEC,
            argumentsObj = buildJsonObject {},
        )
        val secondCall = AppStreamEvent.ToolCall(
            id = "call-2",
            name = ComputerToolNames.READ_FILE,
            argumentsObj = buildJsonObject {},
        )
        val pending = applyToolCallEventToMessage(
            applyToolCallEventToMessage(
                Message(id = "agent-loop", text = "", sender = Sender.AI),
                firstCall,
            ),
            secondCall,
        )

        val afterFirstResult = applyExecutionStatusEventToMessage(
            pending,
            AppStreamEvent.ExecutionStatusUpdate(null),
        )

        assertTrue(afterFirstResult.executionSteps[0].completed)
        assertFalse(afterFirstResult.executionSteps[1].completed)
    }

    @Test
    fun `每个工具只记录它前面尚未归档的思考片段`() {
        val firstCall = AppStreamEvent.ToolCall(
            id = "call-1",
            name = ComputerToolNames.EXEC,
            argumentsObj = buildJsonObject {},
        )
        val secondCall = AppStreamEvent.ToolCall(
            id = "call-2",
            name = ComputerToolNames.EXEC,
            argumentsObj = buildJsonObject {},
        )

        val afterFirst = applyToolCallEventToMessage(
            message = Message(id = "ordered-agent-loop", text = "", sender = Sender.AI),
            event = firstCall,
            reasoningText = "先读取系统信息。",
        )
        val afterSecond = applyToolCallEventToMessage(
            message = afterFirst,
            event = secondCall,
            reasoningText = "先读取系统信息。再检查磁盘。",
        )

        assertEquals("先读取系统信息。", afterSecond.executionSteps[0].reasoningBefore)
        assertEquals("再检查磁盘。", afterSecond.executionSteps[1].reasoningBefore)
    }

    @Test
    fun `重复工具事件不会覆盖首次保存的思考片段`() {
        val toolCall = AppStreamEvent.ToolCall(
            id = "call-1",
            name = ComputerToolNames.EXEC,
            argumentsObj = buildJsonObject {},
        )
        val afterFirst = applyToolCallEventToMessage(
            message = Message(id = "duplicate-tool-call", text = "", sender = Sender.AI),
            event = toolCall,
            reasoningText = "先确认系统版本。",
        )

        val afterDuplicate = applyToolCallEventToMessage(
            message = afterFirst,
            event = toolCall,
            reasoningText = "先确认系统版本。",
        )

        assertEquals(1, afterDuplicate.executionSteps.size)
        assertEquals("先确认系统版本。", afterDuplicate.executionSteps.single().reasoningBefore)
    }
}
