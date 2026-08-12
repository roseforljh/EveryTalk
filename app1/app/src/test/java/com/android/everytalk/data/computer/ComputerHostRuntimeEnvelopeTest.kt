package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ComputerHostRuntimeEnvelopeTest {
    @Test
    fun `主机Envelope保留主机目录并使用独立协议`() {
        val request = ComputerExecRequest(
            command = "systemctl status nginx",
            cwd = "/var/log",
            target = ComputerExecTarget.HOST,
        )
        val envelope = buildComputerRuntimeEnvelope(request)
        val input = ByteArrayInputStream(envelope)

        assertEquals("EVERYTALK_EXEC_HOST_V1", input.readAsciiLine())
        val lengths = List(4) { input.readAsciiLine().toInt() }
        val parts = lengths.map { length -> input.readNBytes(length).toString(Charsets.UTF_8) }
        assertEquals("/var/log", parts[0])
        assertEquals(request.command, parts[2])
    }

    @Test
    fun `主机执行命令不携带模型生成的命令文本`() {
        val command = hostForegroundRuntimeCommand(
            runtimeId = "run_execution_1",
            timeoutSeconds = 120,
            wrapperVersion = "a".repeat(64),
        )

        assertTrue(command.contains("--host-envelope"))
        assertTrue(command.contains("rm -f -- \"\$runtime/environment.sh\""))
        assertTrue(command.endsWith("exit \"\$status\""))
        assertFalse(command.contains("systemctl"))
        assertFalse(command.contains("/workspace"))
    }

    private fun ByteArrayInputStream.readAsciiLine(): String = buildString {
        while (true) {
            val value = read()
            if (value < 0 || value == '\n'.code) break
            append(value.toChar())
        }
    }
}
