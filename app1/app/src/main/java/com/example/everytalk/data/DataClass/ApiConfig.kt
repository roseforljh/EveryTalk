// file: com/example/everytalk/data/DataClass/ApiConfig.kt
package com.example.everytalk.data.DataClass // 请确认包名

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ApiConfig(
    val address: String, // 对于图像等非文本模型，这应该是完整的第三方API端点URL
    val key: String,
    val model: String,   // 模型标识符，例如 "Kwai-Kolors/Kolors"
    val provider: String,
    val id: String = UUID.randomUUID().toString(),
    // name 字段通常由用户在UI上为这个配置命名，或者可以默认为 model 的值
    // 在你的 SettingsScreen 逻辑中，当创建新配置时，name 和 model 被设为用户输入的模型名/ID
    val name: String,
    val isValid: Boolean = true, // 需要明确此字段的更新逻辑

    // --- 核心模态类型 ---
    val modalityType: ModalityType = ModalityType.TEXT,

    // --- 通用文本生成参数 (也可部分用于其他模态) ---
    val temperature: Float? = null,
    val topP: Float? = null,        // Aliases: top_p
    val maxTokens: Int? = null,     // Aliases: max_tokens, maxOutputTokens

    // --- 特定于某些服务的参数 ---
    val defaultUseWebSearch: Boolean? = null, // 例如，某些文本模型可能支持

    // --- 新增：特定于图像生成的参数 (如果希望在配置级别设置默认值) ---
    val imageSize: String? = null,          // 例如 "1024x1024"
    val numInferenceSteps: Int? = null,   // 例如 20, 50
    val guidanceScale: Float? = null,     // 例如 7.5
    // 你可以根据需要添加更多特定于模态的通用参数，
    // 或者为非常特定的模型参数考虑一个 Map<String, String> 类型的 extraParams 字段。
    // val extraParams: Map<String, String>? = null
)