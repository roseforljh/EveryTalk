package com.android.everytalk.ui.topanchor
import com.android.everytalk.statecontroller.*

fun computeTopAnchorReservePx(
    viewportHeightPx: Int,
    driftPx: Int,
    scrollConsumedPx: Int,
    currentReservePx: Int
): Int {
    if (driftPx <= 0) return currentReservePx.coerceAtLeast(0)
    val missingPx = driftPx - scrollConsumedPx.coerceAtLeast(0)
    if (missingPx <= 0) return currentReservePx.coerceAtLeast(0)
    return (currentReservePx + missingPx)
        .coerceAtLeast(0)
        .coerceAtMost(viewportHeightPx.coerceAtLeast(0))
}

fun shrinkTopAnchorReservePx(
    currentReservePx: Int,
    visibleGapPx: Int
): Int {
    return visibleGapPx.coerceAtLeast(0).coerceAtMost(currentReservePx.coerceAtLeast(0))
}
