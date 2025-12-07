package com.android.everytalk.data.database.converter

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.data.database.entity.MessageEntity
import com.android.everytalk.data.database.entity.SenderType
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.MarkdownPart
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Message 与 MessageEntity 之间的转换器
 */
object MessageConverter {
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
    }
    
    /**
     * 将 Message 转换为 MessageEntity
     */
    fun toEntity(message: Message, conversationId: String, orderIndex: Int): MessageEntity {
        return MessageEntity(
            id = message.id,
            conversationId = conversationId,
            text = message.text,
            sender = senderToString(message.sender),
            reasoning = message.reasoning,
            contentStarted = message.contentStarted,
            isError = message.isError,
            name = message.name,
            timestamp = message.timestamp,
            isPlaceholderName = message.isPlaceholderName,
            webSearchResultsJson = message.webSearchResults?.let { 
                try {
                    json.encodeToString(ListSerializer(WebSearchResult.serializer()), it)
                } catch (e: Exception) {
                    null
                }
            },
            currentWebSearchStage = message.currentWebSearchStage,
            imageUrlsJson = message.imageUrls?.let {
                try {
                    json.encodeToString(ListSerializer(String.serializer()), it)
                } catch (e: Exception) {
                    null
                }
            },
            attachmentsJson = if (message.attachments.isNotEmpty()) {
                try {
                    json.encodeToString(ListSerializer(SelectedMediaItem.serializer()), message.attachments)
                } catch (e: Exception) {
                    null
                }
            } else null,
            outputType = message.outputType,
            partsJson = if (message.parts.isNotEmpty()) {
                try {
                    json.encodeToString(
                        com.android.everytalk.ui.components.MarkdownPartSerializer,
                        message.parts
                    )
                } catch (e: Exception) {
                    null
                }
            } else null,
            executionStatus = message.executionStatus,
            orderIndex = orderIndex
        )
    }
    
    /**
     * 将 MessageEntity 转换为 Message
     */
    fun fromEntity(entity: MessageEntity): Message {
        return Message(
            id = entity.id,
            text = entity.text,
            sender = stringToSender(entity.sender),
            reasoning = entity.reasoning,
            contentStarted = entity.contentStarted,
            isError = entity.isError,
            name = entity.name,
            timestamp = entity.timestamp,
            isPlaceholderName = entity.isPlaceholderName,
            webSearchResults = entity.webSearchResultsJson?.let {
                try {
                    json.decodeFromString(ListSerializer(WebSearchResult.serializer()), it)
                } catch (e: Exception) {
                    null
                }
            },
            currentWebSearchStage = entity.currentWebSearchStage,
            imageUrls = entity.imageUrlsJson?.let {
                try {
                    json.decodeFromString(ListSerializer(String.serializer()), it)
                } catch (e: Exception) {
                    null
                }
            },
            attachments = entity.attachmentsJson?.let {
                try {
                    json.decodeFromString(ListSerializer(SelectedMediaItem.serializer()), it)
                } catch (e: Exception) {
                    emptyList()
                }
            } ?: emptyList(),
            outputType = entity.outputType,
            parts = entity.partsJson?.let {
                try {
                    json.decodeFromString(
                        com.android.everytalk.ui.components.MarkdownPartSerializer,
                        it
                    )
                } catch (e: Exception) {
                    emptyList()
                }
            } ?: emptyList(),
            executionStatus = entity.executionStatus
        )
    }
    
    /**
     * 批量转换 Message 列表为 MessageEntity 列表
     */
    fun toEntityList(messages: List<Message>, conversationId: String): List<MessageEntity> {
        return messages.mapIndexed { index, message ->
            toEntity(message, conversationId, index)
        }
    }
    
    /**
     * 批量转换 MessageEntity 列表为 Message 列表
     */
    fun fromEntityList(entities: List<MessageEntity>): List<Message> {
        return entities.map { fromEntity(it) }
    }
    
    private fun senderToString(sender: Sender): String {
        return when (sender) {
            Sender.User -> SenderType.USER
            Sender.AI -> SenderType.AI
            Sender.System -> SenderType.SYSTEM
            Sender.Tool -> SenderType.TOOL
        }
    }
    
    private fun stringToSender(senderStr: String): Sender {
        return when (senderStr) {
            SenderType.USER -> Sender.User
            SenderType.AI -> Sender.AI
            SenderType.SYSTEM -> Sender.System
            SenderType.TOOL -> Sender.Tool
            else -> Sender.User // 默认值
        }
    }
}