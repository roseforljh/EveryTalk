package com.android.everytalk.provider

import android.content.Context
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.models.SelectedMediaItem
import kotlinx.coroutines.flow.Flow

interface LLMProvider {
    val providerName: String
    val supportedChannels: List<String>
    
    fun canHandle(request: ChatRequest): Boolean
    
    suspend fun streamChat(
        request: ChatRequest,
        attachments: List<SelectedMediaItem>,
        context: Context
    ): Flow<AppStreamEvent>
    
    suspend fun getAvailableModels(apiUrl: String, apiKey: String): List<String>
}
