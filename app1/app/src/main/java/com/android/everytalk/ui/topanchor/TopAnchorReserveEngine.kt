package com.android.everytalk.ui.topanchor
import com.android.everytalk.statecontroller.*

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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

/**
 * 在测量阶段上报手动展开区当前（含动画）的高度，与列表布局同帧，
 * 引擎据此不把用户展开当成 AI 新输出去消耗底部预留。
 */
fun Modifier.reportExpansionHeight(onHeight: (Int) -> Unit): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    onHeight(placeable.height)
    layout(placeable.width, placeable.height) {
        placeable.placeRelative(0, 0)
    }
}

/**
 * 用户点开的内容：只把点开那一刻已有的内容高度算作用户展开，
 * 之后 AI 往里追加的高度仍算回复输出，照常消耗底部预留。
 * [contentModifier] 挂在展开内容上测完整高度，[userExpandedHeight] 在外层测量时调用。
 */
class UserExpansionHeightTracker {
    private var contentHeightPx = 0
    private var baselineContentPx = -1

    val contentModifier: Modifier = Modifier.reportExpansionHeight { contentHeightPx = it }

    fun userExpandedHeight(animatedHeightPx: Int, expanded: Boolean): Int {
        if (!expanded && animatedHeightPx <= 0) {
            baselineContentPx = -1
            return 0
        }
        if (baselineContentPx < 0) baselineContentPx = contentHeightPx
        val aiGrowthPx = (contentHeightPx - baselineContentPx).coerceAtLeast(0)
        return (animatedHeightPx - aiGrowthPx).coerceIn(0, baselineContentPx)
    }
}

@Stable
class TopAnchorReserveEngineState {
    private var nextTurnGeneration = 0L
    /** 展开区 key → (所在列表项 key, 当前高度)。由组件在测量时写入、离开组合时移除。 */
    private val interactiveExpansions = mutableMapOf<String, Pair<Any, Int>>()

    var runtime by mutableStateOf(TopAnchorRuntimeState())
        private set

    var reserveReleaseRequested by mutableStateOf(false)
        private set

    val reservePx: Int
        get() = runtime.reservePx

    val suppressesBottomScroll: Boolean
        get() = runtime.suppressesBottomScroll

    val userScrollEnabled: Boolean
        get() = runtime.phase != TopAnchorPhase.InitialSnap

    /** 用户展开的执行详情只改变展示高度，不能被当成 AI 新输出消耗底部预留。 */
    internal fun interactiveExpansionPx(itemKey: Any?): Int =
        interactiveExpansions.values.sumOf { (key, heightPx) -> if (key == itemKey) heightPx else 0 }

    fun updateRuntime(next: TopAnchorRuntimeState) {
        runtime = next
    }

    /** snapshot 那一帧实际排进列表的 reserve。 */
    internal fun reserveIn(snapshot: TopAnchorLayoutSnapshot): Int =
        runtime.reserveForBottomPadding(snapshot.afterContentPadding)

    internal fun setReserve(reservePx: Int, bottomPaddingPx: Int) {
        val next = reservePx.coerceAtLeast(0)
        runtime = runtime.copy(
            reservePx = next,
            reserveBottomPaddingPx = if (next > 0) bottomPaddingPx else -1,
        )
    }

    fun clearRuntime() {
        reserveReleaseRequested = false
        runtime = TopAnchorRuntimeState()
    }

