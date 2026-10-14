package com.android.everytalk.data.sync

import androidx.room.withTransaction
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.SystemSettingEntity

/**
 * 游标、epoch、限流截止与提示复用本地 system_settings；永不纳入同步 payload。
 * 所有控制状态由 Room 事务更新，杀进程后保持已确认批次与用户决定。
 */
internal class SyncStateStore(private val database: AppDatabase) {
    private val settings = database.settingsDao()

    suspend fun readCursor(): SyncCursorState = SyncCursorState(
        cursor = settings.getValue(KEY_CURSOR)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
        epoch = settings.getValue(KEY_EPOCH)?.toLongOrNull() ?: -1L,
    )

    suspend fun saveCursor(cursor: Long, epoch: Long) {
        require(cursor >= 0 && epoch >= 0)
        settings.insertSetting(SystemSettingEntity(KEY_CURSOR, cursor.toString()))
        settings.insertSetting(SystemSettingEntity(KEY_EPOCH, epoch.toString()))
    }

    suspend fun isEnabled(): Boolean = settings.getValue(KEY_ENABLED) == "true"
    suspend fun setEnabled(enabled: Boolean) = database.withTransaction {
        settings.insertSetting(SystemSettingEntity(KEY_ENABLED, enabled.toString()))
    }
    suspend fun epochDecision(): Long? = settings.getValue(KEY_EPOCH_DECISION)?.toLongOrNull()
    suspend fun requireEpochDecision(epoch: Long) = database.withTransaction {
        settings.insertSetting(SystemSettingEntity(KEY_EPOCH_DECISION, epoch.toString()))
        setEnabled(false)
        // 丢弃云端游标快照，但保留所有本机正文；用户同意后从 0 开始重新协调。
        saveCursor(0, epoch)
    }

    suspend fun retryAt(): Long = settings.getValue(KEY_RETRY_AT)?.toLongOrNull() ?: 0L
    suspend fun deferRetry(seconds: Long) {
        val now = System.currentTimeMillis()
        val millis = seconds.coerceIn(0, (Long.MAX_VALUE - now) / 1000) * 1000
        settings.insertSetting(SystemSettingEntity(KEY_RETRY_AT, (now + millis).toString()))
    }
    suspend fun clearRetry() = settings.deleteSetting(KEY_RETRY_AT)

    /** 换账号前取消任务，事务内重置账号专属云端快照；API Key 和本地数据不受影响。 */
    suspend fun prepareAccount(accountId: String, upload: Boolean) = database.withTransaction {
        if (settings.getValue(KEY_ACCOUNT) != accountId) {
            settings.insertSetting(SystemSettingEntity(KEY_ACCOUNT, accountId))
            settings.deleteSetting(KEY_EPOCH)
            settings.deleteSetting(KEY_EPOCH_DECISION)
            settings.insertSetting(SystemSettingEntity(KEY_CURSOR, "0"))
            settings.clearSyncRejections()
            clearRetry()
        }
        if (upload) markAllLocalDataPending() else {
            database.chatDao().keepAllTextLocal()
            database.memoryDao().keepAllLocal()
            settings.keepAllUserSettingsLocal()
        }
        settings.deleteSetting(KEY_EPOCH_DECISION)
        setEnabled(true)
    }

    /** epoch 提示选择上传才调用；生图、草稿、API/语音配置不进入队列。 */
    suspend fun markAllLocalDataPending() = database.withTransaction {
        database.chatDao().markAllTextSessionsPending()
        database.chatDao().markAllTextMessagesPending()
        database.memoryDao().markAllPending()
        settings.markAllUserSettingsPending()
        settings.clearSyncRejections()
        settings.deleteSetting(KEY_EPOCH_DECISION)
        setEnabled(true)
    }

    suspend fun rejectEpochUpload() = database.withTransaction {
        settings.deleteSetting(KEY_EPOCH_DECISION)
        setEnabled(false)
    }

    /** 三选一清空范围：文字会话、文字置顶/分组及记忆；不清密钥、生图和设备设置。 */
    suspend fun clearSyncableLocalData() = database.withTransaction {
        setEnabled(false)
        database.chatDao().clearTextSyncData()
        database.chatDao().deleteAllPendingMessages()
        database.memoryDao().clearSyncData()
        settings.clearPinnedItems(false)
        settings.clearTextGroupsForSync()
        settings.clearUserSettingsForSync()
        settings.clearSyncRejections()
    }

    companion object {
        const val KEY_ENABLED = "sync_enabled"
        const val KEY_CURSOR = "sync_server_cursor"
        const val KEY_EPOCH = "sync_server_epoch"
        const val KEY_EPOCH_DECISION = "sync_epoch_decision_required"
        const val KEY_ACCOUNT = "sync_account_id"
        const val KEY_RETRY_AT = "sync_retry_at"
    }
}

