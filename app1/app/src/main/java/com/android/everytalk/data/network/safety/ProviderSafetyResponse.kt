package com.android.everytalk.data.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal const val AI_CONTENT_SAFETY_ERROR_TYPE = "ai_content_safety"
internal const val AI_CONTENT_SAFETY_BLOCKED_MESSAGE =
    "模型服务已根据安全策略拦截这次生成。请调整请求内容后重试。"

internal class AiContentSafetyBlockedException(
    val providerReason: String? = null,
) : IllegalStateException(AI_CONTENT_SAFETY_BLOCKED_MESSAGE)

/** 将不同模型服务商的安全拦截响应统一成应用内可识别的错误。 */
internal object ProviderSafetyResponse {
    private val safetyReasons = setOf(
        "SAFETY",
        "BLOCKLIST",
        "PROHIBITED_CONTENT",
        "IMAGE_SAFETY",
        "IMAGE_PROHIBITED_CONTENT",
        "RECITATION",
        "SPII",
        "CONTENT_FILTER",
    )

    fun isSafetyReason(reason: String?): Boolean = reason
        ?.trim()
        ?.uppercase()
        ?.let(safetyReasons::contains) == true

    fun geminiBlockReason(response: JsonObject): String? {
        val promptReason = (response["promptFeedback"] as? JsonObject)
            ?.get("blockReason")
            ?.jsonPrimitive
            ?.contentOrNull
        if (isSafetyReason(promptReason)) return promptReason

        return (response["candidates"] as? JsonArray)
            ?.asSequence()
            ?.mapNotNull { candidate ->
                (candidate as? JsonObject)
                    ?.get("finishReason")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }
            ?.firstOrNull(::isSafetyReason)
    }

    fun error(reason: String?): AppStreamEvent.Error = AppStreamEvent.Error(
        message = AI_CONTENT_SAFETY_BLOCKED_MESSAGE,
        code = reason,
        type = AI_CONTENT_SAFETY_ERROR_TYPE,
    )
}
