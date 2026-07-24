package com.android.everytalk.ui.topanchor
import com.android.everytalk.statecontroller.*

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo

data class TopAnchorVisibleItem(
    val key: Any?,
    val index: Int,
    val offset: Int,
    val size: Int
)

data class TopAnchorLayoutSnapshot(
    val totalItemsCount: Int,
    val viewportStartOffset: Int,
    val viewportEndOffset: Int,
    val beforeContentPadding: Int,
    val afterContentPadding: Int,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val visibleItems: List<TopAnchorVisibleItem>
) {
    val viewportHeightPx: Int
        get() = (viewportEndOffset - viewportStartOffset).coerceAtLeast(0)

    val layoutVersion: Long
        get() {
            var result = totalItemsCount.toLong()
            result = result * 31 + viewportStartOffset
            result = result * 31 + viewportEndOffset
            result = result * 31 + beforeContentPadding
            result = result * 31 + afterContentPadding
            result = result * 31 + firstVisibleItemIndex
            result = result * 31 + firstVisibleItemScrollOffset
            result = result * 31 + visibleItems.sumOf { it.size }
            result = result * 31 + visibleItems.sumOf { it.offset }
            return result
        }
}

fun LazyListLayoutInfo.toTopAnchorSnapshot(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int
): TopAnchorLayoutSnapshot {
    return TopAnchorLayoutSnapshot(
        totalItemsCount = totalItemsCount,
        viewportStartOffset = viewportStartOffset,
        viewportEndOffset = viewportEndOffset,
        beforeContentPadding = beforeContentPadding,
        afterContentPadding = afterContentPadding,
        firstVisibleItemIndex = firstVisibleItemIndex,
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        visibleItems = visibleItemsInfo.map { it.toTopAnchorVisibleItem() }
    )
}

private fun LazyListItemInfo.toTopAnchorVisibleItem(): TopAnchorVisibleItem {
    return TopAnchorVisibleItem(
        key = key,
        index = index,
        offset = offset,
        size = size
    )
}
