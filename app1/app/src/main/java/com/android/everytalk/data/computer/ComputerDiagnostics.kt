package com.android.everytalk.data.computer

import com.android.everytalk.util.AppLogger
import java.util.IdentityHashMap

/** Computer 功能中允许写入 Logcat 的固定失败阶段，禁止传入用户数据。 */
internal enum class ComputerFailureStage {
    HOST_KEY_PROBE,
    ADD_SERVER,
    CONTAINER_PROVISION,
    SERVER_REFRESH,
    SERVER_DETAIL_ACTION,
    WORKSPACE_ACTION,
}

/**
 * Computer 安全诊断日志入口。
 * 日志必须保留错误码、异常类型和调用栈，同时禁止写入异常原始消息。
 */
internal object ComputerDiagnostics {
    private const val LOG_COMPONENT = "Computer"
    private const val MAX_CAUSE_DEPTH = 16

    fun logFailure(stage: ComputerFailureStage, error: Throwable) {
        AppLogger.error(LOG_COMPONENT, buildHeader(stage, error), sanitizedThrowable(error))
    }

    internal fun buildHeader(stage: ComputerFailureStage, error: Throwable): String {
        val code = findComputerErrorCode(error) ?: "UNCLASSIFIED"
        return "stage=${stage.name} code=$code type=${error.javaClass.name}"
    }

    /**
     * 复制异常类型、cause 关系和调用栈，主动丢弃 message 与 suppressed。
     * SSHJ、Keystore 和网络异常的 message 可能包含 Host 或其他用户输入，禁止原样写入日志。
     */
    internal fun sanitizedThrowable(error: Throwable): Throwable = sanitizeThrowable(
        error = error,
        visited = IdentityHashMap(),
        depth = 0,
    )

    private fun sanitizeThrowable(
        error: Throwable,
        visited: IdentityHashMap<Throwable, Boolean>,
        depth: Int,
    ): Throwable {
        val typeName = error.javaClass.name
        if (depth >= MAX_CAUSE_DEPTH || visited.put(error, true) != null) {
            return SanitizedComputerLogException("$typeName [cause truncated]", null).apply {
                stackTrace = emptyArray()
            }
        }
        val safeCause = error.cause?.let { cause ->
            sanitizeThrowable(cause, visited, depth + 1)
        }
        return SanitizedComputerLogException(typeName, safeCause).apply {
            stackTrace = error.stackTrace.copyOf()
        }
    }

    private fun findComputerErrorCode(error: Throwable): String? {
        val visited = IdentityHashMap<Throwable, Boolean>()
        var current: Throwable? = error
        repeat(MAX_CAUSE_DEPTH) {
            val value = current ?: return null
            if (visited.put(value, true) != null) return null
            if (value is ComputerException) return value.code
            current = value.cause
        }
        return null
    }

    /** 仅承载允许进入 Logcat 的异常结构，message 只保存原异常类型名。 */
    private class SanitizedComputerLogException(
        originalTypeName: String,
        cause: Throwable?,
    ) : RuntimeException(originalTypeName, cause)
}
