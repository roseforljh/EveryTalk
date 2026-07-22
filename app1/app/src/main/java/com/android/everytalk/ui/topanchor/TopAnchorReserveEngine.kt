package com.android.everytalk.ui.topanchor

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun isTopAnchorCorrectionCurrent(
    runtime: TopAnchorRuntimeState,
    expectedTurn: TopAnchorTurn,
): Boolean {
    val currentTurn = runtime.currentTurn ?: return false
    return runtime.hasRuntime &&
        currentTurn.anchorMessageId == expectedTurn.anchorMessageId &&
        currentTurn.sessionKey == expectedTurn.sessionKey &&
        currentTurn.generation == expectedTurn.generation
}

fun Modifier.appendTopAnchorReserve(reservePx: Int): Modifier {
    if (reservePx <= 0) return this
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height + reservePx) {
            placeable.placeRelative(0, 0)
        }
    }
}

@Stable
class TopAnchorReserveEngineState {
    private var nextTurnGeneration = 0L

    var runtime by mutableStateOf(TopAnchorRuntimeState())
        private set

    val reservePx: Int
        get() = runtime.reservePx

    val suppressesBottomScroll: Boolean
        get() = runtime.suppressesBottomScroll

    val userScrollEnabled: Boolean
        get() = runtime.phase != TopAnchorPhase.InitialSnap

    fun updateRuntime(next: TopAnchorRuntimeState) {
        runtime = next
    }

    fun clearRuntime() {
        runtime = TopAnchorRuntimeState()
    }

    fun activateTurn(turn: TopAnchorTurn) {
        nextTurnGeneration += 1
        runtime = TopAnchorRuntimeState(
            phase = TopAnchorPhase.InitialSnap,
            activeTurn = turn.copy(generation = nextTurnGeneration),
        )
    }

    fun releaseForUserScroll() {
        if (!runtime.hasRuntime) return
        if (runtime.reservePx <= 0) {
            clearRuntime()
            return
        }
        runtime = runtime.copy(phase = TopAnchorPhase.UserControlled)
    }

    fun attachResponseTarget(expectedTurn: TopAnchorTurn, targetItemId: String) {
        val turn = runtime.currentTurn ?: return
        if (!isTopAnchorCorrectionCurrent(runtime, expectedTurn) || turn.targetItemId != null) return
        val updatedTurn = turn.copy(targetItemId = targetItemId)
        runtime = if (runtime.activeTurn != null) {
            runtime.copy(activeTurn = updatedTurn)
        } else {
            runtime.copy(retainedTurn = updatedTurn)
        }
    }
}

