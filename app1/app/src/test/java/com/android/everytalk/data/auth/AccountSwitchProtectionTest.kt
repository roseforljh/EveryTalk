package com.android.everytalk.data.auth

import com.android.everytalk.data.account.AccountUser
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemorySyncBindingStore(initial: String?) : SyncAccountBindingStore {
    var boundAccountId: String? = initial
    override suspend fun readBoundAccountId(): String? = boundAccountId
    override suspend fun bindTo(accountId: String) { boundAccountId = accountId }
    override suspend fun clearBinding() { boundAccountId = null }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountSwitchProtectionTest {
    private val previous = AccountUser("account-a", "a@example.com")
    private val current = AccountUser("account-b", "b@example.com")

    @Test
    fun `切换账号必须出现三选一并阻止隐式上传`() = runTest {
        val authState = kotlinx.coroutines.flow.MutableStateFlow<AuthState>(AuthState.SignedIn(previous))
        val store = MemorySyncBindingStore(previous.id)
        val protection = AccountSwitchProtection(authState, store, backgroundScope) {}
        runCurrent()

        authState.value = AuthState.SignedIn(current)
        runCurrent()
        assertEquals(previous.id, protection.request.value?.previousAccountId)

        protection.choose(AccountSwitchChoice.KEEP_LOCAL).join()
        assertNull(store.boundAccountId)
        assertNull(protection.request.value)
    }

    @Test
    fun `选择上传到当前账号只切换绑定目标`() = runTest {
        val authState = kotlinx.coroutines.flow.MutableStateFlow<AuthState>(AuthState.SignedIn(current))
        val store = MemorySyncBindingStore(previous.id)
        val protection = AccountSwitchProtection(authState, store, backgroundScope) {}
        runCurrent()

        protection.choose(AccountSwitchChoice.UPLOAD_TO_CURRENT).join()
        assertEquals(current.id, store.boundAccountId)
    }

    @Test
    fun `清空本地会调用同步范围清理动作`() = runTest {
        val authState = kotlinx.coroutines.flow.MutableStateFlow<AuthState>(AuthState.SignedIn(current))
        val store = MemorySyncBindingStore(previous.id)
        var cleared = false
        val protection = AccountSwitchProtection(authState, store, backgroundScope) { cleared = true }
        runCurrent()

        protection.choose(AccountSwitchChoice.CLEAR_LOCAL).join()
        assertTrue(cleared)
        assertNull(store.boundAccountId)
    }
}
