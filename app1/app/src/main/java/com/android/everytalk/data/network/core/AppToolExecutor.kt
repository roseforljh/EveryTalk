package com.android.everytalk.data.network

import com.android.everytalk.data.computer.ComputerRequestContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
) -> JsonElement
