package com.android.everytalk.data.computer

import android.content.Context
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
private const val CONTAINER_HELPER_PATH = "/usr/local/libexec/everytalk-containerctl"

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

/**
 * 命令、cwd、环境和 stdin 先写入 0600 Runtime 文件，SSH 命令行只携带受控 ID 与整数。
 */
class ComputerRuntimeEnvelope(
    private val context: Context,
    private val fileTransfer: ComputerFileTransfer,
) {
    suspend fun execute(
        connection: ComputerSshConnection,
        computer: Computer,
        workspace: ComputerWorkspace,
        executionId: String,
        request: ComputerExecRequest,
    ): ComputerExecResult {
        validateRequest(computer, executionId, request)
        var prepared: PreparedRuntime? = null
        return try {
            val readyRuntime = prepare(connection, computer, workspace, executionId, request)
            prepared = readyRuntime
            if (request.background) {
                executeBackground(connection, computer, workspace, readyRuntime, request)
            } else {
                executeForeground(connection, computer, workspace, readyRuntime, request)
            }
        } finally {
            if (!request.background) prepared?.let { cleanup(connection, it) }
            request.secrets.values.forEach { it.fill('\u0000') }
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
                sftp.mkdir(runtimePath)
                sftp.chmod(runtimePath, 0b111000000)
                try {
                    fileTransfer.writePrivateFile(sftp, "$runtimePath/command.sh", commandBytes)
                    fileTransfer.writePrivateFile(sftp, "$runtimePath/cwd", cwdBytes)
                    if (environmentBytes.isNotEmpty()) {
                        fileTransfer.writePrivateFile(sftp, "$runtimePath/environment.sh", environmentBytes)
                    }
                    if (stdinBytes != null) {
                        fileTransfer.writePrivateFile(sftp, "$runtimePath/stdin", stdinBytes)
                    }
                    if (computer.runMode == ComputerRunMode.DIRECT) ensureDirectWrapper(sftp, executionId)
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
            ComputerRunMode.DIRECT ->
                "timeout --signal=TERM --kill-after=5s ${timeoutSeconds}s \"${'$'}HOME/$RUNTIME_WRAPPER_REMOTE_PATH\" \"${'$'}HOME/.everytalk/workspaces/${workspace.id}/.everytalk/runtime/${prepared.runtimeId}\""
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
    ): ComputerExecResult {
        val processId = "process_${UUID.randomUUID().toString().replace("-", "")}"
        val command = when (computer.runMode) {
            ComputerRunMode.DIRECT -> """
                umask 077
                logs="${'$'}HOME/.everytalk/workspaces/${workspace.id}/.everytalk/background/$processId"
                mkdir -p "${'$'}logs"
                nohup setsid "${'$'}HOME/$RUNTIME_WRAPPER_REMOTE_PATH" "${'$'}HOME/.everytalk/workspaces/${workspace.id}/.everytalk/runtime/${prepared.runtimeId}" > "${'$'}logs/stdout.log" 2> "${'$'}logs/stderr.log" < /dev/null &
                printf 'pid=%s\nprocess_id=%s\nlogs=%s\n' "${'$'}!" '$processId' '/workspace/.everytalk/background/$processId'
            """.trimIndent()
            ComputerRunMode.CONTAINER -> {
                val helper = helperPrefix(computer)
                "$helper run-background ${workspace.id} ${prepared.runtimeId} $processId ${request.asRoot}"
            }
        }
        val result = connection.execute(command = command, timeoutMillis = 30_000, maxOutputBytes = 64 * 1024)
        if (result.timedOut || result.exitCode != 0) {
            cleanup(connection, prepared)
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

    private fun ensureDirectWrapper(sftp: net.schmizz.sshj.sftp.SFTPClient, executionId: String) {
        val home = sftp.canonicalize(".").trimEnd('/')
        val binDirectory = "$home/.everytalk/bin"
        if (sftp.statExistence(binDirectory) == null) {
            sftp.mkdir(binDirectory)
            sftp.chmod(binDirectory, 0b111000000)
        }
        val wrapper = context.assets.open("computer/runtime-wrapper.sh").use { it.readBytes() }
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
            wrapper.fill(0)
            runCatching { if (sftp.statExistence(temporary) != null) sftp.rm(temporary) }
        }
    }

    private suspend fun cleanup(connection: ComputerSshConnection, prepared: PreparedRuntime) {
        runCatching {
            connection.withSftp { sftp -> removeRuntimeFiles(sftp, prepared.remoteHostPath) }
        }
    }

    private fun removeRuntimeFiles(sftp: net.schmizz.sshj.sftp.SFTPClient, runtimePath: String) {
        listOf("environment.sh", "stdin", "cwd", "command.sh").forEach { name ->
            runCatching { if (sftp.statExistence("$runtimePath/$name") != null) sftp.rm("$runtimePath/$name") }
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
