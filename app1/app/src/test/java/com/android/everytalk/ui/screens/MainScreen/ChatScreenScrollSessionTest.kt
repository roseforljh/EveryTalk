package com.android.everytalk.ui.screens.MainScreen

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.pinnedAnchorLayoutVersion
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.resolvePinnedAnchorPreScrollConsumption
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.resolveDynamicBottomReserveForVisibleGap
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.resolvePinnedUserBubbleAnchorY
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.restorePinnedBubbleAnchorForSession
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.shouldDispatchImageLoadedToBottomScroller
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.shouldClearTransientBottomReserveOnStreamChange
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.shouldEnableUserScrollForPinnedUserBubble
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.shouldResetTransientBottomReserve
import com.android.everytalk.ui.screens.viewmodel.resolveHistoryExpectedStableConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenScrollSessionTest {

    @Test
    fun `preserves scroll session when conversation id migrates from temporary id to first user message id`() {
        val stableConversationId = "user_message_1"
        val messages = listOf(
            Message(
                id = stableConversationId,
                text = "hello",
                sender = Sender.User
            )
        )

        val result = shouldPreserveScrollSessionOnConversationIdChange(
            previousConversationId = "user_temp_123",
            newConversationId = stableConversationId,
            messages = messages
        )

        assertTrue(result)
    }

    @Test
    fun `does not preserve scroll session for unrelated conversation switches`() {
        val messages = listOf(
            Message(
                id = "user_message_1",
                text = "hello",
                sender = Sender.User
            )
        )

        val result = shouldPreserveScrollSessionOnConversationIdChange(
            previousConversationId = "conversation_old",
            newConversationId = "conversation_new",
            messages = messages
        )

        assertFalse(result)
    }

    @Test
    fun `resets transient bottom reserve when visible conversation changes after streaming`() {
        val result = shouldResetTransientBottomReserve(
            previousConversationId = "conversation_a",
            currentConversationId = "conversation_b",
            isApiCalling = false
        )

        assertTrue(result)
    }

    @Test
    fun `keeps transient bottom reserve while active stream still owns pinned bubble`() {
        val result = shouldResetTransientBottomReserve(
            previousConversationId = "conversation_a",
            currentConversationId = "conversation_b",
            isApiCalling = true
        )

        assertFalse(result)
    }

    @Test
    fun `does not clear transient bottom reserve when stream stops in same conversation`() {
        val result = shouldClearTransientBottomReserveOnStreamChange(isApiCalling = false)

        assertFalse(result)
    }

    @Test
    fun `keeps user scroll enabled while user bubble is pinned over dynamic reserve`() {
        val result = shouldEnableUserScrollForPinnedUserBubble(
            grokScrollCompleted = true,
            isApiCalling = true,
            hasPinnedUserMessage = true,
            hasDynamicBottomReserve = true
        )

        assertTrue(result)
    }

    @Test
    fun `enables user scroll when no pinned dynamic reserve exists`() {
        val result = shouldEnableUserScrollForPinnedUserBubble(
            grokScrollCompleted = true,
            isApiCalling = false,
            hasPinnedUserMessage = false,
            hasDynamicBottomReserve = false
        )

        assertTrue(result)
    }

    @Test
    fun `pre scroll does not consume upward drag when pinned bubble is already at anchor`() {
        val consumed = resolvePinnedAnchorPreScrollConsumption(
            availableY = -18f,
            currentY = 120,
            targetY = 120,
            hasPinnedUserMessage = true,
            hasDynamicBottomReserve = true,
            grokScrollCompleted = true
        )

        assertEquals(0f, consumed)
    }

    @Test
    fun `pre scroll does not consume downward drag for pinned bubble`() {
        val consumed = resolvePinnedAnchorPreScrollConsumption(
            availableY = 18f,
            currentY = 120,
            targetY = 120,
            hasPinnedUserMessage = true,
            hasDynamicBottomReserve = true,
            grokScrollCompleted = true
        )

        assertEquals(0f, consumed)
    }

    @Test
    fun `pinned anchor layout version changes when scroll offset changes`() {
        val before = pinnedAnchorLayoutVersion(
            totalItemsCount = 10,
            firstVisibleItemIndex = 3,
            firstVisibleItemScrollOffset = 0,
            visibleItemsSizeSum = 500,
            visibleItemsOffsetSum = 800
        )
        val after = pinnedAnchorLayoutVersion(
            totalItemsCount = 10,
            firstVisibleItemIndex = 3,
            firstVisibleItemScrollOffset = 24,
            visibleItemsSizeSum = 500,
            visibleItemsOffsetSum = 776
        )

        assertFalse(before == after)
    }

    @Test
    fun `does not restore stale pinned bubble anchor after switching back to conversation`() {
        val restored = restorePinnedBubbleAnchorForSession(
            savedAnchorY = 184,
            isPinnedRuntimeActive = false
        )

        assertEquals(-1, restored)
    }

    @Test
    fun `restores pinned bubble anchor only while pinned runtime is active`() {
        val restored = restorePinnedBubbleAnchorForSession(
            savedAnchorY = 184,
            isPinnedRuntimeActive = true
        )

        assertEquals(184, restored)
    }

    @Test
    fun `image loaded callback does not jump bottom while pinned reserve is active after stream finishes`() {
        val shouldDispatch = shouldDispatchImageLoadedToBottomScroller(
            isApiCalling = false,
            isAtBottom = true,
            hasPinnedUserMessage = true,
            hasDynamicBottomReserve = true
        )

        assertFalse(shouldDispatch)
    }

    @Test
    fun `pinned image reserve shrinks to visible gap like text mode`() {
        val reserve = resolveDynamicBottomReserveForVisibleGap(
            currentReservePx = 900,
            visibleGapPx = 640,
            minPinnedReservePx = 96,
            maxPinnedReservePx = 240,
            hasPinnedUserMessage = true
        )

        assertEquals(640, reserve)
    }

    @Test
    fun `pinned image reserve can shrink to zero like text mode`() {
        val reserve = resolveDynamicBottomReserveForVisibleGap(
            currentReservePx = 900,
            visibleGapPx = 0,
            minPinnedReservePx = 96,
            maxPinnedReservePx = 240,
            hasPinnedUserMessage = true
        )

        assertEquals(0, reserve)
    }

    @Test
    fun `large pinned user bubble anchors by center instead of top`() {
        val anchorY = resolvePinnedUserBubbleAnchorY(
            itemTopY = 120,
            itemHeightPx = 300,
            maxUserBubbleHeightPx = 480
        )

        assertEquals(270, anchorY)
    }

    @Test
    fun `normal pinned user bubble still anchors by top`() {
        val anchorY = resolvePinnedUserBubbleAnchorY(
            itemTopY = 120,
            itemHeightPx = 240,
            maxUserBubbleHeightPx = 480
        )

        assertEquals(120, anchorY)
    }

    @Test
    fun `image regeneration keeps loaded conversation stable id instead of new first message id`() {
        val stableId = resolveHistoryExpectedStableConversationId(
            isImageGeneration = true,
            loadedHistoryIndex = 2,
            currentConversationId = "original_user_id",
            stableIdFromMessages = "new_regenerated_user_id"
        )

        assertEquals("original_user_id", stableId)
    }

    @Test
    fun `new image conversation still migrates to first message stable id`() {
        val stableId = resolveHistoryExpectedStableConversationId(
            isImageGeneration = true,
            loadedHistoryIndex = null,
            currentConversationId = "new_image_generation_123",
            stableIdFromMessages = "first_user_id"
        )

        assertEquals("first_user_id", stableId)
    }
}
