package com.android.everytalk.ui.topanchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopAnchorTurnPolicyTest {
    @Test
    fun `uses sent user before assistant target`() {
        val turn = resolveActiveTopAnchorTurn(
            items = listOf(
                TopAnchorItem("u0", TopAnchorItemRole.User),
                TopAnchorItem("a0", TopAnchorItemRole.AssistantTarget),
                TopAnchorItem("u1", TopAnchorItemRole.User),
                TopAnchorItem("a1", TopAnchorItemRole.AssistantTarget)
            ),
            sentUserMessageId = "u1",
            sessionKey = "s1",
            generation = 1L
        )

        assertEquals("u1", turn?.anchorMessageId)
        assertEquals("a1", turn?.targetItemId)
    }

    @Test
    fun `does not activate when sent id is first user only`() {
        val turn = resolveActiveTopAnchorTurn(
            items = listOf(TopAnchorItem("u1", TopAnchorItemRole.User)),
            sentUserMessageId = "u1",
            sessionKey = "s1",
            generation = 1L
        )

        assertNull(turn)
    }

    @Test
    fun `does not treat non target item as assistant target`() {
        val turn = resolveActiveTopAnchorTurn(
            items = listOf(
                TopAnchorItem("u0", TopAnchorItemRole.User),
                TopAnchorItem("a0", TopAnchorItemRole.AssistantTarget),
                TopAnchorItem("u1", TopAnchorItemRole.User),
                TopAnchorItem("sys", TopAnchorItemRole.NonTarget)
            ),
            sentUserMessageId = "u1",
            sessionKey = "s1",
            generation = 1L
        )

        assertNull(turn)
    }
}
