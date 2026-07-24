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
    fun `manual scroll away from bottom keeps auto scroll locked`() {
        val locked = resolvePreventAutoScroll(
            currentValue = false,
            isProgrammaticScroll = false,
            isStrictlyAtBottom = false
        )

        assertTrue(locked)
    }

    @Test
    fun `reaching bottom manually releases auto scroll lock`() {
        val locked = resolvePreventAutoScroll(
            currentValue = true,
            isProgrammaticScroll = false,
            isStrictlyAtBottom = true
        )

        assertFalse(locked)
    }

    @Test
    fun `programmatic scroll keeps existing lock state`() {
        val locked = resolvePreventAutoScroll(
            currentValue = true,
            isProgrammaticScroll = true,
            isStrictlyAtBottom = true
        )

        assertTrue(locked)
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
