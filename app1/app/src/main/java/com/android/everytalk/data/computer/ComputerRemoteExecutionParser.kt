package com.android.everytalk.data.computer

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 已通过完整校验的 VPS Execution 状态。
 *
 * 该类型只保存固定协议字段，不保存 VPS 返回的任意路径；状态文件中的路径永远由
 * Android 根据 Workspace、Execution ID 重新计算，不能由远端文本覆盖。
 */
data class ParsedRemoteExecutionState(
    val protocol: Int,
    val executionId: String,
    val processId: String,
    val requestHash: String,
    val target: ComputerExecTarget,
    val pid: Long,
    val startTicks: Long,
    val status: ComputerRemoteStatus,
    val exitCode: Int?,
    val startedAt: Long,
    val updatedAt: Long,
    val stdoutBytes: Long,
    val stderrBytes: Long,
    val terminationReason: String? = null,
)

/** 受控结果查询返回的日志片段。完整日志仍留在 VPS，不随状态查询全部回传。 */
data class ParsedRemoteExecutionResult(
    val state: ParsedRemoteExecutionState,
    val stdout: String,
    val stderr: String,
    val stdoutOffset: Long,
    val stderrOffset: Long,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
)

/**
 * 状态协议不可信时使用的明确解析错误。
 *
 * 身份冲突和普通协议损坏不能共用一个错误码，否则恢复流程会把“有人拿不同请求
 * 复用了同一个 Execution ID”误当成暂时网络问题，进而允许危险的重试。
 */
class ComputerRemoteExecutionParseException(
    message: String,
    val code: String = ComputerErrorCodes.EXECUTION_STATE_INVALID,
) : IllegalArgumentException(message)

/**
 * 严格解析 Runtime V2 的固定文本协议。
 *
 * 协议故意不使用 JSON：Wrapper 和受限 Helper 都只输出固定的 key=value，解析器因此可以
 * 在不执行任何远端内容的前提下完成校验。未知字段向前兼容并忽略，已知字段重复、值越界、
 * 必填字段缺失或身份不匹配都会直接失败。
 */
object ComputerRemoteExecutionParser {
    const val PROTOCOL_VERSION = 2

    private const val MAX_PAYLOAD_BYTES = 128 * 1024
    private const val MAX_KEY_LENGTH = 64
    private const val MAX_VALUE_LENGTH = 16 * 1024
    private const val SHA256_HEX_LENGTH = 64

    private val knownKeys = setOf(
        "protocol",
        "execution_id",
        "process_id",
        "request_hash",
        "target",
        "pid",
        "start_ticks",
        "status",
        "exit_code",
        "started_at",
        "updated_at",
        "stdout_bytes",
        "stderr_bytes",
        "stdout",
        "stderr",
        "stdout_base64",
        "stderr_base64",
        "stdout_offset",
        "stderr_offset",
        "stdout_truncated",
        "stderr_truncated",
        "event_type",
        "event_seq",
        "stdout_cursor",
        "stderr_cursor",
        "observed_at",
        "boot_id",
        "termination_reason",
    )

    /** 解析状态查询输出，并验证它确实属于本地期待的 Execution。 */
    fun parseState(
        payload: String,
        expectedExecutionId: String? = null,
        expectedProcessId: String? = null,
        expectedRequestHash: String? = null,
        expectedTarget: ComputerExecTarget? = null,
    ): ParsedRemoteExecutionState {
        val fields = parseFields(payload)
        val protocol = fields.required("protocol").toIntOrNull()
            ?: invalid("protocol 不是整数")
        if (protocol != PROTOCOL_VERSION) invalid("Runtime 协议版本不匹配")

        val executionId = fields.required("execution_id")
        requireIdentifier(executionId, "execution_id", "execution_")
        if (expectedExecutionId != null && executionId != expectedExecutionId) {
            invalid("execution_id 与本地请求不一致")
        }

        val processId = fields.required("process_id")
        requireIdentifier(processId, "process_id", "process_")
        if (expectedProcessId != null && processId != expectedProcessId) {
            invalid("process_id 与本地请求不一致")
        }

        val requestHash = fields.required("request_hash")
        requireSha256(requestHash, "request_hash")
        if (expectedRequestHash != null && requestHash != expectedRequestHash) {
            invalid(
                "request_hash 与本地请求不一致",
                ComputerErrorCodes.EXECUTION_REQUEST_HASH_CONFLICT,
            )
        }

        val target = parseTarget(fields.required("target"))
        if (expectedTarget != null && target != expectedTarget) {
            invalid("target 与本地请求不一致")
        }

        val pid = fields.requiredLong("pid", min = 0)
        val startTicks = fields.requiredLong("start_ticks", min = 0)
        val status = parseStatus(fields.required("status"))
        val exitCode = fields.optionalInt("exit_code")
        if (status in TERMINAL_EXIT_STATUSES && exitCode == null) {
            invalid("终态缺少 exit_code")
        }

        val startedAt = fields.requiredLong("started_at", min = 0)
        val updatedAt = fields.requiredLong("updated_at", min = 0)
        val stdoutBytes = fields.requiredLong("stdout_bytes", min = 0)
        val stderrBytes = fields.requiredLong("stderr_bytes", min = 0)
        val terminationReason = fields["termination_reason"]?.takeIf(String::isNotBlank)
        if (terminationReason != null && terminationReason !in setOf("VPS_RESTARTED", "REMOTE_PROCESS_TERMINATED")) {
            invalid("termination_reason 无效")
        }
        if (updatedAt < startedAt) invalid("updated_at 早于 started_at")

        return ParsedRemoteExecutionState(
            protocol = protocol,
            executionId = executionId,
            processId = processId,
            requestHash = requestHash,
            target = target,
            pid = pid,
            startTicks = startTicks,
            status = status,
            exitCode = exitCode,
            startedAt = startedAt,
            updatedAt = updatedAt,
            stdoutBytes = stdoutBytes,
            stderrBytes = stderrBytes,
            terminationReason = terminationReason,
        )
    }

