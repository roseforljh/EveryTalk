package com.android.everytalk.data.agent

import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ModelParameters
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.resolvedModelTokenLimits
import com.android.everytalk.data.database.entities.AgentRunEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunCoordinatorTest {
    @Test
    fun `拒绝续写等待原 Run 收尾后主动唤醒且不等待其他任务`() = runTest {
        val previous = Job()
        val unrelated = Job()
        val calls = mutableListOf<String>()
        val jobs = mapOf("message:${run.visibleAssistantMessageId}" to previous, "run:other" to unrelated)

        val continuation = backgroundScope.launchInterventionContinuation(jobs, run) { calls += "resumed" }
        testScheduler.runCurrent()
        assertTrue(calls.isEmpty())
        assertFalse(continuation.isCompleted)

        previous.complete()
        testScheduler.runCurrent()
        assertEquals(listOf("resumed"), calls)
        assertTrue(continuation.isCompleted)
        assertTrue(unrelated.isActive)
        unrelated.cancel()
    }

    @Test
    fun `没有旧 Run 时拒绝续写不等待恢复轮询`() = runTest {
        var resumed = false
        val continuation = backgroundScope.launchInterventionContinuation(emptyMap(), run) { resumed = true }
        testScheduler.runCurrent()
        assertTrue(resumed)
        assertTrue(continuation.isCompleted)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `恢复的 Run 收尾交接不会取消原任务或重复续写`() = runTest {
        val previous = Job()
        var count = 0
        val jobs = mapOf("run:${run.id}" to previous, "message:${run.visibleAssistantMessageId}" to previous)
        val continuation = backgroundScope.launchInterventionContinuation(jobs, run) { count++ }
        testScheduler.runCurrent()
        assertTrue(previous.isActive)
        previous.complete()
        testScheduler.runCurrent()
        assertEquals(1, count)
        assertTrue(continuation.isCompleted)
    }

    private val run = AgentRunEntity(
        id = "run-1",
        sessionId = "session-1",
        userMessageId = "user-1",
        visibleAssistantMessageId = "assistant-1",
        configIdSnapshot = null,
        requestSnapshotJson = null,
        status = AgentRunStatus.WAITING_REMOTE_EXECUTION.name,
        currentRequestOrdinal = 1,
        terminalReason = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `首次运行按消息登记时恢复器仍识别为活跃`() {
        val activeJob = Job()

        assertTrue(
            isAgentRunActive(
                activeJobs = mapOf("message:${run.visibleAssistantMessageId}" to activeJob),
                run = run,
            )
        )

        activeJob.cancel()
        assertFalse(
            isAgentRunActive(
                activeJobs = mapOf("message:${run.visibleAssistantMessageId}" to activeJob),
                run = run,
            )
        )
    }

    @Test
    fun `只有使用过VPS且整个Run终止才发送最终通知`() {
        assertTrue(shouldNotifyAgentRunTerminal(AgentRunStatus.COMPLETED, computerExecutionCount = 2))
        assertTrue(shouldNotifyAgentRunTerminal(AgentRunStatus.FAILED, computerExecutionCount = 1))
        assertFalse(shouldNotifyAgentRunTerminal(AgentRunStatus.COMPLETED, computerExecutionCount = 0))
        assertFalse(shouldNotifyAgentRunTerminal(AgentRunStatus.WAITING_MODEL, computerExecutionCount = 1))
    }

    @Test
    fun `恢复失败逐步退避且最长一分钟`() {
        assertEquals(2_000L, agentResumeRetryDelayMillis(1))
        assertEquals(5_000L, agentResumeRetryDelayMillis(2))
        assertEquals(15_000L, agentResumeRetryDelayMillis(3))
        assertEquals(30_000L, agentResumeRetryDelayMillis(4))
        assertEquals(60_000L, agentResumeRetryDelayMillis(20))
    }

    @Test
    fun `只有模型待续写状态保留恢复退避`() {
        assertTrue(shouldBackoffAgentResume(AgentRunStatus.MODEL_CONTINUATION_PENDING.name))
        assertFalse(shouldBackoffAgentResume(AgentRunStatus.WAITING_APPROVAL.name))
        assertFalse(shouldBackoffAgentResume(AgentRunStatus.COMPLETED.name))
        assertFalse(shouldBackoffAgentResume(null))
    }

    @Test
    fun `前台Agent事件使用挂起发送保持单通道顺序`() {
        val source = agentRunCoordinatorSource()
        val firstRunCollector = source.substringAfter("agentLoop.run(request).collect { event ->")
            .substringBefore("notifyTerminalRun")

        assertTrue(firstRunCollector.contains("send(event)"))
        assertFalse(firstRunCollector.contains("trySend(event)"))
    }

    @Test
    fun `中断工具恢复必须把账本决策交回同一个AgentLoop`() {
        val source = agentRunCoordinatorSource()
        val recovery = source.substringAfter("suspend fun resumeInterruptedToolRuns")
            .substringBefore("fun cancelRun")

        assertTrue(recovery.contains("resumableApprovalRuns(computerDao)"))
        assertTrue(recovery.contains("resumeRun(run, record)"))
        assertTrue(source.contains("approvalDecision = approvalDecision"))
    }

    @Test
    fun `极快完成的任务不会在完成后重新登记成活跃`() = runTest {
        val jobs = ConcurrentHashMap<String, Job>()
        val pauses = AgentRunPauseController()
        val job = launchTrackedAgentJob(jobs, "message:fast", pauses, "fast") { }
        assertEquals(job, jobs["message:fast"])
        job.start()
        job.join()
        assertTrue(jobs.isEmpty())
        assertTrue(pauses.snapshots.value.isEmpty())
    }

    @Test
    fun `启动前取消也清理暂停控制槽且不运行任务`() = runTest {
        val jobs = ConcurrentHashMap<String, Job>()
        val pauses = AgentRunPauseController()
        var started = false
        val job = launchTrackedAgentJob(jobs, "message:cancelled", pauses, "cancelled") { started = true }
        job.cancel()
        job.join()
        assertFalse(started)
        assertTrue(jobs.isEmpty())
        assertTrue(pauses.snapshots.value.isEmpty())
    }

    @Test
    fun `旧任务尚未收尾时不能被同键的新任务覆盖`() = runTest {
        val jobs = ConcurrentHashMap<String, Job>()
        val pauses = AgentRunPauseController()
        val job = launchTrackedAgentJob(jobs, "message:same", pauses, "same") { }
        val duplicate = runCatching { launchTrackedAgentJob(jobs, "message:same", pauses, "same") { } }
        assertTrue(duplicate.isFailure)
        assertEquals(job, jobs["message:same"])
        job.cancel()
        job.join()
        assertTrue(jobs.isEmpty())
    }

    @Test
    fun `恢复准备期间停止任务使旧快照失效`() {
        assertTrue(canExecuteAgentSnapshot(run, run))
        assertFalse(canExecuteAgentSnapshot(run, run.copy(status = AgentRunStatus.CANCELLED.name)))
        assertFalse(canExecuteAgentSnapshot(run, run.copy(runGeneration = run.runGeneration + 1)))
        assertFalse(canExecuteAgentSnapshot(run, null))
    }

    @Test
    fun `手动压缩请求不依赖已被回收的恢复快照`() {
        val config = ApiConfig(
            address = "https://example.test",
            key = "test-key",
            model = "test-model",
            provider = "OpenAI",
            id = "config-1",
            name = "默认",
            channel = "OpenAI兼容",
            maxTokens = 2_048,
            modelParameters = ModelParameters(maxContextTokens = 32_000),
        )
        val messages = listOf(SimpleTextApiMessage(id = "m1", role = "user", content = "你好"))
        val limits = resolvedModelTokenLimits(config.maxTokens, config.modelParameters.maxContextTokens)

        val request = buildManualCompactionRequest(config = config, limits = limits, messages = messages)

        assertEquals("test-model", request.model)
        assertEquals("OpenAI兼容", request.channel)
        assertEquals("test-key", request.apiKey)
        assertEquals(messages, request.messages)
        assertEquals(2_048, request.generationConfig?.maxOutputTokens)
        val management = checkNotNull(request.contextManagement)
        assertEquals("config-1", management.configId)
        assertEquals(32_000, management.maxContextTokens)
        assertEquals(2_048, management.reservedOutputTokens)
        // forceLocalCompaction 还要 autoCompressionEnabled 才生效，手动压缩必须自己打开。
        assertTrue(management.autoCompressionEnabled)
    }

    private fun agentRunCoordinatorSource(): String {
        val relativePath = "data/agent/AgentRunCoordinator.kt"
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/$relativePath"),
            File("app/src/main/java/com/android/everytalk/$relativePath"),
            File("app1/app/src/main/java/com/android/everytalk/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "找不到 $relativePath"
        }.readText(Charsets.UTF_8)
    }
}
