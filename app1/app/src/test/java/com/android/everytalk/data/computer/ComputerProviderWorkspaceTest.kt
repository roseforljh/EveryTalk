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

    @Test fun `SSH默认服务器按请求预览主机或按需容器不篡改执行目标`() = runTest {
        for (target in listOf(ComputerExecTarget.HOST, ComputerExecTarget.CONTAINER)) {
            val repository = mockk<ComputerRepository>(relaxed = true)
            val dao = mockk<ComputerDao>(relaxed = true)
            val connection = mockk<ComputerSshConnection>()
            val forward = mockk<ComputerPortForward>(relaxed = true)
            val lease = mockk<ComputerConnectionLease>(relaxed = true)
            val ssh = computer.copy(id = "ssh", provider = ComputerProvider.SSH, status = ComputerStatus.READY)
            val workspace = ComputerWorkspace("ws_preview", ssh.id, "session", ComputerRunMode.DIRECT,
                "/home/user/work", status = ComputerWorkspaceStatus.READY)
            val request = ComputerRequestContext("session", ssh.id, workspace.id)
            every { repository.dao() } returns dao
            every { repository.acquireForegroundActivity() } returns mockk(relaxed = true)
            every { forward.localPort } returns 12345
            coEvery { repository.getComputer(ssh.id) } returns ssh
            coEvery { dao.getWorkspaceById(workspace.id) } returns workspace.toEntity()
            coEvery { connection.execute(any(), any(), any(), any()) } returns
                ComputerSshCommandResult("172.18.0.2", "", 0, false, false, false)
            coEvery { connection.openLocalPortForward(any(), any(), any()) } returns forward
            coEvery { repository.withConnection<String>(ssh.id, any(), any()) } coAnswers {
                thirdArg<suspend (ComputerSshConnection, Computer) -> String>().invoke(connection, ssh)
            }
            coEvery { repository.acquireConnectionAndOpen<ComputerPortForward>(ssh.id, any()) } coAnswers {
                Triple(lease, ssh, secondArg<suspend (ComputerSshConnection) -> ComputerPortForward>().invoke(connection))
            }
            ComputerPreviewManager(repository).use { manager ->
                val result = if (target == ComputerExecTarget.HOST) manager.openPrivate(request, 8080)
                    else manager.openPrivate(request, 8080, target = target)
                assertEquals(target, result.preview.target)
                coVerify { connection.openLocalPortForward(8080,
                    if (target == ComputerExecTarget.HOST) "127.0.0.1" else "172.18.0.2", 0) }
            }
        }
    }

    @Test fun `旧服务器和旧容器会话恢复后默认SSH且按需容器不改变后续调用`() = runTest {
        val dao = mockk<ComputerDao>(relaxed = true)
        val repository = mockk<ComputerRepository>()
        val ssh = computer.copy(id = "ssh", provider = ComputerProvider.SSH, runMode = ComputerRunMode.CONTAINER,
            status = ComputerStatus.READY, sandboxImage = "test-image")
        var stored: com.android.everytalk.data.database.entities.ComputerWorkspaceEntity? = null
        every { repository.dao() } returns dao
        coEvery { repository.getComputer(ssh.id) } returns ssh
        coEvery { dao.getWorkspace(ssh.id, "session") } coAnswers { stored }
        coEvery { dao.getWorkspaceById(any()) } coAnswers { stored }
        coEvery { dao.upsertWorkspace(any()) } coAnswers { stored = firstArg() }
        coEvery { dao.useSshWorkspaceDefault(any()) } coAnswers {
            stored = stored?.copy(runMode = "DIRECT")
        }
        coEvery { dao.updateWorkspaceContainer(any(), any(), any()) } coAnswers {
            stored = stored?.copy(containerName = secondArg(), containerImage = thirdArg())
        }
        val manager = ComputerWorkspaceManager(repository)
        val created = manager.getOrCreateLocal(ssh.id, "session")
        assertEquals(ComputerRunMode.DIRECT, created.runMode)
        assertNull(created.containerName)
        coVerify(exactly = 0) { repository.ensureContainerRuntime(any(), any()) }

        // 模拟旧版本保存的容器元数据；主机文件路径和会话编号必须原样保留。
        stored = created.copy(runMode = ComputerRunMode.CONTAINER, containerName = "everytalk-${created.id}",
            status = ComputerWorkspaceStatus.READY).toEntity()
        val restored = manager.getOrCreateLocal(ssh.id, "session")
        assertEquals(ComputerRunMode.DIRECT, restored.runMode)
        assertEquals(created.id, restored.id)
        assertEquals(created.hostPath, restored.hostPath)
        assertEquals("everytalk-${created.id}", restored.containerName)

        coEvery { repository.ensureContainerRuntime(ssh.id, any()) } returns ssh
        coEvery { repository.withConnection<Unit>(ssh.id, any(), any()) } coAnswers {
            // 准备容器时首条消息可能正落库；容器准备不能写回旧会话编号。
            stored = stored?.copy(conversationId = "stable-session")
        }
        val sandbox = manager.prepareContainer(created.id)
        assertEquals(ComputerRunMode.CONTAINER, sandbox.runMode)
        assertEquals("stable-session", sandbox.conversationId)
        assertEquals("DIRECT", stored?.runMode)
        assertEquals("READY", stored?.status)

        // 容器准备失败也不污染正常 SSH 工作区的可用状态。
        val beforeFailure = stored
        coEvery { repository.withConnection<Unit>(ssh.id, any(), any()) } throws
            ComputerException(ComputerErrorCodes.WORKSPACE_NOT_READY, "模拟 Docker 不可用")
        assertTrue(runCatching { manager.prepareContainer(created.id) }.isFailure)
        assertEquals(beforeFailure, stored)
    }

    @Test fun `Cloudflare 在连接池获取凭据之前被拒绝`() = runTest {
        val client = mockk<ComputerSshClient>()
        val credentials = mockk<ComputerCredentialStore>()
        val pool = ComputerConnectionPool(client, credentials, { current, _ -> current }, { _, _ -> })
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
