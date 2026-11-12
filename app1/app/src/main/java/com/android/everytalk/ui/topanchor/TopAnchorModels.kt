package com.android.everytalk.ui.topanchor
import com.android.everytalk.statecontroller.*

enum class TopAnchorPhase {
    Idle,
    AnchorRecorded,
    InitialSnap,
    AnchoredRunning,
    Retained,
    UserControlled
}

enum class TopAnchorItemRole {
    User,
    AssistantTarget,
    LoadingTarget,
    StatusTarget,
    NonTarget
}

data class TopAnchorTurn(
    val anchorMessageId: String,
    val targetItemId: String?,
    val sessionKey: String,
    val generation: Long
)

data class TopAnchorConfig(
    val tallAnchorThresholdPx: Int,
    val tallAnchorVisibleHeightPx: Int,
    val topInsetPx: Int,
    val stableWindowNanos: Long = 50_000_000L,
    val keepReserveAfterRunEnd: Boolean = true,
    val reserveInsideTrailingItem: Boolean = false,
)

data class TopAnchorItem(
    val id: String,
    val role: TopAnchorItemRole
)

data class TopAnchorRuntimeState(
    val phase: TopAnchorPhase = TopAnchorPhase.Idle,
    val activeTurn: TopAnchorTurn? = null,
    val retainedTurn: TopAnchorTurn? = null,
    val recordedAnchorY: Int = -1,
    val reservePx: Int = 0,
    /** 写入 reservePx 时列表的底部 padding；-1 表示未记录，不做补偿。 */
    val reserveBottomPaddingPx: Int = -1,
) {
    /**
     * 输入法展开/收起只改变列表底部 padding。按与记录时的差值同帧抵消，
     * 列表总滚动范围不变：展开时不多出可滚动空白，收起时也不会先掉下再拉回。
     */
    fun reserveForBottomPadding(bottomPaddingPx: Int): Int {
        if (reservePx <= 0 || reserveBottomPaddingPx < 0) return reservePx.coerceAtLeast(0)
        return (reservePx + reserveBottomPaddingPx - bottomPaddingPx).coerceAtLeast(0)
    }

    val suppressesBottomScroll: Boolean
        get() = phase == TopAnchorPhase.InitialSnap ||
            phase == TopAnchorPhase.AnchoredRunning ||
            phase == TopAnchorPhase.Retained

    val hasRuntime: Boolean
        get() = activeTurn != null || retainedTurn != null

    val currentTurn: TopAnchorTurn?
        get() = activeTurn ?: retainedTurn
}