    fun activateTurn(turn: TopAnchorTurn) {
        reserveReleaseRequested = false
        nextTurnGeneration += 1
        val activatedTurn = turn.copy(generation = nextTurnGeneration)
        val previousTurn = runtime.currentTurn
        val reusesCurrentAnchor = runtime.hasRuntime &&
            previousTurn?.anchorMessageId == activatedTurn.anchorMessageId &&
            previousTurn.sessionKey == activatedTurn.sessionKey

        runtime = if (reusesCurrentAnchor) {
            runtime.copy(
                phase = when (runtime.phase) {
                    // 用户已经离开旧锚点时，新一轮重答仍需重新执行置顶。
                    TopAnchorPhase.UserControlled -> TopAnchorPhase.InitialSnap
                    TopAnchorPhase.InitialSnap,
                    TopAnchorPhase.AnchorRecorded -> TopAnchorPhase.InitialSnap
                    else -> TopAnchorPhase.AnchoredRunning
                },
                activeTurn = activatedTurn,
                retainedTurn = null,
            )
        } else {
            TopAnchorRuntimeState(
                phase = TopAnchorPhase.InitialSnap,
                activeTurn = activatedTurn,
            )
        }
    }

    fun releaseForUserScroll() {
        if (!runtime.hasRuntime) return
        if (runtime.reservePx <= 0) {
            clearRuntime()
            return
        }
        runtime = runtime.copy(phase = TopAnchorPhase.UserControlled)
    }

