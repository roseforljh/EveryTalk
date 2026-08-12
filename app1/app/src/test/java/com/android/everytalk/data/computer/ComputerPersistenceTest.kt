package com.android.everytalk.data.computer

import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.database.entities.toModel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class ComputerPersistenceTest {
    @Test
    fun `credential codec preserves private key and passphrase`() {
        val source = ComputerCredential.PrivateKey(
            privateKey = "private-key-内容".toCharArray(),
            passphrase = "口令".toCharArray(),
        )

        val encoded = ComputerCredentialCodec.encode(source)
        val decoded = ComputerCredentialCodec.decode(encoded) as ComputerCredential.PrivateKey

        assertArrayEquals(source.privateKey, decoded.privateKey)
        assertArrayEquals(source.passphrase, decoded.passphrase)

        encoded.fill(0)
        source.clear()
        decoded.clear()
    }

    @Test
    fun `envelope codec round trip keeps every encrypted field`() {
        val source = ComputerEncryptedEnvelope(
            ciphertext = byteArrayOf(1, 2, 3),
            nonce = byteArrayOf(4, 5),
            wrappedKey = byteArrayOf(6, 7, 8),
            wrappedKeyNonce = byteArrayOf(9, 10),
        )

        val decoded = ComputerEnvelopeCodec.decode(ComputerEnvelopeCodec.encode(source))

        assertArrayEquals(source.ciphertext, decoded.ciphertext)
        assertArrayEquals(source.nonce, decoded.nonce)
        assertArrayEquals(source.wrappedKey, decoded.wrappedKey)
        assertArrayEquals(source.wrappedKeyNonce, decoded.wrappedKeyNonce)
    }

    @Test
    fun `envelope encryption binds ciphertext to its resource`() {
        val masterKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val cipher = ComputerEnvelopeCipher(masterKey)
        val plaintext = "本地 SSH 凭据".toByteArray()
        val envelope = cipher.encrypt("credential:computer-1", plaintext)

        assertArrayEquals(plaintext, cipher.decrypt("credential:computer-1", envelope))

        val failure = runCatching { cipher.decrypt("credential:computer-2", envelope) }.exceptionOrNull()
        assertTrue(failure is ComputerException)
        assertEquals(ComputerErrorCodes.KEYSTORE_UNAVAILABLE, (failure as ComputerException).code)
    }

    @Test
    fun `computer room mapping keeps capabilities and uses safe enum fallbacks`() {
        val json = Json { ignoreUnknownKeys = true }
        val source = Computer(
            id = "computer-1",
            displayName = "测试服务器",
            host = "vps.example.com",
            port = 22,
            username = "root",
            authKind = ComputerAuthKind.PRIVATE_KEY,
            credentialState = ComputerCredentialState.ORIGINAL_ENCRYPTED,
            runMode = ComputerRunMode.CONTAINER,
            status = ComputerStatus.READY,
            capabilities = ComputerCapabilities(
                osId = "ubuntu",
                architecture = "x86_64",
                dockerAvailable = true,
                sftpAvailable = true,
            ),
            allowPrivateNetwork = true,
            permissionMode = ComputerPermissionMode.SMART,
        )

        assertEquals(source, source.toEntity(json).toModel(json))

        val fallback = source.toEntity(json).copy(
            authKind = "UNKNOWN_AUTH",
            credentialState = "UNKNOWN_CREDENTIAL",
            runMode = "UNKNOWN_MODE",
            status = "UNKNOWN_STATUS",
            capabilitiesJson = "{invalid-json",
        ).toModel(json)
        assertEquals(ComputerAuthKind.PASSWORD, fallback.authKind)
        assertEquals(ComputerCredentialState.MISSING, fallback.credentialState)
        assertEquals(ComputerRunMode.DIRECT, fallback.runMode)
        assertEquals(ComputerStatus.ERROR, fallback.status)
        assertNull(fallback.capabilities)
    }

    @Test
    fun `preview room mapping keeps host target`() {
        val source = ComputerPreview(
            workspaceId = "ws-1",
            remotePort = 8080,
            target = ComputerExecTarget.HOST,
        )

        assertEquals(source, source.toEntity().toModel())
    }

    @Test
    fun `修复优先使用已保存sudo密码`() {
        val sudoPassword = "sudo-secret".toCharArray()
        val sshCredential = ComputerCredential.Password("ssh-secret".toCharArray())

        val selected = resolveComputerProvisionPassword(sudoPassword, sshCredential)

        assertArrayEquals("sudo-secret".toCharArray(), selected)
        assertTrue(sshCredential.password.all { it == '\u0000' })
        selected?.fill('\u0000')
    }

    @Test
    fun `未单独填写sudo密码时复用SSH密码`() {
        val sshCredential = ComputerCredential.Password("same-secret".toCharArray())

        val selected = resolveComputerProvisionPassword(null, sshCredential)

        assertArrayEquals("same-secret".toCharArray(), selected)
        selected?.fill('\u0000')
    }

    @Test
    fun `私钥登录且未保存sudo密码时交给免密sudo处理`() {
        val sshCredential = ComputerCredential.PrivateKey("private-key".toCharArray())

        val selected = resolveComputerProvisionPassword(null, sshCredential)

        assertNull(selected)
        assertTrue(sshCredential.privateKey.all { it == '\u0000' })
    }
}
