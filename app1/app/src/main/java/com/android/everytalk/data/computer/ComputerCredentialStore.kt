package com.android.everytalk.data.computer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.CharBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val COMPUTER_KEYSTORE_ALIAS = "everytalk_computer_credentials_v1"
private const val ENVELOPE_VERSION = 1
private const val GCM_TAG_BITS = 128
private const val GCM_NONCE_BYTES = 12
private const val DATA_KEY_BYTES = 32
private const val MAX_ENCRYPTED_FIELD_BYTES = 4 * 1024 * 1024
private const val CREDENTIAL_PAYLOAD_VERSION = 1

internal data class ComputerEncryptedEnvelope(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val wrappedKey: ByteArray,
    val wrappedKeyNonce: ByteArray,
)

/**
 * 实现本地信封加密。每条记录使用独立数据密钥，Keystore Key 只负责包装数据密钥。
 * context 作为 GCM AAD 绑定资源，复制密文到另一个 Computer 或 Secret 后会认证失败。
 */
internal class ComputerEnvelopeCipher(
    private val masterKey: SecretKey,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun encrypt(context: String, plaintext: ByteArray): ComputerEncryptedEnvelope {
        val dataKeyBytes = ByteArray(DATA_KEY_BYTES).also(secureRandom::nextBytes)
        return try {
            val dataKey = SecretKeySpec(dataKeyBytes, KeyProperties.KEY_ALGORITHM_AES)
            val nonce = ByteArray(GCM_NONCE_BYTES).also(secureRandom::nextBytes)
            val ciphertext = aesGcmEncrypt(dataKey, nonce, "computer-data:$context", plaintext)

            // Android Keystore 主密钥要求系统生成随机 IV。加密时不传 GCMParameterSpec，
            // 再把 Keystore 生成的 IV 写入信封，解密时继续使用该 IV 即可兼容现有格式。
            val wrappingCipher = Cipher.getInstance("AES/GCM/NoPadding")
            wrappingCipher.init(Cipher.ENCRYPT_MODE, masterKey)
            wrappingCipher.updateAAD("computer-key:$context".toByteArray(Charsets.UTF_8))
            val wrappedKey = wrappingCipher.doFinal(dataKeyBytes)
            val wrappedKeyNonce = wrappingCipher.iv.copyOf()
            ComputerEncryptedEnvelope(ciphertext, nonce, wrappedKey, wrappedKeyNonce)
        } finally {
            dataKeyBytes.fill(0)
        }
    }

    fun decrypt(context: String, envelope: ComputerEncryptedEnvelope): ByteArray {
        val dataKeyBytes = try {
            aesGcmDecrypt(
                masterKey,
                envelope.wrappedKeyNonce,
                "computer-key:$context",
                envelope.wrappedKey,
            )
        } catch (error: Exception) {
            throw ComputerException(
                code = ComputerErrorCodes.KEYSTORE_UNAVAILABLE,
                message = "本地凭据无法解密",
                cause = error,
            )
        }
        return try {
            aesGcmDecrypt(
                SecretKeySpec(dataKeyBytes, KeyProperties.KEY_ALGORITHM_AES),
                envelope.nonce,
                "computer-data:$context",
                envelope.ciphertext,
            )
        } catch (error: Exception) {
            throw ComputerException(
                code = ComputerErrorCodes.KEYSTORE_UNAVAILABLE,
                message = "本地凭据认证失败",
                cause = error,
            )
        } finally {
            dataKeyBytes.fill(0)
        }
    }

    private fun aesGcmEncrypt(key: SecretKey, nonce: ByteArray, aad: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(key: SecretKey, nonce: ByteArray, aad: String, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext)
    }
}

internal object ComputerEnvelopeCodec {
    private const val MAGIC = 0x45544356 // ETCV

