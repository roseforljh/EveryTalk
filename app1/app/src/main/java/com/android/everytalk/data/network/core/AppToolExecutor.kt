package com.android.everytalk.data.network

import com.android.everytalk.data.computer.ComputerRequestContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 受信工具执行结果。控制字段与工具返回给模型的 JSON 分开，避免普通结果里的
 * `terminate` 被误当成 Agent 控制指令。
 */
data class AppToolExecutionResult(
    val content: JsonElement,
    val terminate: Boolean = false,
)

/**
 * 四类 Provider 共用的本地 Tool 回调。
 * ComputerRequestContext 只在 Android 内存中传递，null 表示本轮没有启用 Agent。
 */
typealias AppToolExecutor = suspend (
    toolName: String,
    arguments: JsonObject,
    toolCallId: String,
    computerRequestContext: ComputerRequestContext?,
    updateStatus: suspend (String?) -> Unit,
) -> AppToolExecutionResult

/**
 * 旧版 Provider 测试和独立调用方使用的三参数 Tool 回调。
 * 该入口不携带 Computer 上下文，只用于保持已有调用方式兼容。
 */
typealias LegacyAppToolExecutor = suspend (
    toolName: String,
    arguments: JsonObject,
    updateStatus: suspend (String?) -> Unit,
) -> JsonElement

/** 把旧回调包装成当前统一回调，新增参数仅由正式的 owner 入口消费。 */
internal fun LegacyAppToolExecutor?.toAppToolExecutor(): AppToolExecutor? {
    val legacyExecutor = this ?: return null
    return { toolName, arguments, _, _, updateStatus ->
        AppToolExecutionResult(legacyExecutor(toolName, arguments, updateStatus))
    }
}
