package io.github.roseforljh.kuntalk.data.local.database

import androidx.room.Embedded
import androidx.room.Relation

data class ConversationWithMessages(
    @Embedded val conversation: ConversationEntity,
    @Relation(
        parentColumn = "conversation_id",
        entityColumn = "conversation_id"
    )
    val messages: List<MessageEntity>
)