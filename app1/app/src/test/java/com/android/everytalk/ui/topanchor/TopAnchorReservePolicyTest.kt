package com.android.everytalk.ui.topanchor

import org.junit.Assert.assertEquals
import org.junit.Test

class TopAnchorReservePolicyTest {
    @Test
    fun `reserve grows by missing scroll`() {
        assertEquals(
            180,
            computeTopAnchorReservePx(
                viewportHeightPx = 900,
                driftPx = 300,
                scrollConsumedPx = 120,
                currentReservePx = 0
            )
        )
    }

    @Test
    fun `reserve caps at viewport height`() {
        assertEquals(
            900,
            computeTopAnchorReservePx(
                viewportHeightPx = 900,
                driftPx = 1400,
                scrollConsumedPx = 0,
                currentReservePx = 0
            )
        )
    }

    @Test
    fun `negative drift does not grow bottom reserve`() {
        assertEquals(
            50,
            computeTopAnchorReservePx(
                viewportHeightPx = 900,
                driftPx = -120,
                scrollConsumedPx = 0,
                currentReservePx = 50
            )
        )
    }

    @Test
    fun `reserve shrinks to visible gap`() {
        assertEquals(120, shrinkTopAnchorReservePx(500, 120))
    }

    private val config = TopAnchorConfig(
        tallAnchorThresholdPx = 600,
        tallAnchorVisibleHeightPx = 200,
        topInsetPx = 100,
        reserveInsideTrailingItem = true,
    )

    private fun snapshot(vararg items: TopAnchorVisibleItem) = TopAnchorLayoutSnapshot(
        totalItemsCount = 10,
        viewportStartOffset = -100,
        viewportEndOffset = 1080,
        beforeContentPadding = 100,
        afterContentPadding = 80,
        firstVisibleItemIndex = items.first().index,
        firstVisibleItemScrollOffset = 0,
        visibleItems = items.toList(),
    )

    @Test
    fun `upper bound ignores scroll position`() {
        // 可视高度 1000：锚点 100 + 回复 300 → 需要 600。
        val pinned = snapshot(
            TopAnchorVisibleItem("u", 2, 0, 100),
            TopAnchorVisibleItem("a", 3, 100, 300 + 600),
        )
        val scrolledUp = snapshot(
            TopAnchorVisibleItem("p", 1, -200, 400),
            TopAnchorVisibleItem("u", 2, 200, 100),
            TopAnchorVisibleItem("a", 3, 300, 300 + 600),
        )
        assertEquals(600, topAnchorReserveUpperBoundPx(pinned, 2, 3, 600, 100, config))
        assertEquals(600, topAnchorReserveUpperBoundPx(scrolledUp, 2, 3, 600, 100, config))
    }

    @Test
    fun `upper bound shrinks when content grows outside viewport`() {
        // 锚点在视口上方、末项在视口下方：只知道可见部分已超过可视高度。
        val anchorAbove = snapshot(
            TopAnchorVisibleItem("a", 3, -50, 1200),
        )
        assertEquals(0, topAnchorReserveUpperBoundPx(anchorAbove, 2, 4, 0, 100, config))
    }

    @Test
    fun `reserve follows bottom padding changes`() {
        val state = TopAnchorRuntimeState(reservePx = 600, reserveBottomPaddingPx = 80)
        assertEquals(600, state.reserveForBottomPadding(80))
        // 输入法展开 300：reserve 同步让出 300，总滚动范围不变。
        assertEquals(300, state.reserveForBottomPadding(380))
        assertEquals(0, state.reserveForBottomPadding(900))
        assertEquals(600, TopAnchorRuntimeState(reservePx = 600).reserveForBottomPadding(380))
    }
}
