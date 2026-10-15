package com.android.everytalk.data.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 附件第一期只同步本机仍可读取的元数据，不同步二进制。
 * fileId 保持 null，第二期文件传输完成后再单独补写。
 */
@Entity(
    tableName = "sync_attachments",
    indices = [Index("sessionId"), Index("messageId")],
)
data class SyncAttachmentEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val messageId: String,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    val fileId: String? = null,
    val kind: String,
    val position: Int = 0,
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    @ColumnInfo(name = "sync_status", defaultValue = "'PENDING'") val syncStatus: String = SyncStatus.PENDING,
)
