package com.android.everytalk.data.computer

import android.content.Context
import com.android.everytalk.util.AppLogger
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

private const val MAX_COMMAND_CHARS = 1024 * 1024
private const val MAX_HOST_COMMAND_CHARS = 64 * 1024
private const val MAX_STDIN_BYTES = 4 * 1024 * 1024
private const val MAX_ENVIRONMENT_BYTES = 1024 * 1024
private const val MAX_EXEC_TIMEOUT_MILLIS = 60 * 60 * 1000L
internal const val COMPUTER_EXEC_OUTPUT_BYTES = 2 * 1024
private const val RUNTIME_ENVELOPE_MAGIC = "EVERYTALK_EXEC_V1"
private const val HOST_RUNTIME_ENVELOPE_MAGIC = "EVERYTALK_EXEC_HOST_V1"
private const val RUNTIME_WRAPPER_REMOTE_PREFIX = ".everytalk/bin/everytalk-runtime-wrapper-"
private const val RUNTIME_WRAPPER_ASSET_PATH = "computer/runtime-wrapper.sh"
private const val CONTAINER_HELPER_PATH = "/usr/local/libexec/everytalk-containerctl"
private const val INSTALLED_RUNTIME_WRAPPER_PATH = "/usr/local/libexec/everytalk-runtime-wrapper"

/** Wrapper 文件名携带完整内容摘要，新 SSH Transport 只需校验现有版本，无需重新上传。 */
internal fun runtimeWrapperRemotePath(version: String): String {
    require(version.length == 64 && version.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Runtime Wrapper 版本无效"
    }
    return "$RUNTIME_WRAPPER_REMOTE_PREFIX$version"
}

/** Direct 模式在执行命令的同一个 SSH Channel 内清理敏感 Runtime 文件。 */
internal fun directForegroundRuntimeCommand(
    workspaceId: String,
    runtimeId: String,
    timeoutSeconds: Long,
    wrapperVersion: String,
): String {
    ComputerIdentifier.requireValid(workspaceId, "Workspace ID")
    ComputerIdentifier.requireValid(runtimeId, "Runtime ID")
    require(timeoutSeconds in 1..3600) { "Runtime timeout 无效" }
    val wrapperPath = runtimeWrapperRemotePath(wrapperVersion)
    return """
        runtime="${'$'}HOME/.everytalk/workspaces/$workspaceId/.everytalk/runtime/$runtimeId"
        cleanup_runtime() {
            rm -f -- "${'$'}runtime/environment.sh" "${'$'}runtime/stdin" "${'$'}runtime/cwd" "${'$'}runtime/command.sh"
            rmdir -- "${'$'}runtime" 2>/dev/null || true
        }
        trap cleanup_runtime EXIT
        trap 'exit 143' HUP INT TERM
        timeout --signal=TERM --kill-after=5s ${timeoutSeconds}s "${'$'}HOME/$wrapperPath" "${'$'}runtime" '' --envelope
        status="${'$'}?"
        trap - HUP INT TERM
        cleanup_runtime
        trap - EXIT
        exit "${'$'}status"
    """.trimIndent()
}

/** 主机模式仍通过固定 Wrapper 和 stdin Envelope 执行，模型命令不会进入 SSH 参数。 */
internal fun hostForegroundRuntimeCommand(
    runtimeId: String,
    timeoutSeconds: Long,
    wrapperVersion: String? = null,
): String {
    ComputerIdentifier.requireValid(runtimeId, "Runtime ID")
    require(timeoutSeconds in 1..3600) { "Runtime timeout 无效" }
    val wrapperCommand = if (wrapperVersion == null) {
        INSTALLED_RUNTIME_WRAPPER_PATH
    } else {
        "${'$'}HOME/${runtimeWrapperRemotePath(wrapperVersion)}"
    }
    return """
        runtime="${'$'}HOME/.everytalk/host-runtime/$runtimeId"
        cleanup_runtime() {
            rm -f -- "${'$'}runtime/environment.sh" "${'$'}runtime/stdin" "${'$'}runtime/cwd" "${'$'}runtime/command.sh"
            rmdir -- "${'$'}runtime" 2>/dev/null || true
        }
        trap cleanup_runtime EXIT
        trap 'exit 143' HUP INT TERM
        timeout --signal=TERM --kill-after=5s ${timeoutSeconds}s "$wrapperCommand" '$runtimeId' '' --host-envelope
        status="${'$'}?"
        trap - HUP INT TERM
        cleanup_runtime
        trap - EXIT
        exit "${'$'}status"
    """.trimIndent()
}

