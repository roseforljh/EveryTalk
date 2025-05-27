package com.example.everytalk.data.DataClass

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiMessage(
    @SerialName("role")
    val role: String,

    @SerialName("content")
    val content: String?, // 恢复为简单的 String? 类型

    @SerialName("name") // OpenAI的 tool role 可能需要 name
    val name: String? = null,

)