@Composable
fun RunTopAnchorReserveEngine(
    state: TopAnchorReserveEngineState,
    listState: LazyListState,
    anchorIndex: Int,
    anchorKey: Any?,
    targetAnchorY: Int,
    trailingRealItemIndex: Int,
    isRunning: Boolean,
    config: TopAnchorConfig,
    enabled: Boolean,
    hasResponseTarget: Boolean = false,
) {
    val currentTurn = state.runtime.currentTurn
    val currentTurnKey = currentTurn?.let { turn ->
        Triple(turn.anchorMessageId, turn.sessionKey, turn.generation)
    }
    val trailingRealItemIndexState = rememberUpdatedState(trailingRealItemIndex)
    var hasObservedRunning by remember(currentTurnKey) { mutableStateOf(isRunning) }

    LaunchedEffect(enabled, currentTurnKey, anchorIndex, anchorKey, targetAnchorY) {
        val turn = currentTurn
        if (!enabled || turn == null || targetAnchorY <= 0 || anchorIndex < 0) {
            return@LaunchedEffect
        }
        if (
            state.runtime.phase != TopAnchorPhase.Retained &&
            state.runtime.phase != TopAnchorPhase.UserControlled
        ) {
            state.updateRuntime(state.runtime.copy(phase = TopAnchorPhase.InitialSnap))
            try {
                snapshotFlow { listState.layoutInfo.totalItemsCount }
                    .first { it > anchorIndex }
                if (listState.layoutInfo.visibleItemsInfo.none { it.index == anchorIndex }) {
                    listState.scrollToItem(anchorIndex, scrollOffset = 0)
                }
                val reserveBeforeCorrection = state.reservePx
                correctTopAnchorOnce(
                    state = state,
                    listState = listState,
                    expectedTurn = turn,
                    anchorKey = anchorKey,
                    targetAnchorY = targetAnchorY,
                    trailingRealItemIndex = trailingRealItemIndexState.value,
                    config = config
                )
                if (state.reservePx > reserveBeforeCorrection) {
                    val layoutVersionBeforeReserveLayout = listState.layoutInfo.toTopAnchorSnapshot(
                        firstVisibleItemIndex = listState.firstVisibleItemIndex,
                        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    ).layoutVersion
                    snapshotFlow {
                        listState.layoutInfo.toTopAnchorSnapshot(
                            firstVisibleItemIndex = listState.firstVisibleItemIndex,
                            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                        ).layoutVersion
                    }.first { it != layoutVersionBeforeReserveLayout }
                    withFrameNanos { }
                    correctTopAnchorOnce(
                        state = state,
                        listState = listState,
                        expectedTurn = turn,
                        anchorKey = anchorKey,
                        targetAnchorY = targetAnchorY,
                        trailingRealItemIndex = trailingRealItemIndexState.value,
                        config = config,
                    )
                }
            } finally {
                if (
                    currentCoroutineContext().isActive &&
                    isTopAnchorCorrectionCurrent(state.runtime, turn) &&
                    state.runtime.phase == TopAnchorPhase.InitialSnap
                ) {
                    state.updateRuntime(state.runtime.copy(phase = TopAnchorPhase.AnchoredRunning))
                }
            }
        }
        runTopAnchorCorrectionLoop(
            state = state,
            listState = listState,
            turn = turn,
            anchorKey = anchorKey,
            targetAnchorY = targetAnchorY,
            trailingRealItemIndex = { trailingRealItemIndexState.value },
            config = config
        )
    }

    LaunchedEffect(
        isRunning,
        hasResponseTarget,
        state.runtime.phase,
        currentTurnKey,
        config.keepReserveAfterRunEnd,
    ) {
        if (isRunning) {
            hasObservedRunning = true
            return@LaunchedEffect
        }
        // 极速回复可能在首帧前结束，运行态会被 StateFlow 合并；回复目标可补足完成证据。
        if (!hasObservedRunning && !hasResponseTarget) return@LaunchedEffect

        val turn = state.runtime.currentTurn ?: return@LaunchedEffect
        if (currentTurn == null || !isTopAnchorCorrectionCurrent(state.runtime, currentTurn)) {
            return@LaunchedEffect
        }
        if (
            state.runtime.phase == TopAnchorPhase.AnchoredRunning ||
            state.runtime.phase == TopAnchorPhase.UserControlled
        ) {
            val completedPhase = state.runtime.phase
            if (!config.keepReserveAfterRunEnd) {
                state.clearRuntime()
                return@LaunchedEffect
            }
            state.updateRuntime(
                state.runtime.copy(
                    phase = if (completedPhase == TopAnchorPhase.UserControlled) {
                        TopAnchorPhase.UserControlled
                    } else {
                        TopAnchorPhase.Retained
                    },
                    activeTurn = null,
                    retainedTurn = turn,
                )
            )
        }
    }
}

private suspend fun correctTopAnchorOnce(
    state: TopAnchorReserveEngineState,
    listState: LazyListState,
    expectedTurn: TopAnchorTurn,
    anchorKey: Any?,
    targetAnchorY: Int,
    trailingRealItemIndex: Int,
    config: TopAnchorConfig
): Boolean {
    if (!isTopAnchorCorrectionCurrent(state.runtime, expectedTurn)) return false
    val snapshot = listState.layoutInfo.toTopAnchorSnapshot(
        firstVisibleItemIndex = listState.firstVisibleItemIndex,
        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
    )
    val anchor = snapshot.visibleItems.firstOrNull { it.key == anchorKey } ?: return false
    // offset 属于 LazyList 的内容坐标；viewportStartOffset 包含负的顶部 contentPadding。
    // 两者相减后才是列表视口内的真实坐标，避免目标位置被顶部 padding 再向下推一次。
    val currentTopY = anchor.offset - snapshot.viewportStartOffset
    // 回复占位尚未出现时，reserve 会附着在用户锚点自身；计算气泡高度时必须扣除这段布局空间。
    val reserveInAnchor = if (
        config.reserveInsideTrailingItem && anchor.index == trailingRealItemIndex
    ) {
        state.reservePx
    } else {
        0
    }
    val anchorContentHeight = (anchor.size - reserveInAnchor).coerceAtLeast(0)
    val currentAnchorY = computeTopAnchorY(
        itemTopY = currentTopY,
        itemHeightPx = anchorContentHeight,
        tallAnchorThresholdPx = config.tallAnchorThresholdPx,
        tallAnchorVisibleHeightPx = config.tallAnchorVisibleHeightPx
    )
    val driftPx = computeTopAnchorDriftPx(currentAnchorY, targetAnchorY)
    if (abs(driftPx) <= 1) return false

    val consumedPx = listState.scrollBy(driftPx.toFloat()).roundToInt()
    if (!isTopAnchorCorrectionCurrent(state.runtime, expectedTurn)) return false
    val nextReserve = computeTopAnchorReservePx(
        viewportHeightPx = snapshot.viewportHeightPx,
        driftPx = driftPx,
        scrollConsumedPx = consumedPx,
        currentReservePx = state.reservePx
    )
    if (nextReserve != state.reservePx) {
        state.updateRuntime(state.runtime.copy(reservePx = nextReserve))
    }
    return true
}

