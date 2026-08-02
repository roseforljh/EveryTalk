package com.android.everytalk.data.DataClass

import kotlinx.serialization.Serializable

const val CONTEXT_COMPRESSION_STATE_SCHEMA_VERSION = 1

/**
 * 随 AI 消息持久化的压缩检查点。聊天界面仍保留完整历史，该状态只控制模型可见请求。
 */
@Serializable
data class ContextCompressionState(
    val schemaVersion: Int = CONTEXT_COMPRESSION_STATE_SCHEMA_VERSION,
    val configId: String,
    val provider: String,
    val channel: String,
    val model: String,
    val summary: String? = null,
    val summarizedThroughMessageId: String? = null,
    val summarizedPrefixFingerprint: String? = null,
    val windowNumber: Long = 0L,
    val windowId: String,
    val previousWindowId: String? = null,
    val estimatedTokensBefore: Long = 0L,
    val estimatedTokensAfter: Long = 0L,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val openAiResponsesInputJson: String? = null,
    val openAiResponsesThroughMessageId: String? = null,
    val openAiResponsesEstimatedTokens: Long = 0L,
    val anthropicMessagesJson: String? = null,
    val anthropicThroughMessageId: String? = null,
    val anthropicEstimatedTokens: Long = 0L,
) {
    fun matchesConfig(config: ApiConfig): Boolean =
        schemaVersion == CONTEXT_COMPRESSION_STATE_SCHEMA_VERSION &&
            configId == config.id

    fun matchesNativeResponsesConfig(config: ApiConfig): Boolean =
        matchesConfig(config) &&
            provider.equals(config.provider, ignoreCase = true) &&
            channel.equals(config.channel, ignoreCase = true) &&
            model.equals(config.model, ignoreCase = true)

    fun matchesNativeAnthropicConfig(config: ApiConfig): Boolean =
        matchesNativeResponsesConfig(config)
}

/** 只在客户端内部使用，不作为普通供应商的扩展请求字段发送。 */
@Serializable
data class RequestContextManagement(
    val configId: String,
    val maxContextTokens: Int,
    val reservedOutputTokens: Int,
    val compactThresholdTokens: Long,
    val autoCompressionEnabled: Boolean,
    val inputTokenCalibration: Long = 0L,
    val estimatedInputTokens: Long = 0L,
    val restoredState: ContextCompressionState? = null,
    val restoredStateCoversRequestPrefix: Boolean = false,
)