    /** ByteArray 入口用于 SSH 通道，统一按 UTF-8 解码并拒绝过大的状态响应。 */
    fun parseState(
        payload: ByteArray,
        expectedExecutionId: String? = null,
        expectedProcessId: String? = null,
        expectedRequestHash: String? = null,
        expectedTarget: ComputerExecTarget? = null,
    ): ParsedRemoteExecutionState {
        if (payload.size > MAX_PAYLOAD_BYTES) invalid("Runtime 状态响应过大")
        return parseState(
            String(payload, StandardCharsets.UTF_8),
            expectedExecutionId,
            expectedProcessId,
            expectedRequestHash,
            expectedTarget,
        )
    }

    /** 解析受控结果查询输出，日志只接受单行或 Base64 字段，避免文本越界污染状态协议。 */
    fun parseResult(
        payload: String,
        expectedExecutionId: String? = null,
        expectedProcessId: String? = null,
        expectedRequestHash: String? = null,
        expectedTarget: ComputerExecTarget? = null,
    ): ParsedRemoteExecutionResult {
        val fields = parseFields(payload)
        val state = parseState(
            payload = payload,
            expectedExecutionId = expectedExecutionId,
            expectedProcessId = expectedProcessId,
            expectedRequestHash = expectedRequestHash,
            expectedTarget = expectedTarget,
        )
        val stdout = decodeOutput(fields, "stdout", "stdout_base64")
        val stderr = decodeOutput(fields, "stderr", "stderr_base64")
        val stdoutOffset = fields.optionalLong("stdout_offset", min = 0) ?: 0
        val stderrOffset = fields.optionalLong("stderr_offset", min = 0) ?: 0
        val stdoutTruncated = fields.optionalBoolean("stdout_truncated") ?: false
        val stderrTruncated = fields.optionalBoolean("stderr_truncated") ?: false
        return ParsedRemoteExecutionResult(
            state = state,
            stdout = stdout,
            stderr = stderr,
            stdoutOffset = stdoutOffset,
            stderrOffset = stderrOffset,
            stdoutTruncated = stdoutTruncated,
            stderrTruncated = stderrTruncated,
        )
    }

    /** 解析长轮询事件；事件元数据和日志状态使用同一份受校验响应。 */
    fun parseWatchEvent(
        payload: String,
        expectedExecutionId: String? = null,
        expectedProcessId: String? = null,
        expectedRequestHash: String? = null,
        expectedTarget: ComputerExecTarget? = null,
    ): ComputerRemoteExecutionWatchEvent {
        val fields = parseFields(payload)
        val result = parseResult(
            payload = payload,
            expectedExecutionId = expectedExecutionId,
            expectedProcessId = expectedProcessId,
            expectedRequestHash = expectedRequestHash,
            expectedTarget = expectedTarget,
        )
        val eventType = fields.required("event_type")
        if (eventType !in setOf("PROGRESS", "TERMINAL", "HEARTBEAT")) invalid("event_type 无效")
        val stdoutCursor = fields.requiredLong("stdout_cursor", min = 0)
        val stderrCursor = fields.requiredLong("stderr_cursor", min = 0)
        if (stdoutCursor < result.stdoutOffset || stderrCursor < result.stderrOffset) {
            invalid("日志游标发生倒退")
        }
        return ComputerRemoteExecutionWatchEvent(
            result = ComputerRemoteExecutionResult(
                snapshot = ComputerRemoteExecutionSnapshot(
                    executionId = result.state.executionId,
                    processId = result.state.processId,
                    status = result.state.status,
                    target = result.state.target,
                    requestHash = result.state.requestHash,
                    pid = result.state.pid,
                    startTicks = result.state.startTicks,
                    exitCode = result.state.exitCode,
                    startedAt = result.state.startedAt,
                    updatedAt = result.state.updatedAt,
                    stdoutBytes = result.state.stdoutBytes,
                    stderrBytes = result.state.stderrBytes,
                    terminationReason = result.state.terminationReason,
                ),
                stdoutOffset = result.stdoutOffset,
                stderrOffset = result.stderrOffset,
                stdout = result.stdout,
                stderr = result.stderr,
                stdoutTruncated = result.stdoutTruncated,
                stderrTruncated = result.stderrTruncated,
            ),
            eventType = eventType,
            eventSequence = fields.requiredLong("event_seq", min = 1),
            stdoutCursor = stdoutCursor,
            stderrCursor = stderrCursor,
            observedAt = fields.requiredLong("observed_at", min = 0),
        )
    }

