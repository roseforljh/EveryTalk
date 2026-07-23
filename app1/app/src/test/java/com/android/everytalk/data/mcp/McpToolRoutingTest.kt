package com.android.everytalk.data.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class McpToolRoutingTest {

    @Test
    fun `aliases are valid stable and server specific`() {
        val first = buildMcpToolAlias("server-a", "读取 网页/内容")
        val repeated = buildMcpToolAlias("server-a", "读取 网页/内容")
        val second = buildMcpToolAlias("server-b", "读取 网页/内容")

        assertEquals(first, repeated)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        assertTrue(first != second)
    }

    @Test
    fun `alias routes duplicate names to the intended server`() {
        val firstConfig = config("server-a", "First")
        val secondConfig = config("server-b", "Second")
        val states = listOf(
            McpServerState(firstConfig, McpStatus.Connected, listOf(McpTool("search"))),
            McpServerState(secondConfig, McpStatus.Connected, listOf(McpTool("search")))
        )

        val resolved = resolveMcpToolWithServer(states, buildMcpToolAlias(secondConfig.id, "search"))

        assertEquals(secondConfig.id, resolved?.first?.id)
        assertEquals("search", resolved?.second?.name)
        assertNull(resolveMcpToolWithServer(states, "search"))
    }

    @Test
    fun `raw names are never accepted`() {
        val config = config("server-a", "First")
        val resolved = resolveMcpToolWithServer(
            listOf(McpServerState(config, McpStatus.Connected, listOf(McpTool("fetch_page")))),
            "fetch_page"
        )

        assertNull(resolved)
    }

    @Test
    fun `synchronized tools do not change connection identity`() {
        val original = config("server-a", "First")
        val synchronized = original.clone(
            commonOptions = original.commonOptions.copy(tools = listOf(McpTool("search")))
        )

        assertTrue(hasSameMcpConnectionSettings(original, synchronized))
        assertFalse(
            hasSameMcpConnectionSettings(
                original,
                McpServerConfig.SseTransportServer(
                    id = original.id,
                    commonOptions = original.commonOptions,
                    url = "https://changed.example.com",
                ),
            ),
        )
    }

    @Test
    fun `closing manager cancels its cleanup scope`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manager = McpClientManager(scope)
        val updateState = McpClientManager::class.java.getDeclaredMethod(
            "updateServerState",
            String::class.java,
            Function1::class.java,
        ).apply { isAccessible = true }
        updateState.invoke(
            manager,
            "server-a",
            { _: McpServerState? -> McpServerState(config("server-a", "Server"), McpStatus.Connected) },
        )

        manager.close()
        manager.close()

        assertTrue(scope.coroutineContext[Job]?.isCancelled == true)
        assertTrue(manager.serverStates.value.isEmpty())
    }

    @Test
    fun `concurrent state updates keep every server`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manager = McpClientManager(scope)
        val updateState = McpClientManager::class.java.getDeclaredMethod(
            "updateServerState",
            String::class.java,
            Function1::class.java,
        ).apply { isAccessible = true }
        val workerCount = 16
        val barrier = CyclicBarrier(workerCount)
        val executor = Executors.newFixedThreadPool(workerCount)

        try {
            val futures = (0 until workerCount).map { index ->
                executor.submit {
                    val serverId = "server-$index"
                    var firstAttempt = true
                    updateState.invoke(
                        manager,
                        serverId,
                        { _: McpServerState? ->
                            if (firstAttempt) {
                                firstAttempt = false
                                barrier.await(5, TimeUnit.SECONDS)
                            }
                            McpServerState(config(serverId, "Server $index"), McpStatus.Connected)
                        },
                    )
                }
            }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }

            assertEquals(workerCount, manager.serverStates.value.size)
        } finally {
            executor.shutdownNow()
            manager.close()
        }
    }

    private fun config(id: String, name: String): McpServerConfig {
        return McpServerConfig.SseTransportServer(
            id = id,
            commonOptions = McpCommonOptions(name = name),
            url = "https://example.com"
        )
    }
}
