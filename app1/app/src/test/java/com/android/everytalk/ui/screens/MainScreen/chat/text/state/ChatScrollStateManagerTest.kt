package com.android.everytalk.ui.screens.MainScreen.chat.text.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatScrollStateManagerTest {

    @Test
    fun `显式到底必须消除全部剩余距离`() {
        assertEquals(BottomCorrection.None, resolveBottomCorrection(remainingPx = 0))
        assertEquals(BottomCorrection.ScrollBy, resolveBottomCorrection(remainingPx = 1))
        assertEquals(BottomCorrection.ScrollBy, resolveBottomCorrection(remainingPx = 5))
        assertEquals(BottomCorrection.ScrollBy, resolveBottomCorrection(remainingPx = 24))
        assertEquals(BottomCorrection.AnchorLastItem, resolveBottomCorrection(remainingPx = null))
    }

    @Test
    fun `距离暂为零但仍可继续滚动时必须重新锚定末项`() {
        assertEquals(
            BottomCorrection.AnchorLastItem,
            resolveBottomCorrection(
                remainingPx = 0,
                canScrollForward = true,
            ),
        )
        assertEquals(
            BottomCorrection.None,
            resolveBottomCorrection(
                remainingPx = 0,
                canScrollForward = false,
            ),
        )
    }

    @Test
    fun `自动跟随抵达真实内容底部后必须停止补偿`() {
        assertEquals(BottomCorrection.None, resolveContentFollowCorrection(remainingPx = 0))
        assertEquals(BottomCorrection.ScrollBy, resolveContentFollowCorrection(remainingPx = 1))
        assertEquals(BottomCorrection.AnchorLastItem, resolveContentFollowCorrection(remainingPx = null))
    }

    @Test
    fun `内容增长离开底部不会擅自锁住自动跟随`() {
        val locked = resolvePreventAutoScroll(
            currentValue = false,
            isProgrammaticScroll = false,
            isWithinBottomRange = false,
        )

        assertFalse(locked)
    }

    @Test
    fun `reaching bottom manually releases auto scroll lock`() {
        val locked = resolvePreventAutoScroll(
            currentValue = true,
            isProgrammaticScroll = false,
            isWithinBottomRange = true,
        )

        assertFalse(locked)
    }

    @Test
    fun `programmatic scroll keeps existing lock state`() {
        val locked = resolvePreventAutoScroll(
            currentValue = true,
            isProgrammaticScroll = true,
            isWithinBottomRange = true,
        )

        assertTrue(locked)
    }

    @Test
    fun `底部48dp内保持跟随超出后显示返回入口`() {
        assertTrue(isWithinBottomActivationRange(remainingPx = 48, canScrollForward = true, activationRangePx = 48))
        assertFalse(isWithinBottomActivationRange(remainingPx = 49, canScrollForward = true, activationRangePx = 48))
        assertFalse(isWithinBottomActivationRange(remainingPx = null, canScrollForward = true, activationRangePx = 48))
        assertTrue(isWithinBottomActivationRange(remainingPx = 0, canScrollForward = false, activationRangePx = 48))
    }

    @Test
    fun `bottom pin reacts only to content revisions and supports disposal`() {
        val source = listOf(
            File("src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/text/state/ChatScrollStateManager.kt"),
            File("app/src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/text/state/ChatScrollStateManager.kt"),
            File("app1/app/src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/text/state/ChatScrollStateManager.kt"),
        ).firstOrNull { it.isFile }?.readText(Charsets.UTF_8)

        requireNotNull(source) { "找不到 ChatScrollStateManager.kt" }
        assertTrue(source.contains("snapshotFlow { bottomContentRevision() }"))
        assertTrue(source.contains("first { revision -> revision != handledRevision }"))
        assertTrue(source.contains("remainingPx = changedRevision.remainingPx"))
        assertTrue(source.contains("canScrollForward = changedRevision.canScrollForward"))
        assertTrue(source.contains("fun dispose()"))
        assertTrue(source.contains("stateObserverJob.cancel()"))
        assertFalse(source.contains("INITIAL_BOTTOM_SETTLE"))
        assertFalse(source.contains("STOP_BOTTOM_PIN_TIMEOUT_MS"))
        assertFalse(source.contains("BottomLayoutSignature"))
        assertFalse(source.contains("layout.canScrollForward"))
        assertFalse(source.contains("lastVisibleOffset"))
    }
}
