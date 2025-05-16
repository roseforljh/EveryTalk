// 修改后的 ApiConfig.kt
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
    val name: String = "My API Config", // <<<< 添加 name 属性, 可以给个默认值或让用户设置
    val isValid: Boolean = true     // <<<< 添加 isValid 属性, 或者写成计算属性
)