package com.android.everytalk.data.sync

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.*
import com.android.everytalk.data.skill.SkillPackageValidator
import com.android.everytalk.data.skill.SkillFileManifestEntry
import com.android.everytalk.models.SelectedMediaItem
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

@kotlinx.serialization.Serializable
internal data class SyncHistoryArchive(val message: Message, val run: AgentRunEntity? = null,
    val entries: List<AgentEntryEntity> = emptyList(), val resultFiles: Map<String, String> = emptyMap(),
    /** 云端任务每轮模型返回的来源记录；本机据此判定同源回放，旧归档没有。 */
    val requests: List<com.android.everytalk.data.database.entities.AgentRequestEntity> = emptyList())

@kotlinx.serialization.Serializable
private data class SyncUploadedFile(val info: SyncFileInfo, val uploadedAt: Long)

/** 已排队不等于云端已引用；版本与预期附件一起落盘，重启不重复生成随机密文。 */
@kotlinx.serialization.Serializable
private data class SyncQueuedResource(val revision: String, val attachments: Map<String, String>, val retired: List<String>)

/**
 * 资源文件与普通记录分开传输。每次只补五条历史消息，进度写入 Room；断网或进程退出后
 * 重复上传由服务器按 SHA-256 去重。旧路径永不恢复，文件下载校验后才原子替换本机内容。
 */
