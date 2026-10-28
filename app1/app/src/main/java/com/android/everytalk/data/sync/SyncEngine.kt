package com.android.everytalk.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface SyncRunResult {
    data object Disabled : SyncRunResult
    data object Completed : SyncRunResult
    data object MoreWork : SyncRunResult
    data class PausedForEpoch(val epoch: Long) : SyncRunResult
}

/** 所有 Worker、用户决定和退出共享此锁，禁止跨账号并发应用远端数据。 */
internal object SyncRuntimeLock { val mutex = Mutex() }

/** Release 也保留的同步诊断；调用处只传阶段、数量、游标和错误类型，绝不传正文、地址或凭据。 */
internal fun syncDiagnostic(message: String) {
    android.util.Log.println(android.util.Log.INFO, "EveryTalkSync", message)
}

/**
 * 对账日志只读取标量列，不读取正文或机密；随机标识散列后用于比较两台设备的缺行、
 * 墓碑、所属会话、正文/思考过程截断哈希和过滤条件。每个进程只在快照变化时输出，不能用总数代替逐条对账。
 */
internal object SyncInventoryDiagnostic {
    private var previous: String? = null

    suspend fun log(database: com.android.everytalk.data.database.AppDatabase) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            fun tag(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
            val rows = mutableListOf<String>()
            // 每行采样最多约 60KB，一次查全表必然撑爆 CursorWindow；按唯一 uuid 分页，每页远低于 2MB。
            var lastUuid = ""
            do {
                var pageRows = 0
                database.openHelper.readableDatabase.query("""
                SELECT m.uuid, s.uuid, m.deleted, s.deleted, m.sync_status, m.ever_synced,
                       m.isPlaceholderName, m.sender, length(m.text), length(m.reasoning),
                       substr(m.text, 1, 4096), substr(m.text, -4096),
                       substr(m.reasoning, 1, 4096), substr(m.reasoning, -4096),
                       substr(m.contentParts, 1, 2048), substr(m.executionTrace, 1, 2048),
                       CASE WHEN m.id LIKE 'cloud:%' THEN 1 ELSE 0 END
                FROM messages m LEFT JOIN chat_sessions s ON s.id=m.sessionId
                WHERE (s.id NOT IN ('last_open_chat_v1','last_open_image_generation_v1') OR s.id IS NULL)
                  AND m.uuid > ?
                ORDER BY m.uuid
                LIMIT 16
            """.trimIndent(), arrayOf<Any>(lastUuid)).use { cursor ->
                while (cursor.moveToNext()) rows += buildString {
                    pageRows++
                    lastUuid = cursor.getString(0)
                    append(tag(cursor.getString(0))); append('/'); append(tag(cursor.getString(1).orEmpty()))
                    // 只输出删除、同步状态、发送者和长度；10..15 是正文采样列，只能参与哈希，不能写入日志。
                    for (column in 2..9) { append('/'); append(cursor.getString(column) ?: "-") }
                    append('/'); append(tag(listOf(10, 11).joinToString("\u001f") { cursor.getString(it).orEmpty() }))
                    append('/'); append(tag(listOf(12, 13).joinToString("\u001f") { cursor.getString(it).orEmpty() }))
                    append('/'); append(tag(listOf(14, 15).joinToString("\u001f") { cursor.getString(it).orEmpty() }))
                    append('/'); append(cursor.getString(16) ?: "-")
                }
            }
            } while (pageRows == 16)
            val digest = tag(rows.joinToString("\n"))
            if (digest != previous) {
                syncDiagnostic("inventory v=2 digest=$digest rows=${rows.size} columns=message/session/md/sd/status/ever/placeholder/sender/textChars/reasoningChars/textHash/reasoningHash/bodyPartsHash/cloud")
                // 每行日志控制在 logcat 的 4 KiB 上限内，防止诊断本身被截断。
                rows.chunked(20).forEachIndexed { index, page ->
                    syncDiagnostic("inventory part=$index rows=${page.joinToString(",")}")
                }
                previous = digest
            }
        }
}

/**
 * 先 pull 检查 epoch 再 push；每页数据与游标原子提交，每批结果独立确认。
 * 上传不推进 pull 游标，以免跳过其他设备的并发变更。最多连续上传 20 批，避免超过 Worker 时间预算。
 */
