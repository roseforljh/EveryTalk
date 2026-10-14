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
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC, id ASC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC, id ASC")
    suspend fun getAll(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun get(id: String): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(memory: MemoryEntity)

    @Update
    suspend fun update(memory: MemoryEntity): Int

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM memories")
    suspend fun clear()
}
