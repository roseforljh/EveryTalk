package com.android.everytalk.data.sync

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.*
import com.android.everytalk.data.agent.CloudAgentStore
import com.android.everytalk.data.agent.CloudRunSnapshot
import com.android.everytalk.data.agent.CloudRunView
import com.android.everytalk.data.computer.ComputerCredentialStore
import javax.crypto.spec.SecretKeySpec
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.*
import com.android.everytalk.data.skill.SkillPackageValidator
import com.android.everytalk.models.SelectedMediaItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** 两份真实 Room 和两个独立私有目录，验证完整资源恢复及拉取不回传。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SyncResourceSynchronizerTest {
    private val application = ApplicationProvider.getApplicationContext<Context>()
    private val databases = mutableListOf<AppDatabase>()
    private val directories = mutableListOf<File>()
    private val user = UUID.randomUUID().toString()
    private fun context(): Context {
        val directory = Files.createTempDirectory("everytalk-resource-test").toFile().also(directories::add)
        return object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getNoBackupFilesDir(): File = File(directory, "no-backup").apply { mkdirs() }
        }
    }
    private suspend fun database(): AppDatabase = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build().also {
        databases += it; SyncStateStore(it).recordSecretsEpoch(0)
    }
    private suspend fun sync(db: AppDatabase, api: SyncApi) = SyncEngine(api, RoomSyncStore(db), SyncStateStore(db)).runOnce()
    @After fun close() { databases.forEach { it.close() }; directories.forEach { it.deleteRecursively() } }

    @Test fun `跨设备恢复完整历史时同步摘要边界而不重新压缩旧原文`() = runBlocking {
        val a = database(); val b = database(); val ca = context(); val cb = context(); val api = FakeSyncApi()
        val session = ChatSessionEntity("compacted-session", 1, 1, false)
        a.chatDao().writeSession(session)
        val old = Message(id = "old-question", text = "旧工具历史".repeat(7000), sender = Sender.User, timestamp = 1).toEntity(session.id)
        val answer = Message(id = "old-answer", text = "已完成", sender = Sender.AI, timestamp = 2).toEntity(session.id)
        val next = Message(id = "new-question", text = "继续", sender = Sender.User, timestamp = 3).toEntity(session.id)
        a.chatDao().writeMessages(listOf(old, answer, next).map { it.copy(syncStatus = SyncStatus.PENDING) })
        // 与真实 Agent 会话一样，气泡会展开成稳定的 assistant/tool 内部记录；摘要必须覆盖这份原文。
        val run = AgentRunEntity("compacted-run", session.id, old.id, answer.id, null, null, "COMPLETED", 0, null, 1, 2)
        a.agentDao().upsertRun(run)
        a.agentDao().upsertEntry(AgentEntryEntity("assistant-entry", run.id, 1, "ASSISTANT", null, null,
            """[{"type":"tool_call","id":"old-call","name":"exec","arguments":{}}]""", "FINAL", 1, 2))
        a.agentDao().upsertEntry(AgentEntryEntity("tool-entry", run.id, 2, "TOOL_RESULT", null, "old-call",
            syncJson.encodeToString(com.android.everytalk.data.agent.AgentContentBlock.ToolResult.serializer(),
                com.android.everytalk.data.agent.AgentContentBlock.ToolResult("old-call", "exec", JsonPrimitive(old.text), false)), "FINAL", 1, 2))
        val answerWithTrace = answer.toMessage().copy(executionSteps = listOf(ExecutionStep("old-call", ExecutionStepType.Tool, "exec"))).toEntity(session.id)
        a.chatDao().writeMessages(listOf(answerWithTrace.copy(syncStatus = SyncStatus.PENDING)))
        val manager = com.android.everytalk.data.agent.AgentContextManager()
        val plan = com.android.everytalk.data.agent.AgentCompactionPlan(
            messagesToSummarize = emptyList(), previousSummary = null, summarizedThroughItemId = "tool:old-call",
            prefixFingerprint = "", retainedTailIds = listOf(next.id), sourcePrefixIds = listOf(old.id, "assistant:assistant-entry", "tool:old-call"),
            summaryRole = "system", tokensBefore = 30_000,
        )
        val checkpoint = com.android.everytalk.data.agent.AgentRunStore(a.agentDao()).saveCompaction(
            session.id, "config", plan, "旧任务已完成", "summary-request", 100, manager.compactionMetadata(plan))
        fun request(messages: List<MessageEntity>) = ChatRequest(
            messages = messages.map { SimpleTextApiMessage(it.id, if (it.sender == Sender.User) "user" else "assistant", it.text) },
            provider = "OpenAI", channel = "OpenAI兼容", apiAddress = "https://example.com", apiKey = "test", model = "test",
            contextManagement = RequestContextManagement("config", 8_000, 500, 7_000, true),
        )
        val limits = ModelTokenLimits(maxOutputTokens = 500, maxContextTokens = 8_000)
        val sourceRequest = request(listOf(old, answer, next))
        val before = manager.prepare("source", sourceRequest.copy(messages = com.android.everytalk.data.agent.AgentRunStore(a.agentDao())
            .expandTranscript(session.id, sourceRequest.messages)), limits, checkpoint)
        assertNull(before.compactionPlan)
        SyncResourceSynchronizer(ca, a, api, user).use { it.synchronize() }
        assertEquals(1, a.agentDao().getSyncableCompactions().size)
        assertTrue(a.settingsDao().getByPrefix("sync_resource_meta:").any { it.value.contains("context_compaction") })
        sync(a, api); sync(b, api)
        assertTrue(api.rows.values.any { it.payload["role"]?.jsonPrimitive?.content == "context_compaction" })
        assertNotNull(b.chatDao().getMessageByUuid(next.uuid))
        SyncResourceSynchronizer(cb, b, api, user).use { it.synchronize() }
        assertEquals(b.settingsDao().getByPrefix("sync_rejected:").toString(), 1, b.agentDao().getSyncableCompactions().size)
        val restored = b.agentDao().getCompactions("cloud:${session.uuid}").single()
        val targetRequest = request(b.chatDao().getMessagesForSession("cloud:${session.uuid}"))
        val targetTranscript = com.android.everytalk.data.agent.AgentRunStore(b.agentDao()).expandTranscript("cloud:${session.uuid}", targetRequest.messages)
        assertTrue(targetTranscript.any { it.id == "tool:old-call" })
        val after = manager.prepare("target", targetRequest.copy(messages = targetTranscript), limits, restored)
        assertNull(after.compactionPlan)
        assertEquals(before.snapshot.activeContextTokens, after.snapshot.activeContextTokens)
        assertEquals("tool:old-call", restored.summarizedThroughItemId)
        assertFalse(after.messages.filterIsInstance<SimpleTextApiMessage>().any { it.content.contains(old.text) })
        assertEquals(old.text, b.chatDao().getMessageByUuid(old.uuid)!!.text)
        val uploads = api.fileUploads
        SyncResourceSynchronizer(ca, a, api, user).use { it.synchronize() }
        SyncResourceSynchronizer(cb, b, api, user).use { it.synchronize() }
        assertEquals(uploads, api.fileUploads)
        assertEquals("confirmed", a.settingsDao().getValue("sync_compaction_uploaded:$user:${checkpoint.id}"))
        assertTrue(a.settingsDao().getByPrefix("sync_compaction_queued:").isEmpty())
        val resourceRoot = File(ca.filesDir, "sync-resources/${UUID.nameUUIDFromBytes(user.toByteArray())}")
        assertTrue(resourceRoot.listFiles().orEmpty().none { it.name.startsWith("compaction-") })
        // 服务端清空后重新上传仍携带摘要；主动清本地文字后不能留下阻止恢复的去重标记。
        SyncStateStore(a).markAllLocalDataPending()
        assertNull(a.settingsDao().getValue("sync_compaction_uploaded:$user:${checkpoint.id}"))
        SyncStateStore(b).clearSyncableLocalData()
        assertTrue(b.settingsDao().getByPrefix("sync_compaction_restored:").isEmpty())
        assertTrue(b.settingsDao().getByPrefix("sync_compaction_uploaded:").isEmpty())
    }

    @Test fun `摘要分块中断可续传且失效引用无缓存时重建后才清错误`() = runBlocking {
        val db = database(); val ctx = context(); val api = FakeSyncApi().apply { validateFileReferences = true; chunkSize = 1024 }
        val session = ChatSessionEntity("summary-retry", 1, 1, false)
        db.chatDao().writeSession(session)
        val message = Message(id = "summary-anchor", text = "继续", sender = Sender.User, timestamp = 1).toEntity(session.id)
        db.chatDao().writeMessages(listOf(message.copy(syncStatus = SyncStatus.PENDING)))
        val checkpoint = AgentCompactionEntryEntity("retry-checkpoint", session.id, null,
            (1..300).joinToString { UUID.randomUUID().toString() }, message.id, "", "{}", 10_000, 500, null, "COMPLETED", 2)
        db.agentDao().upsertCompaction(checkpoint)
        val marker = "sync_compaction_uploaded:$user:${checkpoint.id}"
        var remaining = 1
        val limited = object : SyncApi by api {
            override suspend fun uploadFile(file: File, mime: String): SyncFileInfo {
                if (remaining-- <= 0) throw SyncHttpException(429, 30, "RATE_LIMITED", "模拟摘要分块中断")
                return api.uploadFile(file, mime)
            }
        }
        try { SyncResourceSynchronizer(ctx, db, limited, user).use { it.synchronize() }; fail("应在传完全部摘要前中断") }
        catch (error: SyncHttpException) { assertEquals(429, error.statusCode) }
        assertEquals(1, api.fileUploads)
        assertNull(db.settingsDao().getValue(marker))
        SyncResourceSynchronizer(ctx, db, api, user).use { it.synchronize() }
        assertEquals("queued", db.settingsDao().getValue(marker))
        val previous = db.syncAttachmentDao().getPending(100).filter { it.messageId == message.uuid }
        assertTrue(previous.size > 1)
        assertEquals(previous.size, api.fileUploads)
        File(ctx.filesDir, "sync-resources").deleteRecursively()
        previous.forEach { api.expireFile(it.fileId!!) }
        sync(db, api)
        assertEquals("FILE_NOT_READY", db.settingsDao().getValue("sync_rejected:attachments:${previous.first().id}"))
        SyncResourceSynchronizer(ctx, db, api, user).use { it.synchronize() }
        assertEquals("queued", db.settingsDao().getValue(marker))
        assertEquals("FILE_NOT_READY", db.settingsDao().getValue("sync_rejected:attachments:${previous.first().id}"))
        api.failPushNumber = api.pushes + 1
        try { sync(db, api); fail("模拟已接收引用但确认丢失") } catch (_: java.io.IOException) { }
        assertEquals("queued", db.settingsDao().getValue(marker))
        sync(db, api)
        SyncResourceSynchronizer(ctx, db, api, user).use { it.synchronize() }
        sync(db, api)
        assertEquals("confirmed", db.settingsDao().getValue(marker))
        assertTrue(previous.all { db.syncAttachmentDao().get(it.id)!!.deleted })
        assertEquals(0, db.settingsDao().syncRejectedCount())
        val uploaded = api.fileUploads
        SyncResourceSynchronizer(ctx, db, api, user).use { it.synchronize() }
        assertEquals(uploaded, api.fileUploads)
    }

    @Test fun `文件引用过期后从上传块定向恢复且确认前保留失败`() = runBlocking {
        val db = database(); val ctx = context(); val api = FakeSyncApi().apply { validateFileReferences = true }
        val session = ChatSessionEntity("source", 1, 1, false)
        db.chatDao().writeSession(session)
        val image = File(ctx.filesDir, "repair.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val message = Message(id = "repair", text = "图片", sender = Sender.User,
            attachments = listOf(SelectedMediaItem.ImageFromUri(android.net.Uri.fromFile(image), "media", "image/png", image.absolutePath))).toEntity(session.id)
        db.chatDao().writeMessages(listOf(message))
        SyncResourceSynchronizer(ctx, db, api, user).use { it.synchronize() }
        val old = db.syncAttachmentDao().getPending(10).single()
        assertNotNull(db.settingsDao().getValue("sync_resource_queued:${message.uuid}"))
        assertNull(db.settingsDao().getValue("sync_resource_source:${message.uuid}"))
        // 模拟上传成功后离线跨 TTL；原文件也不在，但持久化块仍能修复。
        api.expireFile(old.fileId!!); image.delete()
        sync(db, api)
        assertEquals("FILE_NOT_READY", db.settingsDao().getValue("sync_rejected:attachments:${old.id}"))
        val limited = object : SyncApi by api {
            override suspend fun uploadFile(file: File, mime: String): SyncFileInfo =
                throw SyncHttpException(429, 30, "RATE_LIMITED", "模拟修复时限流")
        }
        try { SyncResourceSynchronizer(ctx, db, limited, user).use { it.synchronize() }; fail("应保留限流供 Worker 续传") }
        catch (error: SyncHttpException) { assertEquals(429, error.statusCode) }
        assertNull(db.settingsDao().getValue("sync_resource_retry:${old.id}"))
        SyncResourceSynchronizer(ctx, db, api, user).use { it.synchronize() }
        val repaired = db.syncAttachmentDao().get(old.id)!!
        assertNotEquals(old.fileId, repaired.fileId)
        assertEquals(SyncStatus.PENDING, repaired.syncStatus)
        assertEquals("FILE_NOT_READY", db.settingsDao().getValue("sync_rejected:attachments:${old.id}"))
        sync(db, api)
        // 同内容的权威引用可采用另一预约身份；收尾比较内容哈希，不能永久等旧 fileId。
        val accepted = db.syncAttachmentDao().get(old.id)!!
        val cached = File(ctx.filesDir, "sync-resources/${UUID.nameUUIDFromBytes(user.toByteArray())}/${accepted.fileId}-${accepted.sha256}")
        api.expireFile(accepted.fileId!!)
        val canonical = api.uploadFile(cached, accepted.mime)
        db.syncAttachmentDao().upsert(accepted.copy(fileId = canonical.fileId))
        SyncResourceSynchronizer(ctx, db, api, user).use { it.synchronize() }
        assertEquals(0, db.settingsDao().syncRejectedCount())
        assertNotNull(db.settingsDao().getValue("sync_resource_source:${message.uuid}"))
        assertNull(db.settingsDao().getValue("sync_resource_queued:${message.uuid}"))
        val uploaded = api.fileUploads
        SyncResourceSynchronizer(ctx, db, api, user).use { it.synchronize() }; sync(db, api)
        assertEquals(uploaded, api.fileUploads)
    }

    @Test fun `旧版已完成标记与缺失块缓存仍可从原件定向修复`() = runBlocking {
        val db = database(); val ctx = context(); val api = FakeSyncApi().apply { validateFileReferences = true }
        val session = ChatSessionEntity("source", 1, 1, false); db.chatDao().writeSession(session)
        val image = File(ctx.filesDir, "legacy.png").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val message = Message(id = "legacy", text = "原件仍在", sender = Sender.User,
            attachments = listOf(SelectedMediaItem.ImageFromUri(android.net.Uri.fromFile(image), "legacy-media", "image/png", image.absolutePath))).toEntity(session.id)
        db.chatDao().writeMessages(listOf(message))
        SyncResourceSynchronizer(ctx, db, api, user).use { it.synchronize() }
        val row = db.syncAttachmentDao().getPending(10).single()
        // 旧版提前写 source 并清临时块；本机原图仍可恢复，不能因 source 已存在永久跳过。
        db.settingsDao().deleteSetting("sync_resource_queued:${message.uuid}")
        db.settingsDao().insertSetting(SystemSettingEntity("sync_resource_source:${message.uuid}", "$user:${message.storageFingerprint}"))
        File(ctx.filesDir, "sync-resources").deleteRecursively()
        api.expireFile(row.fileId!!)
        sync(db, api)
        SyncResourceSynchronizer(ctx, db, api, user).use { it.synchronize() }
        assertEquals(SyncStatus.PENDING, db.syncAttachmentDao().get(row.id)!!.syncStatus)
        assertNotEquals(row.fileId, db.syncAttachmentDao().get(row.id)!!.fileId)
        assertEquals("FILE_NOT_READY", db.settingsDao().getValue("sync_rejected:attachments:${row.id}"))
        sync(db, api)
        SyncResourceSynchronizer(ctx, db, api, user).use { it.synchronize() }
        assertEquals(0, db.settingsDao().syncRejectedCount())
        assertNotNull(db.settingsDao().getValue("sync_resource_source:${message.uuid}"))
    }

    @Test fun `失效引用没有原件时保留错误且已删除附件不复活`() = runBlocking {
        val db = database(); val ctx = context(); val api = FakeSyncApi().apply { validateFileReferences = true }
        val session = ChatSessionEntity("source", 1, 1, false); db.chatDao().writeSession(session)
        val message = Message(id = "missing", text = "没有原件", sender = Sender.User).toEntity(session.id)
        db.chatDao().writeMessages(listOf(message))
        val row = SyncAttachmentEntity(UUID.randomUUID().toString(), session.uuid, message.uuid, "missing", "application/octet-stream", 3, "a".repeat(64), UUID.randomUUID().toString(), "file", 0)
        db.syncAttachmentDao().upsert(row)
        sync(db, api)
        repeat(2) { SyncResourceSynchronizer(ctx, db, api, user).use { it.synchronize() } }
        assertEquals("FILE_NOT_READY", db.settingsDao().getValue("sync_rejected:attachments:${row.id}"))
        assertEquals(SyncStatus.LOCAL_ONLY, db.syncAttachmentDao().get(row.id)!!.syncStatus)
        assertEquals(0, api.fileUploads)
        db.syncAttachmentDao().upsert(row.copy(deleted = true, syncStatus = SyncStatus.SYNCED))
        SyncResourceSynchronizer(ctx, db, api, user).use { it.synchronize() }
        assertTrue(db.syncAttachmentDao().get(row.id)!!.deleted)
        assertEquals(0, db.settingsDao().syncRejectedCount())
    }

    @Test fun `新资源当轮提交引用且技能失效只重传缺失文件`() = runBlocking {
        val db = database(); val ctx = context(); val api = FakeSyncApi().apply { validateFileReferences = true }
        val skillRoot = File(ctx.filesDir, "skill-repair").apply { mkdirs() }
        File(skillRoot, "SKILL.md").writeText("---\nname: example\ndescription: test\n---\n\n# 内容")
        val validated = SkillPackageValidator.validate(skillRoot)
        val skill = SkillInstallationEntity("repair-skill", validated.name, validated.description, "LOCAL_IMPORT", null, null,
            validated.contentHash, true, "AUTO", null, 100, 100, null, 0)
        db.skillDao().saveVersion(skill, SkillVersionEntity(skill.skillId, skill.currentHash, null, skillRoot.absolutePath, "[]", "{}", 100))
        SyncEngine(api, RoomSyncStore(db), SyncStateStore(db), beforeComplete = {
            SyncResourceSynchronizer(ctx, db, api, user).use { it.synchronize() }
        }).runOnce()
        val setting = db.settingsDao().getUserSetting("skills", skill.skillId)!!
        assertEquals(SyncStatus.SYNCED, setting.syncStatus)
        val refs = syncJson.parseToJsonElement(setting.value).jsonObject.getValue("fileRefs").jsonArray
        api.expireFile(refs.first().jsonPrimitive.content)
        db.settingsDao().upsertUserSettings(listOf(setting.copy(updatedAt = setting.updatedAt + 1, syncStatus = SyncStatus.PENDING)))
        sync(db, api)
        assertEquals("FILE_NOT_READY", db.settingsDao().getValue("sync_rejected:user_settings:${setting.uuid}"))
        // 旧云端技能快照不能清除较新但因失效引用暂停的本机修复版本。
        val oldCloud = api.rows.getValue("user_settings" to setting.uuid)
        RoomSyncStore(db).applyPullPage(PullResponse(listOf(oldCloud), api.seq, false, 0))
        assertEquals("FILE_NOT_READY", db.settingsDao().getValue("sync_rejected:user_settings:${setting.uuid}"))
        assertEquals(setting.updatedAt + 1, db.settingsDao().getUserSetting("skills", skill.skillId)!!.updatedAt)
        val uploads = api.fileUploads
        var remaining = 1
        val limited = object : SyncApi by api {
            override suspend fun uploadFile(file: File, mime: String): SyncFileInfo {
                if (remaining-- <= 0) throw SyncHttpException(429, 30, "RATE_LIMITED", "模拟技能修复中断")
                return api.uploadFile(file, mime)
            }
        }
        try { SyncResourceSynchronizer(ctx, db, limited, user).use { it.synchronize() }; fail("应在清单上传前限流") }
        catch (error: SyncHttpException) { assertEquals(429, error.statusCode) }
        assertEquals(uploads + 1, api.fileUploads)
        // 新同步器模拟重启：只剩一次预约配额，也应越过已传块并完成清单。
        remaining = 1
        SyncResourceSynchronizer(ctx, db, limited, user).use { it.synchronize() }
        assertEquals("FILE_NOT_READY", db.settingsDao().getValue("sync_rejected:user_settings:${setting.uuid}"))
        api.failPushNumber = api.pushes + 1
        try { sync(db, api); fail("模拟服务端已接受但 ACK 丢失") } catch (_: java.io.IOException) { }
        assertEquals("FILE_NOT_READY", db.settingsDao().getValue("sync_rejected:user_settings:${setting.uuid}"))
        sync(db, api)
        assertEquals(0, db.settingsDao().syncRejectedCount())
        // 缺失的技能文件和包含新 fileId 的清单各上传一次，其他 ready 文件仅重新预约。
        assertEquals(uploads + 2, api.fileUploads)
    }

    @Test fun `权威附件非最大时间更新和同毫秒替换都使两层缓存失效但重复记录不失效`() = runBlocking {
        val db = database()
        val store = RoomSyncStore(db, userId = user)
        val session = UUID.randomUUID().toString(); val message = UUID.randomUUID().toString()
        val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
        fun record(id: String, modified: Long, file: String = "file-a", seq: Long) = SyncRecord("attachments", id,
            buildJsonObject {
                put("sessionId", session); put("messageId", message); put("name", "history.et")
                put("mime", "application/octet-stream"); put("size", 10); put("sha256", "a".repeat(64))
                put("fileId", file); put("kind", "file"); put("position", 0); put("clientModifiedAt", modified)
            }, deleted = false, serverSeq = seq)
        suspend fun apply(record: SyncRecord) = store.applyPullPage(PullResponse(listOf(record), record.serverSeq, false, 0))
        suspend fun mark() {
            for (key in listOf("sync_resource_restore_stamp:$user", "sync_resource_received:$message", "sync_resource_receive_failed:$message"))
                db.settingsDao().insertSetting(SystemSettingEntity(key, "settled"))
        }
        suspend fun assertInvalidated() {
            assertNull(db.settingsDao().getValue("sync_resource_restore_stamp:$user"))
            assertNull(db.settingsDao().getValue("sync_resource_received:$message"))
            assertNull(db.settingsDao().getValue("sync_resource_receive_failed:$message"))
        }
        apply(record(a, 100, seq = 1)); apply(record(b, 200, seq = 2))
        val stamp = db.syncAttachmentDao().stamp()
        mark(); apply(record(a, 150, seq = 3))
        assertEquals(stamp, db.syncAttachmentDao().stamp())
        assertInvalidated()
        mark(); apply(record(a, 150, "file-new", 4)); assertInvalidated()
        mark(); apply(record(a, 150, "file-new", 5))
        assertEquals("settled", db.settingsDao().getValue("sync_resource_restore_stamp:$user"))
        assertEquals("settled", db.settingsDao().getValue("sync_resource_received:$message"))
    }

    @Test fun `云端任务提交清单一次推送后原机消失新设备恢复超长原问题与附件`() = runBlocking {
        val a = database(); val b = database(); val ca = context(); val cb = context(); val api = FakeSyncApi()
        val session = ChatSessionEntity("cloud-submit", 1, 1, false).copy(syncStatus = SyncStatus.PENDING)
        a.chatDao().writeSession(session)
        val image = File(ca.filesDir, "question.png").apply { writeBytes(byteArrayOf(2, 4, 6, 8)) }
        val body = "超长原问题😀".repeat(15000)
        val earlier = Message(id = "earlier", text = "更早没上云的问题", sender = Sender.User, timestamp = 5).toEntity(session.id)
        val question = Message(id = "question", text = body, sender = Sender.User, timestamp = 10,
            attachments = listOf(SelectedMediaItem.ImageFromUri(android.net.Uri.fromFile(image), "question-image", "image/png", image.absolutePath)))
            .toEntity(session.id)
        a.chatDao().writeMessages(listOf(earlier, question).map { it.copy(syncStatus = SyncStatus.PENDING) })
        // 不跑常规同步：只靠提交清单。服务器把它与任务放进同一事务，之后原手机不再出现。
        val candidates = a.chatDao().getSubmissionMessageUuids(session.id, question.id)
        assertEquals(listOf(earlier.uuid, question.uuid), candidates)
        val (ready, attachmentIds) = SyncResourceSynchronizer(ca, a, api, user).use { it.prepareSubmission(candidates, question.uuid) }
        val changes = RoomSyncStore(a, userId = user).submissionChanges(session.id, candidates.filter { it in ready }, attachmentIds, question.uuid)
        assertEquals(listOf("sessions", "messages", "messages"), changes.map { it.entity }.take(3))
        assertTrue(changes.single { it.id == question.uuid }.payload.toString().toByteArray().size < 65536)
        assertTrue(api.push(changes).results.all { it.status == "accepted" })
        sync(b, api); SyncResourceSynchronizer(cb, b, api, user).use { it.synchronize() }
        val restored = b.chatDao().getMessageByUuid(question.uuid)!!
        assertEquals(body, restored.text)
        assertEquals("更早没上云的问题", b.chatDao().getMessageByUuid(earlier.uuid)!!.text)
        val media = restored.attachments.single() as SelectedMediaItem.ImageFromUri
        assertArrayEquals(image.readBytes(), File(media.filePath!!).readBytes())
        // 原机之后的常规同步只会得到 unchanged，不重复上传文件。
        val uploads = api.fileUploads
        SyncResourceSynchronizer(ca, a, api, user).use { it.synchronize() }; sync(a, api)
        assertEquals(uploads, api.fileUploads)
        assertEquals(0, a.settingsDao().syncRejectedCount())
    }

    @Test fun `超限正文思考工具轨迹图片与技能文件完整恢复且不回传`() = runBlocking {
        val a = database(); val b = database(); val ca = context(); val cb = context(); val api = FakeSyncApi()
        val session = ChatSessionEntity("source", 100, 100, false, "完整历史")
        a.chatDao().writeSession(session)
        val image = File(ca.filesDir, "image.png").apply { writeBytes(byteArrayOf(1, 3, 5, 7, 9)) }
        val body = "长正文😀".repeat(18000)
        val question = Message(id = "original-question", text = "问题", sender = Sender.User, timestamp = 110)
            .toEntity(session.id).copy(syncStatus = SyncStatus.PENDING)
        a.chatDao().writeMessages(listOf(question))
        val change = ExecutionFileChange("/workspace/main.kt", false, listOf(ExecutionFileEdit("旧值", "新值")))
        val trace = listOf(ExecutionTraceEvent.UserMessageBoundary(question.id), ExecutionTraceEvent.Content("调用之前"), ExecutionTraceEvent.Tool(
            ExecutionStep("tool", ExecutionStepType.Tool, "查看服务器", detail = "uname -a", completed = true, fileChange = change, resultPreview = "结果")),
            ExecutionTraceEvent.Reasoning("完整思考"), ExecutionTraceEvent.Content("调用之后"))
        val message = Message(id = "original", text = body, sender = Sender.AI, timestamp = 120,
            reasoning = "思考".repeat(20000), executionTrace = trace, executionFinishedAt = 150,
            attachments = listOf(SelectedMediaItem.ImageFromUri(android.net.Uri.fromFile(image), "image", "image/png", image.absolutePath)))
            .toEntity(session.id).copy(syncStatus = SyncStatus.PENDING)
        a.chatDao().writeMessages(listOf(message))
        val run = AgentRunEntity("run", session.id, question.id, message.id, null, null, "RUNNING", 0, null, 1, 1)
        a.agentDao().upsertRun(run)
        val targets = listOf(com.android.everytalk.data.agent.AgentTaskTarget("a", "服务器 A"),
            com.android.everytalk.data.agent.AgentTaskTarget("b", "未使用的服务器 B"))
        a.agentDao().upsertEntry(AgentEntryEntity("targets", run.id, 0, "TASK_TARGETS", null, null,
            syncJson.encodeToString(targets), "FINAL", 1, 2))
        val toolPath = "agent_tool_results/run/output.txt"
        val toolFile = File(ca.filesDir, toolPath).apply { parentFile!!.mkdirs(); writeText("完整工具输出".repeat(60000)) }
        a.agentDao().upsertEntry(AgentEntryEntity("entry", run.id, 1, "TOOL_RESULT", null, "tool", """{"fullResultPath":"$toolPath"}""", "COMPLETED", 1, 2))
        val skillRoot = File(ca.filesDir, "skill").apply { mkdirs() }
        File(skillRoot, "SKILL.md").writeText("---\nname: example\ndescription: 恢复测试\n---\n\n# 规则\n完整内容")
        File(skillRoot, "script.py").writeText("print('hello')")
        val validated = SkillPackageValidator.validate(skillRoot)
        val skill = SkillInstallationEntity("test-skill", validated.name, validated.description, "LOCAL_IMPORT", null, null,
            validated.contentHash, true, "AUTO", null, 100, 100, null, 0)
        a.skillDao().saveVersion(skill, SkillVersionEntity(skill.skillId, skill.currentHash, null, skillRoot.absolutePath, "[]", "{}", 100))
        val assetsA = SyncResourceSynchronizer(ca, a, api, user)
        assetsA.synchronize(); sync(a, api); sync(b, api)
        assertEquals(0, a.settingsDao().syncRejectedCount())
        assertTrue(api.fileUploads >= 4)
        val index = api.rows["messages" to message.uuid]!!
        assertTrue(index.payload.toString().toByteArray().size < 65536)
        val assetsB = SyncResourceSynchronizer(cb, b, api, user)
        assetsB.synchronize()
        val restored = b.chatDao().getMessageByUuid(message.uuid)!!
        assertEquals(body, restored.text); assertEquals(message.reasoning, restored.reasoning)
        val restoredQuestion = b.chatDao().getMessageByUuid(question.uuid)!!
        assertEquals(trace.map { if (it is ExecutionTraceEvent.UserMessageBoundary) it.copy(messageId = restoredQuestion.id) else it }, restored.executionTrace)
        val restoredRun = b.agentDao().getRunByVisibleMessage(restored.id)!!
        assertEquals(restoredQuestion.id, restoredRun.userMessageId)
        assertEquals(targets, com.android.everytalk.data.agent.AgentRunStore(b.agentDao()).taskTargets(restoredRun))
        assertEquals(SyncStatus.SYNCED, restored.syncStatus)
        // 模拟旧版已应用归档但保留了原设备引用；升级后重新应用，不能永久保留错误轮次关系。
        val restoredMarker = "sync_resource_restored:${message.uuid}"
        val revision = b.settingsDao().getValue(restoredMarker)!!
        b.chatDao().writeMessages(listOf(restored.copy(executionTrace = trace)))
        b.settingsDao().insertSetting(SystemSettingEntity(restoredMarker, revision.removePrefix("4:")))
        // 旧版没有整体完成戳，升级后的第一轮会完整复查。
        b.settingsDao().deleteSetting("sync_resource_restore_stamp:$user")
        assetsB.synchronize()
        assertEquals(restoredQuestion.id, (b.chatDao().getMessageByUuid(message.uuid)!!.executionTrace.first()
            as ExecutionTraceEvent.UserMessageBoundary).messageId)
        assertEquals(revision, b.settingsDao().getValue(restoredMarker))
        val media = restored.attachments.single() as SelectedMediaItem.ImageFromUri
        assertArrayEquals(image.readBytes(), File(media.filePath!!).readBytes())
        assertTrue(media.filePath!!.startsWith(cb.filesDir.absolutePath))
        assertEquals(toolFile.readText(), File(cb.filesDir, toolPath).readText())
        assertEquals("SYNC_HISTORY_ONLY", b.agentDao().getRunByVisibleMessage(restored.id)!!.terminalReason)
        val restoredVersion = b.skillDao().getVersion(skill.skillId, skill.currentHash)!!
        assertEquals("print('hello')", File(restoredVersion.rootPath, "script.py").readText())
        assertTrue(RoomSyncStore(b).pendingBatch().isEmpty())
        val uploads = api.fileUploads; val pushes = api.pushes
        val restoredTool = File(cb.filesDir, toolPath).apply { setLastModified(1_000_000L) }
        assetsB.synchronize(); sync(b, api)
        assertEquals(uploads, api.fileUploads); assertEquals(pushes, api.pushes)
        assertEquals("已完整恢复的资源不应每轮重新解密改写", 1_000_000L, restoredTool.lastModified())
    }

    @Test fun `只有用户调整边界也上传完整归档并在另一台恢复原顺序`() = runBlocking {
        val a = database(); val b = database(); val ca = context(); val cb = context(); val api = FakeSyncApi()
        val session = ChatSessionEntity("boundary-source", 1, 1, false)
        a.chatDao().writeSession(session)
        val question = Message(id = "steering-user", text = "调整方向", sender = Sender.User, timestamp = 30).toEntity(session.id)
        val ai = Message(id = "boundary-ai", text = "之前之后", sender = Sender.AI, timestamp = 20,
            executionTrace = listOf(ExecutionTraceEvent.Content("之前"), ExecutionTraceEvent.UserMessageBoundary(question.id),
                ExecutionTraceEvent.Content("之后")), executionFinishedAt = 40).toEntity(session.id)
        a.chatDao().writeMessages(listOf(ai, question))
        SyncResourceSynchronizer(ca, a, api, user).use { it.synchronize() }; sync(a, api); sync(b, api)
        SyncResourceSynchronizer(cb, b, api, user).use { it.synchronize() }
        val restored = b.chatDao().getMessageByUuid(ai.uuid)!!
        assertEquals("cloud:${question.uuid}", (restored.executionTrace[1] as ExecutionTraceEvent.UserMessageBoundary).messageId)
        assertEquals(ai.text, restored.text)
        assertTrue(RoomSyncStore(b).pendingBatch().isEmpty())
    }

    @Test fun `云端归档恢复原设备占位但不覆盖本机真实运行且恢复后不回传`() = runBlocking {
        val source = database(); val target = database(); val protected = database()
        val cs = context(); val ct = context(); val cp = context(); val api = FakeSyncApi()
        val session = ChatSessionEntity("archive-source", 1, 1, false)
        source.chatDao().writeSession(session)
        val question = Message(id = "archive-user", text = "调整", sender = Sender.User, timestamp = 30).toEntity(session.id)
        val step = ExecutionStep("write", ExecutionStepType.Tool, "write_file", completed = true,
            fileChange = ExecutionFileChange("/workspace/app.kt", true, listOf(ExecutionFileEdit("", "内容"))))
        val ai = Message(id = "archive-ai", text = "之前之后", sender = Sender.AI, timestamp = 20,
            executionSteps = listOf(step), executionFinishedAt = 40, executionTrace = listOf(
                ExecutionTraceEvent.Content("之前"), ExecutionTraceEvent.UserMessageBoundary(question.id),
                ExecutionTraceEvent.Tool(step), ExecutionTraceEvent.Content("之后"))).toEntity(session.id)
        source.chatDao().writeMessages(listOf(ai, question))
        SyncResourceSynchronizer(cs, source, api, user).use { it.synchronize() }; sync(source, api)
        // 复用真实加密归档，仅把来源设置为生产云执行器使用的合同值。
        api.rows.toMap().forEach { (key, row) ->
            if (row.entity == "attachments") api.rows[key] = row.copy(payload = JsonObject(row.payload + ("origin" to JsonPrimitive("cloud_agent"))))
        }
        for ((db, ctx) in listOf(target to ct, protected to cp)) {
            val localSession = session.copy(id = "original-device-session")
            val placeholder = ai.copy(id = "original-device-ai", sessionId = localSession.id, text = "",
                executionSteps = emptyList(), executionTrace = emptyList(), syncStatus = SyncStatus.SYNCED, everSynced = true)
            db.chatDao().writeSession(localSession)
            db.chatDao().writeMessages(listOf(placeholder, question.copy(id = "original-device-user", sessionId = localSession.id)))
            if (db === protected) db.agentDao().upsertRun(AgentRunEntity("local-run", localSession.id,
                "original-device-user", placeholder.id, null, null, "RUNNING", 0, null, 1, 1))
            sync(db, api)
            val uploads = api.fileUploads
            SyncResourceSynchronizer(ctx, db, api, user).use { it.synchronize() }
            val restored = db.chatDao().getMessageByUuid(ai.uuid)!!
            assertEquals(placeholder.id, restored.id)
            if (db === protected) {
                assertEquals("", restored.text)
                assertEquals("RUNNING", db.agentDao().getRun("local-run")!!.status)
            } else {
                assertEquals(ai.text, restored.text)
                assertEquals(ai.executionSteps, restored.executionSteps)
                assertEquals("original-device-user", (restored.executionTrace[1] as ExecutionTraceEvent.UserMessageBoundary).messageId)
                assertEquals("$user:${restored.storageFingerprint}", db.settingsDao().getValue("sync_resource_source:${ai.uuid}"))
                assertTrue(RoomSyncStore(db).pendingBatch().isEmpty())
                SyncResourceSynchronizer(ctx, db, api, user).use { it.synchronize() }
                assertEquals(uploads, api.fileUploads)
            }
        }
    }

    @Test fun `云端原设备完整归档提交后保留身份游标并回收快照密文且拒绝晚事件`() = runBlocking {
        val source = database(); val target = database(); val second = database()
        val cs = context(); val ct = context(); val cb = context(); val api = FakeSyncApi()
        val session = ChatSessionEntity("cloud-origin", 1, 1, false)
        val question = Message(id = "cloud-question", text = "修改文件", sender = Sender.User, timestamp = 10)
            .toEntity(session.id).copy(syncStatus = SyncStatus.PENDING)
        val placeholder = Message(id = "cloud-answer", text = "", sender = Sender.AI, timestamp = 20).toEntity(session.id)
        target.chatDao().writeSession(session); target.chatDao().writeMessages(listOf(question))
        target.chatDao().writeDrafts(listOf(MessageDraftEntity(placeholder)))
        val store = CloudAgentStore(target)
        val run = store.prepare(user, session.id, question.id, placeholder.id, "original-config")
        val final = CloudRunView(1, run.id, session.uuid, question.uuid, placeholder.uuid, "COMPLETED", 8, 27,
            CloudRunSnapshot("最后快照", "", emptyList(), null, 20, 40))
        // 同步正式消息和归档可能早于 SSE 终态，必须沿用运行中草稿的本机 ID，不能生成第二个气泡。
        val running = final.copy(status = "RUNNING", cursor = 26, snapshot = final.snapshot.copy(finishedAt = null))
        store.apply(user, running)
        val credentials = ComputerCredentialStore(ct, SecretKeySpec(ByteArray(32) { 9 }, "AES"))
        val frozen = "{\"id\":\"${run.id}\",\"apiKey\":\"test-only\"}".toByteArray()
        credentials.saveCloudAgentInput(user, run.id, frozen)
        val step = ExecutionStep("write", ExecutionStepType.Tool, "write_file", completed = true,
            fileChange = ExecutionFileChange("/workspace/a.kt", true, listOf(ExecutionFileEdit("", "完整内容"))))
        val ai = placeholder.toMessage().copy(text = "完整正文".repeat(12000), reasoning = "完整思考", executionFinishedAt = 40,
            executionSteps = listOf(step), executionTrace = listOf(ExecutionTraceEvent.UserMessageBoundary(question.id),
                ExecutionTraceEvent.Tool(step), ExecutionTraceEvent.Content("完整总结"))).toEntity(session.id)
        source.chatDao().writeSession(session); source.chatDao().writeMessages(listOf(question, ai))
        source.agentDao().upsertRun(run.copy(status = "COMPLETED", updatedAt = 40, executionOwner = "LOCAL"))
        val entry = AgentEntryEntity("cloud-entry", run.id, 1, "TOOL_RESULT", null, step.id, "{}", "COMPLETED", 20, 30)
        source.agentDao().upsertEntry(entry)
        val checkpoint = AgentCompactionEntryEntity("cloud-runtime-summary", session.uuid, null, "旧工具已完成", "tool:old", "",
            """{"summary_role":"user","source_prefix_ids":["cloud:${question.uuid}","tool:old"],"retained_ids":["tool:write"]}""", 9000, 500, null, "COMPLETED", 35)
        SyncResourceSynchronizer(cs, source, api, user).use { it.synchronize() }; sync(source, api)
        // 用生产同款 AES-GCM/gzip 信封模拟后端 message_archive 内携带摘要，不借单独摘要附件过关。
        for ((key, row) in api.rows.toMap()) {
            if (row.entity != "attachments" || row.payload["role"]?.jsonPrimitive?.content != "message_archive") continue
            assertEquals(1, row.payload.getValue("chunkCount").jsonPrimitive.int)
            val file = File(cs.filesDir, "archive-fixture")
            val info = SyncFileInfo(row.payload.getValue("fileId").jsonPrimitive.content, row.payload.getValue("size").jsonPrimitive.long,
                row.payload.getValue("sha256").jsonPrimitive.content, "application/octet-stream")
            api.downloadFile(info, file)
            val bytes = file.readBytes()
            val aad = JsonArray(listOf("everytalk-history", user, session.uuid, ai.uuid, "0").map(::JsonPrimitive)).toString().toByteArray()
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val secret = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secret, javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            val original = java.util.zip.GZIPInputStream(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).inputStream()).use { it.readBytes() }
            val archive = syncJson.decodeFromString(SyncHistoryArchive.serializer(), original.toString(Charsets.UTF_8)).copy(compactions = listOf(checkpoint))
            val json = syncJson.encodeToString(SyncHistoryArchive.serializer(), archive).toByteArray()
            val zipped = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(json) } }.toByteArray()
            val nonce = SyncCrypto.randomBytes(12)
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secret, javax.crypto.spec.GCMParameterSpec(128, nonce)); cipher.updateAAD(aad)
            file.writeBytes(nonce + cipher.doFinal(zipped))
            val uploaded = api.uploadFile(file, info.mime)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(json).joinToString("") { "%02x".format(it) }
            api.rows[key] = row.copy(payload = JsonObject(row.payload + mapOf("fileId" to JsonPrimitive(uploaded.fileId), "size" to JsonPrimitive(uploaded.size),
                "sha256" to JsonPrimitive(uploaded.sha256), "resourceDigest" to JsonPrimitive(digest), "resourceVersion" to JsonPrimitive("cloud-summary-$digest"))))
        }
        api.rows.toMap().forEach { (key, row) ->
            if (row.entity == "attachments") api.rows[key] = row.copy(payload = JsonObject(row.payload + ("origin" to JsonPrimitive("cloud_agent"))))
        }
        sync(target, api); sync(second, api)
        assertEquals(placeholder.id, target.chatDao().getMessageByUuid(placeholder.uuid)!!.id)
        val unavailable = object : SyncApi by api {
            override suspend fun downloadFile(info: SyncFileInfo, target: File) =
                throw SyncHttpException(503, serverCode = "TEMPORARILY_UNAVAILABLE", message = "模拟归档未下载")
        }
        try { SyncResourceSynchronizer(ct, target, unavailable, user).use { it.synchronize() }; fail("归档未取得前不能清理") }
        catch (error: SyncHttpException) { assertEquals(503, error.statusCode) }
        assertEquals(running, store.read(run.id))
        assertArrayEquals(frozen, credentials.loadCloudAgentInput(user, run.id))
        assertNull(target.settingsDao().getValue("sync_resource_restored:${ai.uuid}"))
        val uploads = api.fileUploads
        for ((db, ctx) in listOf(target to ct, second to cb)) {
            SyncResourceSynchronizer(ctx, db, api, user).use { it.synchronize() }
            val restored = db.chatDao().getMessageByUuid(ai.uuid)!!
            assertEquals(ai.text, restored.text); assertEquals(ai.reasoning, restored.reasoning)
            assertEquals(ai.executionSteps, restored.executionSteps)
            val localQuestion = db.chatDao().getMessageByUuid(question.uuid)!!
            assertEquals(localQuestion.id, (restored.executionTrace.first() as ExecutionTraceEvent.UserMessageBoundary).messageId)
            val summary = db.agentDao().getCompactions(restored.sessionId).single()
            assertEquals(checkpoint.summary, summary.summary)
            assertEquals("tool:old", summary.summarizedThroughItemId)
            val metadata = syncJson.parseToJsonElement(summary.retainedTailJson).jsonObject
            assertEquals(listOf(localQuestion.id, "tool:old"), metadata.getValue("source_prefix_ids").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("tool:write"), metadata.getValue("retained_ids").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("received", db.settingsDao().getValue("sync_compaction_uploaded:$user:${checkpoint.id}"))
            assertTrue(RoomSyncStore(db).pendingBatch().isEmpty())
        }
        val archived = target.agentDao().getRun(run.id)!!
        assertEquals("CLOUD", archived.executionOwner); assertEquals(user, archived.cloudAccountId)
        assertEquals(run.id, archived.cloudRunId); assertEquals("original-config", archived.configIdSnapshot)
        assertEquals(26L, archived.cloudCursor); assertEquals(8L, archived.cloudGeneration)
        assertEquals("COMPLETED", archived.status); assertEquals("SYNC_HISTORY_ONLY", archived.terminalReason)
        assertEquals(listOf(entry), target.agentDao().getEntries(run.id))
        assertNull(store.read(run.id)); assertNull(credentials.loadCloudAgentInput(user, run.id))
        assertNull(target.chatDao().getDraft(session.id, placeholder.id))
        assertNull(store.project(user, final))
        assertNull(store.apply(user, final.copy(cursor = 28)))
        SyncResourceSynchronizer(ct, target, api, user).use { it.synchronize() }
        assertEquals(ai.text, target.chatDao().getMessageByUuid(ai.uuid)!!.text)
        assertEquals(uploads, api.fileUploads)
    }

    @Test fun `按服务器限制分块图片并恢复新设备空文件技能和换账号`() = runBlocking {
        val a = database(); val b = database(); val ca = context(); val cb = context()
        val api = FakeSyncApi().apply { chunkSize = 4096 }
        val session = ChatSessionEntity("source", 1, 1, false)
        a.chatDao().writeSession(session)
        val image = File(ca.filesDir, "large.png").apply { writeBytes(ByteArray(19001) { (it % 239).toByte() }) }
        val message = Message(id = "picture", text = "图片", sender = Sender.User, imageUrls = listOf(image.toURI().toString())).toEntity(session.id)
        a.chatDao().writeMessages(listOf(message))
        val root = File(ca.filesDir, "skill").apply { mkdirs() }
        File(root, "SKILL.md").writeText("---\nname: chunked\ndescription: 分块\n---\n\n# 文件")
        File(root, "empty.txt").writeText("")
        File(root, "data.bin").writeBytes(image.readBytes())
        val validated = SkillPackageValidator.validate(root)
        val skill = SkillInstallationEntity("chunked", validated.name, validated.description, "LOCAL_IMPORT", null, null, validated.contentHash, true, "AUTO", null, 1, 1, null, 0)
        a.skillDao().saveVersion(skill, SkillVersionEntity(skill.skillId, skill.currentHash, null, root.absolutePath, "[]", "{}", 1))
        SyncResourceSynchronizer(ca, a, api, user).use { it.synchronize() }
        sync(a, api); sync(b, api)
        SyncStateStore(b).markAllLocalDataPending()
        // 模拟新登录的自动初始化，但拉到资源后不能重新上传同一份原件。
        SyncResourceSynchronizer(cb, b, api, user).use { it.synchronize() }
        val restored = b.chatDao().getMessageByUuid(message.uuid)!!
        assertArrayEquals(image.readBytes(), File((restored.attachments.single() as SelectedMediaItem.ImageFromUri).filePath!!).readBytes())
        val version = b.skillDao().getVersion(skill.skillId, skill.currentHash)!!
        assertEquals(0L, File(version.rootPath, "empty.txt").length())
        assertArrayEquals(image.readBytes(), File(version.rootPath, "data.bin").readBytes())
        val uploaded = api.fileUploads
        SyncResourceSynchronizer(cb, b, api, user).use { it.synchronize() }
        assertEquals(uploaded, api.fileUploads)

        // 用户明确选择上传到另一账号后，从已恢复的本机原件重新建引用，不发送旧 fileId。
        b.settingsDao().insertSetting(SystemSettingEntity(SyncStateStore.KEY_ACCOUNT, user))
        SyncStateStore(b).prepareAccount("another-account", true)
        val next = FakeSyncApi().apply { chunkSize = 4096 }
        SyncResourceSynchronizer(cb, b, next, "another-account").use { it.synchronize() }
        sync(b, next)
        assertTrue(next.rows.values.any { it.entity == "attachments" })
        assertEquals(0, b.settingsDao().syncRejectedCount())
    }

    @Test fun `分块上限变化和中断重试不会混合旧块或复活附件墓碑`() = runBlocking {
        val a = database(); val b = database(); val ca = context(); val cb = context()
        val api = FakeSyncApi().apply { chunkSize = 8192 }
        val session = ChatSessionEntity("source", 1, 1, false)
        a.chatDao().writeSession(session)
        val image = File(ca.filesDir, "retry.png").apply { writeBytes(ByteArray(19001) { (it % 251).toByte() }) }
        val source = Message(id = "picture", text = "图", sender = Sender.User, attachments = listOf(
            SelectedMediaItem.ImageFromUri(android.net.Uri.fromFile(image), "stable-media", "image/png", image.absolutePath))).toEntity(session.id)
        a.chatDao().writeMessages(listOf(source))
        var uploaded = 0
        val interrupted = object : SyncApi by api {
            override suspend fun uploadFile(file: File, mime: String): SyncFileInfo {
                if (++uploaded == 2) throw java.io.IOException("模拟断网")
                return api.uploadFile(file, mime)
            }
        }
        try { SyncResourceSynchronizer(ca, a, interrupted, user).use { it.synchronize() }; fail("应模拟中断") }
        catch (_: java.io.IOException) { }
        for (limit in listOf(4096L, 8192L, 4096L)) {
            api.chunkSize = limit
            a.settingsDao().deleteSetting("sync_resource_source:${source.uuid}")
            SyncResourceSynchronizer(ca, a, api, user).use { it.synchronize() }
            val retiring = a.syncAttachmentDao().getPending(100).filter { it.deleted }
            assertTrue("新引用确认前不能删除旧块", retiring.isEmpty())
            sync(a, api); sync(b, api)
            SyncResourceSynchronizer(cb, b, api, user).use { it.synchronize() }
            val restored = b.chatDao().getMessageByUuid(source.uuid)!!.attachments.single() as SelectedMediaItem.ImageFromUri
            assertEquals("stable-media", restored.id)
            assertArrayEquals(image.readBytes(), File(restored.filePath!!).readBytes())
            // 新组确认后再收尾旧组，并提交墓碑；下一次限额变化必须真正生成新分组。
            SyncResourceSynchronizer(ca, a, api, user).use { it.synchronize() }
            sync(a, api)
            assertNull(a.settingsDao().getValue("sync_resource_queued:${source.uuid}"))
            assertEquals(0, a.settingsDao().syncRejectedCount()); assertEquals(0, b.settingsDao().syncRejectedCount())
        }
        val uploads = api.fileUploads
        SyncResourceSynchronizer(ca, a, api, user).use { it.synchronize() }
        sync(a, api)
        assertEquals(uploads, api.fileUploads)
        assertTrue(RoomSyncStore(a).pendingBatch().isEmpty())
    }

    @Test fun `工具归档和图片跨阶段限流重启不重传已完成密文`() = runBlocking {
        val a = database(); val b = database(); val ca = context(); val cb = context(); val cloud = FakeSyncApi()
        val session = ChatSessionEntity("source", 1, 1, false)
        a.chatDao().writeSession(session)
        val image = File(ca.filesDir, "image.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val source = Message(id = "compound", text = "完整回复", sender = Sender.AI,
            executionSteps = listOf(ExecutionStep("tool", ExecutionStepType.Tool, "工具", completed = true)),
            attachments = listOf(SelectedMediaItem.ImageFromUri(android.net.Uri.fromFile(image), "media", "image/png", image.absolutePath)))
            .toEntity(session.id)
        a.chatDao().writeMessages(listOf(source))
        val run = AgentRunEntity("run", session.id, source.id, source.id, null, null, "COMPLETED", 0, null, 1, 1)
        a.agentDao().upsertRun(run)
        val resultPath = "agent_tool_results/run/output.txt"
        val output = File(ca.filesDir, resultPath).apply { parentFile!!.mkdirs(); writeText("完整工具输出") }
        a.agentDao().upsertEntry(AgentEntryEntity("entry", run.id, 1, "TOOL_RESULT", null, "tool", """{"fullResultPath":"$resultPath"}""", "COMPLETED", 1, 2))
        var remaining = 1; var uploads = 0
        val limited = object : SyncApi by cloud {
            override suspend fun uploadFile(file: File, mime: String): SyncFileInfo {
                if (remaining-- <= 0) throw SyncHttpException(429, 60, "RATE_LIMITED", "模拟跨阶段限流")
                uploads++; return cloud.uploadFile(file, mime)
            }
        }
        repeat(2) {
            remaining = 1
            try { SyncResourceSynchronizer(ca, a, limited, user).use { it.synchronize() }; fail("应在下个阶段限流") }
            catch (error: SyncHttpException) { assertEquals(429, error.statusCode) }
            assertEquals(it + 1, uploads)
        }
        remaining = 1
        SyncResourceSynchronizer(ca, a, limited, user).use { it.synchronize() }
        assertEquals(3, uploads)
        sync(a, cloud); sync(b, cloud)
        SyncResourceSynchronizer(cb, b, cloud, user).use { it.synchronize() }
        val restored = b.chatDao().getMessageByUuid(source.uuid)!!
        assertEquals(source.text, restored.text)
        assertEquals(output.readText(), File(cb.filesDir, resultPath).readText())
        assertArrayEquals(image.readBytes(), File((restored.attachments.single() as SelectedMediaItem.ImageFromUri).filePath!!).readBytes())
        assertEquals(3, cloud.rows.values.count { it.entity == "attachments" && !it.deleted })
        assertEquals(0, a.settingsDao().syncRejectedCount()); assertEquals(0, b.settingsDao().syncRejectedCount())
    }

    @Test fun `旧下载失败升级后核对真实错误且相同版本不无限重试`() = runBlocking {
        val a = database(); val b = database(); val ca = context(); val cb = context(); val cloud = FakeSyncApi()
        val session = ChatSessionEntity("source", 1, 1, false)
        a.chatDao().writeSession(session)
        val image = File(ca.filesDir, "image.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val source = Message(id = "image", text = "图片", sender = Sender.User, attachments = listOf(
            SelectedMediaItem.ImageFromUri(android.net.Uri.fromFile(image), "media", "image/png", image.absolutePath))).toEntity(session.id)
        a.chatDao().writeMessages(listOf(source))
        SyncResourceSynchronizer(ca, a, cloud, user).use { it.synchronize() }
        sync(a, cloud); sync(b, cloud)
        val meta = b.settingsDao().getByPrefix("sync_resource_meta:").single()
        val attachment = b.syncAttachmentDao().get(meta.key.substringAfter(':'))!!
        b.settingsDao().insertSetting(SystemSettingEntity("sync_resource_receive_failed:${source.uuid}", "${attachment.id}:${attachment.updatedAt}"))
        var downloads = 0
        val unavailable = object : SyncApi by cloud {
            override suspend fun downloadFile(info: SyncFileInfo, target: File) {
                downloads++
                throw SyncHttpException(404, serverCode = "FILE_NOT_FOUND", message = "不可用")
            }
        }
        repeat(2) { SyncResourceSynchronizer(cb, b, unavailable, user).use { it.synchronize() } }
        assertEquals(1, downloads)
        assertEquals("FILE_NOT_FOUND", b.settingsDao().getValue("sync_rejected:attachments:${attachment.id}"))
        assertTrue(b.settingsDao().getValue("sync_resource_receive_failed:${source.uuid}")!!.startsWith("2:"))
        // 原设备补传新版本后自动恢复，不能让旧失败标记永久挡住新文件。
        b.syncAttachmentDao().upsert(attachment.copy(updatedAt = attachment.updatedAt + 1))
        SyncResourceSynchronizer(cb, b, cloud, user).use { it.synchronize() }
        val restored = b.chatDao().getMessageByUuid(source.uuid)!!.attachments.single() as SelectedMediaItem.ImageFromUri
        assertArrayEquals(image.readBytes(), File(restored.filePath!!).readBytes())
        assertEquals(0, b.settingsDao().syncRejectedCount())
    }

    @Test fun `附件墓碑到达后清理旧下载失败且不产生回传`() = runBlocking {
        val a = database(); val b = database(); val ca = context(); val cb = context(); val cloud = FakeSyncApi()
        val session = ChatSessionEntity("source", 1, 1, false)
        a.chatDao().writeSession(session)
        val image = File(ca.filesDir, "deleted.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val source = Message(id = "deleted-image", text = "图片", sender = Sender.User, attachments = listOf(
            SelectedMediaItem.ImageFromUri(android.net.Uri.fromFile(image), "media", "image/png", image.absolutePath))).toEntity(session.id)
        a.chatDao().writeMessages(listOf(source))
        SyncResourceSynchronizer(ca, a, cloud, user).use { it.synchronize() }
        sync(a, cloud); sync(b, cloud)
        val id = b.settingsDao().getByPrefix("sync_resource_meta:").single().key.substringAfter(':')
        val attachment = a.syncAttachmentDao().get(id)!!
        var downloads = 0
        val unavailable = object : SyncApi by cloud {
            override suspend fun downloadFile(info: SyncFileInfo, target: File) {
                downloads++
                throw SyncHttpException(404, serverCode = "FILE_NOT_FOUND", message = "不可用")
            }
        }
        SyncResourceSynchronizer(cb, b, unavailable, user).use { it.synchronize() }
        assertEquals(1, b.settingsDao().syncRejectedCount())
        a.syncAttachmentDao().upsert(attachment.copy(deleted = true, updatedAt = attachment.updatedAt + 1,
            syncStatus = SyncStatus.PENDING))
        sync(a, cloud); sync(b, cloud)
        SyncResourceSynchronizer(cb, b, unavailable, user).use { it.synchronize() }
        assertEquals(1, downloads)
        assertEquals(0, b.settingsDao().syncRejectedCount())
        assertTrue(RoomSyncStore(b).pendingBatch().isEmpty())
    }

    @Test fun `超过一轮限流配额的文件杀进程后只续传未完成块`() = runBlocking {
        val db = database(); val sourceContext = context(); val cloud = FakeSyncApi().apply { chunkSize = 1024 }
        val session = ChatSessionEntity("source", 1, 1, false)
        db.chatDao().writeSession(session)
        val file = File(sourceContext.filesDir, "many-chunks.png").apply { writeBytes(ByteArray(19001) { (it % 251).toByte() }) }
        val source = Message(id = "many", text = "图", sender = Sender.User, attachments = listOf(
            SelectedMediaItem.ImageFromUri(android.net.Uri.fromFile(file), "media", "image/png", file.absolutePath))).toEntity(session.id)
        db.chatDao().writeMessages(listOf(source))
        var remaining = 10; var uploads = 0
        val limited = object : SyncApi by cloud {
            override suspend fun uploadFile(file: File, mime: String): SyncFileInfo {
                if (remaining-- <= 0) throw SyncHttpException(429, 60, "RATE_LIMITED", "模拟限流")
                uploads++; return cloud.uploadFile(file, mime)
            }
        }
        try { SyncResourceSynchronizer(sourceContext, db, limited, user).use { it.synchronize() }; fail("应限流") }
        catch (error: SyncHttpException) { assertEquals(429, error.statusCode) }
        assertEquals(10, uploads)
        remaining = 10
        // 新对象模拟 Worker/进程重新开始；进度来自数据库，不依赖旧对象内存。
        SyncResourceSynchronizer(sourceContext, db, limited, user).use { it.synchronize() }
        assertEquals(19, uploads)
        sync(db, cloud)
        assertEquals(0, db.settingsDao().syncRejectedCount())
        assertEquals(19, cloud.rows.values.count { it.entity == "attachments" && !it.deleted })
    }

    @Test fun `恢复大量文件时分批续传直到全部完成`() = runBlocking {
        val a = database(); val b = database(); val ca = context(); val cb = context()
        val api = FakeSyncApi().apply { chunkSize = 4096 }
        val session = ChatSessionEntity("source", 1, 1, false)
        a.chatDao().writeSession(session)
        // 60 张图片 = 60 个文件，超过单轮 40 个的上限，必须续传第二轮。
        val pictures = (1..60).map { index ->
            val image = File(ca.filesDir, "many-$index.png").apply { writeBytes(ByteArray(600) { (it + index).toByte() }) }
            Message(id = "picture-$index", text = "图片 $index", sender = Sender.User, timestamp = index.toLong(),
                imageUrls = listOf(image.toURI().toString())).toEntity(session.id)
        }
        a.chatDao().writeMessages(pictures)
        // 上传端每轮最多传 5 条资源，要跑到没有剩余工作为止。
        while (true) {
            val uploader = SyncResourceSynchronizer(ca, a, api, user)
            uploader.use { it.synchronize() }
            sync(a, api)
            if (!uploader.moreWork) break
        }
        sync(b, api)
        SyncStateStore(b).markAllLocalDataPending()
        val first = SyncResourceSynchronizer(cb, b, api, user)
        first.use { it.synchronize() }
        assertTrue("超过单轮上限必须表示还有剩余工作", first.moreWork)
        SyncResourceSynchronizer(cb, b, api, user).use { it.synchronize() }
        // 全部图片都应在两轮内落盘，且不重复下载。
        val files = b.settingsDao().getByPrefix("sync_resource_meta:").mapNotNull { row ->
            b.syncAttachmentDao().get(row.key.substringAfter(':'))
        }
        assertEquals(60, files.size)
        assertEquals(0, b.settingsDao().syncRejectedCount())
        sync(b, api)
        assertTrue(RoomSyncStore(b).pendingBatch().isEmpty())
    }
}
