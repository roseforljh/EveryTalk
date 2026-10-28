package com.android.everytalk.data.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.android.everytalk.data.database.entities.SyncAttachmentEntity

@Dao
interface SyncAttachmentDao {
    /** 父消息/会话的墓碑已是云端事实，停止上传其附件；仅更新元数据，不删除本地文件。 */
    @Query("""
        UPDATE sync_attachments SET deleted = 1, sync_status = 'SYNCED'
        WHERE deleted = 0 AND (
            EXISTS (SELECT 1 FROM messages m WHERE m.uuid = sync_attachments.messageId AND m.deleted = 1)
            OR EXISTS (SELECT 1 FROM chat_sessions s WHERE s.uuid = sync_attachments.sessionId AND s.deleted = 1)
            OR EXISTS (SELECT 1 FROM system_settings s WHERE s.`key` = 'sync_message_deleted:' || sync_attachments.messageId)
        )
    """)
    suspend fun retireDeletedParentAttachments(): Int

    @Query("SELECT * FROM sync_attachments WHERE sync_status = 'PENDING' ORDER BY updated_at LIMIT :limit")
    suspend fun getPending(limit: Int): List<SyncAttachmentEntity>

    @Query("SELECT * FROM sync_attachments WHERE id = :id LIMIT 1")
    suspend fun get(id: String): SyncAttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: SyncAttachmentEntity)

    @Query("UPDATE sync_attachments SET sync_status = 'SYNCED' WHERE id = :id AND updated_at = :version AND sync_status = 'PENDING'")
    suspend fun markSyncedIfVersion(id: String, version: Long): Int

    @Query("UPDATE sync_attachments SET sync_status = 'LOCAL_ONLY' WHERE id = :id AND updated_at = :version")
    suspend fun markRejectedIfVersion(id: String, version: Long): Int

    /** 只恢复同步系统判定为可重试的旧失败附件元数据。 */
    @Query("UPDATE sync_attachments SET sync_status = 'PENDING' WHERE id = :id")
    suspend fun markPendingById(id: String): Int

    @Query("UPDATE sync_attachments SET sync_status = 'PENDING'")
    suspend fun markAllPending()

    @Query("DELETE FROM sync_attachments WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("DELETE FROM sync_attachments WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)
}
