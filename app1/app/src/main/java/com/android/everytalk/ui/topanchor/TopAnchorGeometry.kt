package com.android.everytalk.ui.topanchor
import com.android.everytalk.statecontroller.*

fun computeTopAnchorY(
    itemTopY: Int,
    itemHeightPx: Int,
    tallAnchorThresholdPx: Int,
    tallAnchorVisibleHeightPx: Int
): Int {
    val visibleHeight = if (itemHeightPx <= tallAnchorThresholdPx) {
        itemHeightPx
    } else {
        tallAnchorVisibleHeightPx
    }
    return itemTopY + (itemHeightPx - visibleHeight).coerceAtLeast(0)
}

fun computeTopAnchorDriftPx(
    currentAnchorY: Int,
    targetAnchorY: Int
): Int = currentAnchorY - targetAnchorY
