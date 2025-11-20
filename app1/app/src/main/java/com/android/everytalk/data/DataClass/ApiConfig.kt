package com.android.everytalk.data.DataClass
import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import java.util.UUID

@Keep
@Serializable
data class ApiConfig(
    val address: String,
    val key: String,
    val model: String,
    val provider: String,
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val channel: String = "OpenAI兼容",
    val isValid: Boolean = true,
    val modalityType: ModalityType = ModalityType.TEXT,
    val temperature: Float = 0.0f,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val defaultUseWebSearch: Boolean? = null,
    val imageSize: String? = null,
    val numInferenceSteps: Int? = null,
    val guidanceScale: Float? = null,
    // 新增：Function Calling 工具配置 (JSON字符串)
    val toolsJson: String? = null,
    // 新增：是否启用代码执行
    val enableCodeExecution: Boolean? = null
)