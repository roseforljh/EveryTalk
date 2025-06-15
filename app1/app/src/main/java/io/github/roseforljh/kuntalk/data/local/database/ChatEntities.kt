package io.github.roseforljh.kuntalk.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.roseforljh.kuntalk.data.DataClass.Sender

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["conversation_id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversation_id"])]
)
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    val sender: Sender,
    val text: String,
    val attachments: String?,
    val timestamp: Long,
    @ColumnInfo(name = "is_error")
    val isError: Boolean,
    val reasoning: String?,
    @ColumnInfo(name = "content_started")
    val contentStarted: Boolean,
    @ColumnInfo(name = "is_placeholder_name")
    val isPlaceholderName: Boolean
)