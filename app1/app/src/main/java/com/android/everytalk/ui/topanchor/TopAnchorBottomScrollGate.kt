package com.android.everytalk.ui.topanchor

enum class BottomScrollReason {
    Button,
    ImageLoaded,
    StreamGrowth,
    ThreadSwitch,
    Focus,
    ExternalEvent
}

fun shouldAllowBottomScroll(
    isUserAction: Boolean,
    suppressesBottomScroll: Boolean,
    isAtBottom: Boolean,
    reason: BottomScrollReason
): Boolean {
    if (isUserAction) return true
    if (suppressesBottomScroll) return false
    return isAtBottom || reason == BottomScrollReason.ThreadSwitch
}
