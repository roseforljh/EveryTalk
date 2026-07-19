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
            generation = 1L,
        )

        assertEquals("u1", turn?.anchorMessageId)
        assertEquals("a1", turn?.targetItemId)
    }

    @Test
    fun `activates when sent id is first user`() {
        val turn = resolveActiveTopAnchorTurn(
            items = listOf(
                TopAnchorItem("u1", TopAnchorItemRole.User),
                TopAnchorItem("a1", TopAnchorItemRole.AssistantTarget)
            ),
            sentUserMessageId = "u1",
            sessionKey = "s1",
            generation = 1L,
        )

        assertEquals("u1", turn?.anchorMessageId)
        assertEquals("a1", turn?.targetItemId)
    }

    @Test
    fun `activates as soon as sent user item enters list`() {
        val turn = resolveActiveTopAnchorTurn(
            items = listOf(
                TopAnchorItem("u0", TopAnchorItemRole.User),
                TopAnchorItem("a0", TopAnchorItemRole.AssistantTarget),
                TopAnchorItem("u1", TopAnchorItemRole.User)
            ),
            sentUserMessageId = "u1",
            sessionKey = "s1",
            generation = 1L,
        )

        assertEquals("u1", turn?.anchorMessageId)
        assertNull(turn?.targetItemId)
    }

    @Test
    fun `keeps target null when only non target item follows user`() {
        val turn = resolveActiveTopAnchorTurn(
            items = listOf(
                TopAnchorItem("u0", TopAnchorItemRole.User),
                TopAnchorItem("a0", TopAnchorItemRole.AssistantTarget),
                TopAnchorItem("u1", TopAnchorItemRole.User),
                TopAnchorItem("sys", TopAnchorItemRole.NonTarget)
            ),
            sentUserMessageId = "u1",
            sessionKey = "s1",
            generation = 1L,
        )

        assertEquals("u1", turn?.anchorMessageId)
        assertNull(turn?.targetItemId)
    }

    @Test
    fun `does not fall back to another user when sent id is absent`() {
        val turn = resolveActiveTopAnchorTurn(
            items = listOf(
                TopAnchorItem("u1", TopAnchorItemRole.User),
                TopAnchorItem("a1", TopAnchorItemRole.AssistantTarget)
            ),
            sentUserMessageId = "missing-user",
            sessionKey = "s1",
            generation = 1L,
        )

        assertNull(turn)
    }

    @Test
    fun `pending sent id is sufficient even before api running state`() {
        val turn = resolveActiveTopAnchorTurn(
            items = listOf(
                TopAnchorItem("u1", TopAnchorItemRole.User),
                TopAnchorItem("a1", TopAnchorItemRole.AssistantTarget),
            ),
            sentUserMessageId = "u1",
            sessionKey = "s1",
            generation = 1L,
        )

        assertEquals("u1", turn?.anchorMessageId)
        assertEquals("a1", turn?.targetItemId)
    }
}
