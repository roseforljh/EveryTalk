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
            store.applyPullPage(page)
            if (knownSecretsEpoch < 0) state.recordSecretsEpoch(page.secretsEpoch)
            cursor = SyncCursorState(page.nextCursor, page.epoch)
        } while (page.hasMore)
        return null
    }
}

