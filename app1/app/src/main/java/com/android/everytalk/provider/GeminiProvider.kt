package com.android.everytalk.provider

import android.content.Context
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.GeminiDirectClient
import com.android.everytalk.models.SelectedMediaItem
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

class GeminiProvider(
    private val httpClient: HttpClient
) : LLMProvider {
    
    override val providerName: String = "Gemini"
    
    override val supportedChannels: List<String> = listOf("gemini")
    
    override fun canHandle(request: ChatRequest): Boolean {
        val channel = request.channel.lowercase()
        val provider = request.provider.lowercase()
        val model = request.model.lowercase()
        
        if (channel.contains("openai")) return false
        
        return channel.contains("gemini") || 
               provider.contains("gemini") || 
               model.contains("gemini")
    }
    
    override suspend fun streamChat(
        request: ChatRequest,
        attachments: List<SelectedMediaItem>,
        context: Context
    ): Flow<AppStreamEvent> {
        return GeminiDirectClient.streamChatDirect(httpClient, request)
    }
    
    override suspend fun getAvailableModels(apiUrl: String, apiKey: String): List<String> {
        return emptyList()
    }
}