data class ComputerExecRequest(
    val command: String,
    val cwd: String = "/workspace",
    val environment: Map<String, String> = emptyMap(),
    val secrets: Map<String, CharArray> = emptyMap(),
    val stdin: String? = null,
    val timeoutMillis: Long = 120_000,
    val background: Boolean = false,
    val asRoot: Boolean = false,
    val target: ComputerExecTarget = ComputerExecTarget.CONTAINER,
)

data class ComputerExecResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val processId: String? = null,
    val pid: Long? = null,
    val logPath: String? = null,
)

/** 生成单 Channel Runtime Envelope，调用完成后由调用方覆盖返回的敏感字节。 */
internal fun buildComputerRuntimeEnvelope(request: ComputerExecRequest): ByteArray {
    val environmentBytes = buildRuntimeEnvironment(request.environment, request.secrets)
    val cwdBytes = normalizeExecWorkingDirectory(request).toByteArray(Charsets.UTF_8)
    val commandBytes = request.command.toByteArray(Charsets.UTF_8)
    val stdinBytes = request.stdin?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
    return try {
        ByteArrayOutputStream(
            RUNTIME_ENVELOPE_MAGIC.length + environmentBytes.size + cwdBytes.size +
                commandBytes.size + stdinBytes.size + 96,
        ).use { output ->
            val magic = if (request.target == ComputerExecTarget.HOST) {
                HOST_RUNTIME_ENVELOPE_MAGIC
            } else {
                RUNTIME_ENVELOPE_MAGIC
            }
            output.write("$magic\n".toByteArray(Charsets.US_ASCII))
            listOf(cwdBytes, environmentBytes, commandBytes, stdinBytes).forEach { part ->
                output.write("${part.size}\n".toByteArray(Charsets.US_ASCII))
            }
            output.write(cwdBytes)
            output.write(environmentBytes)
            output.write(commandBytes)
            output.write(stdinBytes)
            output.toByteArray()
        }
    } finally {
        environmentBytes.fill(0)
        cwdBytes.fill(0)
        commandBytes.fill(0)
        stdinBytes.fill(0)
    }
}

private fun normalizeExecWorkingDirectory(request: ComputerExecRequest): String =
    if (request.target == ComputerExecTarget.HOST) {
        ComputerHostWorkingDirectory.normalize(request.cwd)
    } else {
        ComputerWorkspacePath.normalize(request.cwd, allowRoot = true)
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

/**
 * 校验与执行位置有关的参数。
 * Host 确认卡只展示命令和工作目录，因此不接受无法完整展示的环境、Secret、stdin 和后台任务参数。
 */
internal fun requireValidExecTargetOptions(request: ComputerExecRequest) {
    if (request.target != ComputerExecTarget.HOST) return
    when {
        request.secrets.isNotEmpty() -> throw ComputerException(
            ComputerErrorCodes.WORKSPACE_PATH_INVALID,
            "VPS 主机命令不允许注入 Workspace Secret",
        )
        request.environment.isNotEmpty() -> throw ComputerException(
            ComputerErrorCodes.WORKSPACE_PATH_INVALID,
            "VPS 主机命令不允许使用隐藏环境变量，请把必要参数写入可确认的完整命令",
        )
        request.stdin != null -> throw ComputerException(
            ComputerErrorCodes.WORKSPACE_PATH_INVALID,
            "VPS 主机命令不允许使用隐藏标准输入，请把必要操作写入可确认的完整命令",
        )
        request.command.length > MAX_HOST_COMMAND_CHARS -> throw ComputerException(
            ComputerErrorCodes.WORKSPACE_PATH_INVALID,
            "VPS 主机命令过长，无法完整确认",
        )
        request.background -> throw ComputerException(
            ComputerErrorCodes.WORKSPACE_PATH_INVALID,
            "VPS 主机长期任务请在完整命令中使用 systemd、tmux 或其他主机任务管理方式",
        )
        request.asRoot -> throw ComputerException(
            ComputerErrorCodes.SUDO_REQUIRED,
            "主机模式请在完整命令中显式使用 sudo",
        )
    }
}

/** 公共参数校验同时用于审批门和 Runtime，保证无效请求不会先弹确认卡或建立 SSH Channel。 */
internal fun requireValidComputerExecRequest(request: ComputerExecRequest) {
    requireValidExecTargetOptions(request)
    if (request.command.isBlank() || request.command.length > MAX_COMMAND_CHARS || '\u0000' in request.command) {
        throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "exec command 无效")
    }
    if (request.timeoutMillis !in 1..MAX_EXEC_TIMEOUT_MILLIS) {
        throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "exec timeout_ms 无效")
    }
    normalizeExecWorkingDirectory(request)
    request.environment.forEach { (name, value) ->
        ComputerEnvironmentName.requireValid(name)
        if ('\u0000' in value) throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "环境变量值无效")
    }
    request.secrets.keys.forEach(ComputerEnvironmentName::requireValid)
    val stdinBytes = request.stdin?.toByteArray(Charsets.UTF_8)?.size ?: 0
    if (stdinBytes > MAX_STDIN_BYTES) {
        throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "exec stdin 过大")
    }
}

