package com.android.everytalk.data.computer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class ComputerSecretEnvWriterTest {
    @get:Rule val temporary = TemporaryFolder()

    /** 真正运行生产脚本，验证 grep 没有保留行时不会被 set -e 提前终止。 */
    @Test
    fun `空文件和仅有旧变量以及混合配置均能替换`() {
        for ((before, expected) in listOf(
            "" to "API_KEY=new-value\n",
            "API_KEY=old-value\n" to "API_KEY=new-value\n",
            "PORT=3000\nAPI_KEY=old-value\n" to "PORT=3000\nAPI_KEY=new-value\n",
        )) {
            val root = temporary.newFolder()
            val target = File(root, "custom secrets.conf")
            target.writeText(before)
            assertEquals(0, executeScript(root, target.name, "new-value"))
            assertEquals(expected, target.readText())
        }
    }

    @Test
    fun `目标可以是绝对路径且无文件时新建`() {
        val root = temporary.newFolder()
        val target = File(root, "runtime.env")
        assertEquals(0, executeScript(root, target.invariantSeparatorsPath, "new-value"))
        assertEquals("API_KEY=new-value\n", target.readText())
    }

    @Test
    fun `SERVER_ENV 宿主机写入只接受绝对路径且不走容器`() {
        val command = ComputerSecretEnvWriter.buildHostUpsertCommand("/root/defuddle-server/.env", "API_KEY")
        assertTrue(command.contains("/root/defuddle-server/.env"))
        assertFalse(command.contains("docker exec"))
        assertThrows(IllegalArgumentException::class.java) {
            ComputerSecretEnvWriter.buildHostUpsertCommand(".env", "API_KEY")
        }
    }

    @Test
    fun `临时文件无法打开时必须失败且保留原内容`() {
        val root = temporary.newFolder()
        val target = File(root, "runtime.env")
        target.writeText("API_KEY=old-value\n")
        // 在同一个 Shell PID 下占据脚本临时路径，模拟重定向失败。
        assertTrue(executeScript(root, target.name, "new-value", "mkdir \"${'$'}PWD/runtime.env.tmp.${'$'}${'$'}\"; ", allowFailure = true) != 0)
        assertEquals("API_KEY=old-value\n", target.readText())
    }

    @Test
    fun `退出事实决定失败或未知且错误不暴露远端内容`() {
        val result = ComputerSshCommandResult("secret-in-stdout", "secret-in-stderr", 1, false, false, false)
        val failed = assertThrows(ComputerException::class.java) {
            ComputerSecretEnvWriter.requireSuccessfulWrite(result)
        }
        assertEquals(ComputerErrorCodes.EXECUTION_FAILED, failed.code)
        assertFalse(failed.message.orEmpty().contains("secret-in"))
        for (unknown in listOf(result.copy(timedOut = true), result.copy(exitCode = null))) {
            val error = assertThrows(ComputerException::class.java) {
                ComputerSecretEnvWriter.requireSuccessfulWrite(unknown)
            }
            assertEquals(ComputerErrorCodes.EXECUTION_UNKNOWN, error.code)
        }
        ComputerSecretEnvWriter.requireSuccessfulWrite(result.copy(exitCode = 0))
    }

    private fun executeScript(root: File, path: String, input: String, prefix: String = "", allowFailure: Boolean = false): Int {
        // Windows 复用 Git 的 POSIX Shell，Linux/macOS 使用系统 sh，无需新增测试依赖。
        val gitShell = File(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git/bin/bash.exe")
        val shell = if (System.getProperty("os.name").orEmpty().startsWith("Windows") && gitShell.isFile) gitShell.path else "sh"
        val command = prefix + ComputerSecretEnvWriter.buildUpsertCommand(".", path, "API_KEY")
        // Windows 对 bash -c 的引号会再次解析；通过脚本文件执行保留与 SSH 相同的原文。
        val script = File(root, "write-test.sh").apply { writeText(command, Charsets.UTF_8) }
        val process = ProcessBuilder(shell, script.invariantSeparatorsPath).directory(root).start()
        process.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("环境变量写入脚本超时")
        }
        // 测试同样不回显受保护输入。
        assertEquals("", process.inputStream.bufferedReader().readText())
        val exit = process.exitValue()
        if (exit != 0 && !allowFailure) {
            throw AssertionError("环境变量脚本退出码=$exit，stderr=${process.errorStream.bufferedReader().readText()}，command=$command")
        }
        return exit
    }

    @Test
    fun `脚本不包含 Secret 值且固定写入变量`() {
        val command = ComputerSecretEnvWriter.buildUpsertCommand("/opt/app", ".env.local", "API_KEY")
        assertTrue(command.contains("awk"))
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
        assertTrue(ComputerSecretEnvWriter.requireTargetPath("config/.env.production") == "config/.env.production")
        assertTrue(ComputerSecretEnvWriter.requireTargetPath("/root/defuddle-server/.env") == "/root/defuddle-server/.env")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ComputerSecretEnvWriter.requireTargetPath("/root/../etc/passwd")
        }
    }
}
