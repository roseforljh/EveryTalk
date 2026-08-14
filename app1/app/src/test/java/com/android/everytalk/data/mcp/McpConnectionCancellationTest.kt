package com.android.everytalk.data.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class McpConnectionCancellationTest {
    @Test
    fun `disconnect cancels a connection attempt that has not completed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connectStarted = CompletableDeferred<Unit>()
        val manager = McpClientManager(scope) { _, _ ->
            connectStarted.complete(Unit)
            awaitCancellation()
        }
        val config = McpServerConfig.SseTransportServer(
            id = "server-a",
            commonOptions = McpCommonOptions(name = "Server"),
            url = "https://example.com",
        )
        val connectJob = async(Dispatchers.Default) { manager.addServer(config) }

        try {
            withTimeout(1_000) { connectStarted.await() }
            assertTrue(manager.serverStates.value[config.id]?.status is McpStatus.Connecting)

            withTimeout(1_000) { manager.disconnectServer(config.id) }

            assertTrue(connectJob.isCancelled)
            val disconnectedState = manager.serverStates.value[config.id]
            assertTrue(disconnectedState?.status is McpStatus.Idle)
            assertFalse(disconnectedState?.config?.enabled ?: true)
        } finally {
            connectJob.cancelAndJoin()
            manager.close()
        }
    }

    @Test
    fun `disconnect invalidates a duplicate connection already waiting for the same server`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connectStarted = CompletableDeferred<Unit>()
        val connectCount = AtomicInteger()
        val manager = McpClientManager(scope) { _, _ ->
            connectCount.incrementAndGet()
            connectStarted.complete(Unit)
            awaitCancellation()
        }
        val config = McpServerConfig.SseTransportServer(
            id = "server-a",
            commonOptions = McpCommonOptions(name = "Server"),
            url = "https://example.com",
        )
        val firstConnect = async(Dispatchers.Default) { manager.addServer(config) }

        try {
            withTimeout(1_000) { connectStarted.await() }
            val duplicateConnect = async(
                context = Dispatchers.Default,
                start = CoroutineStart.UNDISPATCHED,
            ) { manager.addServer(config) }

            withTimeout(1_000) { manager.disconnectServer(config.id) }
            withTimeout(1_000) { duplicateConnect.await() }

            assertTrue(firstConnect.isCancelled)
            assertTrue(connectCount.get() == 1)
            assertTrue(manager.serverStates.value[config.id]?.status is McpStatus.Idle)
        } finally {
            firstConnect.cancelAndJoin()
            manager.close()
        }
    }
}
