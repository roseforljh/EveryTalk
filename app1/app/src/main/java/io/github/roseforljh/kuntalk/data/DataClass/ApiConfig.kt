package io.github.roseforljh.kuntalk.data.DataClass
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ApiConfig(
    val address: String,
    val key: String,
    val model: String,
    val provider: String,
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val isValid: Boolean = true,
    val modalityType: ModalityType = ModalityType.TEXT,
    val temperature: Float = 0.0f,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val defaultUseWebSearch: Boolean? = null,
    val imageSize: String? = null,
    val numInferenceSteps: Int? = null,
    val guidanceScale: Float? = null,
)
