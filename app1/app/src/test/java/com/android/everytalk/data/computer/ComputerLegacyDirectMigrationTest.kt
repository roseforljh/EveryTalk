package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputerLegacyDirectMigrationTest {
    @Test
    fun `旧Direct服务器迁移为待配置混合模式并保留远端信息`() {
        val source = Computer(
            id = "computer_1",
            displayName = "旧服务器",
            host = "vps.example.com",
            port = 22,
            username = "ubuntu",
            authKind = ComputerAuthKind.PRIVATE_KEY,
            runMode = ComputerRunMode.DIRECT,
            status = ComputerStatus.READY,
            bootstrapVersion = null,
            sandboxImage = null,
        )

        val migrated = migrateLegacyDirectComputer(source)

        assertEquals(ComputerRunMode.CONTAINER, migrated.runMode)
        assertEquals(ComputerStatus.CONFIGURATION_REQUIRED, migrated.status)
        assertEquals(source.host, migrated.host)
        assertEquals(source.username, migrated.username)
        assertNull(migrated.bootstrapVersion)
    }

    @Test
    fun `旧Direct工作空间只补全容器映射并保留Host路径`() {
        val source = ComputerWorkspace(
            id = "ws_1",
            computerId = "computer_1",
            conversationId = "conversation_1",
            runMode = ComputerRunMode.DIRECT,
            hostPath = "/home/ubuntu/.everytalk/workspaces/ws_1",
            status = ComputerWorkspaceStatus.READY,
        )

        val migrated = migrateLegacyDirectWorkspace(source, "everytalk-sandbox:1")

        assertEquals(ComputerRunMode.CONTAINER, migrated.runMode)
        assertEquals(source.hostPath, migrated.hostPath)
        assertEquals("everytalk-ws_1", migrated.containerName)
        assertEquals(ComputerWorkspaceStatus.RECOVERING, migrated.status)
    }
}
