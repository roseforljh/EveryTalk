package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ComputerRuntimeEnvelopeTest {
    @Test
    fun `exec参数通过单个长度前缀Envelope传输`() {
        val request = ComputerExecRequest(
            command = "printf done",
            cwd = "/workspace/project",
            environment = mapOf("LANG" to "zh_CN.UTF-8"),
            stdin = "第一行\n第二行",
        )
        val envelope = buildComputerRuntimeEnvelope(request)
        val input = ByteArrayInputStream(envelope)

        assertEquals("EVERYTALK_EXEC_V1", input.readAsciiLine())
        val lengths = List(4) { input.readAsciiLine().toInt() }
        val parts = lengths.map { length -> input.readNBytes(length).toString(Charsets.UTF_8) }

        assertEquals("project", parts[0])
        assertTrue(parts[1].contains("LANG='zh_CN.UTF-8'"))
        assertFalse(parts[1].contains("TOKEN"))
        assertEquals(request.command, parts[2])
        assertEquals(request.stdin, parts[3])
        assertEquals(-1, input.read())
    }

    @Test
    fun `Runtime准备链路不再使用SFTP多文件写入`() {
        val source = sourceFile("ComputerRuntimeEnvelope.kt")

        assertFalse(source.contains("withSftp"))
        assertFalse(source.contains("command.sh\", commandBytes"))
        assertTrue(source.contains("stdin = envelope"))
        assertTrue(source.contains("maxOutputBytes = COMPUTER_EXEC_OUTPUT_BYTES"))
    }

    @Test
    fun `Direct清理同时识别旧版与版本化Wrapper`() {
        val source = sourceFile("ComputerWorkspaceManager.kt")

        assertTrue(source.contains("everytalk-runtime-wrapper\")"))
        assertTrue(source.contains("everytalk-runtime-wrapper-\"*"))
        assertTrue(source.contains("wrapper_version"))
    }

    @Test
    fun `DirectWorkspace提前创建受管Execution目录`() {
        val source = sourceFile("ComputerWorkspaceManager.kt")

        assertTrue(source.contains("\${'$'}workspace/.everytalk/executions"))
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            java.io.File("src/main/java/com/android/everytalk/data/computer/$name"),
            java.io.File("app/src/main/java/com/android/everytalk/data/computer/$name"),
            java.io.File("app1/app/src/main/java/com/android/everytalk/data/computer/$name"),
        )
        return requireNotNull(candidates.firstOrNull(java.io.File::isFile)).readText(Charsets.UTF_8)
    }

    private fun ByteArrayInputStream.readAsciiLine(): String = buildString {
        while (true) {
            val value = read()
            if (value < 0 || value == '\n'.code) break
            append(value.toChar())
        }
    }
}
