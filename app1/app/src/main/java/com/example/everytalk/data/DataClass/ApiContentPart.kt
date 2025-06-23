package com.example.everytalk.data.DataClass
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
        val mimeType: String
    ) : ApiContentPart()
}