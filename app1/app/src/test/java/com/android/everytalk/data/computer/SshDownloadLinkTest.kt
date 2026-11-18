package com.android.everytalk.data.computer

import com.android.everytalk.data.agent.sshDownloadBinding
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SshDownloadLinkTest {
    @Test
    fun `云端只选择直链协议 旧后端与未选服务器都不回退`() {
        val targets = buildJsonObject { put("selected", buildJsonObject {}) }
        assertEquals("download_link", sshDownloadBinding(true, targets)["operation"]?.jsonPrimitive?.content)
        assertEquals(targets, sshDownloadBinding(true, targets)["targets"])
        assertEquals("unsupported", sshDownloadBinding(false, targets)["kind"]?.jsonPrimitive?.content)
        assertEquals("unsupported", sshDownloadBinding(true, null)["kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `下载只接受服务器的限时地址 不会返回可同步的附件ID`() {
        val link = "https://test.trycloudflare.com/${"a".repeat(48)}/demo.apk"
        val payload = """{"file_link":"$link","name":"demo.apk","size":31,"expires_at":100,"attachment_id":"not-an-attachment"}"""
        val delivered = SshDownloadLink.parse(0, payload)
        assertEquals(link, delivered["file_link"]?.jsonPrimitive?.content)
        assertFalse("attachment_id" in delivered)
        assertThrows(ComputerException::class.java) { SshDownloadLink.parse(0, payload.replace(link, "everytalk-file:demo")) }
        assertThrows(ComputerException::class.java) { SshDownloadLink.parse(1, payload) }
    }

    @Test
    fun `本地执行装载单文件启动器且路径不解释为shell脚本`() {
        val command = SshDownloadLink.command("~/a'b; echo no.apk", null)
        assertTrue(command.startsWith("python3 - '~/a'\\''b; echo no.apk' '' 3600 "))
        assertTrue(command.contains("def publish("))
        assertThrows(IllegalArgumentException::class.java) { SshDownloadLink.command("foo\nbar", null) }
        assertTrue(ComputerToolCallSafety.requiresUnknownApproval(
            ComputerToolNames.DOWNLOAD, buildJsonObject {}, ComputerPermissionMode.FULL,
        ))
    }
}
