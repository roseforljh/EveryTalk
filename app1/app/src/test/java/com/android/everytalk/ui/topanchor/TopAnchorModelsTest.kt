package com.android.everytalk.ui.topanchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopAnchorModelsTest {
    @Test
    fun `initial snap suppresses bottom scroll even when reserve is zero`() {
        val state = TopAnchorRuntimeState(
            phase = TopAnchorPhase.InitialSnap,
            activeTurn = TopAnchorTurn("u2", "a2", "s1", 2L),
            reservePx = 0
        )

        assertTrue(state.suppressesBottomScroll)
        assertTrue(state.hasRuntime)
        assertEquals("u2", state.currentTurn?.anchorMessageId)
    }

    @Test
    fun `retained turn keeps runtime and suppression alive`() {
        val turn = TopAnchorTurn("u2", "a2", "s1", 2L)
        val state = TopAnchorRuntimeState(
            phase = TopAnchorPhase.Retained,
            retainedTurn = turn,
            reservePx = 0
        )

        assertTrue(state.suppressesBottomScroll)
        assertTrue(state.hasRuntime)
        assertEquals(turn, state.currentTurn)
    }

    @Test
    fun `idle state has no runtime`() {
        val state = TopAnchorRuntimeState()

        assertFalse(state.suppressesBottomScroll)
        assertFalse(state.hasRuntime)
    }

    @Test
    fun `user controlled phase keeps reserve runtime without suppressing user scroll`() {
        val turn = TopAnchorTurn("u2", "a2", "s1", 2L)
        val state = TopAnchorRuntimeState(
            phase = TopAnchorPhase.UserControlled,
            retainedTurn = turn,
            reservePx = 120,
        )

        assertFalse(state.suppressesBottomScroll)
        assertTrue(state.hasRuntime)
        assertEquals(120, state.reservePx)
        assertEquals(turn, state.currentTurn)
    }

    @Test
    fun `cleared runtime rejects stale correction even if a ghost reserve exists`() {
        val turn = TopAnchorTurn("u2", "a2", "s1", 2L)

        assertTrue(
            isTopAnchorCorrectionCurrent(
                runtime = TopAnchorRuntimeState(
                    phase = TopAnchorPhase.AnchoredRunning,
                    activeTurn = turn,
                    reservePx = 120,
                ),
                expectedTurn = turn,
            )
        )
        assertFalse(
            isTopAnchorCorrectionCurrent(
                runtime = TopAnchorRuntimeState(
                    phase = TopAnchorPhase.Idle,
                    reservePx = 120,
                ),
                expectedTurn = turn,
            )
        )
    }
}
