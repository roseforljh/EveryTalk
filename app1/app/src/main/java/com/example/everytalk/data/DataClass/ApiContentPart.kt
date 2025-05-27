package com.example.everytalk.data.DataClass // 请确认包名是否正确

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class ApiContentPart {

    @Serializable
    @SerialName("text_content") // 当序列化时，这个对象会被包裹在 "text_content": { ... } 中，或者作为类型标识符
    data class Text(
        val text: String
    ) : ApiContentPart()

    @Serializable
    @SerialName("file_uri_content")
    data class FileUri(
        val uri: String, // HTTP/S 或 GCS URI
        @SerialName("mime_type") // 确保与后端期望的字段名一致
        val mimeType: String
    ) : ApiContentPart()

    @Serializable
    @SerialName("inline_data_content")
    data class InlineData(
        @SerialName("base64Data") // 对应 Gemini API 中的 'data' (base64 编码的字符串)
        val base64Data: String,
        @SerialName("mimeType")
        val mimeType: String
    ) : ApiContentPart()
}