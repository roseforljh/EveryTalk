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
    fun `layout version changes when viewport expands after keyboard hides`() {
        val before = TopAnchorLayoutSnapshot(
            totalItemsCount = 3,
            viewportStartOffset = -96,
            viewportEndOffset = 420,
            beforeContentPadding = 96,
            afterContentPadding = 80,
            firstVisibleItemIndex = 1,
            firstVisibleItemScrollOffset = 0,
            visibleItems = listOf(TopAnchorVisibleItem("u1", 1, 96, 80))
        )
        val after = before.copy(viewportEndOffset = 640)

        assertNotEquals(before.layoutVersion, after.layoutVersion)
    }

    @Test
    fun `layout version changes when visible message keys are reordered`() {
        val before = TopAnchorLayoutSnapshot(
            totalItemsCount = 2,
            viewportStartOffset = -96,
            viewportEndOffset = 420,
            beforeContentPadding = 96,
            afterContentPadding = 80,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            visibleItems = listOf(
                TopAnchorVisibleItem("u1", 0, 0, 80),
                TopAnchorVisibleItem("u2", 1, 92, 80),
            )
        )
        val after = before.copy(
            visibleItems = listOf(
                TopAnchorVisibleItem("u2", 0, 0, 80),
                TopAnchorVisibleItem("u1", 1, 92, 80),
            )
        )

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
