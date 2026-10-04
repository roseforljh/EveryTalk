package com.android.everytalk.data.computer

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Base64

/** 验证连接池在 Host Key 变化时只更新当前服务器并重试一次。 */
class ComputerHostKeyRecoveryTest {
    private val old = Computer(
        id = "server-1",
        displayName = "VPS",
        host = "example.com",
        port = 22,
        username = "root",
        hostKeyAlgorithm = "ssh-ed25519",
        hostKeyBlobBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1)),
        hostKeyFingerprint = "SHA256:old",
        authKind = ComputerAuthKind.PASSWORD,
        runMode = ComputerRunMode.CONTAINER,
        status = ComputerStatus.READY,
    )
    private val replacement = HostKeyProbeResult(
        host = old.host,
        resolvedAddress = "192.0.2.10",
        port = old.port,
        algorithm = "ssh-ed25519",
        keyBlob = byteArrayOf(2),
        fingerprint = "SHA256:new",
    )

    @Test
    fun `指纹变化后保存新指纹并重新读取凭据连接`() = runTest {
        val client = mockk<ComputerSshClient>()
        val credentials = mockk<ComputerCredentialStore>()
        val firstCredential = ComputerCredential.Password("first".toCharArray())
        val retryCredential = ComputerCredential.Password("second".toCharArray())
        val connection = mockk<ComputerSshConnection>(relaxed = true)
        val updated = old.copy(
            resolvedAddress = replacement.resolvedAddress,
            hostKeyAlgorithm = replacement.algorithm,
            hostKeyBlobBase64 = Base64.getEncoder().encodeToString(replacement.keyBlob),
            hostKeyFingerprint = replacement.fingerprint,
        )
        var savedComputer: Computer? = null
        coEvery { credentials.loadComputerCredential(old.id) } returnsMany listOf(firstCredential, retryCredential)
        coEvery { client.connect(old, firstCredential) } throws ComputerException(
            ComputerErrorCodes.HOST_KEY_CHANGED, "指纹变化",
        )
        coEvery { client.probeHostKey(old.host, old.port) } returns replacement
        coEvery { client.connect(updated, retryCredential) } returns connection

        val pool = ComputerConnectionPool(client, credentials, { _, _ ->
            savedComputer = updated
            updated
        }, { _, _ -> })
        pool.acquire(old).use { lease -> assertSame(connection, lease.connection) }

        assertEquals(replacement.fingerprint, savedComputer?.hostKeyFingerprint)
        coVerify(exactly = 2) { credentials.loadComputerCredential(old.id) }
        coVerify(exactly = 1) { client.connect(updated, retryCredential) }
    }

    @Test
    fun `新指纹探测失败时不更新也不重试认证`() = runTest {
        val client = mockk<ComputerSshClient>()
        val credentials = mockk<ComputerCredentialStore>()
        val credential = ComputerCredential.Password("first".toCharArray())
        var updated = false
        coEvery { credentials.loadComputerCredential(old.id) } returns credential
        coEvery { client.connect(old, credential) } throws ComputerException(
            ComputerErrorCodes.HOST_KEY_CHANGED, "指纹变化",
        )
        coEvery { client.probeHostKey(old.host, old.port) } throws ComputerException(
            ComputerErrorCodes.SSH_TIMEOUT, "探测超时",
        )

        val pool = ComputerConnectionPool(client, credentials, { current, _ ->
            updated = true
            current
        }, { _, _ -> })
        val error = runCatching { pool.acquire(old) }.exceptionOrNull() as ComputerException

        assertEquals(ComputerErrorCodes.SSH_TIMEOUT, error.code)
        assertEquals(false, updated)
        coVerify(exactly = 1) { credentials.loadComputerCredential(old.id) }
    }

    @Test
    fun `新系统拒绝专用 Key 时验证原始凭据并恢复登录`() = runTest {
        val client = mockk<ComputerSshClient>()
        val credentials = mockk<ComputerCredentialStore>()
        val dedicated = old.copy(credentialState = ComputerCredentialState.DEDICATED_KEY)
        val updated = dedicated.copy(
            hostKeyBlobBase64 = Base64.getEncoder().encodeToString(replacement.keyBlob),
            hostKeyFingerprint = replacement.fingerprint,
        )
        val firstKey = ComputerCredential.PrivateKey("first-key".toCharArray())
        val retryKey = ComputerCredential.PrivateKey("retry-key".toCharArray())
        val original = ComputerCredential.Password("password".toCharArray())
        val connection = mockk<ComputerSshConnection>(relaxed = true)
        var restoredCredential: ComputerCredential? = null
        coEvery { credentials.loadComputerCredential(dedicated.id) } returnsMany listOf(firstKey, retryKey)
        coEvery { credentials.loadOriginalComputerCredential(dedicated.id) } returns original
        coEvery { client.connect(dedicated, firstKey) } throws ComputerException(
            ComputerErrorCodes.HOST_KEY_CHANGED, "指纹变化",
        )
        coEvery { client.probeHostKey(dedicated.host, dedicated.port) } returns replacement
        coEvery { client.connect(updated, retryKey) } throws ComputerException(
            ComputerErrorCodes.AUTH_FAILED, "专用 Key 已失效",
        )
        coEvery { client.connect(updated, original) } returns connection

        val pool = ComputerConnectionPool(client, credentials, { _, _ -> updated }, { _, credential ->
            restoredCredential = credential
        })
        pool.acquire(dedicated).use { lease -> assertSame(connection, lease.connection) }

        assertEquals(ComputerAuthKind.PASSWORD, restoredCredential?.kind)
        coVerify(exactly = 1) { client.connect(updated, original) }
    }

    @Test
    fun `指纹已保存后专用 Key 失效仍能回退原始凭据`() = runTest {
        val client = mockk<ComputerSshClient>()
        val credentials = mockk<ComputerCredentialStore>()
        val dedicated = old.copy(credentialState = ComputerCredentialState.DEDICATED_KEY)
        val key = ComputerCredential.PrivateKey("obsolete-key".toCharArray())
        val original = ComputerCredential.Password("current-password".toCharArray())
        val connection = mockk<ComputerSshConnection>(relaxed = true)
        var recovered = false
        coEvery { credentials.loadComputerCredential(dedicated.id) } returns key
        coEvery { credentials.loadOriginalComputerCredential(dedicated.id) } returns original
        coEvery { client.connect(dedicated, key) } throws ComputerException(
            ComputerErrorCodes.AUTH_FAILED, "专用 Key 已失效",
        )
        coEvery { client.connect(dedicated, original) } returns connection

        val pool = ComputerConnectionPool(client, credentials, { _, _ ->
            error("指纹未变化，不应再次探测")
        }, { _, credential ->
            assertEquals(ComputerAuthKind.PASSWORD, credential.kind)
            recovered = true
        })
        pool.acquire(dedicated).use { lease -> assertSame(connection, lease.connection) }

        assertEquals(true, recovered)
        coVerify(exactly = 1) { client.connect(dedicated, original) }
        coVerify(exactly = 0) { client.probeHostKey(any(), any()) }
    }

    @Test
    fun `原始凭据也失败时保留当前凭据状态`() = runTest {
        val client = mockk<ComputerSshClient>()
        val credentials = mockk<ComputerCredentialStore>()
        val dedicated = old.copy(credentialState = ComputerCredentialState.DEDICATED_KEY)
        val key = ComputerCredential.PrivateKey("obsolete-key".toCharArray())
        val original = ComputerCredential.Password("old-password".toCharArray())
        var restored = false
        coEvery { credentials.loadComputerCredential(dedicated.id) } returns key
        coEvery { credentials.loadOriginalComputerCredential(dedicated.id) } returns original
        coEvery { client.connect(dedicated, key) } throws ComputerException(
            ComputerErrorCodes.AUTH_FAILED, "专用 Key 已失效",
        )
        coEvery { client.connect(dedicated, original) } throws ComputerException(
            ComputerErrorCodes.AUTH_FAILED, "原始凭据已失效",
        )

        val pool = ComputerConnectionPool(client, credentials, { _, _ -> dedicated }, { _, _ ->
            restored = true
        })
        val failure = runCatching { pool.acquire(dedicated) }.exceptionOrNull() as ComputerException

        assertEquals(ComputerErrorCodes.AUTH_FAILED, failure.code)
        assertEquals(false, restored)
        coVerify(exactly = 0) { credentials.saveComputerCredential(any(), any()) }
    }
}
