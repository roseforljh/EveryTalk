package com.android.everytalk.data.sync

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.*
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
        }
    }
    private suspend fun database(): AppDatabase = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build().also {
        databases += it; SyncStateStore(it).recordSecretsEpoch(0)
    }
    private suspend fun sync(db: AppDatabase, api: SyncApi) = SyncEngine(api, RoomSyncStore(db), SyncStateStore(db)).runOnce()
    @After fun close() { databases.forEach { it.close() }; directories.forEach { it.deleteRecursively() } }

    @Test fun `超限正文思考工具轨迹图片与技能文件完整恢复且不回传`() = runBlocking {
        val a = database(); val b = database(); val ca = context(); val cb = context(); val api = FakeSyncApi()
        val session = ChatSessionEntity("source", 100, 100, false, "完整历史")
        a.chatDao().writeSession(session)
        val image = File(ca.filesDir, "image.png").apply { writeBytes(byteArrayOf(1, 3, 5, 7, 9)) }
        val body = "长正文😀".repeat(18000)
        val question = Message(id = "original-question", text = "问题", sender = Sender.User, timestamp = 110)
            .toEntity(session.id).copy(syncStatus = SyncStatus.PENDING)
        a.chatDao().writeMessages(listOf(question))
        val trace = listOf(ExecutionTraceEvent.UserMessageBoundary(question.id), ExecutionTraceEvent.Content("调用之前"), ExecutionTraceEvent.Tool(
            ExecutionStep("tool", ExecutionStepType.Tool, "查看服务器", detail = "uname -a", completed = true)),
            ExecutionTraceEvent.Reasoning("完整思考"), ExecutionTraceEvent.Content("调用之后"))
        val message = Message(id = "original", text = body, sender = Sender.AI, timestamp = 120,
            reasoning = "思考".repeat(20000), executionTrace = trace, executionFinishedAt = 150,
            attachments = listOf(SelectedMediaItem.ImageFromUri(android.net.Uri.fromFile(image), "image", "image/png", image.absolutePath)))
            .toEntity(session.id).copy(syncStatus = SyncStatus.PENDING)
        a.chatDao().writeMessages(listOf(message))
        val run = AgentRunEntity("run", session.id, question.id, message.id, null, null, "RUNNING", 0, null, 1, 1)
        a.agentDao().upsertRun(run)
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
        assertEquals(restoredQuestion.id, b.agentDao().getRunByVisibleMessage(restored.id)!!.userMessageId)
        assertEquals(SyncStatus.SYNCED, restored.syncStatus)
        // 模拟旧版已应用归档但保留了原设备引用；升级后重新应用，不能永久保留错误轮次关系。
        val restoredMarker = "sync_resource_restored:${message.uuid}"
        val revision = b.settingsDao().getValue(restoredMarker)!!
        b.chatDao().writeMessages(listOf(restored.copy(executionTrace = trace)))
        b.settingsDao().insertSetting(SystemSettingEntity(restoredMarker, revision.removePrefix("2:")))
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
        assetsB.synchronize(); sync(b, api)
        assertEquals(uploads, api.fileUploads); assertEquals(pushes, api.pushes)
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
            sync(a, api); sync(b, api)
            SyncResourceSynchronizer(cb, b, api, user).use { it.synchronize() }
            val restored = b.chatDao().getMessageByUuid(source.uuid)!!.attachments.single() as SelectedMediaItem.ImageFromUri
            assertEquals("stable-media", restored.id)
            assertArrayEquals(image.readBytes(), File(restored.filePath!!).readBytes())
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
}
