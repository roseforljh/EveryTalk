package com.android.everytalk.data.computer

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ComputerRuntimeWrapperCacheTest {
    @Test
    fun `同一连接与相同版本只安装一次`() = runTest {
        val cache = ComputerRuntimeWrapperCache()
        val installs = AtomicInteger()

        repeat(2) {
            cache.ensureInstalled(byteArrayOf(1)) {
                installs.incrementAndGet()
                true
            }
        }

        assertEquals(1, installs.get())
    }

    @Test
    fun `Wrapper 版本变化后重新安装`() = runTest {
        val cache = ComputerRuntimeWrapperCache()
        val installs = AtomicInteger()

        cache.ensureInstalled(byteArrayOf(1)) { installs.incrementAndGet(); true }
        cache.ensureInstalled(byteArrayOf(2)) { installs.incrementAndGet(); true }

        assertEquals(2, installs.get())
    }

    @Test
    fun `安装失败后允许重试`() = runTest {
        val cache = ComputerRuntimeWrapperCache()
        val installs = AtomicInteger()

        val failure = runCatching {
            cache.ensureInstalled(byteArrayOf(1)) {
                installs.incrementAndGet()
                error("安装失败")
            }
        }.exceptionOrNull()
        cache.ensureInstalled(byteArrayOf(1)) { installs.incrementAndGet(); true }

        assertTrue(failure is IllegalStateException)
        assertEquals(2, installs.get())
    }

    @Test
    fun `并发调用只安装一次`() = runTest {
        val cache = ComputerRuntimeWrapperCache()
        val installs = AtomicInteger()

        List(16) {
            async {
                cache.ensureInstalled(byteArrayOf(1)) {
                    installs.incrementAndGet()
                    yield()
                    true
                }
            }
        }.awaitAll()

        assertEquals(1, installs.get())
    }

    @Test
    fun `不同连接各自校验但VPS版本路径保持一致`() = runTest {
        val firstConnectionCache = ComputerRuntimeWrapperCache()
        val secondConnectionCache = ComputerRuntimeWrapperCache()
        val checks = AtomicInteger()

        firstConnectionCache.ensureInstalled(byteArrayOf(1)) { checks.incrementAndGet(); false }
        secondConnectionCache.ensureInstalled(byteArrayOf(1)) { checks.incrementAndGet(); false }

        assertEquals(2, checks.get())
        assertEquals(
            runtimeWrapperRemotePath("a".repeat(64)),
            runtimeWrapperRemotePath("a".repeat(64)),
        )
    }

    @Test
    fun `Direct前台命令在同一SSH执行内清理Runtime并保留退出码`() {
        val command = directForegroundRuntimeCommand(
            workspaceId = "workspace_1",
            runtimeId = "run_execution_1",
            timeoutSeconds = 120,
            wrapperVersion = "a".repeat(64),
        )

        assertTrue(command.contains("timeout --signal=TERM --kill-after=5s 120s"))
        assertTrue(command.contains("everytalk-runtime-wrapper-${"a".repeat(64)}"))
        assertTrue(command.contains("--envelope"))
        assertTrue(command.contains("rm -f -- \"\$runtime/environment.sh\""))
        assertTrue(command.contains("rmdir -- \"\$runtime\""))
        assertTrue(command.endsWith("exit \"\$status\""))
    }
}
