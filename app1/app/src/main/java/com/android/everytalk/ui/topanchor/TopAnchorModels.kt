package com.android.everytalk.ui.topanchor

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
    val keepReserveAfterRunEnd: Boolean = true
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
    val reservePx: Int = 0
) {
    val suppressesBottomScroll: Boolean
        get() = phase == TopAnchorPhase.InitialSnap ||
            phase == TopAnchorPhase.AnchoredRunning ||
            phase == TopAnchorPhase.Retained

    val hasRuntime: Boolean
        get() = activeTurn != null || retainedTurn != null

    val currentTurn: TopAnchorTurn?
        get() = activeTurn ?: retainedTurn
}