private suspend fun runTopAnchorCorrectionLoop(
    state: TopAnchorReserveEngineState,
    listState: LazyListState,
    turn: TopAnchorTurn,
    anchorKey: Any?,
    targetAnchorY: Int,
    trailingRealItemIndex: () -> Int,
    config: TopAnchorConfig
) {
    var stableSinceNanos = 0L
    var lastLayoutVersion = listState.layoutInfo.toTopAnchorSnapshot(
        firstVisibleItemIndex = listState.firstVisibleItemIndex,
        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
    ).layoutVersion

    while (
        currentCoroutineContext().isActive &&
        isTopAnchorCorrectionCurrent(state.runtime, turn) &&
        state.runtime.phase != TopAnchorPhase.Idle
    ) {
        val frameNanos = withFrameNanos { it }
        val snapshot = listState.layoutInfo.toTopAnchorSnapshot(
            firstVisibleItemIndex = listState.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
        )
        val layoutChanged = snapshot.layoutVersion != lastLayoutVersion
        lastLayoutVersion = snapshot.layoutVersion

        // 用户接管后停止自动纠偏，并冻结占位，确保下滑后仍能上滑回原置顶位置。
        val corrected = if (state.runtime.phase == TopAnchorPhase.UserControlled) {
            false
        } else {
            correctTopAnchorOnce(
                state = state,
                listState = listState,
                expectedTurn = turn,
                anchorKey = anchorKey,
                targetAnchorY = targetAnchorY,
                trailingRealItemIndex = trailingRealItemIndex(),
                config = config,
            )
        }
        // 本帧发生过滚动或增加占位时，snapshot 仍是校正前的旧布局。
        // 用旧快照收缩会立即撤销刚完成的校正，造成 reserve 与锚点位置逐帧抖动。
        if (!corrected && state.runtime.phase != TopAnchorPhase.UserControlled) {
            shrinkReserveIfPossible(state, snapshot, trailingRealItemIndex(), config)
        }
        if (
            state.runtime.phase == TopAnchorPhase.UserControlled &&
            state.reservePx == 0
        ) {
            state.clearRuntime()
            return
        }

        if (corrected || layoutChanged) {
            stableSinceNanos = 0L
        } else {
            if (stableSinceNanos == 0L) stableSinceNanos = frameNanos
            if (frameNanos - stableSinceNanos >= config.stableWindowNanos) {
                snapshotFlow {
                    if (!isTopAnchorCorrectionCurrent(state.runtime, turn)) {
                        Long.MIN_VALUE
                    } else {
                        listState.layoutInfo.toTopAnchorSnapshot(
                            firstVisibleItemIndex = listState.firstVisibleItemIndex,
                            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
                        ).layoutVersion
                    }
                }.first { it != lastLayoutVersion }
                stableSinceNanos = 0L
            }
        }
    }
}

private fun shrinkReserveIfPossible(
    state: TopAnchorReserveEngineState,
    snapshot: TopAnchorLayoutSnapshot,
    trailingRealItemIndex: Int,
    config: TopAnchorConfig,
) {
    val trailing = snapshot.visibleItems.firstOrNull { it.index == trailingRealItemIndex } ?: return
    val reserveInTrailingItem = if (config.reserveInsideTrailingItem) state.reservePx else 0
    val contentBottom = trailing.offset + (trailing.size - reserveInTrailingItem).coerceAtLeast(0)
    val gapPx = (snapshot.viewportEndOffset - contentBottom - snapshot.afterContentPadding)
        .coerceAtLeast(0)
    val nextReserve = shrinkTopAnchorReservePx(state.reservePx, gapPx)
    if (nextReserve < state.reservePx) {
        state.updateRuntime(state.runtime.copy(reservePx = nextReserve))
    }
}