internal class SyncEngine(
    private val api: SyncApi,
    private val store: SyncStore,
    private val state: SyncStateStore,
    private val canSync: suspend () -> Boolean = { true },
    private val onSecretsEpochChanged: suspend () -> Unit = {},
    private val afterPull: suspend () -> Unit = {},
    private val beforePull: suspend () -> Unit = {},
    private val beforeComplete: suspend () -> Unit = {},
    private val beforePullPage: suspend () -> Unit = {},
) {
    suspend fun runOnce(): SyncRunResult = SyncRuntimeLock.mutex.withLock {
        if (!canSync() || !state.isEnabled() || state.epochDecision() != null) return@withLock SyncRunResult.Disabled
        store.prepareForSync()
        beforePull()
        pullAll()?.let { return@withLock it }
        afterPull()
        repeat(20) {
            if (!canSync() || !state.isEnabled()) return@withLock SyncRunResult.Disabled
            val pending = store.pendingBatch()
            if (pending.isEmpty()) {
                // 先确认普通记录，再补文件；文件预约被限流时不能拖住模型凭据和文字回复。
                // 资源恢复仍在同一把锁内，不能与其他 Worker 或换账号流程同时写入。
                beforeComplete()
                if (!canSync()) return@withLock SyncRunResult.Disabled
                return@withLock if (store.pendingBatch(1).isEmpty()) SyncRunResult.Completed else SyncRunResult.MoreWork
            }
            syncDiagnostic("push items=${pending.size} entities=${pending.groupingBy { it.entity }.eachCount()}")
            store.reservePush(pending)
            val response = api.push(pending.map { it.change })
            syncDiagnostic("push results=${response.results.groupingBy { it.status }.eachCount()}")
            // 仅输出合同错误码及数量，排查逐条拒绝时无需暴露 ID、正文或凭据。
            val rejections = response.results.mapNotNull { it.error?.code }
                .map { it.takeIf { code -> code.matches(Regex("[A-Za-z_]{1,64}")) } ?: "UNKNOWN_ERROR" }
            if (rejections.isNotEmpty()) syncDiagnostic("push rejected=${rejections.groupingBy { it }.eachCount()}")
            // 退出/换账号发生后，响应不允许写入新的账号快照。
            if (!canSync()) return@withLock SyncRunResult.Disabled
            store.applyPush(response, pending)
            pullAll()?.let { return@withLock it }
        }
        SyncRunResult.MoreWork
    }

    private suspend fun pullAll(): SyncRunResult? {
        var cursor = state.readCursor()
        var restartedForSecretsEpoch = false
        do {
            if (!canSync() || !state.isEnabled()) return SyncRunResult.Disabled
            val page = api.pull(cursor.cursor, 500)
            syncDiagnostic("pull since=${cursor.cursor} next=${page.nextCursor} count=${page.changes.size} more=${page.hasMore}")
            if (!canSync()) return SyncRunResult.Disabled
            require(page.epoch >= 0 && page.nextCursor >= 0)
            val knownSecretsEpoch = state.readSecretsEpoch()
            if (knownSecretsEpoch >= 0 && page.secretsEpoch != knownSecretsEpoch) {
                if (restartedForSecretsEpoch) {
                    return SyncRunResult.Disabled
                }
                // 密文缓存和配置快照必须从零重新核对；普通会话数据仍按原 UUID 幂等处理。
                state.resetSecretsEpoch(page.secretsEpoch)
                onSecretsEpochChanged()
                restartedForSecretsEpoch = true
                cursor = state.readCursor()
                continue
            }
            if (cursor.epoch >= 0 && page.epoch != cursor.epoch) {
                state.requireEpochDecision(page.epoch)
                return SyncRunResult.PausedForEpoch(page.epoch)
            }
            require(page.nextCursor >= cursor.cursor)
            require(page.changes.all { it.serverSeq > cursor.cursor && it.serverSeq <= page.nextCursor })
            require(page.changes.map { it.serverSeq }.distinct().size == page.changes.size)
            require(!page.hasMore || page.nextCursor > cursor.cursor) { "服务器返回无进展的分页" }
            // 凭据保存在 Keystore 文件，不能只依赖 Room 投影；每页应用前也要封存最新修改。
            beforePullPage()
            store.applyPullPage(page)
            if (knownSecretsEpoch < 0) state.recordSecretsEpoch(page.secretsEpoch)
            cursor = SyncCursorState(page.nextCursor, page.epoch)
        } while (page.hasMore)
        return null
    }
}

