package com.android.everytalk.statecontroller

import kotlinx.serialization.Serializable

@Serializable
data class ConversationScrollState(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
    val userScrolledAway: Boolean = false,
    val firstBubbleScreenY: Int = -1,
)

/** 待处理的配置参数，用于暂存添加配置流程中的用户输入。 */
data class PendingConfigParams(
    val provider: String,
    val address: String,
    val key: String,
    val channel: String,
    val isImageGen: Boolean,
    val enableCodeExecution: Boolean? = null,
    val toolsJson: String? = null,
    val imageSize: String? = null,
    val numInferenceSteps: Int? = null,
    val guidanceScale: Float? = null,
    val isRefresh: Boolean = false,
)

@Serializable
data class ConversationFunctionToggleState(
    val webSearchEnabled: Boolean = false,
    val codeExecutionEnabled: Boolean = false,
    val mcpEnabled: Boolean = false,
    val agentEnabled: Boolean = false,
)
