package com.android.everytalk.data.DataClass
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class ApiContentPart {

    @Serializable
    @SerialName("text_content")
    data class Text(
        val text: String
    ) : ApiContentPart()

    @Serializable
    @SerialName("file_uri_content")
    data class FileUri(
        val uri: String,
        @SerialName("mime_type")
        val mimeType: String
    ) : ApiContentPart()

    @Serializable
    @SerialName("inline_data_content")
    data class InlineData(
        @SerialName("base64_data")
        val base64Data: String,
        @SerialName("mime_type")
        val mimeType: String,
        /** 非图片的原文件需要文件名，OpenAI 兼容与 Responses 的文件块要求带上。 */
        @SerialName("file_name")
        val fileName: String? = null,
    ) : ApiContentPart()
}