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

/**
 * 先 pull 检查 epoch 再 push；每页数据与游标原子提交，每批结果独立确认。
 * 上传不推进 pull 游标，以免跳过其他设备的并发变更。最多连续上传 20 批，避免超过 Worker 时间预算。
 */
internal class SyncEngine(
    private val api: SyncApi,
    private val store: SyncStore,
    private val state: SyncStateStore,
    private val canSync: suspend () -> Boolean = { true },
) {
    suspend fun runOnce(): SyncRunResult = SyncRuntimeLock.mutex.withLock {
        if (!canSync() || !state.isEnabled() || state.epochDecision() != null) return@withLock SyncRunResult.Disabled
        pullAll()?.let { return@withLock it }
        repeat(20) {
            if (!canSync() || !state.isEnabled()) return@withLock SyncRunResult.Disabled
            val pending = store.pendingBatch()
            if (pending.isEmpty()) return@withLock SyncRunResult.Completed
            store.reservePush(pending)
            val response = api.push(pending.map { it.change })
            // 退出/换账号发生后，响应不允许写入新的账号快照。
            if (!canSync()) return@withLock SyncRunResult.Disabled
            store.applyPush(response, pending)
            pullAll()?.let { return@withLock it }
        }
        SyncRunResult.MoreWork
    }

    private suspend fun pullAll(): SyncRunResult? {
        var cursor = state.readCursor()
        do {
            if (!canSync() || !state.isEnabled()) return SyncRunResult.Disabled
            val page = api.pull(cursor.cursor, 500)
            if (!canSync()) return SyncRunResult.Disabled
            require(page.epoch >= 0 && page.nextCursor >= 0)
            if (cursor.epoch >= 0 && page.epoch != cursor.epoch) {
                state.requireEpochDecision(page.epoch)
                return SyncRunResult.PausedForEpoch(page.epoch)
            }
            require(page.nextCursor >= cursor.cursor)
            require(page.changes.all { it.serverSeq > cursor.cursor && it.serverSeq <= page.nextCursor })
            require(page.changes.map { it.serverSeq }.distinct().size == page.changes.size)
            require(!page.hasMore || page.nextCursor > cursor.cursor) { "服务器返回无进展的分页" }
            store.applyPullPage(page)
            cursor = SyncCursorState(page.nextCursor, page.epoch)
        } while (page.hasMore)
        return null
    }
}

