package com.example.everytalk.data.DataClass // 调整包名以匹配你的项目结构

enum class ModalityType(val displayName: String) {
    TEXT("文本大模型"),
    IMAGE("图像生成"),
    AUDIO("音频生成"),
    VIDEO("视频生成"),
    MULTIMODAL("多模态模型"); // 可以根据需要添加更多

    companion object {
        fun fromDisplayName(nameToFind: String): ModalityType? {
            return entries.find { it.displayName == nameToFind }
        }
    }
}