package com.android.everytalk.data.network.openclaw

import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.network.AppStreamEvent
import kotlinx.coroutines.flow.Flow

interface OpenClawChatTransport {
    suspend fun streamChat(request: ChatRequest): Flow<AppStreamEvent>

    suspend fun abortCurrentRun() {
    }
}
