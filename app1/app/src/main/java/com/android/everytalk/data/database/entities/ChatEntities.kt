package com.android.everytalk.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.MarkdownPart

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey
    val id: String, // Stable conversation ID
    val creationTimestamp: Long,
    val lastModifiedTimestamp: Long,
    val isImageGeneration: Boolean,
    val title: String? = null // Optional title caching
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val sessionId: String, // Foreign Key to ChatSessionEntity
    val text: String,
    val sender: Sender,
    val reasoning: String?,
    val contentStarted: Boolean,
    val isError: Boolean,
    val name: String?,
    val timestamp: Long,
    val isPlaceholderName: Boolean,
    val webSearchResults: List<WebSearchResult>?,
    val currentWebSearchStage: String?,
    val imageUrls: List<String>?,
    val attachments: List<SelectedMediaItem>,
    val outputType: String,
    val parts: List<MarkdownPart>,
    val executionStatus: String?
)

fun MessageEntity.toMessage(): Message {
    return Message(
        id = id,
        text = text,
        sender = sender,
        reasoning = reasoning,
        contentStarted = contentStarted,
        isError = isError,
        name = name,
        timestamp = timestamp,
        isPlaceholderName = isPlaceholderName,
        webSearchResults = webSearchResults,
        currentWebSearchStage = currentWebSearchStage,
        imageUrls = imageUrls,
        attachments = attachments,
        outputType = outputType,
        parts = parts,
        executionStatus = executionStatus
    )
}

fun Message.toEntity(sessionId: String): MessageEntity {
    return MessageEntity(
        id = id,
        sessionId = sessionId,
        text = text,
        sender = sender,
        reasoning = reasoning,
        contentStarted = contentStarted,
        isError = isError,
        name = name,
        timestamp = timestamp,
        isPlaceholderName = isPlaceholderName,
        webSearchResults = webSearchResults,
        currentWebSearchStage = currentWebSearchStage,
        imageUrls = imageUrls,
        attachments = attachments,
        outputType = outputType,
        parts = parts,
        executionStatus = executionStatus
    )
}