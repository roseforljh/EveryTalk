package com.example.everytalk.data.DataClass
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class AbstractApiMessage : IMessage {
    abstract override val role: String
    abstract override val name: String?
}

@Serializable
@SerialName("simple_text_message")
data class SimpleTextApiMessage(
    @SerialName("id")
    override val id: String = java.util.UUID.randomUUID().toString(),
    
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
    @SerialName("id")
    override val id: String = java.util.UUID.randomUUID().toString(),
    
    @SerialName("role")
    override val role: String,

    @SerialName("parts")
    val parts: List<ApiContentPart>,

    @SerialName("name")
    override val name: String? = null
) : AbstractApiMessage()