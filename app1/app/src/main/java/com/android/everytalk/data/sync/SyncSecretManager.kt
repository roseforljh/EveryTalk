package com.android.everytalk.data.sync

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json

/**
 * 管理同步密码、DEK 和本机缓存。
 *
 * 同步密码只在调用栈中短暂存在；服务器只收到 Argon2id 包裹后的 DEK 和字段密文。
 * 本机选择记住后，DEK 由 Android Keystore 的设备密钥再次包裹，应用数据库和偏好设置中
 * 都不会出现明文 DEK。密码重置由上层先调用 DELETE /sync/secrets，再清除云端密钥记录；
 * 本机缓存可以保留，避免用户在同一设备上丢失已经解锁的配置。
 */
internal class SyncSecretManager(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    fun hasCachedDek(userId: String, keyVersion: Long, secretsEpoch: Long): Boolean =
        readCachedDek(userId, keyVersion, secretsEpoch) != null

    fun create(
        userId: String,
        password: CharArray,
        secretsEpoch: Long,
        params: SyncKdfParams = SyncCrypto.defaultKdfParams,
        rememberOnDevice: Boolean = true,
    ): SyncCryptoKeysValue {
        require(userId.isNotBlank())
        require(password.isNotEmpty()) { "同步密码不能为空" }
        require(secretsEpoch >= 0)
        val salt = SyncCrypto.randomBytes(16)
        val version = secretsEpoch + 1
        val kek = try {
            SyncCrypto.deriveKek(password, salt, params)
        } finally {
            password.fill('\u0000')
        }
        val dek = SyncCrypto.randomBytes(32)
        val wrapped = SyncCrypto.wrapDek(kek, dek, version, SyncCrypto.randomBytes(12))
        kek.fill(0)
        if (rememberOnDevice) cacheDek(userId, dek, version, secretsEpoch)
        dek.fill(0)
        return SyncCryptoKeysValue(
            version = version,
            kdfParams = params,
            salt = java.util.Base64.getEncoder().encodeToString(salt),
            wrappedDek = wrapped,
        )
    }

    /** 用拉取到的 crypto_keys 解锁；错误密码和损坏密文只返回异常给 UI 处理。 */
    fun unlock(
        userId: String,
        password: CharArray,
        value: SyncCryptoKeysValue,
        secretsEpoch: Long,
        rememberOnDevice: Boolean = true,
    ) {
        require(value.version == secretsEpoch + 1)
        val salt = SyncCrypto.decodeCanonicalBase64(value.salt)
        val kek = try {
            SyncCrypto.deriveKek(password, salt, value.kdfParams)
        } finally {
            password.fill('\u0000')
        }
        val dek = try {
            SyncCrypto.unwrapDek(kek, value.wrappedDek)
        } finally {
            kek.fill(0)
        }
        try {
            if (rememberOnDevice) cacheDek(userId, dek, value.version, secretsEpoch)
        } finally {
            dek.fill(0)
        }
    }

    fun readCachedDek(userId: String, keyVersion: Long, secretsEpoch: Long): ByteArray? {
        val metadata = preferences.getString(KEY_METADATA, null)?.split('|') ?: return null
        if (metadata.size != 3 || metadata[0] != userId || metadata[1].toLongOrNull() != keyVersion ||
            metadata[2].toLongOrNull() != secretsEpoch) return null
        val nonce = preferences.getString(KEY_NONCE, null)?.let(::decodeUrlBase64) ?: return null
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null)?.let(::decodeUrlBase64) ?: return null
        return runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(128, nonce))
                updateAAD(metadata.joinToString("|").toByteArray(Charsets.UTF_8))
            }.doFinal(ciphertext)
        }.getOrNull()?.also { require(it.size == 32) }
    }

    /** secrets_epoch 变化时必须丢掉旧 DEK，避免旧密文再次入队。 */
    fun clearCachedDek() = preferences.edit()
        .remove(KEY_METADATA).remove(KEY_NONCE).remove(KEY_CIPHERTEXT).apply()

    /** 用于构造 user_settings.value 中的密文字段。调用者负责及时清零返回的 DEK。 */
    fun encryptField(
        userId: String,
        kind: String,
        key: String,
        jsonPointer: String,
        keyVersion: Long,
        secretsEpoch: Long,
        value: kotlinx.serialization.json.JsonElement,
    ): kotlinx.serialization.json.JsonObject? = readCachedDek(userId, keyVersion, secretsEpoch)?.useBytes { dek ->
        SyncCrypto.encryptJson(dek, value, SyncCrypto.aad(userId, kind, key, jsonPointer, keyVersion, secretsEpoch), keyVersion)
    }

    /** 解密已拉取的单字段；密码错误、旧代次或损坏密文都返回 null 给上层显示提示。 */
    fun decryptField(
        userId: String,
        kind: String,
        key: String,
        jsonPointer: String,
        secretsEpoch: Long,
        value: kotlinx.serialization.json.JsonElement,
    ): kotlinx.serialization.json.JsonElement? = runCatching {
        val envelope = syncJson.decodeFromJsonElement(SyncEncryptedEnvelope.serializer(), value)
        readCachedDek(userId, envelope.keyVersion, secretsEpoch)?.useBytes { dek ->
            SyncCrypto.decryptJson(dek, envelope, SyncCrypto.aad(userId, kind, key, jsonPointer, envelope.keyVersion, secretsEpoch))
        }
    }.getOrNull()

    private fun cacheDek(userId: String, dek: ByteArray, keyVersion: Long, secretsEpoch: Long) {
        val metadata = "$userId|$keyVersion|$secretsEpoch"
        val nonce = SyncCrypto.randomBytes(12)
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deviceKey(), GCMParameterSpec(128, nonce))
            updateAAD(metadata.toByteArray(Charsets.UTF_8))
        }.doFinal(dek)
        preferences.edit()
            .putString(KEY_METADATA, metadata)
            .putString(KEY_NONCE, encodeUrlBase64(nonce))
            .putString(KEY_CIPHERTEXT, encodeUrlBase64(ciphertext))
            .apply()
    }

    private fun deviceKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build())
        }.generateKey()
    }

    private fun decodeUrlBase64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
    private fun encodeUrlBase64(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

    private inline fun <T> ByteArray.useBytes(block: (ByteArray) -> T): T = try { block(this) } finally { fill(0) }

    private companion object {
        const val PREFERENCES = "everytalk_sync_secrets"
        const val KEYSTORE_ALIAS = "everytalk_sync_dek_cache"
        const val KEY_METADATA = "metadata"
        const val KEY_NONCE = "nonce"
        const val KEY_CIPHERTEXT = "ciphertext"
    }
}
