package com.android.everytalk.data.computer

import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.toEntity
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 真实公共边界测试：即使上游错误调用 SSH，也不能加载 Cloudflare 的凭据或发连接。 */
class ComputerProviderWorkspaceTest {
    @get:Rule val folder = TemporaryFolder()
    private val computer = Computer(
        id = "cf",
        displayName = "Cloudflare",
        provider = ComputerProvider.CLOUDFLARE,
        host = "cloudflare",
        port = 443,
        username = "cloudflare",
        authKind = ComputerAuthKind.PASSWORD,
        runMode = ComputerRunMode.DIRECT,
    )

    @Test fun `Cloudflare 在连接池获取凭据之前被拒绝`() = runTest {
        val client = mockk<ComputerSshClient>()
        val credentials = mockk<ComputerCredentialStore>()
        val pool = ComputerConnectionPool(client, credentials)
        val error = runCatching { pool.acquire(computer) }.exceptionOrNull() as ComputerException
        assertEquals("PROVIDER_MISMATCH", error.code)
        coVerify(exactly = 0) { credentials.loadComputerCredential(any()) }
        confirmVerified(client, credentials)
    }

    @Test fun `Cloudflare 只保存映射不创建文件夹且旧 SSH 路径保持 ID`() = runTest {
        val dao = mockk<ComputerDao>()
        val repository = mockk<ComputerRepository>()
        every { repository.dao() } returns dao
        coEvery { repository.getComputer("cf") } returns computer
        val saved = slot<com.android.everytalk.data.database.entities.ComputerWorkspaceEntity>()
        coEvery { dao.upsertWorkspace(capture(saved)) } just Runs
        coEvery { dao.getWorkspace("cf", "session") } returns null
        val root = folder.root.resolve("agent-workspaces")
        val manager = ComputerWorkspaceManager(repository)
        val created = manager.getOrCreateAppLocal("cf", "session", root)
        assertFalse(root.exists())
        assertEquals(root.resolve(created.id).absolutePath, created.hostPath)
        assertEquals(ComputerWorkspaceStatus.READY, created.status)

        coEvery { dao.getWorkspace("cf", "session") } returns created.copy(hostPath = "~/.everytalk/workspaces/old").toEntity()
        val restored = manager.getOrCreateAppLocal("cf", "session", root)
        assertEquals(created.id, restored.id)
        assertEquals(created.hostPath, restored.hostPath)
        assertFalse(root.exists())
        coEvery { dao.getWorkspaceById(created.id) } returns saved.captured
        val failure = runCatching { manager.prepare(created.id) }.exceptionOrNull() as ComputerException
        assertEquals("PROVIDER_MISMATCH", failure.code)
    }
}
