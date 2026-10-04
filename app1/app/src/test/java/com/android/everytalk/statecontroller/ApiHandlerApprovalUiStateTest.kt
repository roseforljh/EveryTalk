package com.android.everytalk.statecontroller

import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.agent.AgentRunStatus
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiHandlerApprovalUiStateTest {
    @Test
    fun `等待Agent审批时保留文本会话进行中状态`() {
        assertTrue(
            shouldKeepApprovalUiActive(
                waitingForAgentApproval = true,
                isImageGeneration = false,
            ),
        )
    }

    @Test
    fun `等待人工协助时同样保留文本会话进行中状态`() {
        assertTrue(
            shouldKeepApprovalUiActive(
                waitingForAgentApproval = true,
                isImageGeneration = false,
            ),
        )
    }

    @Test
    fun `普通结束和图片任务不会保留审批中的文本状态`() {
        assertFalse(
            shouldKeepApprovalUiActive(
                waitingForAgentApproval = false,
                isImageGeneration = false,
            ),
        )
        assertFalse(
            shouldKeepApprovalUiActive(
                waitingForAgentApproval = true,
                isImageGeneration = true,
            ),
        )
    }

    @Test
    fun `发出审批后异常或取消不能凭历史事件保留永久加载状态`() {
        assertFalse(shouldKeepApprovalUiActive(true, false, completedNormally = false))
        assertTrue(shouldKeepApprovalUiActive(true, false, completedNormally = true))
    }

    @Test
    fun `可重试网络中断后的续写仍保持Agent界面运行态`() {
        assertTrue(
            shouldKeepResumedAgentUiActive(
                AppStreamEvent.Error(
                    message = "连接中断",
                    code = "connection_aborted",
                    type = "retryable_network",
                )
            )
        )
        assertTrue(shouldKeepResumedAgentUiActive(AppStreamEvent.Content("继续输出")))
    }

    @Test
    fun `Agent真正结束或永久失败才允许界面归位`() {
        assertFalse(shouldKeepResumedAgentUiActive(AppStreamEvent.Finish("stop")))
        assertFalse(shouldKeepResumedAgentUiActive(AppStreamEvent.StreamEnd("message-1")))
        assertFalse(shouldKeepResumedAgentUiActive(AppStreamEvent.Error("永久失败")))
    }

    @Test
    fun `Room恢复状态只让未结束Run占用界面`() {
        assertTrue(isActiveAgentUiStatus(AgentRunStatus.WAITING_REMOTE_EXECUTION))
        assertTrue(isActiveAgentUiStatus(AgentRunStatus.MODEL_CONTINUATION_PENDING))
        assertFalse(isActiveAgentUiStatus(AgentRunStatus.INTERRUPTED))
        assertFalse(isActiveAgentUiStatus(AgentRunStatus.COMPLETED))
        assertTrue(restoredAgentExecutionStatus(AgentRunStatus.MODEL_CONTINUATION_PENDING)?.contains("恢复") == true)
    }

    @Test
    fun `恢复流再次遇到工具审批时收尾保持会话进行中与消息ID`() {
        val stateHolder = ViewModelStateHolder()
        val job = Job()
        stateHolder.textApiJob = job
        stateHolder._isTextApiCalling.value = true
        stateHolder._currentTextStreamingAiMessageId.value = "resumed-msg-1"

        finalizeResumedAgentStreamingState(
            stateHolder = stateHolder,
            visibleAssistantMessageId = "resumed-msg-1",
            waitingForAgentApproval = true,
            completedNormally = true,
        )

        assertNull("原流的 Job 引用应被正常解绑", stateHolder.textApiJob)
        assertTrue("二次审批等待期间必须保留进行中状态，禁止提前解锁输入框", stateHolder._isTextApiCalling.value)
        assertEquals("resumed-msg-1", stateHolder._currentTextStreamingAiMessageId.value)
    }

    @Test
    fun `恢复流正常全部完成时收尾清空会话进行中状态`() {
        val stateHolder = ViewModelStateHolder()
        stateHolder.textApiJob = Job()
        stateHolder._isTextApiCalling.value = true
        stateHolder._currentTextStreamingAiMessageId.value = "resumed-msg-2"

        finalizeResumedAgentStreamingState(
            stateHolder = stateHolder,
            visibleAssistantMessageId = "resumed-msg-2",
            waitingForAgentApproval = false,
            completedNormally = true,
        )

        assertNull(stateHolder.textApiJob)
        assertFalse(stateHolder._isTextApiCalling.value)
        assertNull(stateHolder._currentTextStreamingAiMessageId.value)
    }

    @Test
    fun `恢复流异常中断时即使有审批标记也不保留进行中状态`() {
        val stateHolder = ViewModelStateHolder()
        stateHolder.textApiJob = Job()
        stateHolder._isTextApiCalling.value = true
        stateHolder._currentTextStreamingAiMessageId.value = "resumed-msg-3"

        finalizeResumedAgentStreamingState(
            stateHolder = stateHolder,
            visibleAssistantMessageId = "resumed-msg-3",
            waitingForAgentApproval = true,
            completedNormally = false,
        )

        assertNull(stateHolder.textApiJob)
        assertFalse("异常中断不得依据历史审批标记误保留加载态", stateHolder._isTextApiCalling.value)
        assertNull(stateHolder._currentTextStreamingAiMessageId.value)
    }
}
