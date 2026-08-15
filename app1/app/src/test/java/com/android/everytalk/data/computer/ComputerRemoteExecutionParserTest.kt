package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 严格锁定 Runtime V2 的身份校验和结果读取边界。 */
class ComputerRemoteExecutionParserTest {
    private val executionId = "execution_test"
    private val processId = "process_$executionId"
    private val requestHash = "a".repeat(64)

    @Test
    fun `状态协议完整且身份匹配`() {
        val state = ComputerRemoteExecutionParser.parseState(
            payload = payload(status = "RUNNING"),
            expectedExecutionId = executionId,
            expectedProcessId = processId,
            expectedRequestHash = requestHash,
            expectedTarget = ComputerExecTarget.CONTAINER,
        )

        assertEquals(ComputerRemoteStatus.RUNNING, state.status)
        assertEquals(0L, state.pid)
        assertEquals(ComputerExecTarget.CONTAINER, state.target)
    }

    @Test
    fun `结果协议解码受控输出`() {
        val result = ComputerRemoteExecutionParser.parseResult(
            payload = payload(status = "SUCCEEDED") +
                "stdout_base64=aGVsbG8=\n" +
                "stderr_base64=\n" +
                "stdout_offset=0\n" +
                "stderr_offset=0\n",
            expectedExecutionId = executionId,
            expectedRequestHash = requestHash,
            expectedTarget = ComputerExecTarget.CONTAINER,
        )

        assertEquals("hello", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun `监听事件必须解码增量日志并推进游标`() {
        val event = ComputerRemoteExecutionParser.parseWatchEvent(
            payload = payload(status = "RUNNING") +
                "event_type=PROGRESS\n" +
                "event_seq=2000005\n" +
                "stdout_offset=3\n" +
                "stderr_offset=1\n" +
                "stdout_cursor=8\n" +
                "stderr_cursor=1\n" +
                "observed_at=2\n" +
                "stdout_base64=aGVsbG8=\n" +
                "stderr_base64=\n",
            expectedExecutionId = executionId,
            expectedRequestHash = requestHash,
            expectedTarget = ComputerExecTarget.CONTAINER,
        )

        assertEquals("PROGRESS", event.eventType)
        assertEquals("hello", event.result.stdout)
        assertEquals(8L, event.stdoutCursor)
        assertEquals(1L, event.stderrCursor)
    }

    @Test
    fun `监听事件游标倒退必须拒绝`() {
        assertThrows(ComputerRemoteExecutionParseException::class.java) {
            ComputerRemoteExecutionParser.parseWatchEvent(
                payload = payload(status = "RUNNING") +
                    "event_type=HEARTBEAT\n" +
                    "event_seq=1\n" +
                    "stdout_offset=5\n" +
                    "stderr_offset=0\n" +
                    "stdout_cursor=4\n" +
                    "stderr_cursor=0\n" +
                    "observed_at=2\n" +
                    "stdout_base64=\n" +
                    "stderr_base64=\n",
            )
        }
    }

    @Test
    fun `缺少身份字段或身份不匹配都拒绝`() {
        assertThrows(ComputerRemoteExecutionParseException::class.java) {
            ComputerRemoteExecutionParser.parseState(
                payload = payload(status = "RUNNING").replace("request_hash=$requestHash\n", ""),
            )
        }
        assertThrows(ComputerRemoteExecutionParseException::class.java) {
            ComputerRemoteExecutionParser.parseState(
                payload = payload(status = "RUNNING"),
                expectedRequestHash = "b".repeat(64),
            )
        }
        assertThrows(ComputerRemoteExecutionParseException::class.java) {
            ComputerRemoteExecutionParser.parseState(
                payload = payload(status = "RUNNING"),
                expectedProcessId = "process_other",
            )
        }
    }

    @Test
    fun `请求哈希不一致返回专用冲突码`() {
        val error = assertThrows(ComputerRemoteExecutionParseException::class.java) {
            ComputerRemoteExecutionParser.parseState(
                payload = payload(status = "RUNNING"),
                expectedRequestHash = "b".repeat(64),
            )
        }

        assertEquals(ComputerErrorCodes.EXECUTION_REQUEST_HASH_CONFLICT, error.code)
    }

    private fun payload(status: String): String = buildString {
        appendLine("protocol=2")
        appendLine("execution_id=$executionId")
        appendLine("process_id=$processId")
        appendLine("request_hash=$requestHash")
        appendLine("target=CONTAINER")
        appendLine("pid=0")
        appendLine("start_ticks=0")
        appendLine("status=$status")
        appendLine("exit_code=${if (status == "SUCCEEDED") 0 else ""}")
        appendLine("started_at=1")
        appendLine("updated_at=2")
        appendLine("stdout_bytes=0")
        appendLine("stderr_bytes=0")
    }
}
