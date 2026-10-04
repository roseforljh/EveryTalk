package com.android.everytalk.data.computer

import com.android.everytalk.util.AppLogger
import io.mockk.coEvery
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class ComputerConnectionPoolRegistryTest {

    private val testComputer = Computer(
        id = "server-reg-1",
        displayName = "VPS",
        host = "example.com",
        port = 22,
        username = "root",
        hostKeyAlgorithm = "ssh-ed25519",
        hostKeyBlobBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1)),
        hostKeyFingerprint = "SHA256:original",
        authKind = ComputerAuthKind.PASSWORD,
        runMode = ComputerRunMode.CONTAINER,
        status = ComputerStatus.READY,
    )

    private val replacement = HostKeyProbeResult(
        host = testComputer.host,
        resolvedAddress = "192.0.2.10",
        port = testComputer.port,
        algorithm = "ssh-ed25519",
        keyBlob = byteArrayOf(2),
        fingerprint = "SHA256:replacement",
    )

    @Before
    fun setUp() {
        mockkObject(AppLogger)
        justRun { AppLogger.warn(any(), any()) }
        ComputerConnectionPoolRegistry.resetForTesting()
    }

    @After
    fun tearDown() {
        ComputerConnectionPoolRegistry.resetForTesting()
        unmockkObject(AppLogger)
    }

    @Test
    fun `单例连接池动态路由至最新注册的回调且关闭后回退`() = runTest {
        val client = mockk<ComputerSshClient>()
        val credentials = mockk<ComputerCredentialStore>()
        val credential = ComputerCredential.Password("pwd".toCharArray())
        val connection = mockk<ComputerSshConnection>(relaxed = true)

        coEvery { credentials.loadComputerCredential(testComputer.id) } returns credential
        coEvery { client.connect(any(), any()) } returns connection

        var repo1Called = false
        var repo2Called = false

        val reg1 = ComputerConnectionPoolRegistry.register(
            onHostKeyChanged = { comp, probe ->
                repo1Called = true
                comp.copy(hostKeyFingerprint = probe.fingerprint)
            },
            onDedicatedKeyRejected = { _, _ -> },
        )

        val pool1 = ComputerConnectionPoolRegistry.get(
            sshClient = client,
            credentialStore = credentials,
        )

        val reg2 = ComputerConnectionPoolRegistry.register(
            onHostKeyChanged = { comp, probe ->
                repo2Called = true
                comp.copy(hostKeyFingerprint = probe.fingerprint)
            },
            onDedicatedKeyRejected = { _, _ -> },
        )

        val pool2 = ComputerConnectionPoolRegistry.get(
            sshClient = client,
            credentialStore = credentials,
        )

        assertSame("多次获取必须返回同一个进程级单例连接池", pool1, pool2)

        // 模拟触发 Host Key 变更恢复：首次连接报 HOST_KEY_CHANGED，探针返回 replacement
        coEvery { client.connect(testComputer, credential) } throws ComputerException(
            ComputerErrorCodes.HOST_KEY_CHANGED, "指纹变化",
        )
        coEvery { client.probeHostKey(testComputer.host, testComputer.port) } returns replacement
        val updatedComputer = testComputer.copy(hostKeyFingerprint = replacement.fingerprint)
        coEvery { client.connect(updatedComputer, credential) } returns connection

        pool1.acquire(testComputer).use { lease ->
            assertSame(connection, lease.connection)
        }

        assertTrue("处于后注册的活跃回调应被优先执行", repo2Called)
        assertEquals(false, repo1Called)

        // 关闭 reg2（模拟第二个 Repository close）
        reg2.close()
        repo2Called = false

        // 再次触发变更，应回退至仍在生命周期内的 reg1
        pool1.acquire(testComputer).use { lease ->
            assertSame(connection, lease.connection)
        }

        assertTrue("reg2 关闭后应自动回退到前一个存活的 reg1 回调", repo1Called)

        // 关闭 reg1（模拟全部 Repository 已销毁）
        reg1.close()

        // 无任何存活回调时触发变更必须清晰失败，绝不执行已关闭的过时回调
        var failedWithExpectedCode = false
        try {
            pool1.acquire(testComputer).use { }
        } catch (e: ComputerException) {
            if (e.code == ComputerErrorCodes.COMPUTER_NOT_READY) {
                failedWithExpectedCode = true
            }
        }
        assertTrue("全部 Repository 注销后必须清晰失败，不能执行过时实例", failedWithExpectedCode)
    }
}
