package com.android.everytalk.data.auth

import android.content.Context
import com.android.everytalk.data.account.AccountManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 页面和 WorkManager 共用进程内唯一认证实例与刷新锁。
 * 本地模式由 AuthRepository 读取 DataStore 后直接结束初始化，不刷新 token。
 */
internal class AuthRuntime private constructor(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val account = AccountManager.create(context, scope, restoreOnCreate = false)
    val auth = AuthRepository(account, DataStoreAuthModeStore(context), scope, onLogout = {
        com.android.everytalk.service.SyncScheduler.cancel(context)
    })
    val binding = DataStoreSyncAccountBindingStore(context)

    companion object {
        @Volatile private var instance: AuthRuntime? = null
        /** 网络层没有 Context；进程内尚未创建认证实例时视为未登录。 */
        fun current(): AuthRuntime? = instance
        fun get(context: Context): AuthRuntime = instance ?: synchronized(this) {
            instance ?: AuthRuntime(context.applicationContext).also { instance = it }
        }
    }
}
