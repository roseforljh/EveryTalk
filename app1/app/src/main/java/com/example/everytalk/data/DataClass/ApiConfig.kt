package com.example.everytalk.data.DataClass

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ApiConfig(
    val address: String,
    val key: String,
    val model: String,
    val provider: String,
    val id: String = UUID.randomUUID().toString(),
    val name: String = "My API Config",
    val isValid: Boolean = true,

    // --- 建议新增的生成参数字段 ---
    val temperature: Float? = null, // 温度参数，可选，默认为 null (让 API 服务端使用其默认值)
    val topP: Float? = null,        // Top-P 核心采样参数，可选，默认为 null
    val maxTokens: Int? = null      // 最大生成 Token 数，可选，默认为 null
    // 您还可以根据需要添加更多配置，比如：
    // val defaultUseWebSearch: Boolean? = null // 这个配置是否默认开启联网搜索
)