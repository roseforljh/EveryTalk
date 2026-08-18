package com.android.everytalk.statecontroller

import com.android.everytalk.data.agent.AGENT_INTERNAL_ERROR_TYPE
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.network.AppStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiHandlerRetryPolicyTest {

    @Test
    fun `Agent内部错误不会被包装成网络异常`() {
        val error = streamEventErrorThrowable(
            AppStreamEvent.Error(
                message = "FOREIGN KEY constraint failed",
                type = AGENT_INTERNAL_ERROR_TYPE,
            )
        )

        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `Provider原始错误原因必须传给用户错误文本`() {
        val error = streamEventErrorThrowable(
            AppStreamEvent.Error(
                message = "Gemini API 错误: 400 Bad Request",
                rawMessage = "Function call is missing a thought_signature",
            )
        )

        assertTrue(error.message.orEmpty().contains("Function call is missing a thought_signature"))
    }

    @Test
    fun `network retry never returns early without retry action`() {
        val shouldReturnEarly = shouldReturnEarlyForNetworkRetry(
            allowRetry = true,
            isNetworkError = true,
            currentRetryCount = 0,
            maxRetryAttempts = 3,
            hasRetryAction = false,
        )

        assertFalse(shouldReturnEarly)
    }

    @Test
    fun `network retry can return early only when retry action exists`() {
        val shouldReturnEarly = shouldReturnEarlyForNetworkRetry(
            allowRetry = true,
            isNetworkError = true,
            currentRetryCount = 0,
            maxRetryAttempts = 3,
            hasRetryAction = true,
        )

        assertTrue(shouldReturnEarly)
    }

    @Test
    fun `non network errors never return early`() {
        val shouldReturnEarly = shouldReturnEarlyForNetworkRetry(
            allowRetry = true,
            isNetworkError = false,
            currentRetryCount = 0,
            maxRetryAttempts = 3,
            hasRetryAction = true,
        )

        assertFalse(shouldReturnEarly)
    }

    @Test
    fun `reconcile message after status clear keeps cleared terminal status`() {
        val staleUpdatedMessage = Message(
            id = "ai-1",
            text = "已经开始输出",
            sender = Sender.AI,
            contentStarted = true,
            currentWebSearchStage = "searching_web",
            executionStatus = "后端进度：正在汇总结果",
        )
        val clearedMessage = staleUpdatedMessage.copy(
            currentWebSearchStage = null,
            executionStatus = null,
        )

        val reconciledMessage = reconcileMessageAfterStatusClear(
            updatedMessage = staleUpdatedMessage,
            clearedMessage = clearedMessage,
        )

        assertEquals("已经开始输出", reconciledMessage.text)
        assertTrue(reconciledMessage.contentStarted)
        assertNull(reconciledMessage.currentWebSearchStage)
        assertNull(reconciledMessage.executionStatus)
    }

    @Test
    fun `reconcile message after status clear preserves latest non status fields`() {
        val staleUpdatedMessage = Message(
            id = "ai-2",
            text = "最终答案",
            sender = Sender.AI,
            contentStarted = true,
            currentWebSearchStage = "webfetch_reading",
            executionStatus = "我先帮你读一下网页内容…",
            outputType = "markdown",
        )
        val clearedMessage = Message(
            id = "ai-2",
            text = "旧文本",
            sender = Sender.AI,
            contentStarted = false,
            currentWebSearchStage = null,
            executionStatus = null,
            outputType = "general",
        )

        val reconciledMessage = reconcileMessageAfterStatusClear(
            updatedMessage = staleUpdatedMessage,
            clearedMessage = clearedMessage,
        )

        assertEquals("最终答案", reconciledMessage.text)
        assertTrue(reconciledMessage.contentStarted)
        assertEquals("markdown", reconciledMessage.outputType)
        assertNull(reconciledMessage.currentWebSearchStage)
        assertNull(reconciledMessage.executionStatus)
    }
}
