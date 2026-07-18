package com.android.everytalk.ui.topanchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TopAnchorLayoutSnapshotTest {
    @Test
    fun `layout version changes when scroll offset changes`() {
        val before = TopAnchorLayoutSnapshot(
            totalItemsCount = 3,
            viewportStartOffset = 0,
            viewportEndOffset = 900,
            beforeContentPadding = 0,
            afterContentPadding = 0,
            firstVisibleItemIndex = 1,
            firstVisibleItemScrollOffset = 0,
            visibleItems = listOf(TopAnchorVisibleItem("u1", 1, 100, 80))
        )
        val after = before.copy(firstVisibleItemScrollOffset = 24)

        assertNotEquals(before.layoutVersion, after.layoutVersion)
    }

    @Test
    fun `viewport height never goes negative`() {
        val snapshot = TopAnchorLayoutSnapshot(
            totalItemsCount = 0,
            viewportStartOffset = 900,
            viewportEndOffset = 100,
            beforeContentPadding = 0,
            afterContentPadding = 0,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            visibleItems = emptyList()
        )

        assertEquals(0, snapshot.viewportHeightPx)
    }
}