private fun buildRuntimeEnvironment(
    environment: Map<String, String>,
    secrets: Map<String, CharArray>,
): ByteArray {
    val content = buildString {
        environment.toSortedMap().forEach { (name, value) ->
            append(name).append('=').append(runtimeShellQuote(value)).append('\n')
        }
        secrets.toSortedMap().forEach { (name, value) ->
            append(name).append('=').append(runtimeShellQuote(String(value))).append('\n')
        }
    }
    val bytes = content.toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_ENVIRONMENT_BYTES) {
        bytes.fill(0)
        throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "exec 环境变量过大")
    }
    return bytes
}

private fun runtimeShellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

private data class DirectRuntimeWrapper(
    val bytes: ByteArray,
    val sha256: ByteArray,
    val version: String,
)

private data class RuntimeExecutionTimings(
    var wrapperMillis: Long = 0,
    var wrapperInstalled: Boolean = false,
    var envelopeMillis: Long = 0,
    var commandMillis: Long = 0,
)

/**
 * 命令、cwd、环境和 stdin 通过同一个 SSH exec Channel 交给 Wrapper。
 * SSH 命令行只携带受控 ID、版本摘要与整数，实际内容不会进入远端进程参数。
 */
class ComputerRuntimeEnvelope(
    private val context: Context,
) {
    /** Wrapper 内容随 APK 固定，进程内只读取和计算一次版本摘要。 */
    private val directRuntimeWrapper: DirectRuntimeWrapper by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val bytes = context.assets.open(RUNTIME_WRAPPER_ASSET_PATH).use { it.readBytes() }
        val digest = sha256(bytes)
        DirectRuntimeWrapper(bytes = bytes, sha256 = digest, version = digest.toHex())
    }

    /** 模型首轮响应期间预连接 SSH，并校验 VPS 上持久化的 Wrapper。 */
    suspend fun prewarm(connection: ComputerSshConnection, computer: Computer) {
        when (computer.runMode) {
            ComputerRunMode.DIRECT -> ensureDirectRuntimeWrapper(connection)
            ComputerRunMode.CONTAINER -> verifyContainerRuntime(connection, computer)
        }
    }

    suspend fun execute(
        connection: ComputerSshConnection,
        computer: Computer,
        workspace: ComputerWorkspace,
        executionId: String,
        request: ComputerExecRequest,
    ): ComputerExecResult {
        validateRequest(computer, executionId, request)
        val timings = RuntimeExecutionTimings()
        var envelope: ByteArray? = null
        return try {
            if (computer.runMode == ComputerRunMode.DIRECT) {
                val wrapperStarted = System.nanoTime()
                try {
                    timings.wrapperInstalled = ensureDirectRuntimeWrapper(connection)
                } finally {
                    timings.wrapperMillis = elapsedMillis(wrapperStarted)
                }
            }
            val envelopeStarted = System.nanoTime()
            val runtimeEnvelope = try {
                buildComputerRuntimeEnvelope(request)
            } finally {
                timings.envelopeMillis = elapsedMillis(envelopeStarted)
            }
            envelope = runtimeEnvelope
            val commandStarted = System.nanoTime()
            try {
                if (request.background) {
                    executeBackground(connection, computer, workspace, executionId, request, runtimeEnvelope)
                } else {
                    executeForeground(connection, computer, workspace, executionId, request, runtimeEnvelope)
                }
            } finally {
                timings.commandMillis = elapsedMillis(commandStarted)
            }
        } finally {
            envelope?.fill(0)
            request.secrets.values.forEach { it.fill('\u0000') }
            AppLogger.debug(
                "ComputerRuntime",
                "exec timing target=${request.target.name} mode=${computer.runMode.name} background=${request.background} " +
                    "wrapperInstalled=${timings.wrapperInstalled} wrapperMs=${timings.wrapperMillis} " +
                    "envelopeMs=${timings.envelopeMillis} commandMs=${timings.commandMillis}",
            )
        }
    }

    private fun validateRequest(computer: Computer, executionId: String, request: ComputerExecRequest) {
        ComputerIdentifier.requireValid(executionId, "Execution ID")
        requireValidComputerExecRequest(request)
        if (computer.runMode != ComputerRunMode.CONTAINER) {
            throw ComputerException(
                ComputerErrorCodes.COMPUTER_NOT_READY,
                "服务器需要先完成 Container 配置",
                action = "REPAIR_COMPUTER",
            )
        }
    }

    private suspend fun executeForeground(
        connection: ComputerSshConnection,
        computer: Computer,
        workspace: ComputerWorkspace,
        executionId: String,
        request: ComputerExecRequest,
        envelope: ByteArray,
    ): ComputerExecResult {
        val runtimeId = runtimeId(executionId)
        val timeoutSeconds = ((request.timeoutMillis + 999) / 1000).coerceIn(1, 3600)
        val command = when {
            request.target == ComputerExecTarget.HOST -> hostForegroundRuntimeCommand(
                runtimeId = runtimeId,
                timeoutSeconds = timeoutSeconds,
                wrapperVersion = directRuntimeWrapper.version.takeIf { computer.runMode == ComputerRunMode.DIRECT },
            )
            computer.runMode == ComputerRunMode.DIRECT -> directForegroundRuntimeCommand(
                workspaceId = workspace.id,
                runtimeId = runtimeId,
                timeoutSeconds = timeoutSeconds,
                wrapperVersion = directRuntimeWrapper.version,
            )
            else -> {
                val helper = helperPrefix(computer)
                "$helper run ${workspace.id} $runtimeId ${request.asRoot} $timeoutSeconds"
            }
        }
        val result = connection.execute(
            command = command,
            stdin = envelope,
            timeoutMillis = request.timeoutMillis + 15_000,
            maxOutputBytes = COMPUTER_EXEC_OUTPUT_BYTES,
        )
        return ComputerExecResult(
            exitCode = result.exitCode,
            stdout = redact(result.stdout, request.secrets.values),
            stderr = redact(result.stderr, request.secrets.values),
            timedOut = result.timedOut || result.exitCode == 124,
            stdoutTruncated = result.stdoutTruncated,
            stderrTruncated = result.stderrTruncated,
        )
    }

    private suspend fun executeBackground(
        connection: ComputerSshConnection,
        computer: Computer,
        workspace: ComputerWorkspace,
        executionId: String,
        request: ComputerExecRequest,
        envelope: ByteArray,
    ): ComputerExecResult {
        val runtimeId = runtimeId(executionId)
        val processId = "process_${UUID.randomUUID().toString().replace("-", "")}"
        val command = when (computer.runMode) {
            ComputerRunMode.DIRECT -> {
                val wrapperPath = runtimeWrapperRemotePath(directRuntimeWrapper.version)
                "\"\$HOME/$wrapperPath\" " +
                    "\"\$HOME/.everytalk/workspaces/${workspace.id}/.everytalk/runtime/$runtimeId\" " +
                    "\"\$HOME/.everytalk/workspaces/${workspace.id}/.everytalk/background/$processId\" --envelope"
            }
            ComputerRunMode.CONTAINER -> {
                val helper = helperPrefix(computer)
                "$helper run-background ${workspace.id} $runtimeId $processId ${request.asRoot}"
            }
        }
        val result = connection.execute(
            command = command,
            stdin = envelope,
            timeoutMillis = 30_000,
            maxOutputBytes = COMPUTER_EXEC_OUTPUT_BYTES,
        )
        if (result.timedOut || result.exitCode != 0) {
            throw ComputerException(ComputerErrorCodes.EXECUTION_UNKNOWN, "后台进程启动结果无法确认")
        }
        val values = result.stdout.lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
        val redactedStderr = redact(result.stderr, request.secrets.values)
        return ComputerExecResult(
            exitCode = 0,
            stdout = "",
            stderr = redactedStderr,
            timedOut = false,
            stdoutTruncated = false,
            stderrTruncated = result.stderrTruncated,
            processId = values["process_id"] ?: processId,
            pid = values["pid"]?.toLongOrNull(),
            logPath = values["logs"] ?: "/workspace/.everytalk/background/$processId",
        )
    }

    /**
     * 远端校验与缺失时安装都走 exec Channel。Wrapper 使用版本化文件名，
     * 所以新 Transport 只执行一次 `test -x`，不会重新传输或读取整个文件。
     */
    private suspend fun ensureDirectRuntimeWrapper(connection: ComputerSshConnection): Boolean =
        connection.ensureRuntimeWrapper(directRuntimeWrapper.sha256) {
            val path = runtimeWrapperRemotePath(directRuntimeWrapper.version)
            val check = connection.execute(
                command = """
                    target="${'$'}HOME/$path"
                    [ -x "${'$'}target" ] &&
                        [ "${'$'}(sha256sum "${'$'}target" | cut -d' ' -f1)" = '${directRuntimeWrapper.version}' ]
                """.trimIndent(),
                timeoutMillis = 15_000,
                maxOutputBytes = 1024,
            )
            if (!check.timedOut && check.exitCode == 0) return@ensureRuntimeWrapper false

            val install = connection.execute(
                command = """
                    set -eu
                    umask 077
                    bin="${'$'}HOME/.everytalk/bin"
                    target="${'$'}HOME/$path"
                    temporary="${'$'}target.tmp.${'$'}${'$'}"
                    mkdir -p "${'$'}bin"
                    chmod 700 "${'$'}HOME/.everytalk" "${'$'}bin"
                    trap 'rm -f -- "${'$'}temporary"' EXIT HUP INT TERM
                    cat > "${'$'}temporary"
                    chmod 700 "${'$'}temporary"
                    actual="${'$'}(sha256sum "${'$'}temporary" | cut -d' ' -f1)"
                    [ "${'$'}actual" = '${directRuntimeWrapper.version}' ]
                    mv -f "${'$'}temporary" "${'$'}target"
                    trap - EXIT HUP INT TERM
                """.trimIndent(),
                stdin = directRuntimeWrapper.bytes,
                timeoutMillis = 30_000,
                maxOutputBytes = 4 * 1024,
            )
            if (install.timedOut || install.exitCode != 0) {
                throw ComputerException(ComputerErrorCodes.HELPER_INTEGRITY_FAILED, "Direct Runtime Wrapper 安装失败")
            }
            true
        }

    /** Container Helper 已按版本持久安装，只在预热阶段确认它仍可用。 */
    private suspend fun verifyContainerRuntime(connection: ComputerSshConnection, computer: Computer) {
        val result = connection.execute(
            command = "${helperPrefix(computer)} version",
            timeoutMillis = 15_000,
            maxOutputBytes = 1024,
        )
        if (
            result.timedOut ||
            result.exitCode != 0 ||
            result.stdout.lineSequence().none { it.trim() == "version=$COMPUTER_BOOTSTRAP_VERSION" }
        ) {
            throw ComputerException(ComputerErrorCodes.HELPER_INTEGRITY_FAILED, "Container Helper 校验失败")
        }
    }

    private fun helperPrefix(computer: Computer): String = if (computer.username == "root") {
        CONTAINER_HELPER_PATH
    } else {
        "sudo -n -- $CONTAINER_HELPER_PATH"
    }

    private fun redact(output: String, secrets: Collection<CharArray>): String {
        var redacted = output
        secrets.forEach { value ->
            if (value.isNotEmpty()) redacted = redacted.replace(String(value), "[REDACTED]")
        }
        return redacted
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun runtimeId(executionId: String): String = "run_$executionId".also {
        ComputerIdentifier.requireValid(it, "Runtime ID")
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000
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