    fun encode(envelope: ComputerEncryptedEnvelope): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(ENVELOPE_VERSION)
            output.writeBoundedBytes(envelope.nonce)
            output.writeBoundedBytes(envelope.wrappedKeyNonce)
            output.writeBoundedBytes(envelope.wrappedKey)
            output.writeBoundedBytes(envelope.ciphertext)
        }
        bytes.toByteArray()
    }

    fun decode(encoded: ByteArray): ComputerEncryptedEnvelope = DataInputStream(ByteArrayInputStream(encoded)).use { input ->
        require(input.readInt() == MAGIC) { "凭据文件标识无效" }
        require(input.readInt() == ENVELOPE_VERSION) { "凭据文件版本不受支持" }
        val nonce = input.readBoundedBytes()
        val wrappedKeyNonce = input.readBoundedBytes()
        val wrappedKey = input.readBoundedBytes()
        val ciphertext = input.readBoundedBytes()
        require(input.available() == 0) { "凭据文件包含多余数据" }
        ComputerEncryptedEnvelope(ciphertext, nonce, wrappedKey, wrappedKeyNonce)
    }

    private fun DataOutputStream.writeBoundedBytes(value: ByteArray) {
        require(value.size <= MAX_ENCRYPTED_FIELD_BYTES)
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readBoundedBytes(): ByteArray {
        val size = readInt()
        require(size in 0..MAX_ENCRYPTED_FIELD_BYTES) { "凭据文件字段大小无效" }
        return ByteArray(size).also(::readFully)
    }
}

internal object ComputerCredentialCodec {
    fun encode(credential: ComputerCredential): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(CREDENTIAL_PAYLOAD_VERSION)
            when (credential) {
                is ComputerCredential.Password -> {
                    output.writeByte(1)
                    output.writeSecretChars(credential.password)
                }
                is ComputerCredential.PrivateKey -> {
                    output.writeByte(2)
                    output.writeSecretChars(credential.privateKey)
                    output.writeNullableSecretChars(credential.passphrase)
                }
            }
        }
        bytes.toByteArray()
    }

    fun decode(encoded: ByteArray): ComputerCredential = DataInputStream(ByteArrayInputStream(encoded)).use { input ->
        require(input.readInt() == CREDENTIAL_PAYLOAD_VERSION) { "凭据版本不受支持" }
        val credential = when (input.readUnsignedByte()) {
            1 -> ComputerCredential.Password(input.readSecretChars())
            2 -> ComputerCredential.PrivateKey(input.readSecretChars(), input.readNullableSecretChars())
            else -> error("凭据类型无效")
        }
        require(input.available() == 0) { "凭据包含多余数据" }
        credential
    }

    private fun DataOutputStream.writeSecretChars(value: CharArray) {
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value))
        val bytes = ByteArray(encoded.remaining()).also(encoded::get)
        try {
            require(bytes.size <= MAX_ENCRYPTED_FIELD_BYTES)
            writeInt(bytes.size)
            write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun DataOutputStream.writeNullableSecretChars(value: CharArray?) {
        if (value == null) {
            writeInt(-1)
            return
        }
        writeSecretChars(value)
    }

    private fun DataInputStream.readSecretChars(size: Int = readInt()): CharArray {
        require(size in 0..MAX_ENCRYPTED_FIELD_BYTES) { "凭据字段大小无效" }
        val bytes = ByteArray(size).also(::readFully)
        return try {
            val decoded = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes))
            CharArray(decoded.remaining()).also(decoded::get)
        } finally {
            bytes.fill(0)
        }
    }

    private fun DataInputStream.readNullableSecretChars(): CharArray? {
        val size = readInt()
        return if (size == -1) null else readSecretChars(size)
    }
}

class ComputerCredentialStore(private val context: Context) {
    private val rootDirectory = File(context.noBackupFilesDir, "computer_credentials")
    private val envelopeCipher by lazy { ComputerEnvelopeCipher(getOrCreateMasterKey()) }

