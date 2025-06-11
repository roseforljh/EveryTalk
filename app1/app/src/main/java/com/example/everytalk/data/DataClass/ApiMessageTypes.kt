package com.example.everytalk.data.DataClass
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class AbstractApiMessage {
    abstract val role: String
    abstract val name: String?
}

@Serializable
@SerialName("simple_text_message")
data class SimpleTextApiMessage(
    @SerialName("role")
    override val role: String,

    @SerialName("content")
    val content: String,

    @SerialName("name")
    override val name: String? = null
) : AbstractApiMessage()

@Serializable
@SerialName("parts_message")
data class PartsApiMessage(
    @SerialName("role")
    override val role: String,

    @SerialName("parts")
    val parts: List<ApiContentPart>,

    @SerialName("name")
    override val name: String? = null
) : AbstractApiMessage()