    private fun parseFields(payload: String): Map<String, String> {
        if (payload.toByteArray(StandardCharsets.UTF_8).size > MAX_PAYLOAD_BYTES) {
            invalid("Runtime 响应过大")
        }
        val fields = LinkedHashMap<String, String>()
        val lines = payload.split('\n')
        lines.forEachIndexed { index, rawLine ->
            val line = if (rawLine.endsWith('\r')) rawLine.dropLast(1) else rawLine
            if (line.isEmpty() && index == lines.lastIndex) return@forEachIndexed
            if (line.isEmpty()) invalid("Runtime 响应包含空行")
            val separator = line.indexOf('=')
            if (separator <= 0) invalid("Runtime 响应不是 key=value")
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            if (key.length > MAX_KEY_LENGTH || !isValidKey(key)) invalid("Runtime 字段名无效")
            if (value.length > MAX_VALUE_LENGTH || value.any { it == '\u0000' || it == '\r' || it == '\n' }) {
                invalid("Runtime 字段值无效")
            }
            if (key in knownKeys && fields.put(key, value) != null) invalid("Runtime 字段重复")
        }
        return fields
    }

    private fun decodeOutput(fields: Map<String, String>, plainKey: String, encodedKey: String): String {
        val plain = fields[plainKey]
        val encoded = fields[encodedKey]
        if (plain != null && encoded != null) invalid("Runtime 输出字段重复")
        if (plain != null) return plain
        if (encoded == null) return ""
        return try {
            val decoded = Base64.getDecoder().decode(encoded)
            if (decoded.size > MAX_VALUE_LENGTH) invalid("Runtime 输出过大")
            String(decoded, StandardCharsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            throw ComputerRemoteExecutionParseException("Runtime 输出 Base64 无效")
        }
    }

    private fun parseTarget(value: String): ComputerExecTarget = when (value) {
        "CONTAINER" -> ComputerExecTarget.CONTAINER
        "HOST" -> ComputerExecTarget.HOST
        else -> invalid("target 无效")
    }

    private fun parseStatus(value: String): ComputerRemoteStatus = runCatching {
        ComputerRemoteStatus.valueOf(value)
    }.getOrElse { invalid("status 无效") }

    private fun requireIdentifier(value: String, field: String, prefix: String) {
        if (!value.startsWith(prefix) || value.length > 128 || !isValidIdentifier(value)) {
            invalid("$field 无效")
        }
    }

    private fun requireSha256(value: String, field: String) {
        if (value.length != SHA256_HEX_LENGTH || value.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            invalid("$field 无效")
        }
    }

    private fun isValidIdentifier(value: String): Boolean =
        value.isNotEmpty() && value.all { it == '_' || it == '-' || it in 'A'..'Z' || it in 'a'..'z' || it.isDigit() }

    private fun isValidKey(value: String): Boolean =
        value.isNotEmpty() && value.all { it == '_' || it in 'A'..'Z' || it in 'a'..'z' || it.isDigit() }

    private fun invalid(
        message: String,
        code: String = ComputerErrorCodes.EXECUTION_STATE_INVALID,
    ): Nothing = throw ComputerRemoteExecutionParseException(message, code)

    private fun Map<String, String>.required(name: String): String = this[name]
        ?: invalid("缺少 Runtime 字段 $name")

    private fun Map<String, String>.requiredLong(name: String, min: Long): Long {
        val value = required(name).toLongOrNull() ?: invalid("$name 不是整数")
        if (value < min) invalid("$name 超出范围")
        return value
    }

    private fun Map<String, String>.optionalLong(name: String, min: Long): Long? {
        val raw = this[name] ?: return null
        if (raw.isEmpty()) return null
        val value = raw.toLongOrNull() ?: invalid("$name 不是整数")
        if (value < min) invalid("$name 超出范围")
        return value
    }

    private fun Map<String, String>.optionalInt(name: String): Int? {
        val raw = this[name] ?: return null
        if (raw.isEmpty()) return null
        return raw.toIntOrNull() ?: invalid("$name 不是整数")
    }

    private fun Map<String, String>.optionalBoolean(name: String): Boolean? = when (val raw = this[name]) {
        null, "" -> null
        "true" -> true
        "false" -> false
        else -> invalid("$name 不是布尔值")
    }

    private val TERMINAL_EXIT_STATUSES = setOf(
        ComputerRemoteStatus.SUCCEEDED,
        ComputerRemoteStatus.FAILED,
        ComputerRemoteStatus.TIMED_OUT,
        ComputerRemoteStatus.CANCELLED,
    )
}
