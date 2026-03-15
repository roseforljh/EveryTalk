package com.android.everytalk.data.network.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenClawAbortStateTest {

    @Test
    fun `stores active run context`() {
        OpenClawRuntimeState.update(sessionKey = "et:conv-1", runId = "run-1")

        val current = OpenClawRuntimeState.current()

        assertEquals("et:conv-1", current?.sessionKey)
        assertEquals("run-1", current?.runId)
        assertEquals(false, current?.abortRequested)
    }

    @Test
    fun `mark abort requested keeps run context`() {
        OpenClawRuntimeState.update(sessionKey = "et:conv-1", runId = "run-1")
        OpenClawRuntimeState.markAbortRequested(sessionKey = "et:conv-1", runId = "run-1")

        val current = OpenClawRuntimeState.current()

        assertEquals("et:conv-1", current?.sessionKey)
        assertEquals("run-1", current?.runId)
        assertEquals(true, current?.abortRequested)
    }

    @Test
    fun `clear removes active run context`() {
        OpenClawRuntimeState.update(sessionKey = "et:conv-1", runId = "run-1")
        OpenClawRuntimeState.clear()

        assertNull(OpenClawRuntimeState.current())
    }

    @Test
    fun `update with null run id preserves session key and clears run id`() {
        OpenClawRuntimeState.update(sessionKey = "et:conv-1", runId = "run-1")
        OpenClawRuntimeState.update(sessionKey = "et:conv-1", runId = null)

        val current = OpenClawRuntimeState.current()

        assertEquals("et:conv-1", current?.sessionKey)
        assertNull(current?.runId)
        assertEquals(false, current?.abortRequested)
    }
}
