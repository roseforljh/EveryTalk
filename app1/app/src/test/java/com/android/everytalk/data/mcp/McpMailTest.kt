package com.android.everytalk.data.mcp

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpMailTest {
    @Test fun `邮箱网关只接受 HTTPS 根地址`() {
        assertEquals("https://mail.example.com/mcp/mail", mailEndpoint("https://mail.example.com/"))
        assertTrue(runCatching { mailEndpoint("http://mail.example.com") }.isFailure)
        assertTrue(runCatching { mailEndpoint("https://user:pass@mail.example.com") }.isFailure)
        assertTrue(runCatching { mailEndpoint("https://mail.example.com/mcp/mail?x=1") }.isFailure)
    }

    @Test fun `QQ 授权码保存后返回固定网关配置`() = runTest {
        val store = MemoryMailSecrets()
        val config = McpMailManager(store).save(
            McpMailProvider.QQ,
            McpMailInput("https://mail.example.com", "k".repeat(32), "a@qq.com", "app-code"),
        )
        assertEquals("mcp-mail-qq", config.id)
        assertEquals("https://mail.example.com/mcp/mail", config.url)
        assertTrue(store.values["mail:qq"]!!.contains("mail.example.com"))
    }

    private class MemoryMailSecrets : McpOAuthSecrets {
        val values = mutableMapOf<String, String>()
        override suspend fun read(key: String) = values[key]
        override suspend fun write(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }
}
