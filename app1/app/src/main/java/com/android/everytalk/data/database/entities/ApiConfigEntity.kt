package com.android.everytalk.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ModalityType

@Entity(tableName = "api_configs")
data class ApiConfigEntity(
    @PrimaryKey
    val id: String,
    val address: String,
    val key: String,
    val model: String,
    val provider: String,
    val name: String,
    val channel: String,
    val isValid: Boolean,
    val modalityType: ModalityType,
    val temperature: Float,
    val topP: Float?,
    val maxTokens: Int?,
    val defaultUseWebSearch: Boolean?,
    val imageSize: String?,
    val numInferenceSteps: Int?,
    val guidanceScale: Float?,
    val toolsJson: String?,
    val enableCodeExecution: Boolean?,
    // Use this field to differentiate between text (false) and image (true) configs if needed by queries,
    // though modalityType might be enough. SharedPreferences kept them in separate lists.
    // I will add a type column to distinguish "text_config_list" vs "image_config_list" if needed,
    // but ModalityType.IMAGE vs TEXT handles it.
    // Wait, loadImageGenApiConfigs() uses KEY_IMAGE_GEN_API_CONFIG_LIST.
    // Are there cases where ModalityType is TEXT but it's stored in image config list? Unlikely.
    // However, keeping a "isImageGenConfig" flag might be safer if we want to mimic SP exactly.
    // But ModalityType should be the source of truth.
    val isImageGenConfig: Boolean = false 
)

fun ApiConfigEntity.toApiConfig(): ApiConfig {
    return ApiConfig(
        id = id,
        address = address,
        key = key,
        model = model,
        provider = provider,
        name = name,
        channel = channel,
        isValid = isValid,
        modalityType = modalityType,
        temperature = temperature,
        topP = topP,
        maxTokens = maxTokens,
        defaultUseWebSearch = defaultUseWebSearch,
        imageSize = imageSize,
        numInferenceSteps = numInferenceSteps,
        guidanceScale = guidanceScale,
        toolsJson = toolsJson,
        enableCodeExecution = enableCodeExecution
    )
}

fun ApiConfig.toEntity(isImageGenConfig: Boolean = false): ApiConfigEntity {
    return ApiConfigEntity(
        id = id,
        address = address,
        key = key,
        model = model,
        provider = provider,
        name = name,
        channel = channel,
        isValid = isValid,
        modalityType = modalityType,
        temperature = temperature,
        topP = topP,
        maxTokens = maxTokens,
        defaultUseWebSearch = defaultUseWebSearch,
        imageSize = imageSize,
        numInferenceSteps = numInferenceSteps,
        guidanceScale = guidanceScale,
        toolsJson = toolsJson,
        enableCodeExecution = enableCodeExecution,
        isImageGenConfig = isImageGenConfig
    )
}