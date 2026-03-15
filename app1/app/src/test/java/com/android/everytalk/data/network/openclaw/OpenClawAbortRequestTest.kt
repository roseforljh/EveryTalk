package com.android.everytalk.data.network.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenClawAbortRequestTest {

    @Test
    fun `build abort request with session and run id`() {
        val request = OpenClawAbortApi.buildAbortRequest(
            requestId = "req-1",
            sessionKey = "et:conv-1",
            runId = "run-1"
        )

        assertEquals("chat.abort", request.method)
        assertEquals("et:conv-1", request.params.sessionKey)
        assertEquals("run-1", request.params.runId)
    }

    @Test
    fun `build abort request without run id`() {
        val request = OpenClawAbortApi.buildAbortRequest(
            requestId = "req-1",
            sessionKey = "et:conv-1",
            runId = null
        )

        assertEquals("chat.abort", request.method)
        assertEquals("et:conv-1", request.params.sessionKey)
        assertNull(request.params.runId)
    }
}
