package com.android.everytalk.statecontroller

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * 暂停期间取消 UI 上游收集并保留最后一帧，恢复后立即读取最新状态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> Flow<T>.freezeWhileStreamingPaused(paused: StateFlow<Boolean>): Flow<T> =
    paused.flatMapLatest { isPaused ->
        if (isPaused) emptyFlow() else this@freezeWhileStreamingPaused
    }
