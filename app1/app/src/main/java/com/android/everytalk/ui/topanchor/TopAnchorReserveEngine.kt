package com.android.everytalk.ui.topanchor

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

@Stable
class TopAnchorReserveEngineState {
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
    enabled: Boolean
) {
    val currentTurn = state.runtime.currentTurn

    LaunchedEffect(enabled, currentTurn, anchorIndex, anchorKey, targetAnchorY, trailingRealItemIndex) {
        val turn = currentTurn
        if (!enabled || turn == null || targetAnchorY <= 0 || anchorIndex < 0) {
            return@LaunchedEffect
        }
        if (state.runtime.phase != TopAnchorPhase.Retained) {
            state.updateRuntime(state.runtime.copy(phase = TopAnchorPhase.InitialSnap))
            try {
                snapshotFlow { listState.layoutInfo.totalItemsCount }
                    .first { it > anchorIndex }
                if (listState.layoutInfo.visibleItemsInfo.none { it.index == anchorIndex }) {
                    listState.scrollToItem(anchorIndex, scrollOffset = 0)
                }
                correctTopAnchorOnce(
                    state = state,
                    listState = listState,
                    anchorKey = anchorKey,
                    targetAnchorY = targetAnchorY,
                    config = config
                )
            } finally {
                if (
                    currentCoroutineContext().isActive &&
                    state.runtime.currentTurn == turn &&
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
            trailingRealItemIndex = trailingRealItemIndex,
            config = config
        )
    }

    LaunchedEffect(isRunning, state.runtime.phase, state.runtime.currentTurn, config.keepReserveAfterRunEnd) {
        if (
            !isRunning &&
            config.keepReserveAfterRunEnd &&
            state.runtime.phase == TopAnchorPhase.AnchoredRunning
        ) {
            val turn = state.runtime.activeTurn ?: return@LaunchedEffect
            if (state.runtime.currentTurn != turn) return@LaunchedEffect
            state.updateRuntime(
                state.runtime.copy(
                    phase = TopAnchorPhase.Retained,
                    activeTurn = null,
                    retainedTurn = turn
                )
            )
        }
    }
}

private suspend fun correctTopAnchorOnce(
    state: TopAnchorReserveEngineState,
    listState: LazyListState,
    anchorKey: Any?,
    targetAnchorY: Int,
    config: TopAnchorConfig
): Boolean {
    val snapshot = listState.layoutInfo.toTopAnchorSnapshot(
        firstVisibleItemIndex = listState.firstVisibleItemIndex,
        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
    )
    val anchor = snapshot.visibleItems.firstOrNull { it.key == anchorKey } ?: return false
    val currentTopY = anchor.offset - snapshot.viewportStartOffset
    val currentAnchorY = computeTopAnchorY(
        itemTopY = currentTopY,
        itemHeightPx = anchor.size,
        tallAnchorThresholdPx = config.tallAnchorThresholdPx,
        tallAnchorVisibleHeightPx = config.tallAnchorVisibleHeightPx
    )
    val driftPx = computeTopAnchorDriftPx(currentAnchorY, targetAnchorY)
    if (abs(driftPx) <= 1) return false

    val consumedPx = listState.scrollBy(driftPx.toFloat()).roundToInt()
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
    trailingRealItemIndex: Int,
    config: TopAnchorConfig
) {
    var stableSinceNanos = 0L
    var lastLayoutVersion = listState.layoutInfo.toTopAnchorSnapshot(
        firstVisibleItemIndex = listState.firstVisibleItemIndex,
        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
    ).layoutVersion

    while (
        currentCoroutineContext().isActive &&
        state.runtime.currentTurn == turn &&
        state.runtime.phase != TopAnchorPhase.Idle
    ) {
        val frameNanos = withFrameNanos { it }
        val snapshot = listState.layoutInfo.toTopAnchorSnapshot(
            firstVisibleItemIndex = listState.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
        )
        val layoutChanged = snapshot.layoutVersion != lastLayoutVersion
        lastLayoutVersion = snapshot.layoutVersion

        val corrected = correctTopAnchorOnce(state, listState, anchorKey, targetAnchorY, config)
        shrinkReserveIfPossible(state, snapshot, trailingRealItemIndex)

        if (corrected || layoutChanged) {
            stableSinceNanos = 0L
        } else {
            if (stableSinceNanos == 0L) stableSinceNanos = frameNanos
            if (frameNanos - stableSinceNanos >= config.stableWindowNanos) {
                snapshotFlow {
                    if (state.runtime.currentTurn != turn) {
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
    trailingRealItemIndex: Int
) {
    val trailing = snapshot.visibleItems.firstOrNull { it.index == trailingRealItemIndex } ?: return
    val contentBottom = trailing.offset + trailing.size
    val gapPx = (snapshot.viewportEndOffset - contentBottom - snapshot.afterContentPadding)
        .coerceAtLeast(0)
    val nextReserve = shrinkTopAnchorReservePx(state.reservePx, gapPx)
    if (nextReserve < state.reservePx) {
        state.updateRuntime(state.runtime.copy(reservePx = nextReserve))
    }
}
