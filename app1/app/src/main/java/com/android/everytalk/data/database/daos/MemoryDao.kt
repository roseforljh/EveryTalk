package com.android.everytalk.data.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.android.everytalk.data.database.entities.MemoryEntity
import kotlinx.coroutines.flow.Flow

/** DAO 仅操作记忆表；容量和权限由 Repository 在同一事务内校验。 */
@Dao
interface MemoryDao {
    @Query("UPDATE memories SET sync_status = 'LOCAL_ONLY'")
    suspend fun keepAllLocal()

    @Query("DELETE FROM memories")
    suspend fun clearSyncData()
    @Query("SELECT * FROM memories WHERE deleted = 0 ORDER BY updatedAt DESC, id ASC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE deleted = 0 ORDER BY updatedAt DESC, id ASC")
    suspend fun getAll(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id AND deleted = 0")
    suspend fun get(id: String): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(memory: MemoryEntity)

    @Update
    suspend fun update(memory: MemoryEntity): Int

    /** 删除的修改时间同时驱动本地展示和服务端 LWW 判断，页面查询永不返回墓碑。 */
    @Query("UPDATE memories SET deleted = 1, updatedAt = :now, updated_at = :now, sync_status = 'PENDING' WHERE id = :id AND deleted = 0")
    suspend fun delete(id: String, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE memories SET deleted = 1, updatedAt = :now, updated_at = :now, sync_status = 'PENDING' WHERE deleted = 0")
    suspend fun clear(now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM memories WHERE sync_status = 'PENDING' LIMIT :limit")
    suspend fun getPendingSyncMemories(limit: Int = Int.MAX_VALUE): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRemote(memory: MemoryEntity): Long

    @Update
    suspend fun updateRemote(memory: MemoryEntity): Int

    @Query("UPDATE memories SET sync_status = 'SYNCED' WHERE uuid = :uuid AND updated_at = :version AND sync_status = 'PENDING'")
    suspend fun markSyncedIfVersion(uuid: String, version: Long): Int

    @Query("UPDATE memories SET sync_status = 'LOCAL_ONLY' WHERE uuid = :uuid AND updated_at = :version")
    suspend fun markRejectedIfVersion(uuid: String, version: Long): Int

    /** 只恢复同步系统判定为可重试的旧失败记忆。 */
    @Query("UPDATE memories SET sync_status = 'PENDING' WHERE uuid = :uuid")
    suspend fun markPendingByUuid(uuid: String): Int

    @Query("UPDATE memories SET sync_status = 'PENDING'")
    suspend fun markAllPending()
}
