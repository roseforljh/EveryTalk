package com.android.everytalk.data.computer

private const val MAX_HOST_COMMAND_CHARS = 64 * 1024
private const val MAX_EXEC_TIMEOUT_MILLIS = 60 * 60 * 1000L
internal const val COMPUTER_EXEC_OUTPUT_BYTES = 2 * 1024
private const val TIMEOUT_EXIT_CODE = 124

/** 固定的内层脚本：进入工作目录后 eval 从 stdin 读到的命令，命令文本不出现在远端进程参数里。 */
private const val EXEC_INNER_SCRIPT = "d=\$1; [ \"\$d\" = \"~\" ] && d=\$HOME; cd -- \"\$d\" || exit 97; eval \"\$(cat)\""

/** 外层脚本：服务器有 timeout 时由它限时并在超时后强杀，没有时退回 SSH 侧超时。 */
private const val EXEC_OUTER_SCRIPT = "if command -v timeout >/dev/null 2>&1; then " +
    "exec timeout -k 5 \"\$2\" sh -c \"\$0\" sh \"\$1\"; fi; exec sh -c \"\$0\" sh \"\$1\""

/** 一次 exec：在一条 SSH exec 通道里直接执行，服务器上不写任何文件。 */
data class ComputerExecRequest(
    val command: String,
    val cwd: String = "~",
    val timeoutMillis: Long = 120_000,
)

data class ComputerExecResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val remoteStatus: ComputerRemoteStatus? = null,
    /** 从远端启动确认到结果形成的耗时。 */
    val durationMillis: Long? = null,
)

/** 外层命令由登录 Shell 解析，只包含单引号参数，兼容 bash/zsh/fish。 */
internal fun directExecCommand(cwd: String, timeoutSeconds: Long): String {
    val directory = ComputerHostWorkingDirectory.normalize(cwd)
    require(timeoutSeconds in 1..MAX_EXEC_TIMEOUT_MILLIS / 1000) { "exec timeout 无效" }
    return listOf("sh", "-c", EXEC_OUTER_SCRIPT, EXEC_INNER_SCRIPT, directory, timeoutSeconds.toString())
        .joinToString(" ") { if (it == "sh" || it == "-c") it else shellSingleQuote(it) }
}

private fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** 命令走 stdin，输出和退出码直接从通道取回；断线即视为本次执行失败，不做远端续接。 */
internal suspend fun ComputerSshConnection.runComputerExec(request: ComputerExecRequest): ComputerExecResult {
    requireValidComputerExecRequest(request)
    val startedAt = System.currentTimeMillis()
    val result = execute(
        command = directExecCommand(request.cwd, (request.timeoutMillis + 999) / 1000),
        stdin = request.command.toByteArray(Charsets.UTF_8),
        // 留出 timeout -k 的强杀时间，远端没有 timeout 命令时由这里兜底关闭通道。
        timeoutMillis = request.timeoutMillis + 10_000,
        maxOutputBytes = COMPUTER_EXEC_OUTPUT_BYTES,
    )
    val durationMillis = System.currentTimeMillis() - startedAt
    val timedOut = result.timedOut ||
        (result.exitCode == TIMEOUT_EXIT_CODE && durationMillis >= request.timeoutMillis)
    return ComputerExecResult(
        exitCode = result.exitCode,
        stdout = result.stdout,
        stderr = result.stderr,
        timedOut = timedOut,
        stdoutTruncated = result.stdoutTruncated,
        stderrTruncated = result.stderrTruncated,
        remoteStatus = when {
            timedOut -> ComputerRemoteStatus.TIMED_OUT
            result.exitCode == 0 -> ComputerRemoteStatus.SUCCEEDED
            else -> ComputerRemoteStatus.FAILED
        },
        durationMillis = durationMillis,
    )
}

/** Host cwd 只接受绝对路径或 SSH 用户 Home，禁止控制字符和父目录片段。 */
internal object ComputerHostWorkingDirectory {
    fun normalize(input: String): String {
        val value = input.trim()
        if (
            value.isEmpty() || value.length > 4096 ||
            value.any { it == '\u0000' || it == '\n' || it == '\r' || it == '\\' } ||
            value.split('/').any { it == ".." } ||
            (value != "~" && !value.startsWith('/'))
        ) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "VPS 工作目录无效")
        }
        return value
    }
}

/** 公共参数校验同时用于审批门和 Runtime，保证无效请求不会先弹确认卡或建立 SSH Channel。 */
internal fun requireValidComputerExecRequest(request: ComputerExecRequest) {
    if (request.command.isBlank() || '\u0000' in request.command) {
        throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "exec command 无效")
    }
    if (request.command.length > MAX_HOST_COMMAND_CHARS) {
        throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "VPS 主机命令过长，无法完整确认")
    }
    if (request.timeoutMillis !in 1..MAX_EXEC_TIMEOUT_MILLIS) {
        throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "exec timeout_ms 无效")
    }
    ComputerHostWorkingDirectory.normalize(request.cwd)
}

internal object ComputerEnvironmentName {
    fun requireValid(name: String): String {
        val valid = name.isNotEmpty() && name.length <= 128 &&
            (name.first() == '_' || name.first() in 'A'..'Z' || name.first() in 'a'..'z') &&
            name.drop(1).all { it == '_' || it in 'A'..'Z' || it in 'a'..'z' || it.isDigit() }
        if (!valid) throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "环境变量名无效")
        return name
    }
}
