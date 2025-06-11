package com.example.everytalk.data.DataClass
import com.example.everytalk.model.SelectedMediaItem
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class Sender {
    User,
    AI,
    System,
    Tool
}

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val sender: Sender,
    val reasoning: String? = null,
    val contentStarted: Boolean = false,
    val isError: Boolean = false,
    val name: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isPlaceholderName: Boolean = false,
    val webSearchResults: List<WebSearchResult>? = null,
    val currentWebSearchStage: String? = null,
    val imageUrls: List<String>? = null,
    val attachments: List<SelectedMediaItem>? = null
)