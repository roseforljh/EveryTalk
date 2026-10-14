package com.android.everytalk.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** 同步绑定账号的用户选择；不保存聊天正文，也不保存 Token。 */
internal interface SyncAccountBindingStore {
    suspend fun readBoundAccountId(): String?
    suspend fun bindTo(accountId: String)
    suspend fun clearBinding()
}

private val Context.everyTalkSyncBindingDataStore by preferencesDataStore(name = "everytalk_sync_binding")

/**
 * 记录最近一次明确选择同步到哪个账号。
 * 没有绑定记录时，登录新账号不会弹换账号保护，避免把“普通登录”误判成数据迁移。
 */
internal class DataStoreSyncAccountBindingStore(private val context: Context) : SyncAccountBindingStore {
    override suspend fun readBoundAccountId(): String? = context.everyTalkSyncBindingDataStore.data.first()
        .get(BOUND_ACCOUNT_ID_KEY)

    override suspend fun bindTo(accountId: String) {
        context.everyTalkSyncBindingDataStore.edit { preferences ->
            preferences[BOUND_ACCOUNT_ID_KEY] = accountId
        }
    }

    override suspend fun clearBinding() {
        context.everyTalkSyncBindingDataStore.edit { preferences ->
            preferences.remove(BOUND_ACCOUNT_ID_KEY)
        }
    }

    private companion object {
        val BOUND_ACCOUNT_ID_KEY = stringPreferencesKey("last_sync_bound_account_id")
    }
}
