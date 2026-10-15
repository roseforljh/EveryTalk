package com.android.everytalk.data.sync

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * 合同 0.2.0 的 Argon2id 参数。
 * 密钥派生只在后台线程执行；这里不保存密码，也不把密码转换成日志字符串。
 */
@Serializable
internal data class SyncKdfParams(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val outputBytes: Int = 32,
)

@Serializable
internal data class SyncEncryptedEnvelope(
    @SerialName("\$enc") val marker: Int = 1,
    val alg: String = "AES-256-GCM",
    val nonce: String,
    val ct: String,
    val keyVersion: Long,
)

@Serializable
internal data class SyncWrappedDek(val envelope: SyncEncryptedEnvelope)

/** 服务器合同中的 crypto_keys.value，字段名必须与合同保持一致。 */
@Serializable
internal data class SyncCryptoKeysValue(
    val version: Long,
    val kdf: String = "argon2id",
    val kdfParams: SyncKdfParams,
    val salt: String,
    val wrappedDek: SyncEncryptedEnvelope,
)

/**
 * 只负责合同规定的密码学原语，不负责 Room、网络和用户界面状态。
 * AAD 使用合同要求的紧凑 JSON 数组，字段路径必须是 RFC 6901 JSON Pointer。
 */
internal object SyncCrypto {
    private const val ARGON2_VERSION = Argon2Parameters.ARGON2_VERSION_13
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val DEK_BYTES = 32
    private const val MAX_ENVELOPE_BYTES = 8192
    private val random = SecureRandom()
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    /** 合同推荐参数；派生必须放在 Dispatchers.Default/IO，不能在 Compose 主线程调用。 */
    val defaultKdfParams: SyncKdfParams = SyncKdfParams(
        memoryKiB = 65_536,
        iterations = 3,
        parallelism = 1,
        outputBytes = DEK_BYTES,
    )

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun deriveKek(password: CharArray, salt: ByteArray, params: SyncKdfParams): ByteArray {
        require(params.memoryKiB in 8_192..262_144)
        require(params.iterations in 1..10)
        require(params.parallelism in 1..4)
        require(params.outputBytes == DEK_BYTES)
        require(salt.size in 16..32)
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(ARGON2_VERSION)
                .withMemoryAsKB(params.memoryKiB)
                .withIterations(params.iterations)
                .withParallelism(params.parallelism)
                .withSalt(salt)
                .build(),
        )
        return ByteArray(params.outputBytes).also { output ->
            generator.generateBytes(password, output)
        }
    }

    fun aad(
        userId: String,
        kind: String,
        key: String,
        jsonPointer: String,
        keyVersion: Long,
        secretsEpoch: Long,
    ): ByteArray {
        require(jsonPointer.isEmpty() || jsonPointer.startsWith('/'))
        val values = buildJsonArray {
            add(JsonPrimitive("everytalk-sync")); add(JsonPrimitive("0.2.0"))
            add(JsonPrimitive(userId)); add(JsonPrimitive(kind)); add(JsonPrimitive(key))
            add(JsonPrimitive(jsonPointer)); add(JsonPrimitive(keyVersion)); add(JsonPrimitive(secretsEpoch))
        }
        return values.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun encrypt(
        dek: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
        keyVersion: Long,
        nonce: ByteArray = randomBytes(NONCE_BYTES),
    ): SyncEncryptedEnvelope {
        require(dek.size == DEK_BYTES)
        require(nonce.size == NONCE_BYTES)
        require(keyVersion > 0)
        val ciphertext = cipher(Cipher.ENCRYPT_MODE, dek, nonce, aad).doFinal(plaintext)
        val envelope = SyncEncryptedEnvelope(
            nonce = Base64.getEncoder().encodeToString(nonce),
            ct = Base64.getEncoder().encodeToString(ciphertext),
            keyVersion = keyVersion,
        )
        require(json.encodeToString(envelope).toByteArray(StandardCharsets.UTF_8).size <= MAX_ENVELOPE_BYTES)
        return envelope
    }

    fun decrypt(dek: ByteArray, envelope: SyncEncryptedEnvelope, aad: ByteArray): ByteArray {
        require(dek.size == DEK_BYTES)
        require(envelope.marker == 1 && envelope.alg == "AES-256-GCM" && envelope.keyVersion > 0)
        val nonce = decodeCanonicalBase64(envelope.nonce).also { require(it.size == NONCE_BYTES) }
        val ciphertext = decodeCanonicalBase64(envelope.ct).also { require(it.size >= 16) }
        return cipher(Cipher.DECRYPT_MODE, dek, nonce, aad).doFinal(ciphertext)
    }

    fun wrapDek(
        kek: ByteArray,
        dek: ByteArray,
        keyVersion: Long,
        nonce: ByteArray,
    ): SyncEncryptedEnvelope = encrypt(kek, dek, byteArrayOf(), keyVersion, nonce)

    fun unwrapDek(kek: ByteArray, envelope: SyncEncryptedEnvelope): ByteArray =
        decrypt(kek, envelope, byteArrayOf())

    /** 把任意 JSON 字段作为明文编码后加密，解密端再按同样的 JSON 类型还原。 */
    fun encryptJson(
        dek: ByteArray,
        value: kotlinx.serialization.json.JsonElement,
        aad: ByteArray,
        keyVersion: Long,
    ): JsonObject = json.encodeToJsonElement(encrypt(dek, value.toString().toByteArray(StandardCharsets.UTF_8), aad, keyVersion)).jsonObject

    fun decryptJson(
        dek: ByteArray,
        envelope: SyncEncryptedEnvelope,
        aad: ByteArray,
    ): kotlinx.serialization.json.JsonElement = Json.parseToJsonElement(
        decrypt(dek, envelope, aad).toString(StandardCharsets.UTF_8),
    )

    fun decodeCanonicalBase64(value: String): ByteArray {
        require(value.isNotEmpty() && value.length % 4 == 0)
        require(value.matches(Regex("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?")))
        val decoded = Base64.getDecoder().decode(value)
        require(Base64.getEncoder().encodeToString(decoded) == value)
        return decoded
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
}
