package com.android.everytalk.data.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证 Pause gate 的竞态、隔离和终态清理，不依赖真实网络或长时间等待。 */
class AgentRunPauseControllerTest {
    @Test
    fun `Pause后立即Resume不会丢唤醒`() = runTest {
        val controller = controller("message-1", "run-1")
        assertTrue(controller.requestPause("message-1"))
        assertTrue(controller.resume("message-1"))

        val waiter = async { controller.awaitIfPaused("run-1") }
        waiter.await()

        assertEquals(AgentRunControlState.RUNNING, controller.snapshots.value.getValue("message-1").state)
    }

    @Test
    fun `到达安全点后挂起直到Resume`() = runTest {
        val controller = controller("message-1", "run-1")
        assertTrue(controller.requestPause("message-1"))
        val waiter = launch { controller.awaitIfPaused("run-1") }

        withTimeout(1_000) {
            controller.snapshots.first { it.getValue("message-1").state == AgentRunControlState.PAUSED }
        }
        assertFalse(waiter.isCompleted)

        assertTrue(controller.resume("message-1"))
        waiter.join()
        assertEquals(AgentRunControlState.RUNNING, controller.snapshots.value.getValue("message-1").state)
    }

    @Test
    fun `Pause与自然终止竞争后不会残留幽灵状态`() = runTest {
        repeat(100) { index ->
            val messageId = "message-$index"
            val controller = controller(messageId, "run-$index")
            coroutineScope {
                listOf(
                    launch { controller.requestPause(messageId) },
                    launch { controller.finish(messageId) },
                ).joinAll()
            }
            controller.finish(messageId)
            assertTrue(controller.snapshots.value.isEmpty())
            assertFalse(controller.requestPause(messageId))
        }
    }

    @Test
    fun `Pause后Abort会解除挂起并清理状态`() = runTest {
        val controller = controller("message-1", "run-1")
        assertTrue(controller.requestPause("message-1"))
        val waiter = launch {
            try {
                controller.awaitIfPaused("run-1")
            } finally {
                controller.finish("message-1")
            }
        }
        controller.snapshots.first { it.getValue("message-1").state == AgentRunControlState.PAUSED }

        waiter.cancel()
        waiter.join()

        assertTrue(controller.snapshots.value.isEmpty())
        assertFalse(controller.resume("message-1"))
    }

    @Test
    fun `两个Run的Pause状态互不串扰`() = runTest {
        val controller = AgentRunPauseController()
        controller.register("message-a")
        controller.bind("run-a", "message-a")
        controller.register("message-b")
        controller.bind("run-b", "message-b")

        assertTrue(controller.requestPause("message-b"))
        val waiter = launch { controller.awaitIfPaused("run-b") }
        controller.snapshots.first { it.getValue("message-b").state == AgentRunControlState.PAUSED }

        assertEquals(AgentRunControlState.RUNNING, controller.snapshots.value.getValue("message-a").state)
        assertEquals("run-a", controller.snapshots.value.getValue("message-a").runId)
        assertTrue(controller.resume("message-b"))
        waiter.join()
    }

    @Test
    fun `finish不会对外发布短暂RUNNING快照`() = runTest {
        val controller = controller("message-1", "run-1")
        assertTrue(controller.requestPause("message-1"))
        controller.finish("message-1")

        assertTrue(controller.snapshots.value.isEmpty())
        assertFalse(controller.requestPause("message-1"))
        assertFalse(controller.resume("message-1"))
    }

    private fun controller(messageId: String, runId: String): AgentRunPauseController =
        AgentRunPauseController().also {
            it.register(messageId)
            it.bind(runId, messageId)
        }
}
