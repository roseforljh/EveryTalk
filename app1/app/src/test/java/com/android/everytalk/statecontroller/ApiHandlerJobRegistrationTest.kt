package com.android.everytalk.statecontroller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiHandlerJobRegistrationTest {
    @Test
    fun `scope启动前已取消也能结束已登记的文本和图片运行状态`() {
        listOf(false, true).forEach { imageMode ->
            val holder = ViewModelStateHolder()
            val messageId = "prepared-message"
            if (imageMode) {
                holder._currentImageStreamingAiMessageId.value = messageId
                holder._isImageApiCalling.value = true
            } else {
                holder._currentTextStreamingAiMessageId.value = messageId
                holder._isTextApiCalling.value = true
            }
            val scope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
            var bodyEntered = false
            val job = scope.launchRegisteredJob(
                register = { if (imageMode) holder.imageApiJob = it else holder.textApiJob = it },
                onCompletion = { clearCompletedStreamingJob(holder, it, messageId, imageMode) },
            ) { bodyEntered = true }
            assertTrue(job.isCompleted)
            assertFalse(bodyEntered)
            assertNull(if (imageMode) holder.imageApiJob else holder.textApiJob)
            assertNull(if (imageMode) holder._currentImageStreamingAiMessageId.value else holder._currentTextStreamingAiMessageId.value)
            assertFalse(if (imageMode) holder._isImageApiCalling.value else holder._isTextApiCalling.value)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `已登记但未调度的请求被取消时也能解锁`() = runTest {
        val holder = ViewModelStateHolder()
        holder._currentTextStreamingAiMessageId.value = "prepared"
        holder._isTextApiCalling.value = true
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var entered = false
        val job = scope.launchRegisteredJob(
            register = { holder.textApiJob = it },
            onCompletion = { clearCompletedStreamingJob(holder, it, "prepared", false) },
        ) {
            entered = true
            awaitCancellation()
        }
        job.cancelAndJoin()
        assertFalse(entered)
        assertNull(holder.textApiJob)
        assertFalse(holder._isTextApiCalling.value)
        scope.cancel()
    }

    @Test
    fun `旧任务收尾不能清掉新请求和远端取消确认状态`() {
        val holder = ViewModelStateHolder()
        val oldJob = Job()
        val newJob = Job()
        holder.textApiJob = newJob
        holder._currentTextStreamingAiMessageId.value = "new-message"
        holder._isTextApiCalling.value = true
        holder._isRemoteCancellationPending.value = true
        clearCompletedStreamingJob(holder, oldJob, "old-message", false)
        assertSame(newJob, holder.textApiJob)
        assertEquals("new-message", holder._currentTextStreamingAiMessageId.value)
        assertTrue(holder._isTextApiCalling.value)
        assertTrue(holder._isRemoteCancellationPending.value)
        oldJob.complete()
        newJob.complete()
    }

    @Test
    fun `正常审批等待主动释放Job所有权后完成兜底不会误派发队列`() {
        val holder = ViewModelStateHolder()
        val job = Job()
        holder.textApiJob = null
        holder._currentTextStreamingAiMessageId.value = "approval-message"
        holder._isTextApiCalling.value = true
        clearCompletedStreamingJob(holder, job, "approval-message", false)
        assertTrue(holder._isTextApiCalling.value)
        assertEquals("approval-message", holder._currentTextStreamingAiMessageId.value)
        job.complete()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `远端停止永不返回时等待有上限但后台Job不被取消`() = runTest {
        val remote = Job()
        val startedAt = testScheduler.currentTime
        assertFalse(awaitRemoteStopConfirmation(remote) { true })
        assertEquals(REMOTE_STOP_CONFIRMATION_TIMEOUT_MS, testScheduler.currentTime - startedAt)
        assertTrue(remote.isActive)
        // 后台确认成功后，下一次核对可以立即结束，不必重复发起停止。
        remote.complete()
        assertTrue(awaitRemoteStopConfirmation(remote) { true })
        assertFalse(awaitRemoteStopConfirmation(remote) { false })
    }

    @Test
    fun `立即完成的恢复任务也能识别自己并清除处理中状态`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var registeredJob: Job? = null
        var cleanupRan = false

        val job = scope.launchRegisteredJob(
            register = { registeredJob = it },
        ) {
            val thisJob = coroutineContext[Job]
            try {
                // 远端任务已经完成时，恢复流可能不发生任何挂起便直接结束。
            } finally {
                if (registeredJob == thisJob) {
                    registeredJob = null
                    cleanupRan = true
                }
            }
        }
        runBlocking { job.join() }

        assertTrue("恢复任务结束后必须执行当前任务清理", cleanupRan)
        assertNull("处理中 Job 必须清空", registeredJob)
        scope.cancel()
    }
}
