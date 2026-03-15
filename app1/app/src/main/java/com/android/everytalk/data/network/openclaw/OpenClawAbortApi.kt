package com.android.everytalk.data.network.openclaw

object OpenClawAbortApi {
    fun buildAbortRequest(
        requestId: String,
        sessionKey: String,
        runId: String?
    ): OpenClawAbortRequest {
        return OpenClawAbortRequest(
            id = requestId,
            method = "chat.abort",
            params = OpenClawAbortParams(
                sessionKey = sessionKey,
                runId = runId
            )
        )
    }
}
