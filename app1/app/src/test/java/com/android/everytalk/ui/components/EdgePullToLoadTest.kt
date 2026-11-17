package com.android.everytalk.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgePullToLoadTest {
    @Test
    fun `边界处继续拉只按阻尼跟手，拉够阈值才算可加载，惯性不触发`() {
        val state = EdgePullToLoadState(fromTop = true, thresholdPx = 100f).apply { enabled = true }
        // 手指下拉 100px：全部被吃掉，但位移只有约 1/3。
        val consumed = state.connection.onPostScroll(Offset.Zero, Offset(0f, 100f), NestedScrollSource.UserInput)
        assertEquals(100f, consumed.y, 0.01f)
        assertTrue(state.offset in 30f..40f)
        assertFalse(state.reachedThreshold)
        repeat(10) { state.connection.onPostScroll(Offset.Zero, Offset(0f, 100f), NestedScrollSource.UserInput) }
        assertTrue(state.reachedThreshold)
        // 惯性滑到边界、反方向的剩余量都不拉动。
        val before = state.offset
        state.connection.onPostScroll(Offset.Zero, Offset(0f, 100f), NestedScrollSource.SideEffect)
        state.connection.onPostScroll(Offset.Zero, Offset(0f, -100f), NestedScrollSource.UserInput)
        assertEquals(before, state.offset, 0.01f)
    }

    @Test
    fun `没有更多可加载时不拦截`() {
        val state = EdgePullToLoadState(fromTop = false, thresholdPx = 100f)
        assertEquals(Offset.Zero, state.connection.onPostScroll(Offset.Zero, Offset(0f, -100f), NestedScrollSource.UserInput))
        assertEquals(0f, state.offset, 0.01f)
    }
}