    /**
     * 回答结束后继续保留 reserve，避免用户正在看历史内容时列表总高度突变。
     * 只有用户已经滑到真实回复底部并继续向下时，才请求平滑收回这段虚拟空间。
     */
    fun requestRetainedReserveRelease(
        scrollDeltaY: Float,
        listState: LazyListState,
        trailingRealItemIndex: Int,
        reserveInsideTrailingItem: Boolean,
    ) {
        if (
            scrollDeltaY >= 0f ||
            reserveReleaseRequested ||
            runtime.phase != TopAnchorPhase.UserControlled ||
            runtime.activeTurn != null ||
            runtime.retainedTurn == null ||
            runtime.reservePx <= 0
        ) {
            return
        }
        val layoutInfo = listState.layoutInfo
        val trailingItem = layoutInfo.visibleItemsInfo.firstOrNull {
            it.index == trailingRealItemIndex
        } ?: return
        val reserveInTrailingItem = if (reserveInsideTrailingItem) {
            runtime.reserveForBottomPadding(layoutInfo.afterContentPadding)
        } else {
            0
        }
        val realContentBottom = trailingItem.offset +
            (trailingItem.size - reserveInTrailingItem).coerceAtLeast(0)
        val viewportContentBottom = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
        if (realContentBottom <= viewportContentBottom + 1) {
            reserveReleaseRequested = true
        }
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

    fun updateInteractiveExpansion(itemKey: Any, key: String, heightPx: Int) {
        if (heightPx > 0) {
            interactiveExpansions[key] = itemKey to heightPx
        } else {
            interactiveExpansions.remove(key)
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
            state.runtime.phase == TopAnchorPhase.InitialSnap ||
            state.runtime.phase == TopAnchorPhase.AnchorRecorded
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
                    anchorIndex = anchorIndex,
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
                        anchorIndex = anchorIndex,
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
            anchorIndex = anchorIndex,
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

    LaunchedEffect(state.reserveReleaseRequested, currentTurnKey) {
        val turn = state.runtime.retainedTurn
        if (!state.reserveReleaseRequested || turn == null || state.reservePx <= 0) {
            return@LaunchedEffect
        }
        val reserveAnimation = Animatable(state.reservePx.toFloat())
        reserveAnimation.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        ) {
            if (
                state.reserveReleaseRequested &&
                state.runtime.phase == TopAnchorPhase.UserControlled &&
                state.runtime.activeTurn == null &&
                isTopAnchorCorrectionCurrent(state.runtime, turn)
            ) {
                val animatedReservePx = value.roundToInt().coerceAtLeast(0)
                if (animatedReservePx != state.reservePx) {
                    state.updateRuntime(state.runtime.copy(reservePx = animatedReservePx))
                }
            }
        }
        if (
            state.reserveReleaseRequested &&
            state.runtime.activeTurn == null &&
            isTopAnchorCorrectionCurrent(state.runtime, turn)
        ) {
            state.clearRuntime()
        }
    }
}

private suspend fun correctTopAnchorOnce(
    state: TopAnchorReserveEngineState,
    listState: LazyListState,
    expectedTurn: TopAnchorTurn,
    anchorIndex: Int,
    anchorKey: Any?,
    targetAnchorY: Int,
    trailingRealItemIndex: Int,
    config: TopAnchorConfig
): Boolean {
    if (!isTopAnchorCorrectionCurrent(state.runtime, expectedTurn)) return false
    var snapshot = listState.layoutInfo.toTopAnchorSnapshot(
        firstVisibleItemIndex = listState.firstVisibleItemIndex,
        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
    )
    var anchor = snapshot.visibleItems.firstOrNull { it.key == anchorKey }
    var reacquiredAnchor = false
    if (anchor == null) {
        if (anchorIndex !in 0 until snapshot.totalItemsCount) return false
        // 前序 Markdown、图片或公式异步扩高时，目标用户项可能被推出可见区。
        // 先按当前索引重新捕获，再用稳定 key 校验，避免纠偏循环永久失去锚点。
        listState.scrollToItem(anchorIndex, scrollOffset = 0)
        if (!isTopAnchorCorrectionCurrent(state.runtime, expectedTurn)) return false
        snapshot = listState.layoutInfo.toTopAnchorSnapshot(
            firstVisibleItemIndex = listState.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
        )
        anchor = snapshot.visibleItems.firstOrNull { it.key == anchorKey } ?: return false
        reacquiredAnchor = true
    }
    // offset 属于 LazyList 的内容坐标；viewportStartOffset 包含负的顶部 contentPadding。
    // 两者相减后才是列表视口内的真实坐标，避免目标位置被顶部 padding 再向下推一次。
    val currentTopY = anchor.offset - snapshot.viewportStartOffset
    // 回复占位尚未出现时，reserve 会附着在用户锚点自身；计算气泡高度时必须扣除这段布局空间。
    val appliedReservePx = state.reserveIn(snapshot)
    val reserveInAnchor = if (
        config.reserveInsideTrailingItem && anchor.index == trailingRealItemIndex
    ) {
        appliedReservePx
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
    if (abs(driftPx) <= 1) return reacquiredAnchor

    val consumedPx = listState.scrollBy(driftPx.toFloat()).roundToInt()
    if (!isTopAnchorCorrectionCurrent(state.runtime, expectedTurn)) return false
    val nextReserve = computeTopAnchorReservePx(
        viewportHeightPx = snapshot.viewportHeightPx,
        driftPx = driftPx,
        scrollConsumedPx = consumedPx,
        currentReservePx = appliedReservePx
    )
    if (nextReserve != appliedReservePx) {
        state.setReserve(nextReserve, snapshot.afterContentPadding)
    }
    return true
}

private suspend fun runTopAnchorCorrectionLoop(
    state: TopAnchorReserveEngineState,
    listState: LazyListState,
    turn: TopAnchorTurn,
    anchorIndex: Int,
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

        // 用户接管后停止自动纠偏，但 reserve 仍按真实内容收缩。
        val corrected = if (state.runtime.phase == TopAnchorPhase.UserControlled) {
            false
        } else {
            correctTopAnchorOnce(
                state = state,
                listState = listState,
                expectedTurn = turn,
                anchorIndex = anchorIndex,
                anchorKey = anchorKey,
                targetAnchorY = targetAnchorY,
                trailingRealItemIndex = trailingRealItemIndex(),
                config = config,
            )
        }
        // 本帧发生过滚动或增加占位时，snapshot 仍是校正前的旧布局，不能拿来收缩。
        // 手势触发的退出动画已经接管 reserve，不再按内容收缩。
        if (!corrected && !state.reserveReleaseRequested) {
            val reserveBeforeShrink = state.reservePx
            shrinkReserveToContent(
                state = state,
                snapshot = snapshot,
                anchorIndex = anchorIndex,
                trailingRealItemIndex = trailingRealItemIndex(),
                targetAnchorY = targetAnchorY,
                config = config,
            )
            if (
                state.runtime.phase == TopAnchorPhase.AnchoredRunning &&
                reserveBeforeShrink > 0 &&
                state.reservePx == 0
            ) {
                // 回答内容已完全填满置顶时创建的空白，交还给聊天列表继续向下跟随。
                state.clearRuntime()
                return
            }
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

/**
 * 让锚点停在目标位置所需 reserve 的上限：可视高度 - 目标位置 - 锚点到真实内容底部的高度。
 * 只用可见项计算；锚点在视口上方或末项在视口下方时，真实高度只会更大，上限仍成立。
 * 与滚动位置无关，用户滑开、子项替换或中间插入都不会漏扣，底部不会残留多余空白。
 * 用户手动展开的高度不算回复内容。锚点和末项之间没有可见项时返回 -1。
 */
internal fun topAnchorReserveUpperBoundPx(
    snapshot: TopAnchorLayoutSnapshot,
    anchorIndex: Int,
    trailingRealItemIndex: Int,
    reserveInTrailingItemPx: Int,
    targetAnchorY: Int,
    config: TopAnchorConfig,
    interactiveExpansionPx: (itemKey: Any?) -> Int = { 0 },
): Int {
    if (anchorIndex < 0 || trailingRealItemIndex < anchorIndex) return -1
    val first = snapshot.visibleItems.firstOrNull { it.index in anchorIndex..trailingRealItemIndex } ?: return -1
    val last = snapshot.visibleItems.last { it.index in anchorIndex..trailingRealItemIndex }
    fun TopAnchorVisibleItem.realSize() =
        (size - if (index == trailingRealItemIndex) reserveInTrailingItemPx else 0).coerceAtLeast(0)
    val topY = if (first.index == anchorIndex) {
        computeTopAnchorY(
            itemTopY = first.offset,
            itemHeightPx = first.realSize(),
            tallAnchorThresholdPx = config.tallAnchorThresholdPx,
            tallAnchorVisibleHeightPx = config.tallAnchorVisibleHeightPx,
        )
    } else {
        first.offset
    }
    val contentBottom = last.offset + last.realSize()
    val availableBottom = snapshot.viewportEndOffset - snapshot.afterContentPadding
    val targetOffset = targetAnchorY + snapshot.viewportStartOffset
    val expandedPx = snapshot.visibleItems
        .filter { it.index in first.index..last.index }
        .sumOf { interactiveExpansionPx(it.key) }
    return (availableBottom - targetOffset - (contentBottom - topY - expandedPx)).coerceAtLeast(0)
}

private fun shrinkReserveToContent(
    state: TopAnchorReserveEngineState,
    snapshot: TopAnchorLayoutSnapshot,
    anchorIndex: Int,
    trailingRealItemIndex: Int,
    targetAnchorY: Int,
    config: TopAnchorConfig,
) {
    if (state.reservePx <= 0 || trailingRealItemIndex !in 0 until snapshot.totalItemsCount) return
    val applied = state.reserveIn(snapshot)
    val upperBound = topAnchorReserveUpperBoundPx(
        snapshot = snapshot,
        anchorIndex = anchorIndex,
        trailingRealItemIndex = trailingRealItemIndex,
        reserveInTrailingItemPx = if (config.reserveInsideTrailingItem) applied else 0,
        targetAnchorY = targetAnchorY,
        config = config,
        interactiveExpansionPx = state::interactiveExpansionPx,
    )
    if (upperBound < 0) return
    val next = shrinkTopAnchorReservePx(applied, upperBound)
    // 输入法展开时 applied 可能已被抵消为 0；内容填满可视区就彻底放掉记录值。
    if (next < applied || upperBound == 0) {
        state.setReserve(next, snapshot.afterContentPadding)
    }
}
