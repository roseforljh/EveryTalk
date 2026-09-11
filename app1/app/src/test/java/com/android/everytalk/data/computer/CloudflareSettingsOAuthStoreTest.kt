package com.android.everytalk.data.computer

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareSettingsOAuthStoreTest {
    @Test
    fun `设置 OAuth state 只能按目标消费一次`() {
        val store = CloudflareSettingsOAuthStore()
        val request = store.create("computer-draft")
        assertNotNull(store.consume(request.state, "computer-draft"))
        assertNull(store.consume(request.state, "computer-draft"))
    }

    @Test
    fun `错误目标不能消费 verifier`() {
        val store = CloudflareSettingsOAuthStore()
        val request = store.create("computer-draft")
        assertNull(store.consume(request.state, "other-draft"))
        assertNotNull(store.consume(request.state, "computer-draft"))
    }

    @Test
    fun `回调携带错误 nonce 时不能消费 verifier`() {
        val store = CloudflareSettingsOAuthStore()
        val request = store.create("computer-draft")
        assertNull(store.consume(request.state, "computer-draft", "wrong"))
        assertNotNull(store.consume(request.state, "computer-draft", request.nonce))
    }

    @Test
    fun `owns 只判断归属不会提前消费 state`() {
        val store = CloudflareSettingsOAuthStore()
        val request = store.create("computer-1")
        assertTrue(store.owns(request.state, "computer-1"))
        assertFalse(store.owns(request.state, "computer-2"))
        assertNotNull(store.consume(request.state, "computer-1"))
    }
}
