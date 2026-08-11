package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 锁定 root helper 的关键安全边界，防止后续修改重新放开 Docker 控制权。 */
class ComputerContainerHelperContractTest {
    @Test
    fun `Workspace 和 Preview Container 都禁止自动重启`() {
        val source = helperSource()

        assertEquals(2, Regex("--restart\\s+no").findAll(source).count())
        assertTrue(source.contains("docker update --restart=no"))
        assertFalse(source.contains("unless-stopped"))
    }

    @Test
    fun `helper 只接受固定子命令参数并校验资源归属`() {
        val source = helperSource()
        val expectedArgumentCounts = mapOf(
            "install" to 2,
            "version" to 0,
            "build-image" to 0,
            "set-network" to 1,
            "ensure-workspace" to 1,
            "container-address" to 1,
            "run" to 4,
            "run-background" to 4,
            "terminal" to 1,
            "open-public" to 3,
            "close-public" to 1,
            "delete-workspace" to 2,
        )

        expectedArgumentCounts.forEach { (command, count) ->
            val contract = Regex("${Regex.escape(command)}\\)\\s+require_exact_args $count")
            assertTrue("$command 必须校验参数数量", contract.containsMatchIn(source))
        }
        assertTrue(source.contains("Container 归属校验失败"))
        assertTrue(source.contains("Preview 归属校验失败"))
        assertTrue(source.contains("已安装 helper 禁止重复 install"))
        assertFalse(source.contains("docker \$@"))
    }

    private fun helperSource(): String {
        val candidates = listOf(
            File("src/main/assets/computer/everytalk-containerctl.sh"),
            File("app/src/main/assets/computer/everytalk-containerctl.sh"),
            File("app1/app/src/main/assets/computer/everytalk-containerctl.sh"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "找不到 everytalk-containerctl.sh"
        }.readText(Charsets.UTF_8)
    }
}
