package com.android.everytalk.data.computer

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudflareOAuthClientTest {
    @Test fun `拒绝重复 state 片段和额外尾斜线`() {
        listOf("?code=c&state=a&state=b", "?code=c&state=a#fragment", "/?code=c&state=a").forEach { suffix ->
            assertThrows(IllegalArgumentException::class.java) {
                parseCloudflareOAuthCallback("everytalk://oauth/cloudflare$suffix", "everytalk://oauth/cloudflare")
            }
        }
    }
    private val config = CloudflareOAuthConfig("client", "everytalk://oauth/cloudflare")

    @Test
    fun `回调只接受配置的 redirect uri 和 code state`() {
        val callback = parseCloudflareOAuthCallback("everytalk://oauth/cloudflare?code=c1&state=s1", config.redirectUri)
        assertEquals("c1", callback.code)
        assertEquals("s1", callback.state)
    }

    @Test
    fun `回调拒绝错误或错误地址`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseCloudflareOAuthCallback("everytalk://other?code=c1&state=s1", config.redirectUri)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseCloudflareOAuthCallback("everytalk://oauth/cloudflare?error=access_denied", config.redirectUri)
        }
    }

    @Test
    fun `回调携带 nonce 时保留 nonce`() {
        val callback = parseCloudflareOAuthCallback("everytalk://oauth/cloudflare?code=c1&state=s1&nonce=n1", config.redirectUri)
        assertEquals("n1", callback.nonce)
    }
}
