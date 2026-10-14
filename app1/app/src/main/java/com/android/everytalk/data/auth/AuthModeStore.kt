package com.android.everytalk.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

internal enum class AuthMode {
    UNSET,
    LOCAL_ONLY,
    SIGNED_IN,
}

/** 认证状态边界，测试可以用内存实现，生产实现使用 Preferences DataStore。 */
internal interface AuthModeStore {
    suspend fun read(): AuthMode
    suspend fun write(mode: AuthMode)
}

private val Context.everyTalkAuthDataStore by preferencesDataStore(name = "everytalk_auth")

internal class DataStoreAuthModeStore(private val context: Context) : AuthModeStore {
    override suspend fun read(): AuthMode = context.everyTalkAuthDataStore.data.first()
        .get(AUTH_MODE_KEY)
        ?.let { value -> runCatching { AuthMode.valueOf(value) }.getOrNull() }
        ?: AuthMode.UNSET

    override suspend fun write(mode: AuthMode) {
        context.everyTalkAuthDataStore.edit { preferences ->
            preferences[AUTH_MODE_KEY] = mode.name
        }
    }

    private companion object {
        val AUTH_MODE_KEY = stringPreferencesKey("auth_mode")
    }
}
