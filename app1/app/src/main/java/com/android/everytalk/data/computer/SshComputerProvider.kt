package com.android.everytalk.data.computer

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 现有 SSH/VPS 工具的 Provider 边界。
 * SSH 的真实执行仍由旧的 ComputerToolExecutor 完成；这个适配层只负责让
 * ComputerProviderRouter 依赖明确的 Provider 契约。
 */
fun interface SshComputerProvider {
    suspend fun execute(
        toolName: String,
        arguments: JsonObject,
        toolCallId: String,
        context: ComputerRequestContext,
        updateStatus: suspend (String?) -> Unit,
    ): JsonElement
}

/** 将已有 ComputerToolExecutor 的函数签名转换为正式 SSH Provider。 */
class DelegatingSshComputerProvider(
    private val delegate: suspend (String, JsonObject, String, ComputerRequestContext, suspend (String?) -> Unit) -> JsonElement,
) : SshComputerProvider {
    override suspend fun execute(
        toolName: String,
        arguments: JsonObject,
        toolCallId: String,
        context: ComputerRequestContext,
        updateStatus: suspend (String?) -> Unit,
    ): JsonElement = delegate(toolName, arguments, toolCallId, context, updateStatus)
}
