package com.android.everytalk.data.sync

import android.content.Context
import androidx.room.withTransaction
import com.android.everytalk.data.computer.*
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.*
import com.android.everytalk.data.mcp.EncryptedMcpOAuthSecrets
import com.android.everytalk.data.mcp.McpOAuthProvider
import com.android.everytalk.data.mcp.McpMailProvider
import com.android.everytalk.data.skill.SkillSecretStore
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException

/**
 * 只有 Worker 通过账号绑定检查后才能调用。原设备解密本机 Keystore 内容，使用账号 DEK
 * 重新加密；另一设备解密后存入自己的 Keystore，绝不复制原设备的加密文件。
 */
internal class SyncCredentialSynchronizer(
    context: Context, private val db: AppDatabase, private val api: SyncApi,
    private val secrets: SyncSecretManager, private val user: String,
    private val credentials: ComputerCredentialStore = ComputerCredentialStore(context, recordSyncChanges = false),
    private val oauth: EncryptedMcpOAuthSecrets = EncryptedMcpOAuthSecrets(context, recordSyncChanges = false),
    private val variables: SkillSecretStore = SkillSecretStore(context, recordSyncChanges = false),
) {
    private val context = context.applicationContext
    private val settings = db.settingsDao()
    private val mutations = context.getSharedPreferences("sync_credential_mutations", Context.MODE_PRIVATE)

    /** 联网前封存游客期间的凭据修改，不能先 pull 把它覆盖；没有旧密钥时等待账号恢复。 */
    suspend fun captureLocal() {
        val epoch = SyncStateStore(db).readSecretsEpoch()
        if (epoch >= 0 && secrets.hasCachedDek(user, epoch + 1, epoch)) capturePrivateFields(epoch)
    }

    suspend fun recover() {
        val epoch = SyncStateStore(db).readSecretsEpoch().coerceAtLeast(0)
        val cached = secrets.readCachedDek(user, epoch + 1, epoch)
        val existing = try { cached?.let { java.util.Base64.getEncoder().encodeToString(it) } } finally { cached?.fill(0) }
        val recovered = api.recoverAccountKey(epoch, existing)
        require(recovered.secretsEpoch == epoch)
        secrets.acceptAccountKey(user, recovered)
        validateEncryptedFields(epoch)
        capturePrivateFields(epoch)
        // 已拉到的 API/语音/搜索 Key 和 headers 此刻自动恢复，不需要页面解锁。
        RoomSyncStore(db, secrets, user).restoreUnlockedSettings()
        restorePrivateFields(epoch)
        capturePrivateFields(epoch)
        // 授权写完以后再通知连接层，避免页面先看到配置、却永远沿用此前的未认证连接。
        SyncLocalChanges.notifyChanged()
    }

    /** 损坏密文逐项提示，绝不记录值；保留本机 Key，并让其他项目继续恢复。 */
    private suspend fun validateEncryptedFields(epoch: Long) {
        for (row in settings.getAllUserSettings().filterNot { it.deleted || it.kind == "crypto_keys" }) {
            fun valid(value: JsonElement, pointer: String): Boolean = when (value) {
                is JsonObject -> if ("\$enc" in value) secrets.decryptField(user, row.kind, row.key, pointer, epoch, value) != null
                    else value.all { (name, child) -> valid(child, "$pointer/${name.replace("~", "~0").replace("/", "~1")}") }
                is JsonArray -> value.withIndex().all { (index, child) -> valid(child, "$pointer/$index") }
                else -> true
            }
            val marker = "sync_rejected:user_settings:${row.uuid}"
            if (!valid(syncJson.parseToJsonElement(row.value), "")) settings.insertSetting(SystemSettingEntity(marker, "CREDENTIAL_DECRYPTION_FAILED"))
            else if (settings.getValue(marker) == "CREDENTIAL_DECRYPTION_FAILED") settings.deleteSetting(marker)
        }
    }

    private suspend fun capturePrivateFields(epoch: Long) {
        for (computer in db.computerDao().getAllSnapshot()) isolate("computers", computer.id) {
            val key = computer.id
            val cloudflare = db.computerDao().getCloudflareConfig(key)
            if (cloudflare != null) {
                val auth = db.computerDao().getCloudflareAuthorization(cloudflare.authorizationId)
                val token = auth?.takeUnless { it.revoked }?.let { credentials.loadAgentAuthorization(it.credentialReference) }
                if (auth != null && token != null) try {
                    saveSecret("computers", key, "providerCredential", buildJsonObject {
                        put("config", syncJson.encodeToJsonElement(CloudflareComputerConfigEntity.serializer(), cloudflare))
                        put("authorization", syncJson.encodeToJsonElement(CloudflareAuthorizationEntity.serializer(), auth))
                        put("material", token.concatToString())
                    }, epoch, mutations.getLong("agent-authorization:${auth.credentialReference}", 0))
                } finally { token.fill('\u0000') }
                else if (auth?.revoked == true) saveSecret("computers", key, "providerCredential", JsonNull, epoch, computer.updatedAt)
                else if (auth != null) clearRemoved("computers", key, "providerCredential", "agent-authorization:${auth.credentialReference}", epoch)
                return@isolate
            }
            if (computer.hostKeyBlobBase64 != null) saveSecret("computers", key, "sshTrust", buildJsonObject {
                computer.hostKeyAlgorithm?.let { put("algorithm", it) }; put("blob", computer.hostKeyBlobBase64)
                computer.hostKeyFingerprint?.let { put("fingerprint", it) }
            }, epoch, computer.updatedAt)
            // 读取失败不能当作用户清空，否则会把仍然有效的云端凭据覆盖成删除。
            val current = try { credentials.loadComputerCredential(key) }
                catch (error: ComputerException) { if (error.code == ComputerErrorCodes.CREDENTIAL_MISSING) null else throw error }
            if (current != null) try {
                val encoded = ComputerCredentialCodec.encode(current)
                try { saveSecret("computers", key, "sshCredential", JsonPrimitive(java.util.Base64.getEncoder().encodeToString(encoded)), epoch, mutations.getLong("credential:$key", 0)) }
                finally { encoded.fill(0) }
            } finally { current.clear() }
            else clearRemoved("computers", key, "sshCredential", "credential:$key", epoch)
            val original = credentials.loadOriginalComputerCredential(key)
            if (original != null) try {
                val encoded = ComputerCredentialCodec.encode(original)
                try { saveSecret("computers", key, "originalCredential", JsonPrimitive(java.util.Base64.getEncoder().encodeToString(encoded)), epoch, mutations.getLong("original-credential:$key", 0)) }
                finally { encoded.fill(0) }
            } finally { original.clear() }
            else clearRemoved("computers", key, "originalCredential", "original-credential:$key", epoch)
            val sudo = credentials.loadComputerSudoPassword(key)
            sudo?.let { chars ->
                try { saveSecret("computers", key, "sudoPassword", JsonPrimitive(chars.concatToString()), epoch, mutations.getLong("sudo-password:$key", 0)) }
                finally { chars.fill('\u0000') }
            }
            if (sudo == null) clearRemoved("computers", key, "sudoPassword", "sudo-password:$key", epoch)
        }
        for (provider in McpOAuthProvider.entries) {
            val row = settings.getAllUserSettings().firstOrNull { it.kind == "mcp_servers" && !it.deleted &&
                (runCatching { syncJson.parseToJsonElement(it.value).jsonObject["localId"]?.jsonPrimitive?.content }.getOrNull() == provider.serverId) } ?: continue
            isolate(row.kind, row.key) {
            val token = oauth.read("token:${provider.key}")
            if (token != null) saveSecret("mcp_servers", row.key, "oauthCredential", JsonPrimitive(token), epoch, mutations.getLong("agent-authorization:mcp:token:${provider.key}", 0))
            else clearRemoved("mcp_servers", row.key, "oauthCredential", "agent-authorization:mcp:token:${provider.key}", epoch)
            }
        }
        for (provider in McpMailProvider.entries) {
            val row = settings.getAllUserSettings().firstOrNull { it.kind == "mcp_servers" && !it.deleted &&
                (runCatching { syncJson.parseToJsonElement(it.value).jsonObject["localId"]?.jsonPrimitive?.content }.getOrNull() == provider.serverId) } ?: continue
            isolate(row.kind, row.key) {
            val token = oauth.read("mail:${provider.key}")
            if (token != null) saveSecret("mcp_servers", row.key, "mailCredential", JsonPrimitive(token), epoch, mutations.getLong("agent-authorization:mcp:mail:${provider.key}", 0))
            else clearRemoved("mcp_servers", row.key, "mailCredential", "agent-authorization:mcp:mail:${provider.key}", epoch)
            }
        }
        for (skill in db.skillDao().getAll()) for (entry in variables.list(skill.skillId)) {
            val key = variableKey(skill.skillId, entry.name)
            isolate("skill_variables", key) {
            val chars = variables.load(skill.skillId, entry.name) ?: return@isolate
            try {
                val old = settings.getUserSetting("skill_variables", key)
                if (old == null) settings.upsertUserSettings(listOf(UserSettingEntity("skill_variables", key,
                    buildJsonObject { put("skillId", skill.skillId); put("name", entry.name) }.toString(), updatedAt = entry.updatedAt)))
                saveSecret("skill_variables", key, "value", JsonPrimitive(chars.concatToString()), epoch, entry.updatedAt)
            } finally { chars.fill('\u0000') }
            }
        }
        for (entry in variables.deleted()) {
            val key = variableKey(entry.skillId, entry.name)
            val old = settings.getUserSetting("skill_variables", key) ?: continue
            if (!old.deleted && entry.updatedAt >= old.updatedAt) settings.upsertUserSettings(listOf(old.copy(
                deleted = true, updatedAt = maxOf(entry.updatedAt, old.updatedAt + 1), syncStatus = SyncStatus.PENDING)))
        }
    }

    private suspend fun clearRemoved(kind: String, key: String, field: String, scope: String, epoch: Long) {
        val modified = mutations.getLong(scope, 0)
        if (modified > 0) saveSecret(kind, key, field, JsonNull, epoch, modified)
    }

    private suspend fun saveSecret(kind: String, key: String, name: String, plaintext: JsonElement, epoch: Long, modifiedAt: Long = 0) = db.withTransaction {
        val row = settings.getUserSetting(kind, key)?.takeUnless { it.deleted } ?: return@withTransaction
        val value = syncJson.parseToJsonElement(row.value).jsonObject
        val previous = value[name]
        if (row.syncStatus == SyncStatus.SYNCED && previous != null && modifiedAt <= row.updatedAt) return@withTransaction
        if (previous != null && SyncContractSanitizer.isValidEnvelope(previous) &&
            secrets.decryptField(user, kind, key, "/$name", epoch, previous) == plaintext) return@withTransaction
        val encrypted = secrets.encryptField(user, kind, key, "/$name", epoch + 1, epoch, plaintext) ?: return@withTransaction
        val next = JsonObject(value + (name to encrypted))
        SyncContractSanitizer.validate(next)
        val modified = maxOf(modifiedAt.takeIf { it > 0 } ?: row.updatedAt, row.updatedAt + 1)
        settings.upsertUserSettings(listOf(row.copy(value = next.toString(), updatedAt = modified,
            syncStatus = SyncStatus.PENDING)))
    }

    private suspend fun restorePrivateFields(epoch: Long) {
        for (row in settings.getAllUserSettings()) {
            val value = runCatching { syncJson.parseToJsonElement(row.value).jsonObject }.getOrNull() ?: continue
            fun plain(name: String): String? = value[name]?.let {
                (secrets.decryptField(user, row.kind, row.key, "/$name", epoch, it) as? JsonPrimitive)?.contentOrNull
            }
            // 未上传的本机修改优先；远端密文有变化时才写 Keystore，避免每轮刷新覆盖本机新凭据。
            if (row.syncStatus != SyncStatus.SYNCED) continue
            val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(row.value.toByteArray()).joinToString("") { "%02x".format(it) }
            val marker = "sync_private_applied:${row.uuid}"
            if (settings.getValue(marker) == fingerprint) continue
            if (row.deleted) {
                if (row.kind == "skill_variables") {
                    val identity = runCatching {
                        if ('.' in row.key) row.key.split('.', limit = 2).map { String(java.util.Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
                        else String(java.util.Base64.getUrlDecoder().decode(row.key), Charsets.UTF_8).split('\u0000', limit = 2)
                    }.getOrNull()
                    val skill = value["skillId"]?.jsonPrimitive?.contentOrNull ?: identity?.getOrNull(0)
                    val name = value["name"]?.jsonPrimitive?.contentOrNull ?: identity?.getOrNull(1)
                    if (skill != null && name != null) variables.delete(skill, name)
                }
                continue
            }
            isolate(row.kind, row.key) {
            when (row.kind) {
                "computers" -> {
                    value["providerCredential"]?.let { encrypted ->
                        val decoded = secrets.decryptField(user, row.kind, row.key, "/providerCredential", epoch, encrypted)
                        if (decoded == JsonNull) {
                            db.computerDao().getCloudflareConfig(row.key)?.let { config ->
                                db.computerDao().getCloudflareAuthorization(config.authorizationId)?.let { auth ->
                                    credentials.deleteAgentAuthorization(auth.credentialReference)
                                    db.computerDao().revokeCloudflareAuthorization(auth.authorizationId)
                                    db.computerDao().getComputer(row.key)?.let { computer -> db.computerDao().upsertComputer(computer.copy(
                                        credentialState = ComputerCredentialState.MISSING.name, status = ComputerStatus.AUTHORIZATION_REQUIRED.name)) }
                                }
                            }
                        }
                        val bundle = decoded as? JsonObject
                        if (bundle != null) {
                            val config = syncJson.decodeFromJsonElement(CloudflareComputerConfigEntity.serializer(), bundle.getValue("config"))
                            val auth = syncJson.decodeFromJsonElement(CloudflareAuthorizationEntity.serializer(), bundle.getValue("authorization"))
                            require(config.computerId == row.key && config.authorizationId == auth.authorizationId)
                            credentials.saveAgentAuthorization(auth.credentialReference, bundle.getValue("material").jsonPrimitive.content.toCharArray())
                            db.withTransaction {
                                db.computerDao().upsertCloudflareAuthorization(auth)
                                db.computerDao().upsertCloudflareConfig(config)
                                db.computerDao().getComputer(row.key)?.let { computer -> db.computerDao().upsertComputer(computer.copy(
                                    providerConfigRef = config.computerId, credentialState = ComputerCredentialState.ORIGINAL_ENCRYPTED.name,
                                    status = ComputerStatus.DISCONNECTED.name, lastErrorCode = null)) }
                            }
                        }
                    }
                    for (field in listOf("sshCredential", "originalCredential")) {
                        if (value[field]?.let { secrets.decryptField(user, row.kind, row.key, "/$field", epoch, it) } == JsonNull) {
                            if (field == "sshCredential") credentials.deleteComputerCredential(row.key) else credentials.deleteOriginalComputerCredential(row.key)
                            continue
                        }
                        val raw = plain(field) ?: continue
                        val bytes = SyncCrypto.decodeCanonicalBase64(raw)
                        // 云端凭据与本机一致时不重写，否则会把正常连接打回 DISCONNECTED 并再次触发上传。
                        val local = try {
                            if (field == "sshCredential") credentials.loadComputerCredential(row.key) else credentials.loadOriginalComputerCredential(row.key)
                        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                        val localBytes = local?.let { try { ComputerCredentialCodec.encode(it) } finally { it.clear() } }
                        val unchanged = localBytes != null && java.security.MessageDigest.isEqual(bytes, localBytes)
                        localBytes?.fill(0)
                        if (unchanged) { bytes.fill(0); continue }
                        val credential = try { ComputerCredentialCodec.decode(bytes) } finally { bytes.fill(0) }
                        try {
                            if (field == "sshCredential") {
                                credentials.saveComputerCredential(row.key, credential)
                                // authKind 是用户原始登录方式，由普通配置同步负责；专用私钥不能覆盖它。
                                db.computerDao().getComputer(row.key)?.let { computer -> db.computerDao().upsertComputer(computer.copy(
                                    credentialState = ComputerCredentialState.ORIGINAL_ENCRYPTED.name,
                                    status = ComputerStatus.DISCONNECTED.name, lastErrorCode = null)) }
                            }
                            else credentials.saveOriginalComputerCredential(row.key, credential)
                        } finally { credential.clear() }
                    }
                    plain("sudoPassword")?.let { credentials.saveComputerSudoPassword(row.key, it.toCharArray()) }
                    if (value["sudoPassword"]?.let { secrets.decryptField(user, row.kind, row.key, "/sudoPassword", epoch, it) } == JsonNull)
                        credentials.deleteComputerSudoPassword(row.key)
                    value["sshTrust"]?.let { encrypted ->
                        val trust = secrets.decryptField(user, row.kind, row.key, "/sshTrust", epoch, encrypted)?.jsonObject
                        if (trust != null) db.computerDao().getComputer(row.key)?.let { computer -> db.computerDao().upsertComputer(computer.copy(
                            hostKeyAlgorithm = trust["algorithm"]?.jsonPrimitive?.contentOrNull,
                            hostKeyBlobBase64 = trust["blob"]?.jsonPrimitive?.contentOrNull,
                            hostKeyFingerprint = trust["fingerprint"]?.jsonPrimitive?.contentOrNull)) }
                    }
                }
                "mcp_servers" -> {
                    val localId = value["localId"]?.jsonPrimitive?.contentOrNull
                    val provider = McpOAuthProvider.entries.firstOrNull { it.serverId == localId }
                    if (provider != null) plain("oauthCredential")?.let { oauth.write("token:${provider.key}", it) }
                    if (provider != null && value["oauthCredential"]?.let { secrets.decryptField(user, row.kind, row.key, "/oauthCredential", epoch, it) } == JsonNull) oauth.remove("token:${provider.key}")
                    val mailProvider = McpMailProvider.entries.firstOrNull { it.serverId == localId }
                    if (mailProvider != null) plain("mailCredential")?.let { oauth.write("mail:${mailProvider.key}", it) }
                    if (mailProvider != null && value["mailCredential"]?.let { secrets.decryptField(user, row.kind, row.key, "/mailCredential", epoch, it) } == JsonNull) oauth.remove("mail:${mailProvider.key}")
                }
                "skill_variables" -> {
                    val skillId = value["skillId"]?.jsonPrimitive?.contentOrNull
                    val name = value["name"]?.jsonPrimitive?.contentOrNull
                    if (skillId != null && name != null) plain("value")?.let { variables.save(skillId, name, it.toCharArray(), row.updatedAt) }
                }
            }
            settings.insertSetting(SystemSettingEntity(marker, fingerprint))
            }
        }
    }

    /** 单项本地存储损坏不能挡住整批恢复；只记录种类和逻辑标识，不记录异常正文或凭据。 */
    private suspend fun isolate(kind: String, key: String, block: suspend () -> Unit) {
        try {
            block()
            settings.getUserSetting(kind, key)?.let { row ->
                val marker = "sync_rejected:user_settings:${row.uuid}"
                if (settings.getValue(marker) == "CREDENTIAL_STORAGE_FAILED") settings.deleteSetting(marker)
            }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            settings.getUserSetting(kind, key)?.let { row ->
                settings.insertSetting(SystemSettingEntity("sync_rejected:user_settings:${row.uuid}", "CREDENTIAL_STORAGE_FAILED"))
            }
        }
    }

    /** 按合同分别编码再用点连接，变量名仍可由 key 还原；值只存在于密文信封中。 */
    private fun variableKey(skill: String, name: String): String = listOf(skill, name).joinToString(".") {
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
    }.also { require(it.toByteArray(Charsets.UTF_8).size <= 512) }
}
