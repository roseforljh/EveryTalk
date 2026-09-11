package com.android.everytalk.data.computer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerSecretEnvWriterTest {
    @Test
    fun `脚本不包含 Secret 值且固定写入变量`() {
        val command = ComputerSecretEnvWriter.buildUpsertCommand("/opt/app", ".env.local", "API_KEY")
        assertTrue(command.contains("grep -v"))
        assertTrue(command.contains("API_KEY") || command.contains("'API_KEY'"))
        assertFalse(command.contains("real-secret"))
    }

    @Test
    fun `Container 写入使用 docker exec stdin 且不暴露 Secret`() {
        val command = ComputerSecretEnvWriter.buildContainerUpsertCommand("everytalk-ws_1", ".env", "API_KEY")
        assertTrue(command.startsWith("docker exec -i 'everytalk-ws_1' sh -c"))
        assertTrue(command.contains("/workspace") && command.contains(".env"))
        assertFalse(command.contains("real-secret"))
    }

    @Test
    fun `允许 Workspace 内任意环境文件名但拒绝越界路径`() {
        assertTrue(ComputerSecretEnvWriter.requireWorkspaceRelativePath("config/.env.production") == "config/.env.production")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ComputerSecretEnvWriter.requireWorkspaceRelativePath("../etc/passwd")
        }
    }
}