internal class SyncResourceSynchronizer(
    context: Context, private val db: AppDatabase, private val api: SyncApi,
    private val user: String,
) : AutoCloseable {
    private val context = context.applicationContext
    private val root = File(context.filesDir, "sync-resources/${UUID.nameUUIDFromBytes(user.toByteArray())}").apply { mkdirs() }
    private val settings = db.settingsDao()
    private val chat = db.chatDao()
    private val state = SyncStateStore(db)
    var moreWork = false
        private set
    private var downloads = 0
    private var historyDek: ByteArray? = null
    private var chunkBytes = CHUNK_BYTES
    private val queuedAttachments = linkedMapOf<String, String>() // attachmentId -> sha256，ACK 可重新分配 fileId。
    private val retiringAttachments = linkedSetOf<String>()
    private val repairingMessages = linkedSetOf<String>()
    private val repairingSkills = linkedSetOf<String>()
    override fun close() { historyDek?.fill(0); historyDek = null }

    suspend fun synchronize() {
        val recovered = api.recoverHistoryKey()
        require(recovered.version == 1L && recovered.secretsEpoch == 0L)
        historyDek?.fill(0)
        historyDek = SyncCrypto.decodeCanonicalBase64(recovered.dek).also { require(it.size == 32) }
        // 已拿到历史密钥就清理旧配置缺失提示；后面的暂时限流不能继续显示“未配置主密钥”。
        settings.deleteSetting("sync_rejected:services:resources")
        chunkBytes = api.fileChunkBytes()
        finalizeQueuedResources()
        repairRejectedResources()
        restoreAttachments()
        restoreSkills()
        uploadSkills()
        var transferred = 0
        // 每次同步都会走到这里。先用轻量列和已完成标记筛掉传过的消息，只有真要补传的才读整行；
        // 原先逐页读全部历史，几千条消息要十几秒，期间整把同步锁被占着，新的本地修改只能等。
        val republish = settings.getValue("sync_resource_republish") == "true"
        val uploaded = settings.getByPrefix("sync_resource_source:").associate { it.key to it.value }
        val failures = settings.getByPrefix("sync_resource_failed:").associate { it.key to it.value }
        val queued = settings.getByPrefix("sync_resource_queued:").associate { it.key to it.value }
        for (candidate in chat.getResourceSyncCandidates()) {
            val repairing = candidate.uuid in repairingMessages
            if (candidate.id.startsWith("cloud:") && !republish && !repairing) continue
            val marker = "sync_resource_source:${candidate.uuid}"
            val failureMarker = "sync_resource_failed:${candidate.uuid}"
            val candidateRevision = "$user:${candidate.storageFingerprint}"
            if (!repairing && (uploaded[marker] == candidateRevision || failures[failureMarker] == candidateRevision ||
                    queued["sync_resource_queued:${candidate.uuid}"]?.let { syncJson.decodeFromString<SyncQueuedResource>(it).revision } == candidateRevision)) continue
            val message = chat.getMessageByUuid(candidate.uuid)?.takeUnless { it.deleted } ?: continue
            val revision = "$user:${message.storageFingerprint}"
            val needsTransfer = needsArchive(message) || mediaItems(message).isNotEmpty()
            if (needsTransfer && transferred >= 5) { moreWork = true; return }
            val session = chat.getStoredSession(message.sessionId)?.takeUnless { it.deleted } ?: continue
            queuedAttachments.clear()
            retiringAttachments.clear()
            try { uploadMessage(message, session) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: SyncHttpException) {
                if (error.statusCode in setOf(401, 408, 409, 429) || error.statusCode >= 500) throw error
                settings.insertSetting(SystemSettingEntity("sync_rejected:messages:${message.uuid}", error.serverCode?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) } ?: "RESOURCE_UPLOAD_FAILED"))
                settings.insertSetting(SystemSettingEntity(failureMarker, revision)); continue
            }
            catch (_: IllegalArgumentException) {
                settings.insertSetting(SystemSettingEntity("sync_rejected:messages:${message.uuid}", "RESOURCE_VALIDATION_FAILED"))
                settings.insertSetting(SystemSettingEntity(failureMarker, revision)); continue
            }
            // 只记录“已排队”。引用确认前保留密文，进程退出后仍可修复过期 fileId。
            if (chat.getMessageByUuid(message.uuid)?.storageFingerprint == message.storageFingerprint) {
                settings.insertSetting(SystemSettingEntity("sync_resource_queued:${message.uuid}", syncJson.encodeToString(
                    SyncQueuedResource.serializer(), SyncQueuedResource(revision, queuedAttachments.toMap(), retiringAttachments.toList()))))
                moreWork = true
            }
            if (needsTransfer) transferred++
        }
        settings.deleteSetting("sync_resource_republish")
    }

    /** 只有本版本的全部引用已获确认才收尾；排队完成不能当成同步完成。 */
    private suspend fun finalizeQueuedResources() {
        for (entry in settings.getByPrefix("sync_resource_queued:")) {
            val messageId = entry.key.substringAfter(':')
            val queued = syncJson.decodeFromString<SyncQueuedResource>(entry.value)
            val message = chat.getMessageByUuid(messageId)
            if (message == null || message.deleted) {
                settings.deleteSetting(entry.key)
                continue
            }
            if (queued.revision != "$user:${message.storageFingerprint}") continue
            // 确认内容而非预约身份；ignored 后的权威记录可能使用同内容的另一个 fileId。
            val confirmed = queued.attachments.all { (id, hash) ->
                db.syncAttachmentDao().get(id)?.let { !it.deleted && it.syncStatus == SyncStatus.SYNCED && it.sha256 == hash } == true
            }
            if (!confirmed) continue
            db.withTransaction {
                for (id in queued.retired) db.syncAttachmentDao().get(id)?.takeUnless { it.deleted }?.let {
                    db.syncAttachmentDao().upsert(it.copy(deleted = true, updatedAt = maxOf(System.currentTimeMillis(), it.updatedAt + 1), syncStatus = SyncStatus.PENDING))
                }
                settings.insertSetting(SystemSettingEntity("sync_resource_source:$messageId", queued.revision))
                settings.deleteSetting(entry.key)
            }
            root.listFiles()?.filter { it.name.startsWith("out-$messageId-") || it.name.startsWith("tool-$messageId-") }?.forEach { it.delete() }
        }
    }

    /** 旧版本的拒绝也按具体资源修复；不重排旧 fileId，不恢复用户主动保留本地的数据。 */
    private suspend fun repairRejectedResources() {
        for (failure in settings.getByPrefix("sync_rejected:").filter { it.value == "FILE_NOT_READY" }) {
            val id = failure.key.substringAfterLast(':')
            if (failure.key.startsWith("sync_rejected:attachments:")) {
                val row = db.syncAttachmentDao().get(id)?.takeUnless { it.deleted } ?: continue
                val message = chat.getMessageByUuid(row.messageId)?.takeUnless { it.deleted } ?: continue
                val session = chat.getStoredSession(message.sessionId)?.takeUnless { it.deleted } ?: continue
                val retryKey = "sync_resource_retry:$id"
                if ((settings.getValue(retryKey)?.toLongOrNull() ?: 0) > System.currentTimeMillis()) continue
                // 只废弃仍指向失效身份的回执；上次重传成功但引用尚未提交时保留进度。
                val receiptKey = "sync_file_uploaded:$user:${row.sha256}"
                settings.getValue(receiptKey)?.let { raw ->
                    if (syncJson.decodeFromString<SyncUploadedFile>(raw).info.fileId == row.fileId) settings.deleteSetting(receiptKey)
                }
                val info = SyncFileInfo(requireNotNull(row.fileId), row.size, row.sha256, row.mime)
                val cached = File(root, "${info.fileId}-${info.sha256}").takeIf { it.isFile && it.length() == info.size && syncFileHash(it) == info.sha256 }
                // 暂态错误交给 Worker 退避；请求完成前不能记冷却，否则 429 后重启会跳过续传。
                val next = if (cached != null) uploadFile(cached, row.mime) else api.reserveReadyFile(info)
                if (next != null) {
                    settings.insertSetting(SystemSettingEntity(retryKey, (System.currentTimeMillis() + 60_000).toString()))
                    moreWork = true
                    db.withTransaction {
                        if (db.syncAttachmentDao().get(id) != row || chat.getMessageByUuid(message.uuid)?.deleted != false || chat.getStoredSession(session.id)?.deleted != false) return@withTransaction
                        val updated = row.copy(fileId = next.fileId, updatedAt = maxOf(System.currentTimeMillis(), row.updatedAt + 1), syncStatus = SyncStatus.PENDING)
                        db.syncAttachmentDao().upsert(updated)
                        settings.getValue("sync_resource_meta:$id")?.let { raw ->
                            val metadata = syncJson.parseToJsonElement(raw).jsonObject
                            settings.insertSetting(SystemSettingEntity("sync_resource_meta:$id", JsonObject(metadata + mapOf("fileId" to JsonPrimitive(next.fileId), "clientModifiedAt" to JsonPrimitive(updated.updatedAt))).toString()))
                        }
                    }
                } else {
                    val metadata = settings.getValue("sync_resource_meta:$id")?.let { syncJson.parseToJsonElement(it).jsonObject }
                    val canRebuild = when (metadata?.get("role")?.jsonPrimitive?.contentOrNull) {
                        "message_archive" -> needsArchive(message) && (!message.id.startsWith("cloud:") || settings.getValue("sync_resource_restored:${message.uuid}") != null)
                        "tool_result" -> metadata["resultPath"]?.jsonPrimitive?.contentOrNull?.let { safeResultFile(it).isFile } == true
                        else -> mediaItems(message).any { it.id == metadata?.get("mediaId")?.jsonPrimitive?.contentOrNull && materialize(it) != null }
                    }
                    if (!canRebuild) {
                        settings.insertSetting(SystemSettingEntity(retryKey, (System.currentTimeMillis() + 60_000).toString()))
                        continue
                    }
                    // 有完整原件才能重建；cloud: 仅表示来源，不应挡住定向修复。
                    repairingMessages += message.uuid
                    settings.deleteSetting("sync_resource_source:${message.uuid}")
                    settings.deleteSetting("sync_resource_queued:${message.uuid}")
                    settings.deleteSetting("sync_resource_failed:${message.uuid}")
                    settings.deleteSetting("sync_resource_receive_failed:${message.uuid}")
                }
            } else if (failure.key.startsWith("sync_rejected:user_settings:")) {
                val row = settings.getUserSettingByUuid(id)?.takeIf { it.kind == "skills" && !it.deleted } ?: continue
                val skill = db.skillDao().getInstallation(row.key) ?: continue
                val version = db.skillDao().getVersion(skill.skillId, skill.currentHash) ?: continue
                if (!File(version.rootPath, "SKILL.md").isFile) continue
                val retryKey = "sync_resource_retry:$id"
                if ((settings.getValue(retryKey)?.toLongOrNull() ?: 0) > System.currentTimeMillis()) continue
                val value = syncJson.parseToJsonElement(row.value).jsonObject
                val refs = (value["fileRefs"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty().toSet()
                val invalidated = "invalidated:${row.updatedAt}"
                if (settings.getValue(retryKey) != invalidated) {
                    for (receipt in settings.getByPrefix("sync_file_uploaded:$user:")) {
                        if (syncJson.decodeFromString<SyncUploadedFile>(receipt.value).info.fileId in refs) settings.deleteSetting(receipt.key)
                    }
                    // 同一失败版本只清一次旧回执；否则技能超过限流配额时永远重传不到后面的块。
                    settings.insertSetting(SystemSettingEntity(retryKey, invalidated))
                }
                repairingSkills += row.key
                settings.deleteSetting("sync_resource_failed:user_settings:$id")
            }
        }
    }

    private suspend fun uploadMessage(message: MessageEntity, session: ChatSessionEntity) {
        if (needsArchive(message)) uploadArchive(message, session)
        val mediaItems = mediaItems(message)
        for ((index, media) in mediaItems.withIndex()) {
            val local = materialize(media) ?: throw IllegalArgumentException("LOCAL_FILE_MISSING")
            val name = (media as? SelectedMediaItem.GenericFile)?.displayName ?: "${media.id}.${media.mimeType.substringAfter('/').substringBefore('+')}"
            val kind = when (media) { is SelectedMediaItem.ImageFromUri, is SelectedMediaItem.ImageFromBitmap -> "image"; is SelectedMediaItem.Audio -> "audio"; else -> "file" }
            val chunks = uploadChunks(local, media.mimeType)
            val digest = syncFileHash(local)
            val version = "$digest:$chunkBytes"
            for ((part, info) in chunks.withIndex()) queueAttachment(message, session, info, name, kind, index, "media:${media.id}:$version:$part", buildJsonObject {
                put("role", "media"); put("mediaId", media.id); put("chunkIndex", part); put("chunkCount", chunks.size)
                put("resourceVersion", version)
                put("originalSize", local.length()); put("originalDigest", digest)
            })
            retireResources(message.uuid, "media", version, "mediaId", media.id)
        }
        if (needsArchive(message) || mediaItems.isNotEmpty()) chat.markMessagePendingByUuid(message.uuid)
        settings.deleteSetting("sync_rejected:messages:${message.uuid}")
    }

    private fun needsArchive(message: MessageEntity): Boolean = message.executionSteps.isNotEmpty() ||
        message.executionTrace.any { it is ExecutionTraceEvent.Tool || it is ExecutionTraceEvent.UserMessageBoundary } || !message.webSearchResults.isNullOrEmpty() ||
        message.contextCompressionState != null || SyncPayloadMapper.message(message, "00000000-0000-4000-8000-000000000000").payload.toString().toByteArray().size > 64 * 1024

    private suspend fun uploadArchive(message: MessageEntity, session: ChatSessionEntity) {
        // 完整历史归档保留用户可见文字、思考、搜索结果和工具轨迹。附件单独传，不能把设备路径复制到云端。
        val archived = message.toMessage().copy(id = message.uuid, conversationId = session.uuid, attachments = emptyList(),
            imageUrls = null, parts = emptyList(),
            executionTrace = message.executionTrace.map { event -> if (event is ExecutionTraceEvent.UserMessageBoundary)
                event.copy(messageId = chat.resolveMessageSyncUuid(event.messageId)) else event },
            contextUsageSnapshot = message.contextUsageSnapshot?.copy(messageId = message.uuid))
        val run = db.agentDao().getRunByVisibleMessage(message.id)
        val entries = run?.let { db.agentDao().getEntries(it.id) }.orEmpty()
        val results = linkedMapOf<String, File>()
        fun collectResults(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    element["fullResultPath"]?.jsonPrimitive?.contentOrNull?.let { path ->
                        val file = safeResultFile(path)
                        if (file.isFile && file.length() > 0) results[path] = file
                    }
                    element.values.forEach(::collectResults)
                }
                is JsonArray -> element.forEach(::collectResults)
                else -> Unit
            }
        }
        entries.forEach { runCatching { syncJson.parseToJsonElement(it.payloadJson) }.getOrNull()?.let(::collectResults) }
        // 工具完整输出单独流式加密和分块，不能因输出超过几 MB 就悄悄丢掉，也不把整个文件读入内存。
        for ((path, local) in results) {
            // AAD 绑定消息身份，临时密文文件名也必须带消息 UUID，不能被另一条消息误用。
            val encrypted = File(root, "tool-${message.uuid}-${hashBytes(path.toByteArray())}-${syncFileHash(local)}.enc")
            if (!encrypted.isFile) encryptResult(local, encrypted, session.uuid, message.uuid, path)
            val parts = uploadChunks(encrypted, "application/octet-stream")
            val digest = syncFileHash(encrypted)
            val version = "$digest:$chunkBytes"
            for ((index, info) in parts.withIndex()) queueAttachment(message, session, info, "tool-result.et", "file", index,
                "tool:${hashBytes(path.toByteArray())}:$version:$index", buildJsonObject {
                    put("role", "tool_result"); put("resultPath", path); put("chunkIndex", index); put("chunkCount", parts.size)
                    put("resourceVersion", version)
                    put("originalSize", encrypted.length()); put("originalDigest", digest)
                })
            retireResources(message.uuid, "tool_result", version, "resultPath", path)
        }
        val history = SyncHistoryArchive(archived, run?.copy(sessionId = session.uuid, visibleAssistantMessageId = message.uuid,
            userMessageId = chat.resolveMessageSyncUuid(run.userMessageId),
            requestSnapshotJson = null), entries)
        val json = syncJson.encodeToString(SyncHistoryArchive.serializer(), history)
        val digest = hashBytes(json.toByteArray())
        val archive = File(root, "out-${message.uuid}-$digest-${state.readSecretsEpoch()}")
        if (!archive.isFile) {
            val compressed = java.io.ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(json.toByteArray()) } }.toByteArray()
            archive.writeBytes(crypt(compressed, session.uuid, message.uuid, true))
        }
        val chunks = ((archive.length() + chunkBytes - 1) / chunkBytes).toInt()
        // 同一正文重试可能重新生成 nonce，分块上限也可能改变；不能和前一批混用 ID。
        val version = "${syncFileHash(archive)}:$chunkBytes"
        archive.inputStream().use { input ->
            for (index in 0 until chunks) {
                val chunk = File(root, "chunk-${message.uuid}-$digest-$index")
                chunk.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var remaining = chunkBytes
                    while (remaining > 0) { val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); if (count < 0) break; output.write(buffer, 0, count); remaining -= count }
                }
                val info = try { uploadFile(chunk, "application/octet-stream") } finally { chunk.delete() }
                val extra = buildJsonObject { put("role", "message_archive"); put("resourceDigest", digest)
                    put("resourceVersion", version)
                    put("chunkIndex", index); put("chunkCount", chunks); put("keyVersion", 1) }
                queueAttachment(message, session, info, "history.et", "file", index, "archive:$version:$index", extra)
            }
        }
        // 新归档变小时为旧尾块发墓碑，否则接收端会误以为归档永远缺块。
        retireResources(message.uuid, "message_archive", version)
    }

    private suspend fun retireResources(message: String, role: String, version: String, groupField: String? = null, groupValue: String? = null) {
        for (row in settings.getByPrefix("sync_resource_meta:")) {
            val metadata = syncJson.parseToJsonElement(row.value).jsonObject
            val previous = metadata["resourceVersion"]?.jsonPrimitive?.contentOrNull
            if (metadata["messageId"]?.jsonPrimitive?.contentOrNull == message && metadata["role"]?.jsonPrimitive?.contentOrNull == role &&
                (groupField == null || metadata[groupField]?.jsonPrimitive?.contentOrNull == groupValue) && previous != version) {
                db.syncAttachmentDao().get(row.key.substringAfter(':'))?.takeUnless { it.deleted }?.let {
                    // 旧引用保留到新资源获确认，避免共享文件先失去最后一个引用。
                    retiringAttachments += it.id
                }
            }
        }
    }

    /** 已存在的本地图片 URL 也转为附件，设备路径不会作为云端图片地址复制。 */
    private fun mediaItems(message: MessageEntity): List<SelectedMediaItem> = message.attachments + message.imageUrls.orEmpty().mapNotNull { raw ->
        val uri = Uri.parse(raw)
        val local = when (uri.scheme) { "file" -> uri.path?.let(::File); null -> File(raw); else -> null }
        if (local?.isFile != true || message.attachments.any { (it as? SelectedMediaItem.ImageFromUri)?.filePath == local.absolutePath }) null
        else SelectedMediaItem.ImageFromUri(Uri.fromFile(local), UUID.nameUUIDFromBytes(raw.toByteArray()).toString(), "image/" + local.extension.lowercase().let { if (it == "jpg") "jpeg" else it.ifBlank { "png" } }, local.absolutePath)
    }

    /** 大文件按服务器的实际限制分块，预约幂等键基于内容；重启只补缺块。 */
    private suspend fun uploadFile(file: File, mime: String): SyncFileInfo {
        val hash = syncFileHash(file)
        val marker = "sync_file_uploaded:$user:$hash"
        val receipt = settings.getValue(marker)?.let { runCatching { syncJson.decodeFromString<SyncUploadedFile>(it) }.getOrNull() }
        val age = receipt?.let { System.currentTimeMillis() - it.uploadedAt }
        // 已完成块不再预约，避免 429 后反复预约前十块、永远到不了后面的块。
        // 六小时短于服务端未引用文件的 24 小时回收期；进程退出也能继续用已落盘的回执。
        if (receipt != null && age != null && age in 0 until TimeUnit.HOURS.toMillis(6) &&
            receipt.info.size == file.length() && receipt.info.sha256 == hash) return receipt.info
        val info = api.uploadFile(file, mime)
        require(info.size == file.length() && info.sha256 == hash)
        // 与接收缓存共用路径，引用被拒或排队跨 TTL 时仍有原字节可补传。
        val cached = File(root, "${info.fileId}-${info.sha256}")
        if (file.canonicalFile != cached.canonicalFile) file.copyTo(cached, overwrite = true)
        settings.insertSetting(SystemSettingEntity(marker, syncJson.encodeToString(SyncUploadedFile.serializer(), SyncUploadedFile(info, System.currentTimeMillis()))))
        return info
    }

    private suspend fun uploadChunks(local: File, mime: String): List<SyncFileInfo> {
        if (local.length() == 0L) return emptyList()
        if (local.length() <= chunkBytes) return listOf(uploadFile(local, mime))
        val result = mutableListOf<SyncFileInfo>()
        local.inputStream().use { input ->
            while (true) {
                val temporary = File.createTempFile("transfer-", ".part", root)
                try {
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024); var remaining = chunkBytes
                        while (remaining > 0) { val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); if (count < 0) break; output.write(buffer, 0, count); remaining -= count }
                    }
                    if (temporary.length() == 0L) break
                    result += uploadFile(temporary, mime)
                    require(result.size <= 1024) { "文件分块数超过合同限制" }
                } finally { temporary.delete() }
            }
        }
        return result
    }

    private suspend fun queueAttachment(message: MessageEntity, session: ChatSessionEntity, info: SyncFileInfo,
        rawName: String, kind: String, position: Int, scope: String, extra: JsonObject) = db.withTransaction {
        val current = chat.getMessageByUuid(message.uuid) ?: return@withTransaction
        if (current.deleted || chat.getStoredSession(session.id)?.deleted != false) return@withTransaction
        val baseId = UUID.nameUUIDFromBytes("${message.uuid}:$scope".toByteArray()).toString()
        val identity = "sync_resource_identity:$baseId"
        var id = settings.getValue(identity) ?: baseId
        // 附件墓碑不允许复活；旧内容再次出现时分配新标识，重试仍沿用这一标识。
        if (db.syncAttachmentDao().get(id)?.deleted == true) {
            id = UUID.randomUUID().toString()
            settings.insertSetting(SystemSettingEntity(identity, id))
        }
        val old = db.syncAttachmentDao().get(id)
        val payload = SyncPayloadMapper.attachment(SyncAttachmentEntity(id, session.uuid, message.uuid,
            rawName.substringAfterLast('/').substringAfterLast('\\').filter { !it.isISOControl() }.take(200).ifBlank { "file" },
            info.mime, info.size, info.sha256, info.fileId, kind, position, updatedAt = maxOf(System.currentTimeMillis(), (old?.updatedAt ?: 0) + 1))).payload
        settings.insertSetting(SystemSettingEntity("sync_resource_meta:$id", JsonObject(payload + extra).toString()))
        db.syncAttachmentDao().upsert(SyncAttachmentEntity(id, session.uuid, message.uuid,
            payload.getValue("name").jsonPrimitive.content, info.mime, info.size, info.sha256, info.fileId, kind, position,
            updatedAt = payload.getValue("clientModifiedAt").jsonPrimitive.long))
        queuedAttachments[id] = info.sha256
    }

    private fun materialize(item: SelectedMediaItem): File? {
        val path = when (item) { is SelectedMediaItem.ImageFromUri -> item.filePath; is SelectedMediaItem.ImageFromBitmap -> item.filePath
            is SelectedMediaItem.GenericFile -> item.filePath; is SelectedMediaItem.Audio -> item.filePath }
        path?.let(::File)?.takeIf { it.isFile && it.length() > 0 }?.let { return it }
        val target = File(root, "media-${UUID.nameUUIDFromBytes(item.id.toByteArray())}")
        return try {
            when (item) {
                is SelectedMediaItem.ImageFromBitmap -> target.writeBytes(android.util.Base64.decode(item.bitmapData, android.util.Base64.DEFAULT))
                is SelectedMediaItem.Audio -> { if (item.data.isBlank()) return null; target.writeBytes(android.util.Base64.decode(item.data, android.util.Base64.DEFAULT)) }
                else -> {
                    val uri = when (item) { is SelectedMediaItem.ImageFromUri -> item.uri; is SelectedMediaItem.GenericFile -> item.uri; else -> return null }
                    val input = context.contentResolver.openInputStream(uri) ?: return null
                    input.use { source -> target.outputStream().use { source.copyTo(it) } }
                }
            }
            target.takeIf { it.length() > 0 }
        } catch (_: java.io.IOException) { null } catch (_: SecurityException) { null }
    }

    private suspend fun restoreAttachments() {
        // 归档重建会给旧附件发墓碑，旧 ID 的失败提示随之失效。
        // 统一清理也覆盖升级前留下的记录；仍存活的失败附件必须保留，不能假报成功。
        var cleared = 0
        for (failure in settings.getByPrefix("sync_rejected:attachments:")) {
            val attachment = db.syncAttachmentDao().get(failure.key.removePrefix("sync_rejected:attachments:"))
            if (attachment == null || attachment.deleted) {
                settings.deleteSetting(failure.key)
                cleared++
            }
        }
        if (cleared > 0) syncDiagnostic("resource stale failures cleared=$cleared")
        val records = settings.getByPrefix("sync_resource_meta:").mapNotNull { row ->
            val id = row.key.substringAfter(':')
            val attachment = db.syncAttachmentDao().get(id)?.takeIf { !it.deleted && it.syncStatus == SyncStatus.SYNCED } ?: return@mapNotNull null
            val payload = runCatching { syncJson.parseToJsonElement(row.value).jsonObject }.getOrNull() ?: return@mapNotNull null
            attachment to payload
        }
        for ((messageId, entries) in records.groupBy { it.first.messageId }) {
            // 恢复不降速，只是分批：每批之间放开同步锁并立刻续传，下一轮开头会先上传这期间的本地修改。
            if (downloads >= RESTORE_DOWNLOADS_PER_RUN) { moreWork = true; break }
            val message = chat.getMessageByUuid(messageId)?.takeUnless { it.deleted } ?: continue
            val failureMarker = "sync_resource_receive_failed:$messageId"
            // 旧版本只保存笼统的下载失败，升级后重查一次并记录真实错误码；
            // 同一版本仍然只尝试一次永久错误，不能让坏附件无限请求服务器。
            val version = "2:" + entries.joinToString { "${it.first.id}:${it.first.updatedAt}" }
            if (settings.getValue(failureMarker) == version) continue
            try {
            val toolFiles = entries.filter { it.second["role"]?.jsonPrimitive?.contentOrNull == "tool_result" }
                .groupBy { it.second.getValue("resultPath").jsonPrimitive.content }
            for ((path, parts) in toolFiles) {
                val metadata = parts.maxBy { it.first.updatedAt }.second
                val chosen = parts.filter { it.second["originalDigest"] == metadata["originalDigest"] && it.second["resourceVersion"] == metadata["resourceVersion"] }.sortedBy { it.second.getValue("chunkIndex").jsonPrimitive.int }
                if (chosen.size != metadata.getValue("chunkCount").jsonPrimitive.int) continue
                val encrypted = assemble(chosen.map { (record, _) -> SyncFileInfo(requireNotNull(record.fileId), record.size, record.sha256, record.mime) },
                    metadata.getValue("originalSize").jsonPrimitive.long, metadata.getValue("originalDigest").jsonPrimitive.content)
                decryptResult(encrypted, safeResultFile(path), entries.first().first.sessionId, messageId, path)
            }
            val archives = entries.filter { it.second["role"]?.jsonPrimitive?.contentOrNull == "message_archive" &&
                it.second["keyVersion"]?.jsonPrimitive?.longOrNull == 1L }
                .groupBy { it.second["resourceVersion"] ?: it.second["resourceDigest"] }.values
                .filter { group -> group.size == group.first().second["chunkCount"]?.jsonPrimitive?.intOrNull &&
                    group.map { it.second["chunkIndex"]?.jsonPrimitive?.intOrNull }.sortedBy { it ?: -1 } == group.indices.toList() }
                .maxByOrNull { group -> group.maxOf { it.first.updatedAt } }.orEmpty()
            val count = archives.firstOrNull()?.second?.get("chunkCount")?.jsonPrimitive?.intOrNull
            if (count != null && archives.size == count && archives.map { it.second["resourceDigest"] }.distinct().size == 1) {
                val digest = archives.first().second.getValue("resourceDigest").jsonPrimitive.content
                val marker = "sync_resource_restored:$messageId"
                // 第 4 版允许真实 CLOUD Run 交接，并保留其账号、游标和 generation。
                val restoredRevision = "4:$digest"
                if (settings.getValue(marker) != restoredRevision) {
                    val history = readArchive(archives, messageId, digest)
                    val restored = history.message
                    require(restored.id == messageId && restored.conversationId == entries.first().first.sessionId)
                    history.resultFiles.forEach { (path, encoded) ->
                        val target = safeResultFile(path)
                        val bytes = SyncCrypto.decodeCanonicalBase64(encoded)
                        require(bytes.size <= 8 * 1024 * 1024)
                        target.parentFile?.mkdirs()
                        val temporary = File(target.parentFile, target.name + ".sync-part")
                        temporary.writeBytes(bytes)
                        java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                    db.withTransaction {
                        val current = chat.getMessageByUuid(messageId)?.takeUnless { it.deleted } ?: return@withTransaction
                        // 手机生成的本机原件不被覆盖；云端执行的权威归档可恢复原设备占位，但绝不覆盖本机活跃 Run。
                        val localRun = db.agentDao().getRunByVisibleMessage(current.id)
                        val cloudOrigin = archives.all { it.second["origin"]?.jsonPrimitive?.contentOrNull == "cloud_agent" }
                        val cloudOwned = cloudOrigin && when {
                            localRun == null -> true
                            localRun.executionOwner == "CLOUD" -> localRun.cloudAccountId == user &&
                                localRun.cloudRunId == history.run?.id && localRun.id == history.run?.id
                            else -> localRun.terminalReason == "SYNC_HISTORY_ONLY"
                        }
                        // cloud: 只是本机占位命名，不能绕过云任务的账号/run 校验或覆盖真实本机 Run。
                        val canRestore = cloudOwned || !cloudOrigin && current.id.startsWith("cloud:") &&
                            (localRun == null || localRun.terminalReason == "SYNC_HISTORY_ONLY")
                        if (canRestore) {
                            val next = restored.copy(id = current.id, conversationId = current.sessionId, attachments = current.attachments,
                                contextUsageSnapshot = restored.contextUsageSnapshot?.copy(messageId = current.id),
                                executionTrace = restored.executionTrace.map { event -> if (event is ExecutionTraceEvent.UserMessageBoundary)
                                    event.copy(messageId = chat.resolveLocalMessageReference(event.messageId)) else event })
                            val entity = next.toEntity(current.sessionId).copy(uuid = current.uuid, updatedAt = current.updatedAt,
                                everSynced = true, syncStatus = SyncStatus.SYNCED)
                            chat.writeMessages(listOf(entity))
                            if (cloudOwned) settings.insertSetting(SystemSettingEntity("sync_resource_source:$messageId", "$user:${entity.storageFingerprint}"))
                            history.run?.let { archivedRun ->
                                if (cloudOrigin) require(archivedRun.status in setOf("COMPLETED", "FAILED", "CANCELLED"))
                                val mapped = archivedRun.copy(sessionId = current.sessionId, visibleAssistantMessageId = current.id,
                                    userMessageId = chat.resolveLocalMessageReference(archivedRun.userMessageId),
                                    requestSnapshotJson = null, status = if (archivedRun.status in setOf("COMPLETED", "FAILED", "CANCELLED")) archivedRun.status else "CANCELLED",
                                    terminalReason = "SYNC_HISTORY_ONLY", loopState = "COMPLETED")
                                val run = if (cloudOwned && localRun?.executionOwner == "CLOUD") localRun.copy(
                                    status = mapped.status, updatedAt = mapped.updatedAt, userMessageId = mapped.userMessageId,
                                    terminalReason = "SYNC_HISTORY_ONLY", loopState = mapped.status,
                                ) else mapped
                                db.agentDao().upsertRun(run)
                                history.requests.forEach { request -> require(request.runId == run.id); db.agentDao().upsertRequest(request) }
                                history.entries.forEach { entry -> require(entry.runId == run.id); db.agentDao().upsertEntry(entry) }
                                if (run.executionOwner == "CLOUD") db.agentDao().deleteRunSnapshotChunks(run.id)
                            }
                            chat.deleteDrafts(current.sessionId, listOf(current.id))
                            settings.insertSetting(SystemSettingEntity(marker, restoredRevision))
                        }
                    }
                }
                // 先提交完整历史再清密文。重启可重复清理，绝不在仅收到 terminal/简版消息时回收。
                val archived = db.agentDao().getRunByVisibleMessage(message.id)
                if (archived?.executionOwner == "CLOUD" && archived.cloudAccountId == user &&
                    archived.terminalReason == "SYNC_HISTORY_ONLY" && settings.getValue(marker) == restoredRevision) {
                    com.android.everytalk.data.computer.ComputerCredentialStore(context).deleteCloudAgentInput(user, archived.id)
                }
            }
            val media = entries.filter { it.second["role"]?.jsonPrimitive?.contentOrNull in setOf(null, "media") && it.first.fileId != null }
            if (media.isNotEmpty()) {
                val items = media.groupBy { it.second["mediaId"]?.jsonPrimitive?.contentOrNull ?: it.first.id }.values
                    .sortedBy { it.first().first.position }.map { parts ->
                    val (attachment, metadata) = parts.maxBy { it.first.updatedAt }
                    val local = if (metadata["chunkCount"] != null) {
                        val infos = parts.filter { it.second["originalDigest"] == metadata["originalDigest"] && it.second["resourceVersion"] == metadata["resourceVersion"] }.sortedBy { it.second.getValue("chunkIndex").jsonPrimitive.int }.map { (record, _) ->
                            SyncFileInfo(requireNotNull(record.fileId), record.size, record.sha256, record.mime)
                        }
                        require(infos.size == metadata.getValue("chunkCount").jsonPrimitive.int)
                        assemble(infos, metadata.getValue("originalSize").jsonPrimitive.long, metadata.getValue("originalDigest").jsonPrimitive.content)
                    } else file(attachment)
                    val mediaId = metadata["mediaId"]?.jsonPrimitive?.contentOrNull ?: attachment.id
                    when (attachment.kind) {
                        "image", "thumbnail" -> SelectedMediaItem.ImageFromUri(Uri.fromFile(local), mediaId, attachment.mime, local.absolutePath)
                        "audio" -> SelectedMediaItem.Audio(mediaId, attachment.mime, filePath = local.absolutePath)
                        else -> SelectedMediaItem.GenericFile(Uri.fromFile(local), mediaId, attachment.name, attachment.mime, local.absolutePath)
                    }
                }
                db.withTransaction {
                    val current = chat.getMessageByUuid(messageId)?.takeUnless { it.deleted } ?: return@withTransaction
                    // 原设备已有可读原件时保留路径；本机文件丢失则允许用云端原件补回。
                    if ((current.id.startsWith("cloud:") || current.attachments.any { materialize(it) == null }) && current.attachments != items) {
                        val restored = current.toMessage().copy(attachments = items).toEntity(current.sessionId).copy(
                            uuid = current.uuid, updatedAt = current.updatedAt, syncStatus = current.syncStatus, everSynced = current.everSynced)
                        chat.writeMessages(listOf(restored))
                        // 这里只换成本机可读路径，不代表内容改变；已确认版本跟随新指纹，避免无意义回传。
                        val sourceKey = "sync_resource_source:$messageId"
                        if (settings.getValue(sourceKey) == "$user:${current.storageFingerprint}") {
                            settings.insertSetting(SystemSettingEntity(sourceKey, "$user:${restored.storageFingerprint}"))
                        }
                    }
                }
            }
            chat.getMessageByUuid(messageId)?.let { current ->
                if (current.id.startsWith("cloud:")) settings.insertSetting(SystemSettingEntity("sync_resource_source:$messageId", "$user:${current.storageFingerprint}"))
            }
            settings.deleteSetting("sync_rejected:attachments:${entries.first().first.id}")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: SyncHttpException) {
                if (error.statusCode in setOf(401, 408, 409, 429) || error.statusCode >= 500) throw error
                // 只记录受限字符的错误码与状态，绝不输出文件地址、内容或认证信息。
                val code = error.serverCode?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) } ?: "RESOURCE_DOWNLOAD_FAILED"
                syncDiagnostic("resource download http=${error.statusCode} code=$code")
                settings.insertSetting(SystemSettingEntity("sync_rejected:attachments:${entries.first().first.id}", code))
                settings.insertSetting(SystemSettingEntity(failureMarker, version))
            } catch (_: java.security.GeneralSecurityException) {
                settings.insertSetting(SystemSettingEntity("sync_rejected:attachments:${entries.first().first.id}", "RESOURCE_DECRYPTION_FAILED"))
                settings.insertSetting(SystemSettingEntity(failureMarker, version))
            } catch (_: IllegalArgumentException) {
                settings.insertSetting(SystemSettingEntity("sync_rejected:attachments:${entries.first().first.id}", "RESOURCE_VALIDATION_FAILED"))
                settings.insertSetting(SystemSettingEntity(failureMarker, version))
            } catch (_: java.util.zip.ZipException) {
                settings.insertSetting(SystemSettingEntity("sync_rejected:attachments:${entries.first().first.id}", "RESOURCE_VALIDATION_FAILED"))
                settings.insertSetting(SystemSettingEntity(failureMarker, version))
            } catch (error: java.io.IOException) {
                // CipherInputStream 将 GCM 标签错误包在 IOException 中；损坏密文不能无限重试。
                if (generateSequence<Throwable>(error) { it.cause }.any { it is java.security.GeneralSecurityException } || error is java.io.EOFException) {
                    settings.insertSetting(SystemSettingEntity("sync_rejected:attachments:${entries.first().first.id}", "RESOURCE_DECRYPTION_FAILED"))
                    settings.insertSetting(SystemSettingEntity(failureMarker, version))
                } else throw error
            }
        }
        SyncLocalChanges.notifyChanged()
    }

    private suspend fun file(attachment: SyncAttachmentEntity): File = cached(SyncFileInfo(requireNotNull(attachment.fileId), attachment.size, attachment.sha256, attachment.mime))

    /** 分块解密写临时压缩文件，GCM 验证完成才解析；解压流计数防止压缩炸弹耗尽内存。 */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private suspend fun readArchive(entries: List<Pair<SyncAttachmentEntity, JsonObject>>, messageId: String, digest: String): SyncHistoryArchive {
        val encrypted = File(root, "receive-$messageId.enc")
        val compressed = File(root, "receive-$messageId.gz")
        try {
            encrypted.outputStream().use { output ->
                for ((attachment, _) in entries.sortedBy { it.second.getValue("chunkIndex").jsonPrimitive.int })
                    file(attachment).inputStream().use { it.copyTo(output) }
            }
            val epoch = 0L
            val dek = requireNotNull(historyDek).copyOf()
            try {
                encrypted.inputStream().use { input ->
                    val nonce = ByteArray(12); java.io.DataInputStream(input).readFully(nonce)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(128, nonce))
                    cipher.updateAAD(historyAad(entries.first().first.sessionId, messageId, epoch))
                    javax.crypto.CipherInputStream(input, cipher).use { source -> compressed.outputStream().use { source.copyTo(it) } }
                }
            } finally { dek.fill(0) }
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            val memoryBudget = (Runtime.getRuntime().maxMemory() / 4).coerceIn(4L * 1024 * 1024, 128L * 1024 * 1024)
            var readBytes = 0L
            val history = GZIPInputStream(compressed.inputStream()).use { gzip ->
                val bounded = object : java.io.FilterInputStream(gzip) {
                    private fun count(size: Int): Int { if (size > 0) { readBytes += size; require(readBytes <= memoryBudget) { "RESOURCE_TOO_LARGE_FOR_DEVICE" } }; return size }
                    override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = count(`in`.read(buffer, offset, length))
                }
                java.security.DigestInputStream(bounded, hash).use { input ->
                    syncJson.decodeFromStream(SyncHistoryArchive.serializer(), input)
                }
            }
            require(hash.digest().joinToString("") { "%02x".format(it) } == digest) { "历史归档校验失败" }
            return history
        } finally { encrypted.delete(); compressed.delete() }
    }
    private suspend fun cached(info: SyncFileInfo): File {
        val target = File(root, "${info.fileId}-${info.sha256}")
        if (!target.isFile || target.length() != info.size || syncFileHash(target) != info.sha256) { api.downloadFile(info, target); downloads++ }
        return target
    }

    /** 工具输出使用独立 AAD 绑定路径，认证成功后才覆盖文件；失败保留原文件。 */
    private fun encryptResult(source: File, target: File, session: String, message: String, path: String) {
        val nonce = SyncCrypto.randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(requireNotNull(historyDek), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(resultAad(session, message, path))
        val temporary = File(target.path + ".part")
        try {
            temporary.outputStream().use { output -> output.write(nonce); javax.crypto.CipherOutputStream(output, cipher).use { encrypted -> source.inputStream().use { it.copyTo(encrypted) } } }
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private fun decryptResult(source: File, target: File, session: String, message: String, path: String) {
        val temporary = File(target.path + ".sync-part")
        target.parentFile?.mkdirs()
        try {
            source.inputStream().use { input ->
                val nonce = ByteArray(12); java.io.DataInputStream(input).readFully(nonce)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(requireNotNull(historyDek), "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD(resultAad(session, message, path))
                javax.crypto.CipherInputStream(input, cipher).use { plain -> temporary.outputStream().use { plain.copyTo(it) } }
            }
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private fun resultAad(session: String, message: String, path: String): ByteArray =
        JsonArray(listOf("everytalk-tool-result", user, session, message, path, "0").map(::JsonPrimitive)).toString().toByteArray(Charsets.UTF_8)

    /** 所有分块完成且整体哈希一致后才发布原文件，不能把半个文件交给图片或 Skill 加载器。 */
    private suspend fun assemble(parts: List<SyncFileInfo>, size: Long, hash: String): File {
        require(parts.size <= 1024 && size >= 0 && hash.matches(Regex("[0-9a-f]{64}")))
        require(size == 0L || parts.isNotEmpty())
        val target = File(root, "assembled-$hash")
        if (target.isFile && target.length() == size && syncFileHash(target) == hash) return target
        val temporary = File(root, "assembled-$hash.part")
        try {
            temporary.outputStream().use { output ->
                var total = 0L
                for (part in parts) { total += part.size; require(total <= size); cached(part).inputStream().use { it.copyTo(output) } }
                require(total == size)
            }
            require(syncFileHash(temporary) == hash)
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally { temporary.delete() }
    }

    private suspend fun uploadSkills() {
        for (skill in db.skillDao().getAll()) {
            val row = settings.getUserSetting("skills", skill.skillId)?.takeUnless { it.deleted } ?: continue
            val value = syncJson.parseToJsonElement(row.value).jsonObject
            if (skill.skillId !in repairingSkills && (value["fileManifest"] != null || value["manifestRef"] != null)) continue
            val version = db.skillDao().getVersion(skill.skillId, skill.currentHash) ?: continue
            val directory = File(version.rootPath)
            if (!directory.isDirectory) continue
            isolatedResource("user_settings", row.uuid, "upload:${skill.currentHash}") {
            val validated = SkillPackageValidator.validate(directory)
            require(validated.contentHash == skill.currentHash)
            val fileRefs = mutableListOf<String>()
            val manifest = validated.manifest.map { entry ->
                val local = File(directory, entry.path)
                val parts = uploadChunks(local, "application/octet-stream")
                fileRefs += parts.map { it.fileId }
                buildJsonObject { put("path", entry.path); put("size", local.length()); put("sha256", syncFileHash(local))
                    put("chunks", syncJson.encodeToJsonElement(ListSerializer(SyncFileInfo.serializer()), parts)); put("text", entry.text) }
            }
            val manifestFile = File(root, "manifest-${UUID.nameUUIDFromBytes(skill.skillId.toByteArray())}.json")
            manifestFile.writeText(JsonArray(manifest).toString())
            val manifestInfo = try { uploadFile(manifestFile, "application/octet-stream") } finally { manifestFile.delete() }
            val refs = (fileRefs + manifestInfo.fileId).distinct().map(::JsonPrimitive)
            require(refs.size <= 1024) { "Skill 文件数超过服务器合同限制" }
            db.withTransaction {
                val current = settings.getUserSetting("skills", row.key)?.takeUnless { it.deleted } ?: return@withTransaction
                if (db.skillDao().getInstallation(skill.skillId)?.currentHash != skill.currentHash) return@withTransaction
                val latest = syncJson.parseToJsonElement(current.value).jsonObject
                val next = JsonObject((latest - "fileManifest") + mapOf("manifestRef" to syncJson.encodeToJsonElement(SyncFileInfo.serializer(), manifestInfo),
                    "fileRefs" to JsonArray(refs)))
                settings.upsertUserSettings(listOf(current.copy(value = next.toString(), updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1), syncStatus = SyncStatus.PENDING)))
            }
            }
        }
    }

    private suspend fun restoreSkills() {
        for (row in settings.getAllUserSettings().filter { it.kind == "skills" && !it.deleted &&
                settings.getValue("sync_rejected:user_settings:${it.uuid}") != "FILE_NOT_READY" }) {
            val skill = db.skillDao().getInstallation(row.key) ?: continue
            isolatedResource("user_settings", row.uuid, "receive:${row.updatedAt}") {
            val value = RoomSyncStore(db, SyncSecretManager(context), user).decodedSetting(row) ?: return@isolatedResource
            val manifest = value["fileManifest"] as? JsonArray ?: value["manifestRef"]?.let {
                val info = syncJson.decodeFromJsonElement(SyncFileInfo.serializer(), it)
                require(info.size <= 1024 * 1024) { "Skill 文件清单过大" }
                syncJson.parseToJsonElement(cached(info).readText()).jsonArray
            } ?: return@isolatedResource
            val old = db.skillDao().getVersion(skill.skillId, skill.currentHash)
            if (old != null && File(old.rootPath).resolve("SKILL.md").isFile) return@isolatedResource
            val directory = File(root, "skill-${UUID.nameUUIDFromBytes(skill.skillId.toByteArray())}-${skill.currentHash}").apply { mkdirs() }
            for (item in manifest) {
                val entry = item.jsonObject
                val path = requireNotNull(entry["path"]?.jsonPrimitive?.contentOrNull)
                require(path.isNotEmpty() && !path.contains('\\') && !path.contains(':') && !path.startsWith('/') && path.split('/').none { it == ".." || it.isEmpty() })
                val target = File(directory, path)
                require(target.canonicalPath.startsWith(directory.canonicalPath + File.separator))
                val size = entry.getValue("size").jsonPrimitive.long
                val hash = entry.getValue("sha256").jsonPrimitive.content
                val parts = entry["chunks"]?.let { syncJson.decodeFromJsonElement(ListSerializer(SyncFileInfo.serializer()), it) }
                    ?: listOf(SyncFileInfo(entry.getValue("fileId").jsonPrimitive.content, size, hash, "application/octet-stream"))
                target.parentFile?.mkdirs(); assemble(parts, size, hash).copyTo(target, overwrite = true)
            }
            val validated = SkillPackageValidator.validate(directory)
            require(validated.contentHash == skill.currentHash) { "Skill 文件版本校验失败" }
            db.withTransaction {
                if (db.skillDao().getInstallation(row.key)?.currentHash != skill.currentHash) return@withTransaction
                db.skillDao().upsertVersion(SkillVersionEntity(skill.skillId, skill.currentHash, null, directory.absolutePath,
                    syncJson.encodeToString(ListSerializer(SkillFileManifestEntry.serializer()), validated.manifest),
                    buildJsonObject { validated.frontmatter.forEach { (key, value) -> put(key, value) } }.toString(), skill.createdAt))
            }
            }
        }
    }

    /** 一份坏资源不能挡住其他会话；网络故障仍向上重试，永久错误按版本停止重试并显示。 */
    private suspend fun isolatedResource(entity: String, id: String, revision: String, block: suspend () -> Unit) {
        val marker = "sync_resource_failed:$entity:$id"
        if (settings.getValue(marker) == revision) return
        try {
            block()
            if (settings.getValue("sync_rejected:$entity:$id") != "FILE_NOT_READY") settings.deleteSetting("sync_rejected:$entity:$id")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: SyncHttpException) {
            if (error.statusCode in setOf(401, 408, 409, 429) || error.statusCode >= 500) throw error
            settings.insertSetting(SystemSettingEntity("sync_rejected:$entity:$id", "RESOURCE_TRANSFER_FAILED"))
            settings.insertSetting(SystemSettingEntity(marker, revision))
        } catch (_: IllegalArgumentException) {
            settings.insertSetting(SystemSettingEntity("sync_rejected:$entity:$id", "RESOURCE_VALIDATION_FAILED"))
            settings.insertSetting(SystemSettingEntity(marker, revision))
        } catch (_: NoSuchElementException) {
            settings.insertSetting(SystemSettingEntity("sync_rejected:$entity:$id", "RESOURCE_VALIDATION_FAILED"))
            settings.insertSetting(SystemSettingEntity(marker, revision))
        }
    }

    private suspend fun crypt(bytes: ByteArray, session: String, message: String, encrypt: Boolean): ByteArray {
        val epoch = 0L
        val dek = requireNotNull(historyDek).copyOf()
        try {
            val nonce = if (encrypt) SyncCrypto.randomBytes(12) else bytes.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(historyAad(session, message, epoch))
            return if (encrypt) nonce + cipher.doFinal(bytes) else cipher.doFinal(bytes, 12, bytes.size - 12)
        } finally { dek.fill(0) }
    }

    private fun hashBytes(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun historyAad(session: String, message: String, epoch: Long): ByteArray =
        JsonArray(listOf("everytalk-history", user, session, message, epoch.toString()).map(::JsonPrimitive)).toString().toByteArray()
    /** 工具结果只允许进入应用的结果归档目录，不能由云端路径写到配置或可执行目录。 */
    private fun safeResultFile(path: String): File {
        require(path.startsWith("agent_tool_results/") && !path.contains('\\') && !path.contains(':') && path.split('/').none { it == ".." || it.isBlank() })
        val target = File(context.filesDir, path)
        require(target.canonicalPath.startsWith(File(context.filesDir, "agent_tool_results").canonicalPath + File.separator))
        return target
    }
    private companion object {
        const val CHUNK_BYTES = 4L * 1024 * 1024
        const val RESTORE_DOWNLOADS_PER_RUN = 40
    }
}
