package com.android.everytalk.data.sync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.*
import com.android.everytalk.data.computer.*
import com.android.everytalk.data.mcp.EncryptedMcpOAuthSecrets
import com.android.everytalk.data.mcp.McpOAuthProvider
import com.android.everytalk.data.skill.SkillSecretStore
import com.android.everytalk.data.skill.SkillSecretMetadata
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/** 两份 Room 使用真实 AES-GCM 和 AAD；仅把 Android 设备密钥缓存替换成内存，避免依赖真机。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SyncCredentialSynchronizerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databases = mutableListOf<AppDatabase>()
    private val user = UUID.randomUUID().toString()
    private val dek = ByteArray(32) { (it + 1).toByte() }
    private fun device() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build().also(databases::add)
    private fun secrets(): SyncSecretManager {
        var accepted = false
        return mockk {
            every { hasCachedDek(user, 1, 0) } answers { accepted }
            every { readCachedDek(user, 1, 0) } answers { if (accepted) dek.copyOf() else null }
            every { cachedKeyVersion(user, 0) } answers { if (accepted) 1L else null }
            every { acceptAccountKey(user, any()) } answers { assertEquals(java.util.Base64.getEncoder().encodeToString(dek), secondArg<AccountSyncKey>().dek); accepted = true }
            every { encryptField(user, any(), any(), any(), 1, 0, any()) } answers {
                SyncCrypto.encryptJson(dek, arg(6), SyncCrypto.aad(user, arg(1), arg(2), arg(3), 1, 0), 1)
            }
            every { decryptField(user, any(), any(), any(), 0, any()) } answers {
                runCatching { SyncCrypto.decryptJson(dek, syncJson.decodeFromJsonElement(SyncEncryptedEnvelope.serializer(), arg(5)),
                    SyncCrypto.aad(user, arg(1), arg(2), arg(3), 1, 0)) }.getOrNull()
            }
        }
    }
    @After fun close() { databases.forEach { it.close() }; unmockkAll(); dek.fill(0) }

    @Test fun `账号自动恢复模型Key与MCP头且清空Key不复活也不回传`() = runBlocking {
        val a = device(); val b = device(); val sa = secrets(); val sb = secrets(); val cloud = FakeSyncApi()
        val api = object : SyncApi by cloud {
            override suspend fun recoverAccountKey(epoch: Long, existingDek: String?) = AccountSyncKey(1, 0, java.util.Base64.getEncoder().encodeToString(dek))
        }
        val id = UUID.randomUUID().toString()
        val config = ApiConfig("https://example.org/v1", "test-key-only", "model", "OpenAI", id, "模型").toEntity()
        a.apiConfigDao().insertConfigs(listOf(config))
        a.mcpConfigDao().insertConfig(McpServerConfigEntity("mcp-example", "MCP", "https://example.org/mcp", headers = """[["Authorization","Bearer test-only"]]"""))
        suspend fun sync(db: AppDatabase, secret: SyncSecretManager) {
            val credentials = SyncCredentialSynchronizer(context, db, api, secret, user)
            SyncEngine(api, RoomSyncStore(db, secret, user), SyncStateStore(db), beforePull = { credentials.captureLocal() }, afterPull = { credentials.recover() }).runOnce()
        }
        sync(a, sa); sync(b, sb)
        assertEquals(config.key, b.apiConfigDao().getTextConfig(id)!!.key)
        assertEquals(a.mcpConfigDao().getAllSnapshot().single().headers, b.mcpConfigDao().getAllSnapshot().single().headers)
        assertFalse(cloud.rows.values.joinToString().contains("test-key-only"))
        val pushes = cloud.pushes
        sync(b, sb); assertEquals(pushes, cloud.pushes)
        // 已安装旧版的失败信封也要修复，不能只修将来新建的信封。
        val row = a.settingsDao().getUserSetting("api_configs", id)!!
        val value = syncJson.parseToJsonElement(row.value).jsonObject
        val incomplete = JsonObject(value.getValue("apiKey").jsonObject.filterKeys { it !in setOf("\$enc", "alg") })
        a.settingsDao().upsertUserSettings(listOf(row.copy(value = JsonObject(value + ("apiKey" to incomplete)).toString(), syncStatus = SyncStatus.LOCAL_ONLY)))
        sync(a, sa); sync(b, sb)
        assertTrue(SyncContractSanitizer.isValidEnvelope(syncJson.parseToJsonElement(a.settingsDao().getUserSetting("api_configs", id)!!.value).jsonObject.getValue("apiKey")))
        assertEquals(config.key, b.apiConfigDao().getTextConfig(id)!!.key)
        a.apiConfigDao().insertConfigs(listOf(config.copy(key = "")))
        SyncSettingsProjector(a, sa, user, 0).refresh()
        sync(a, sa); sync(b, sb)
        assertEquals("", b.apiConfigDao().getTextConfig(id)!!.key)
        sync(b, sb); assertEquals("", b.apiConfigDao().getTextConfig(id)!!.key)
        assertTrue(RoomSyncStore(b, sb, user).pendingBatch().isEmpty())
    }

    @Test fun `换账号移除旧密文和文件引用但保留本机配置`() = runBlocking {
        val db = device(); val id = UUID.randomUUID().toString()
        db.settingsDao().insertSetting(SystemSettingEntity(SyncStateStore.KEY_ACCOUNT, user))
        db.settingsDao().upsertUserSettings(listOf(UserSettingEntity("api_configs", id,
            buildJsonObject { put("name", "本机模型"); put("apiKey", SyncCrypto.encryptJson(dek, JsonPrimitive("private"), SyncCrypto.aad(user, "api_configs", id, "/apiKey", 1, 0), 1)) }.toString()),
            UserSettingEntity("skills", "example", """{"fileRefs":["old-account-file"],"manifestRef":{},"name":"技能"}""")))
        SyncStateStore(db).prepareAccount(UUID.randomUUID().toString(), true)
        val rows = db.settingsDao().getAllUserSettings().joinToString()
        assertFalse(rows.contains("\"ct\"")); assertFalse(rows.contains("old-account-file"))
        assertTrue(rows.contains("本机模型")); assertTrue(rows.contains("技能"))
        assertEquals(-1L, SyncStateStore(db).readSecretsEpoch())
    }

    @Test fun `SSH和OAuth及技能变量跨设备恢复且撤销不复活坏凭据不阻断其他项目`() = runBlocking {
        val a = device(); val b = device(); val sa = secrets(); val sb = secrets(); val cloud = FakeSyncApi()
        val sshId = UUID.randomUUID().toString(); val cfId = UUID.randomUUID().toString()
        val api = object : SyncApi by cloud {
            override suspend fun recoverAccountKey(epoch: Long, existingDek: String?) = AccountSyncKey(1, 0, java.util.Base64.getEncoder().encodeToString(dek))
        }
        val ssh = Computer(id = sshId, displayName = "SSH", host = "ssh.example.org", username = "user", port = 22, authKind = ComputerAuthKind.PASSWORD, runMode = ComputerRunMode.DIRECT).toEntity(syncJson)
        val cf = ssh.copy(id = cfId, displayName = "CF", provider = ComputerProvider.CLOUDFLARE.name)
        a.computerDao().upsertComputer(ssh); a.computerDao().upsertComputer(cf)
        val authorization = CloudflareAuthorizationEntity("auth", "cf:credential", "[]", 1, null, false, 1)
        a.computerDao().upsertCloudflareAuthorization(authorization)
        a.computerDao().upsertCloudflareConfig(CloudflareComputerConfigEntity(cf.id, authorization.authorizationId, "account", "CF", "{}"))
        a.mcpConfigDao().insertConfig(McpServerConfigEntity("mcp-github", "GitHub", McpOAuthProvider.GITHUB.endpoint))
        val skill = SkillInstallationEntity("vars", "变量测试", "", "LOCAL_IMPORT", null, null, "hash", true, "AUTO", null, 1, 1, null, 0)
        a.skillDao().upsertInstallation(skill)
        val stores = listOf(a, b).map { db ->
            val secretValues = mutableMapOf<String, String>()
            val store = mockk<ComputerCredentialStore>(relaxed = true) {
                coEvery { loadComputerCredential(any()) } answers { secretValues[firstArg<String>()]?.let { ComputerCredential.Password(it.toCharArray()) }
                    ?: throw ComputerException(ComputerErrorCodes.CREDENTIAL_MISSING, "test missing") }
                coEvery { saveComputerCredential(any(), any()) } coAnswers { secretValues[firstArg()] = (secondArg<ComputerCredential>() as ComputerCredential.Password).password.concatToString() }
                coEvery { deleteComputerCredential(any()) } coAnswers { secretValues.remove(firstArg<String>()); Unit }
                coEvery { loadOriginalComputerCredential(any()) } returns null
                coEvery { loadComputerSudoPassword(any()) } returns null
                coEvery { loadAgentAuthorization(any()) } answers { secretValues[firstArg<String>()]?.toCharArray() }
                coEvery { saveAgentAuthorization(any(), any()) } coAnswers { secretValues[firstArg()] = secondArg<CharArray>().concatToString() }
                coEvery { deleteAgentAuthorization(any()) } coAnswers { secretValues.remove(firstArg<String>()); Unit }
            }
            val tokens = mutableMapOf<String, String>()
            val oauth = mockk<EncryptedMcpOAuthSecrets> {
                coEvery { read(any()) } answers { tokens[firstArg()] }
                coEvery { write(any(), any()) } coAnswers { tokens[firstArg()] = secondArg() }
                coEvery { remove(any()) } coAnswers { tokens.remove(firstArg<String>()); Unit }
            }
            val vars = mutableMapOf<String, String>()
            val variables = mockk<SkillSecretStore> {
                coEvery { list(any()) } answers { vars.keys.map { SkillSecretMetadata(firstArg(), it, 1) } }
                coEvery { deleted() } returns emptyList()
                coEvery { load(any(), any()) } answers { vars[secondArg()]?.toCharArray() }
                coEvery { save(any(), any(), any(), any()) } coAnswers { vars[secondArg()] = thirdArg<CharArray>().concatToString() }
                coEvery { delete(any(), any()) } coAnswers { vars.remove(secondArg<String>()); Unit }
            }
            if (db === a) { secretValues[sshId] = "ssh-test-only"; secretValues["cf:credential"] = "cf-test-only"; tokens["token:github"] = "oauth-test-only"; vars["TEST_VALUE"] = "variable-test-only" }
            Triple(store, oauth, variables)
        }
        suspend fun sync(index: Int) {
            val db = listOf(a, b)[index]; val secret = listOf(sa, sb)[index]; val storesForDevice = stores[index]
            val credentials = SyncCredentialSynchronizer(context, db, api, secret, user, storesForDevice.first, storesForDevice.second, storesForDevice.third)
            SyncEngine(api, RoomSyncStore(db, secret, user), SyncStateStore(db), beforePull = { credentials.captureLocal() }, afterPull = { credentials.recover() }).runOnce()
        }
        sync(0)
        val captured = a.settingsDao().getUserSetting("computers", sshId)!!
        assertTrue("SSH 未封存，状态=${captured.syncStatus}，错误=${a.settingsDao().getValue("sync_rejected:user_settings:${captured.uuid}")}，字段=${syncJson.parseToJsonElement(captured.value).jsonObject.keys}",
            "sshCredential" in syncJson.parseToJsonElement(captured.value).jsonObject)
        sync(1)
        val restoredSsh = runCatching { stores[1].first.loadComputerCredential(sshId) }.getOrNull()
        val remote = b.settingsDao().getUserSetting("computers", sshId)!!
        assertNotNull("SSH 未恢复，状态=${remote.syncStatus}，错误=${b.settingsDao().getValue("sync_rejected:user_settings:${remote.uuid}")}", restoredSsh)
        assertEquals("ssh-test-only", (restoredSsh as ComputerCredential.Password).password.concatToString())
        assertEquals("cf-test-only", stores[1].first.loadAgentAuthorization("cf:credential")!!.concatToString())
        assertEquals("oauth-test-only", stores[1].second.read("token:github"))
        assertEquals("variable-test-only", stores[1].third.load("vars", "TEST_VALUE")!!.concatToString())
        assertEquals(ComputerStatus.DISCONNECTED.name, b.computerDao().getComputer(sshId)!!.status)
        assertFalse(cloud.rows.values.joinToString().contains("test-only"))
        val pushes = cloud.pushes; sync(1); assertEquals(pushes, cloud.pushes)

        a.computerDao().revokeCloudflareAuthorization("auth")
        val revision = a.settingsDao().getUserSetting("computers", cfId)!!.updatedAt + 10
        a.computerDao().upsertComputer(cf.copy(updatedAt = revision))
        sync(0); sync(1)
        assertTrue(b.computerDao().getCloudflareAuthorization("auth")!!.revoked)
        assertNull(stores[1].first.loadAgentAuthorization("cf:credential"))
        sync(1); assertTrue(RoomSyncStore(b, sb, user).pendingBatch().isEmpty())

        // 一把设备凭据损坏时仍恢复其余项目，且不能把损坏误判成删除上送。
        coEvery { stores[0].first.loadComputerCredential(sshId) } throws IllegalStateException("不应出现在日志中的内容")
        sync(0)
        val row = a.settingsDao().getUserSetting("computers", sshId)!!
        assertEquals("CREDENTIAL_STORAGE_FAILED", a.settingsDao().getValue("sync_rejected:user_settings:${row.uuid}"))
        assertNotEquals(JsonNull, syncJson.parseToJsonElement(row.value).jsonObject["sshCredential"])
    }

    @Test fun `MCP授权晚于配置恢复时重连一次普通刷新不重连`() = runBlocking {
        val db = device()
        val config = McpOAuthProvider.GITHUB.defaultConfig()
        db.mcpConfigDao().insertConfig(McpServerConfigEntity.fromModel(config))
        mockkObject(AppDatabase.Companion)
        every { AppDatabase.getDatabase(any()) } returns db
        mockkConstructor(com.android.everytalk.data.mcp.McpClientManager::class)
        every { anyConstructed<com.android.everytalk.data.mcp.McpClientManager>().serverStates } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
        val connected = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        coEvery { anyConstructed<com.android.everytalk.data.mcp.McpClientManager>().addServer(any()) } coAnswers { connected.send(Unit) }
        coEvery { anyConstructed<com.android.everytalk.data.mcp.McpClientManager>().removeServer(any()) } just Runs
        every { anyConstructed<com.android.everytalk.data.mcp.McpClientManager>().close() } just Runs
        val manager = com.android.everytalk.statecontroller.viewmodel.McpManager(context)
        try {
            kotlinx.coroutines.withTimeout(5000) { connected.receive() }
            val row = UserSettingEntity("mcp_servers", syncStableSettingKey("mcp_servers", config.id), "{}", syncStatus = SyncStatus.SYNCED)
            db.settingsDao().upsertUserSettings(listOf(row))
            db.settingsDao().insertSetting(SystemSettingEntity("sync_private_applied:${row.uuid}", "changed"))
            SyncLocalChanges.notifyChanged()
            kotlinx.coroutines.withTimeout(5000) { connected.receive() }
            coVerify(exactly = 1) { anyConstructed<com.android.everytalk.data.mcp.McpClientManager>().removeServer(config.id) }
            SyncLocalChanges.notifyChanged()
            assertNull(kotlinx.coroutines.withTimeoutOrNull(300) { connected.receive() })
        } finally { manager.close(); connected.close() }
    }
}
