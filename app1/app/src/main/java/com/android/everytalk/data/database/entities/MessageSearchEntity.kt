package com.android.everytalk.data.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 历史会话全文索引（FTS4）。body 是应用自己切好的词：中日韩文字按双字、其他文字按单词，
 * 用空格分隔，所以选最简单的 simple 分词器即可，所有 Android 版本都支持。
 * 只存本机，可随时从 messages 重建，不参与同步。
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_SIMPLE)
@Entity(tableName = "message_search")
data class MessageSearchEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long = 0,
    val body: String,
)

/** 消息与索引行的对应关系；fingerprint 变化（内容被改写）时重建该行。 */
@Entity(tableName = "message_search_map", indices = [Index(value = ["docid"], unique = true)])
data class MessageSearchMapEntity(
    @PrimaryKey val messageId: String,
    val docid: Long,
    val fingerprint: String,
)
