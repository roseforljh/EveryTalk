package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class ComputerDirectExecCommandTest {
    @Test
    fun `命令经stdin执行且不出现在远端参数里`() {
        val command = "echo \"dir=\$(pwd)\"; printf '%s\\n' 'it''s ok'; exit 3"
        val remote = directExecCommand("/tmp", 30)
        assertFalse(remote.contains("printf"))

        val (exit, stdout) = runLocally(remote, command)
        assertEquals(3, exit)
        assertEquals(listOf("dir=/tmp", "its ok"), stdout.trim().lines())
    }

    @Test
    fun `波浪号工作目录进入用户Home`() {
        val (exit, stdout) = runLocally(directExecCommand("~", 30), "pwd")
        assertEquals(0, exit)
        assertEquals(runLocally("sh -c 'cd \"\$HOME\" && pwd'", "").second.trim(), stdout.trim())
    }

    /** 与 SSH 登录 Shell 相同：外层命令交给 sh -c 解析，命令文本从 stdin 送入。 */
    private fun runLocally(remote: String, stdin: String): Pair<Int, String> {
        val gitShell = File(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git/bin/bash.exe")
        val shell = if (System.getProperty("os.name").orEmpty().startsWith("Windows") && gitShell.isFile) gitShell.path else "sh"
        // Windows 会再次解析 -c 参数里的引号，写成脚本文件才能保留与 SSH 相同的原文。
        val script = File.createTempFile("direct-exec", ".sh").apply { writeText(remote, Charsets.UTF_8); deleteOnExit() }
        val process = ProcessBuilder(shell, script.invariantSeparatorsPath).start()
        process.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
        check(process.waitFor(20, TimeUnit.SECONDS)) { "本地 Shell 超时" }
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }
}