    suspend fun saveComputerCredential(computerId: String, credential: ComputerCredential) = withContext(Dispatchers.IO) {
        val scope = "credential:$computerId"
        val plaintext = ComputerCredentialCodec.encode(credential)
        try {
            writeEnvelope(fileForScope(scope), envelopeCipher.encrypt(scope, plaintext))
        } finally {
            plaintext.fill(0)
            credential.clear()
        }
    }

    suspend fun loadComputerCredential(computerId: String): ComputerCredential = withContext(Dispatchers.IO) {
        val scope = "credential:$computerId"
        val file = fileForScope(scope)
        if (!file.isFile) {
            throw ComputerException(
                code = ComputerErrorCodes.CREDENTIAL_MISSING,
                message = "本地服务器凭据不存在",
                action = "UPDATE_CREDENTIAL",
            )
        }
        val plaintext = envelopeCipher.decrypt(scope, readEnvelope(file))
        try {
            ComputerCredentialCodec.decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    suspend fun deleteComputerCredential(computerId: String) = withContext(Dispatchers.IO) {
        cryptoShred(fileForScope("credential:$computerId"))
    }

    suspend fun saveWorkspaceSecret(secretId: String, value: CharArray) = withContext(Dispatchers.IO) {
        val scope = "workspace-secret:$secretId"
        val credential = ComputerCredential.Password(value)
        val plaintext = ComputerCredentialCodec.encode(credential)
        try {
            writeEnvelope(fileForScope(scope), envelopeCipher.encrypt(scope, plaintext))
        } finally {
            plaintext.fill(0)
            credential.clear()
        }
    }

    suspend fun loadWorkspaceSecret(secretId: String): CharArray = withContext(Dispatchers.IO) {
        val scope = "workspace-secret:$secretId"
        val file = fileForScope(scope)
        if (!file.isFile) {
            throw ComputerException(
                code = ComputerErrorCodes.CREDENTIAL_MISSING,
                message = "Workspace Secret 不存在",
            )
        }
        val plaintext = envelopeCipher.decrypt(scope, readEnvelope(file))
        try {
            val decoded = ComputerCredentialCodec.decode(plaintext)
            when (decoded) {
                is ComputerCredential.Password -> decoded.password
                is ComputerCredential.PrivateKey -> {
                    decoded.clear()
                    error("Workspace Secret 类型无效")
                }
            }
        } finally {
            plaintext.fill(0)
        }
    }

    suspend fun deleteWorkspaceSecret(secretId: String) = withContext(Dispatchers.IO) {
        cryptoShred(fileForScope("workspace-secret:$secretId"))
    }

    private fun getOrCreateMasterKey(): SecretKey {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(COMPUTER_KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    COMPUTER_KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        } catch (error: Exception) {
            throw ComputerException(
                code = ComputerErrorCodes.KEYSTORE_UNAVAILABLE,
                message = "Android Keystore 当前不可用",
                cause = error,
            )
        }
    }

    private fun fileForScope(scope: String): File {
        val name = MessageDigest.getInstance("SHA-256")
            .digest(scope.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(rootDirectory, "$name.bin")
    }

    private fun writeEnvelope(file: File, envelope: ComputerEncryptedEnvelope) {
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        val encoded = ComputerEnvelopeCodec.encode(envelope)
        val output = atomicFile.startWrite()
        try {
            output.write(encoded)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        } finally {
            encoded.fill(0)
        }
    }

    private fun readEnvelope(file: File): ComputerEncryptedEnvelope {
        val bytes = AtomicFile(file).readFully()
        return try {
            ComputerEnvelopeCodec.decode(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Flash 存储无法保证物理覆写。这里先用随机 wrapped key 替换信封，再删除文件，
     * 使遗留 ciphertext 无法通过原 Keystore Key 恢复。
     */
    private fun cryptoShred(file: File) {
        if (!file.isFile) return
        runCatching {
            val existing = readEnvelope(file)
            writeEnvelope(
                file,
                existing.copy(wrappedKey = ByteArray(existing.wrappedKey.size).also(SecureRandom()::nextBytes)),
            )
        }
        file.delete()
    }
}
