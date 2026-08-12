package com.android.everytalk.statecontroller.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ComputerPrewarmTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `同一Workspace合并并发预热并在完成后允许下一次预热`() = runTest {
        val jobs = ConcurrentHashMap<String, Job>()
        val executions = AtomicInteger()
        val firstRelease = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)

        repeat(16) {
            launchComputerPrewarm(this, jobs, "workspace_1", dispatcher) {
                executions.incrementAndGet()
                firstRelease.await()
            }
        }
        runCurrent()
        assertEquals(1, executions.get())

        firstRelease.complete(Unit)
        advanceUntilIdle()
        assertTrue(jobs.isEmpty())

        launchComputerPrewarm(this, jobs, "workspace_1", dispatcher) {
            executions.incrementAndGet()
        }
        advanceUntilIdle()

        assertEquals(2, executions.get())
        assertTrue(jobs.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `开启Agent和发送消息复用同一准备任务`() = runTest {
        val preparations = ConcurrentHashMap<String, Deferred<String>>()
        val executions = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)

        val first = sharedComputerPreparation(this, preparations, "computer_1\u0000workspace_1", dispatcher) {
            executions.incrementAndGet()
            release.await()
            "workspace_1"
        }
        val second = sharedComputerPreparation(this, preparations, "computer_1\u0000workspace_1", dispatcher) {
            executions.incrementAndGet()
            "workspace_2"
        }
        runCurrent()

        assertEquals(1, executions.get())
        assertTrue(first === second)
        release.complete(Unit)
        assertEquals("workspace_1", second.await())
    }

    @Test
    fun `准备缓存使用不会随首条消息变化的WorkspaceID`() {
        val source = sourceFile("ComputerManager.kt")
        val prepareComputer = source.substringAfter("private fun prepareComputer(")
            .substringBefore("private fun startPrewarm")
        val migrateConversation = source.substringAfter("suspend fun migrateConversationId")
            .substringBefore("suspend fun probeHostKey")

        assertTrue(prepareComputer.contains("requestContext.workspaceId"))
        assertEquals(false, prepareComputer.contains("conversationId)"))
        assertEquals(false, migrateConversation.contains("migrateRequestPreparations"))
        assertTrue(prepareComputer.contains("preparationKey(computer.id, requestContext.workspaceId)"))
    }

    @Test
    fun `模型请求只冻结本地Workspace并把SSH留给后台`() {
        val source = sourceFile("ComputerManager.kt")
        val prepareRequest = source.substringAfter("suspend fun prepareRequest")
            .substringBefore("private fun prepareComputer")
        val execute = source.substringAfter("suspend fun execute(")
            .substringBefore("suspend fun migrateConversationId")

        assertTrue(prepareRequest.contains("getOrCreateLocal"))
        assertEquals(false, prepareRequest.contains(".await()"))
        assertTrue(execute.contains("prepareComputer(computer, requestContext).await()"))
    }

    @Test
    fun `远端Workspace准备只按ID更新运行字段`() {
        val managerSource = sourceFile("../../data/computer/ComputerWorkspaceManager.kt")
        val daoSource = sourceFile("../../data/database/daos/ComputerDao.kt")
        val prepare = managerSource.substringAfter("suspend fun prepare(workspaceId: String)")
            .substringBefore("suspend fun getWorkspace")

        assertTrue(prepare.contains("getWorkspaceById(workspaceId)"))
        assertTrue(prepare.contains("updateWorkspaceRuntimeState("))
        assertEquals(false, prepare.contains("upsertWorkspace("))
        assertTrue(daoSource.contains("suspend fun updateWorkspaceRuntimeState("))
        assertEquals(
            false,
            daoSource.substringAfter("suspend fun updateWorkspaceRuntimeState(")
                .substringBefore("/** Container")
                .contains("conversationId"),
        )
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            java.io.File("src/main/java/com/android/everytalk/statecontroller/viewmodel/$name"),
            java.io.File("app/src/main/java/com/android/everytalk/statecontroller/viewmodel/$name"),
            java.io.File("app1/app/src/main/java/com/android/everytalk/statecontroller/viewmodel/$name"),
        )
        return requireNotNull(candidates.firstOrNull(java.io.File::isFile)).readText(Charsets.UTF_8)
    }
}
