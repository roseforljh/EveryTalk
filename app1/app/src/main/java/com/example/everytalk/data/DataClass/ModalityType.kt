package com.example.everytalk.data.DataClass

import androidx.annotation.Keep

@Keep
enum class ModalityType(val displayName: String) {
    TEXT("文本大模型"),
    IMAGE("图像生成"),
    AUDIO("音频生成"),
    VIDEO("视频生成"),
    MULTIMODAL("多模态模型");

    companion object {
        fun fromDisplayName(nameToFind: String): ModalityType? {
            return entries.find { it.displayName == nameToFind }
        }
    }
}