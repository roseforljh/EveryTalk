package com.android.everytalk.data.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.android.everytalk.data.database.entities.SyncAttachmentEntity

@Dao
interface SyncAttachmentDao {
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
