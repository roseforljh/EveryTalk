package com.android.everytalk.data.computer

import android.content.Context
import com.android.everytalk.util.AppLogger
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RenameFlags
import java.security.MessageDigest
import java.util.EnumSet
import java.util.UUID

private const val MAX_COMMAND_CHARS = 1024 * 1024
private const val MAX_STDIN_BYTES = 4 * 1024 * 1024
private const val MAX_ENVIRONMENT_BYTES = 1024 * 1024
private const val MAX_EXEC_TIMEOUT_MILLIS = 60 * 60 * 1000L
private const val RUNTIME_WRAPPER_REMOTE_PATH = ".everytalk/bin/everytalk-runtime-wrapper"
private const val RUNTIME_WRAPPER_ASSET_PATH = "computer/runtime-wrapper.sh"
private const val CONTAINER_HELPER_PATH = "/usr/local/libexec/everytalk-containerctl"

/** Direct 模式在执行命令的同一个 SSH Channel 内清理敏感 Runtime 文件。 */
internal fun directForegroundRuntimeCommand(
    workspaceId: String,
    runtimeId: String,
    timeoutSeconds: Long,
): String {
    ComputerIdentifier.requireValid(workspaceId, "Workspace ID")
    ComputerIdentifier.requireValid(runtimeId, "Runtime ID")
    require(timeoutSeconds in 1..3600) { "Runtime timeout 无效" }
    return """
        runtime="${'$'}HOME/.everytalk/workspaces/$workspaceId/.everytalk/runtime/$runtimeId"
        timeout --signal=TERM --kill-after=5s ${timeoutSeconds}s "${'$'}HOME/$RUNTIME_WRAPPER_REMOTE_PATH" "${'$'}runtime"
        status="${'$'}?"
        rm -f -- "${'$'}runtime/environment.sh" "${'$'}runtime/stdin" "${'$'}runtime/cwd" "${'$'}runtime/command.sh"
        rmdir -- "${'$'}runtime" 2>/dev/null || true
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

private data class PreparedRuntime(
    val runtimeId: String,
    val remoteHostPath: String,
)

private data class DirectRuntimeWrapper(
    val bytes: ByteArray,
    val sha256: ByteArray,
)

private data class RuntimeExecutionTimings(
    var wrapperMillis: Long = 0,
    var wrapperInstalled: Boolean = false,
    var prepareMillis: Long = 0,
    var commandMillis: Long = 0,
    var cleanupMillis: Long = 0,
)

/**
 * 命令、cwd、环境和 stdin 先写入 0600 Runtime 文件，SSH 命令行只携带受控 ID 与整数。
 */
class ComputerRuntimeEnvelope(
    private val context: Context,
    private val fileTransfer: ComputerFileTransfer,
) {
    /** Wrapper 内容随 APK 固定，进程内只读取和计算一次版本摘要。 */
    private val directRuntimeWrapper: DirectRuntimeWrapper by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val bytes = context.assets.open(RUNTIME_WRAPPER_ASSET_PATH).use { it.readBytes() }
        DirectRuntimeWrapper(bytes = bytes, sha256 = sha256(bytes))
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
        var prepared: PreparedRuntime? = null
        return try {
            val prepareStarted = System.nanoTime()
            val readyRuntime = try {
                prepare(connection, computer, workspace, executionId, request, timings)
            } finally {
                timings.prepareMillis = elapsedMillis(prepareStarted)
            }
            prepared = readyRuntime
            val commandStarted = System.nanoTime()
            val result = try {
                if (request.background) {
                    executeBackground(connection, computer, workspace, readyRuntime, request, timings)
                } else {
                    executeForeground(connection, computer, workspace, readyRuntime, request)
                }
            } finally {
                timings.commandMillis = elapsedMillis(commandStarted)
            }
            if (!request.background && computer.runMode == ComputerRunMode.DIRECT) {
                // 正常返回说明远端清理段已经执行；异常时 prepared 保留给 finally 的 SFTP 兜底。
                prepared = null
            }
            result
        } finally {
            if (!request.background) prepared?.let { cleanup(connection, it, timings) }
            request.secrets.values.forEach { it.fill('\u0000') }
            AppLogger.debug(
                "ComputerRuntime",
                "exec timing mode=${computer.runMode.name} background=${request.background} " +
                    "wrapperInstalled=${timings.wrapperInstalled} wrapperMs=${timings.wrapperMillis} " +
                    "prepareMs=${timings.prepareMillis} commandMs=${timings.commandMillis} " +
                    "cleanupMs=${timings.cleanupMillis}",
            )
        }
    }

    private fun validateRequest(computer: Computer, executionId: String, request: ComputerExecRequest) {
        ComputerIdentifier.requireValid(executionId, "Execution ID")
        if (request.command.isBlank() || request.command.length > MAX_COMMAND_CHARS || '\u0000' in request.command) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "exec command 无效")
        }
        if (request.timeoutMillis !in 1..MAX_EXEC_TIMEOUT_MILLIS) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "exec timeout_ms 无效")
        }
        if (request.asRoot && computer.runMode != ComputerRunMode.CONTAINER) {
            throw ComputerException(ComputerErrorCodes.COMPUTER_NOT_READY, "as_root 只允许 Container 模式")
        }
        ComputerWorkspacePath.normalize(request.cwd, allowRoot = true)
        request.environment.forEach { (name, value) ->
            ComputerEnvironmentName.requireValid(name)
            if ('\u0000' in value) throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "环境变量值无效")
        }
        request.secrets.keys.forEach(ComputerEnvironmentName::requireValid)
        val stdinBytes = request.stdin?.toByteArray()?.size ?: 0
        if (stdinBytes > MAX_STDIN_BYTES) {
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "exec stdin 过大")
        }
    }

    private suspend fun prepare(
        connection: ComputerSshConnection,
        computer: Computer,
        workspace: ComputerWorkspace,
        executionId: String,
        request: ComputerExecRequest,
        timings: RuntimeExecutionTimings,
    ): PreparedRuntime {
        val runtimeId = "run_$executionId"
        ComputerIdentifier.requireValid(runtimeId, "Runtime ID")
        val environmentBytes = buildEnvironmentFile(request.environment, request.secrets)
        val commandBytes = request.command.toByteArray(Charsets.UTF_8)
        val cwdBytes = ComputerWorkspacePath.normalize(request.cwd, allowRoot = true).toByteArray(Charsets.UTF_8)
        val stdinBytes = request.stdin?.toByteArray(Charsets.UTF_8)
        try {
            return connection.withSftp { sftp ->
                val root = fileTransfer.resolveWorkspaceRoot(sftp, workspace)
                val runtimeRoot = "$root/.everytalk/runtime"
                val runtimePath = "$runtimeRoot/$runtimeId"
                if (sftp.statExistence(runtimePath) != null) {
                    throw ComputerException(ComputerErrorCodes.IDEMPOTENCY_CONFLICT, "Runtime ID 已存在")
                }
                if (computer.runMode == ComputerRunMode.DIRECT) {
                    val wrapperStarted = System.nanoTime()
                    try {
                        timings.wrapperInstalled = connection.ensureRuntimeWrapper(directRuntimeWrapper.sha256) {
                            installDirectWrapper(sftp, executionId, directRuntimeWrapper.bytes)
                        }
                    } finally {
                        timings.wrapperMillis = elapsedMillis(wrapperStarted)
                    }
                }
                fileTransfer.createPrivateDirectory(sftp, runtimePath)
                try {
                    fileTransfer.writePrivateFile(sftp, "$runtimePath/command.sh", commandBytes)
                    fileTransfer.writePrivateFile(sftp, "$runtimePath/cwd", cwdBytes)
                    if (environmentBytes.isNotEmpty()) {
                        fileTransfer.writePrivateFile(sftp, "$runtimePath/environment.sh", environmentBytes)
                    }
                    if (stdinBytes != null) {
                        fileTransfer.writePrivateFile(sftp, "$runtimePath/stdin", stdinBytes)
                    }
                    PreparedRuntime(runtimeId, runtimePath)
                } catch (error: Throwable) {
                    removeRuntimeFiles(sftp, runtimePath)
                    throw error
                }
            }
        } finally {
            environmentBytes.fill(0)
            commandBytes.fill(0)
            cwdBytes.fill(0)
            stdinBytes?.fill(0)
        }
    }

    private suspend fun executeForeground(
        connection: ComputerSshConnection,
        computer: Computer,
        workspace: ComputerWorkspace,
        prepared: PreparedRuntime,
        request: ComputerExecRequest,
    ): ComputerExecResult {
        val timeoutSeconds = ((request.timeoutMillis + 999) / 1000).coerceIn(1, 3600)
        val command = when (computer.runMode) {
            ComputerRunMode.DIRECT -> directForegroundRuntimeCommand(
                workspaceId = workspace.id,
                runtimeId = prepared.runtimeId,
                timeoutSeconds = timeoutSeconds,
            )
            ComputerRunMode.CONTAINER -> {
                val helper = helperPrefix(computer)
                "$helper run ${workspace.id} ${prepared.runtimeId} ${request.asRoot} $timeoutSeconds"
            }
        }
        val result = connection.execute(
            command = command,
            timeoutMillis = request.timeoutMillis + 15_000,
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
        prepared: PreparedRuntime,
        request: ComputerExecRequest,
        timings: RuntimeExecutionTimings,
    ): ComputerExecResult {
        val processId = "process_${UUID.randomUUID().toString().replace("-", "")}"
        val command = when (computer.runMode) {
            ComputerRunMode.DIRECT -> """
                umask 077
                logs="${'$'}HOME/.everytalk/workspaces/${workspace.id}/.everytalk/background/$processId"
                mkdir -p "${'$'}logs"
                chmod 700 "${'$'}logs"
                nohup setsid "${'$'}HOME/$RUNTIME_WRAPPER_REMOTE_PATH" "${'$'}HOME/.everytalk/workspaces/${workspace.id}/.everytalk/runtime/${prepared.runtimeId}" "${'$'}logs" > "${'$'}logs/stdout.log" 2> "${'$'}logs/stderr.log" < /dev/null &
                pid="${'$'}!"
                attempt=0
                while [ ! -f "${'$'}logs/state" ] && [ "${'$'}attempt" -lt 30 ]; do
                    sleep 0.1
                    attempt="${'$'}((attempt + 1))"
                done
                [ -f "${'$'}logs/state" ] || { kill -TERM "-${'$'}pid" 2>/dev/null || true; exit 77; }
                state_pid="${'$'}(awk -F= '${'$'}1 == "pid" { print ${'$'}2; exit }' "${'$'}logs/state")"
                [ "${'$'}state_pid" = "${'$'}pid" ] || { kill -TERM "-${'$'}pid" 2>/dev/null || true; exit 77; }
                printf 'pid=%s\nprocess_id=%s\nlogs=%s\n' "${'$'}pid" '$processId' '/workspace/.everytalk/background/$processId'
            """.trimIndent()
            ComputerRunMode.CONTAINER -> {
                val helper = helperPrefix(computer)
                "$helper run-background ${workspace.id} ${prepared.runtimeId} $processId ${request.asRoot}"
            }
        }
        val result = connection.execute(command = command, timeoutMillis = 30_000, maxOutputBytes = 64 * 1024)
        if (result.timedOut || result.exitCode != 0) {
            cleanup(connection, prepared, timings)
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

    private fun installDirectWrapper(
        sftp: net.schmizz.sshj.sftp.SFTPClient,
        executionId: String,
        wrapper: ByteArray,
    ) {
        val home = sftp.canonicalize(".").trimEnd('/')
        val binDirectory = "$home/.everytalk/bin"
        if (sftp.statExistence(binDirectory) == null) {
            fileTransfer.createPrivateDirectory(sftp, binDirectory)
        }
        val target = "$home/$RUNTIME_WRAPPER_REMOTE_PATH"
        val temporary = "$target.$executionId.tmp"
        try {
            fileTransfer.writePrivateFile(sftp, temporary, wrapper, executable = true)
            sftp.rename(temporary, target, EnumSet.of(RenameFlags.OVERWRITE, RenameFlags.ATOMIC))
            val remoteBytes = ByteArray(wrapper.size)
            sftp.open(target, EnumSet.of(OpenMode.READ)).use { file ->
                var offset = 0
                while (offset < remoteBytes.size) {
                    val count = file.read(offset.toLong(), remoteBytes, offset, remoteBytes.size - offset)
                    if (count <= 0) break
                    offset += count
                }
            }
            if (!MessageDigest.isEqual(sha256(wrapper), sha256(remoteBytes))) {
                throw ComputerException(ComputerErrorCodes.HELPER_INTEGRITY_FAILED, "Direct Runtime Wrapper 校验失败")
            }
            remoteBytes.fill(0)
        } finally {
            runCatching { sftp.rm(temporary) }
        }
    }

    private suspend fun cleanup(
        connection: ComputerSshConnection,
        prepared: PreparedRuntime,
        timings: RuntimeExecutionTimings,
    ) {
        val cleanupStarted = System.nanoTime()
        try {
            runCatching {
                connection.withSftp { sftp -> removeRuntimeFiles(sftp, prepared.remoteHostPath) }
            }
        } finally {
            timings.cleanupMillis += elapsedMillis(cleanupStarted)
        }
    }

    private fun removeRuntimeFiles(sftp: net.schmizz.sshj.sftp.SFTPClient, runtimePath: String) {
        listOf("environment.sh", "stdin", "cwd", "command.sh").forEach { name ->
            runCatching { sftp.rm("$runtimePath/$name") }
        }
        runCatching { sftp.rmdir(runtimePath) }
    }

    private fun buildEnvironmentFile(environment: Map<String, String>, secrets: Map<String, CharArray>): ByteArray {
        val content = buildString {
            environment.toSortedMap().forEach { (name, value) ->
                append(name).append('=').append(shellQuote(value)).append('\n')
            }
            secrets.toSortedMap().forEach { (name, value) ->
                append(name).append('=').append(shellQuote(String(value))).append('\n')
            }
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_ENVIRONMENT_BYTES) {
            bytes.fill(0)
            throw ComputerException(ComputerErrorCodes.WORKSPACE_PATH_INVALID, "exec 环境变量过大")
        }
        return bytes
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

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
