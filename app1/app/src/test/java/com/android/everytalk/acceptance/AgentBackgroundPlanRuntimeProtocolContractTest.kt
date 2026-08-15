package com.android.everytalk.acceptance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证 VPS Runtime 能持续提供增量事件，而非每秒重新查询完整状态。 */
class AgentBackgroundPlanRuntimeProtocolContractTest {
    private val wrapper = AgentBackgroundPlanTestFiles.asset("runtime-wrapper.sh")
    private val helper = AgentBackgroundPlanTestFiles.asset("everytalk-containerctl.sh")
    private val runtimeEnvelope = AgentBackgroundPlanTestFiles.source("data/computer/ComputerRuntimeEnvelope.kt")

    @Test
    fun `Wrapper必须提供按Execution监听事件的协议命令`() {
        assertTrue(
            "Runtime 缺少 watch-execution/watch-executions 监听命令",
            wrapper.contains("watch-execution") || wrapper.contains("watch-executions"),
        )
    }

    @Test
    fun `ContainerHelper必须仅转发固定参数的监听命令`() {
        assertTrue(
            "Container helper 缺少监听命令白名单",
            helper.contains("watch-execution") || helper.contains("watch-executions"),
        )
        val commandBranch = helper.lineSequence()
            .firstOrNull { it.trimStart().startsWith("watch-execution)") }
            .orEmpty()
        assertTrue("监听命令必须使用固定参数数量校验", commandBranch.contains("require_exact_args"))
        assertTrue("Helper 必须调用 Wrapper 冻结后的 --watch-execution", helper.contains("--watch-execution"))
        assertFalse("旧的 --watch-exec 会导致 Wrapper 拒绝请求", helper.contains("--watch-exec \""))
    }

    @Test
    fun `事件协议必须携带序号状态日志游标和时间`() {
        val protocol = wrapper + runtimeEnvelope
        assertTrue("缺少事件序号 event_seq", protocol.contains("event_seq"))
        assertTrue("缺少 stdout_cursor", protocol.contains("stdout_cursor"))
        assertTrue("缺少 stderr_cursor", protocol.contains("stderr_cursor"))
        assertTrue("缺少事件时间 observed_at 或 updated_at", protocol.contains("observed_at") || protocol.contains("updated_at"))
        assertTrue("缺少 execution_id", protocol.contains("execution_id"))
        assertFalse("event_seq 不能永远固定为 1", wrapper.contains("event_seq=1\\nstdout_cursor"))
    }

    @Test
    fun `协议必须区分进度终态断线恢复和远端异常`() {
        val remoteProtocol = wrapper + runtimeEnvelope
        assertTrue("远端协议缺少 PROGRESS 事件", remoteProtocol.contains("PROGRESS"))
        assertTrue("远端协议缺少 TERMINAL 事件", remoteProtocol.contains("TERMINAL"))

        // SSH 断线和恢复由 Android 连接层观察，不强迫 VPS Wrapper 伪造网络事件。
        val androidSources = AgentBackgroundPlanTestFiles.allProductionKotlin().joinToString("\n") { it.second }
        listOf("CONNECTION_LOST", "RECONNECTED", "REMOTE_TASK_MISSING", "VPS_RESTARTED").forEach { event ->
            assertTrue("Android 事件模型缺少 $event", androidSources.contains(event))
        }
    }

    @Test
    fun `增量读取必须从已确认游标继续且限制单批大小`() {
        assertTrue("Runtime 必须接受 stdout 游标", wrapper.contains("stdout_cursor") || wrapper.contains("stdout_offset"))
        assertTrue("Runtime 必须接受 stderr 游标", wrapper.contains("stderr_cursor") || wrapper.contains("stderr_offset"))
        assertTrue("日志读取必须限制单次字节数", wrapper.contains("max_bytes"))
        assertFalse("监听协议不能每次输出无界完整日志", wrapper.contains("cat \"\$stdout_log\""))
        assertTrue("返回游标必须按实际读取字节推进", wrapper.contains("stdout_cursor + stdout_count"))
        assertTrue("Android 必须真实发起 Watch Channel", runtimeEnvelope.contains("suspend fun watchExecution("))
    }

    @Test
    fun `取消协议必须保留完整身份校验`() {
        listOf(
            "expected_request_hash",
            "start_ticks",
            "process_group_owner_allowed",
            "state_owner_allowed",
        ).forEach { marker -> assertTrue("取消协议缺少 $marker 校验", wrapper.contains(marker)) }
    }

    @Test
    fun `后台任务不得套前台超时`() {
        val backgroundBranch = runtimeEnvelope.substringAfter("val timeoutSeconds = if (request.background)", "")
            .substringBefore("var envelope", "")
        assertTrue(
            "后台任务应传入 0 或明确禁用 timeout",
            backgroundBranch.contains("0L") || backgroundBranch.contains("timeoutSeconds = 0") || backgroundBranch.contains("timeoutMillis = 0"),
        )
    }

    @Test
    fun `不可信状态必须独立报错且禁止伪造请求哈希`() {
        val rejectBlock = wrapper.substringAfter("reject_untrusted_state() {", "")
            .substringBefore("\n    }", "")
        assertTrue("不可信状态必须使用独立退出码", rejectBlock.contains("exit 47"))
        assertFalse("不可信状态不能伪造 request_hash", rejectBlock.contains("request_hash="))
        assertTrue("Android 必须识别状态不可信退出码", runtimeEnvelope.contains("47 -> throw ComputerException"))
        assertTrue("状态不可信必须使用独立错误码", runtimeEnvelope.contains("EXECUTION_STATE_INVALID"))
        val queryBlock = wrapper.substringAfter("查询前先完成信任校验", "")
            .substringBefore("if [ -f \"\$state_file\" ]", "")
        assertTrue("长监听读取状态前必须先校验归属", queryBlock.contains("state_owner_allowed"))
        assertTrue("长监听读取状态前必须先校验身份", queryBlock.contains("state_has_expected_identity"))
    }
}
