package com.android.everytalk.statecontroller

import com.android.everytalk.data.network.AppStreamEvent
import org.junit.Assert.assertFalse
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
}
