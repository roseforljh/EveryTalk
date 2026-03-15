package com.android.everytalk.data.network.openclaw

data class OpenClawRunContext(
    val sessionKey: String,
    val runId: String? = null,
    val abortRequested: Boolean = false
)

object OpenClawRuntimeState {
    @Volatile
    private var activeContext: OpenClawRunContext? = null

    fun update(sessionKey: String, runId: String?) {
        if (sessionKey.isBlank()) return
        activeContext = OpenClawRunContext(
            sessionKey = sessionKey,
            runId = runId,
            abortRequested = activeContext?.abortRequested == true
        )
    }

    fun current(): OpenClawRunContext? = activeContext

    fun markAbortRequested(sessionKey: String, runId: String?) {
        activeContext = OpenClawRunContext(
            sessionKey = sessionKey,
            runId = runId,
            abortRequested = true
        )
    }

    fun clear() {
        activeContext = null
    }
}

