package com.android.everytalk.data.computer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerHostCommandPolicyTest {
    @Test
    fun `查看服务状态可以直接在主机执行`() {
        val assessment = ComputerHostCommandPolicy.assess(
            ComputerExecRequest(
                command = "systemctl status nginx",
                cwd = "~",
                target = ComputerExecTarget.HOST,
            ),
        )

        assertFalse(assessment.requiresConfirmation)
    }

    @Test
    fun `重启服务必须由用户确认`() {
        val assessment = ComputerHostCommandPolicy.assess(
            ComputerExecRequest(
                command = "systemctl restart nginx",
                cwd = "~",
                target = ComputerExecTarget.HOST,
            ),
        )

        assertTrue(assessment.requiresConfirmation)
        assertTrue(ComputerHostCommandRisk.HOST_WRITE in assessment.risks)
    }

    @Test
    fun `提权重定向和未知命令不能绕过确认`() {
        val cases = listOf(
            "sudo systemctl status nginx" to ComputerHostCommandRisk.PRIVILEGE_ESCALATION,
            "uname -a > /tmp/system.txt" to ComputerHostCommandRisk.SHELL_SYNTAX,
            "my-diagnostic-tool --all" to ComputerHostCommandRisk.UNKNOWN_COMMAND,
        )

        cases.forEach { (command, expectedRisk) ->
            val assessment = ComputerHostCommandPolicy.assess(
                ComputerExecRequest(command = command, cwd = "~", target = ComputerExecTarget.HOST),
            )
            assertTrue(command, assessment.requiresConfirmation)
            assertTrue(command, expectedRisk in assessment.risks)
        }
    }

    @Test
    fun `主机环境变量stdin和后台执行在确认前直接拒绝`() {
        val requests = listOf(
            ComputerExecRequest(
                command = "uname -a",
                cwd = "~",
                environment = mapOf("LANG" to "C"),
                target = ComputerExecTarget.HOST,
            ),
            ComputerExecRequest(
                command = "uname -a",
                cwd = "~",
                stdin = "input",
                target = ComputerExecTarget.HOST,
            ),
            ComputerExecRequest(
                command = "uname -a",
                cwd = "~",
                background = true,
                target = ComputerExecTarget.HOST,
            ),
        )

        requests.forEach { request ->
            assertTrue(runCatching { requireValidComputerExecRequest(request) }.exceptionOrNull() is ComputerException)
        }
    }

    @Test
    fun `常用系统诊断命令保持只读自动执行`() {
        val commands = listOf(
            "hostname",
            "uptime",
            "free -m",
            "df -h",
            "ps -eo pid,comm,%cpu,%mem --sort=-%cpu",
            "ss -lntup",
            "ip -brief address",
            "systemctl is-active nginx",
            "journalctl --disk-usage",
            "docker ps",
            "docker stats --no-stream",
        )

        commands.forEach { command ->
            val assessment = ComputerHostCommandPolicy.assess(
                ComputerExecRequest(command = command, cwd = "~", target = ComputerExecTarget.HOST),
            )
            assertFalse(command, assessment.requiresConfirmation)
        }
    }

    @Test
    fun `简单只读诊断链可以一次自动执行`() {
        val commands = listOf(
            "uname -a && uptime",
            "hostname; free -m; df -h",
            "uname -a\ncat /etc/os-release\nnproc",
        )

        commands.forEach { command ->
            val assessment = ComputerHostCommandPolicy.assess(
                ComputerExecRequest(command = command, cwd = "~", target = ComputerExecTarget.HOST),
            )
            assertFalse(command, assessment.requiresConfirmation)
        }
    }

    @Test
    fun `复杂或包含未知片段的命令组合必须确认`() {
        val commands = listOf(
            "uname -a || true",
            "! systemctl is-active nginx",
            "(uname -a)",
            "{ uname -a; }",
            "df -h \$HOME",
            "uname -a; my-diagnostic-tool",
            "printf 'x'; uptime",
        )

        commands.forEach { command ->
            val assessment = ComputerHostCommandPolicy.assess(
                ComputerExecRequest(command = command, cwd = "~", target = ComputerExecTarget.HOST),
            )
            assertTrue(command, assessment.requiresConfirmation)
        }
    }

    @Test
    fun `敏感文件读取与包管理写操作必须确认`() {
        val cases = listOf(
            "cat /etc/shadow" to ComputerHostCommandRisk.SENSITIVE_READ,
            "cat ~/.ssh/id_ed25519" to ComputerHostCommandRisk.SENSITIVE_READ,
            "apt install nginx" to ComputerHostCommandRisk.HOST_WRITE,
            "docker rm app" to ComputerHostCommandRisk.HOST_WRITE,
        )

        cases.forEach { (command, expectedRisk) ->
            val assessment = ComputerHostCommandPolicy.assess(
                ComputerExecRequest(command = command, cwd = "~", target = ComputerExecTarget.HOST),
            )
            assertTrue(command, expectedRisk in assessment.risks)
        }
    }

    @Test
    fun `Workspace Secret禁止注入VPS主机`() {
        val error = runCatching {
            requireValidExecTargetOptions(
                ComputerExecRequest(
                    command = "env",
                    cwd = "~",
                    secrets = mapOf("TOKEN" to "secret".toCharArray()),
                    target = ComputerExecTarget.HOST,
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is ComputerException)
    }

    @Test
    fun `主机后台参数被拒绝避免留下无法管理的进程`() {
        val error = runCatching {
            requireValidExecTargetOptions(
                ComputerExecRequest(
                    command = "sleep 30",
                    cwd = "~",
                    background = true,
                    target = ComputerExecTarget.HOST,
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is ComputerException)
    }

    @Test
    fun `伪装成诊断参数的主机写操作仍需确认`() {
        val cases = listOf(
            "ip link set eth0 down" to ComputerHostCommandRisk.HOST_WRITE,
            "ss -K dst 203.0.113.1" to ComputerHostCommandRisk.HOST_WRITE,
            "journalctl --vacuum-time=1d" to ComputerHostCommandRisk.HOST_WRITE,
            "docker inspect app" to ComputerHostCommandRisk.SENSITIVE_READ,
            "docker logs app" to ComputerHostCommandRisk.SENSITIVE_READ,
        )

        cases.forEach { (command, expectedRisk) ->
            val assessment = ComputerHostCommandPolicy.assess(
                ComputerExecRequest(command = command, cwd = "~", target = ComputerExecTarget.HOST),
            )
            assertTrue(command, assessment.requiresConfirmation)
            assertTrue(command, expectedRisk in assessment.risks)
        }
    }

    @Test
    fun `诊断工具的执行与敏感参数不能自动放行`() {
        val cases = listOf(
            "ip netns exec production sh" to ComputerHostCommandRisk.HOST_WRITE,
            "ip vrf exec blue sh" to ComputerHostCommandRisk.HOST_WRITE,
            "ps eww -p 1" to ComputerHostCommandRisk.SENSITIVE_READ,
            "ps -eo pid,args" to ComputerHostCommandRisk.SENSITIVE_READ,
            "journalctl -u app -n 50" to ComputerHostCommandRisk.SENSITIVE_READ,
            "docker stats" to ComputerHostCommandRisk.UNKNOWN_COMMAND,
            "ip monitor" to ComputerHostCommandRisk.UNKNOWN_COMMAND,
            "ss --events" to ComputerHostCommandRisk.UNKNOWN_COMMAND,
        )

        cases.forEach { (command, expectedRisk) ->
            val assessment = ComputerHostCommandPolicy.assess(
                ComputerExecRequest(command = command, cwd = "~", target = ComputerExecTarget.HOST),
            )
            assertTrue(command, assessment.requiresConfirmation)
            assertTrue(command, expectedRisk in assessment.risks)
        }
    }

    @Test
    fun `无效主机参数不会进入确认流程`() {
        val cases = listOf(
            ComputerExecRequest(command = "uname -a", cwd = "relative", target = ComputerExecTarget.HOST),
            ComputerExecRequest(command = "uname -a", cwd = "~", timeoutMillis = 0, target = ComputerExecTarget.HOST),
            ComputerExecRequest(command = "x".repeat(64 * 1024 + 1), cwd = "~", target = ComputerExecTarget.HOST),
            ComputerExecRequest(command = "uname -a", cwd = "~", asRoot = true, target = ComputerExecTarget.HOST),
        )

        cases.forEach { request ->
            assertTrue(runCatching { requireValidComputerExecRequest(request) }.exceptionOrNull() is ComputerException)
        }
    }
}
