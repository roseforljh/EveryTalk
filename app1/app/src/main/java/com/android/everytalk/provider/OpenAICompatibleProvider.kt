package com.android.everytalk.provider

import android.content.Context
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.OpenAIDirectClient
import com.android.everytalk.models.SelectedMediaItem
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

class OpenAICompatibleProvider(
    private val httpClient: HttpClient
) : LLMProvider {
    
    override val providerName: String = "OpenAI"
    
    override val supportedChannels: List<String> = listOf(
        "openai", 
        "openai-compatible", 
        "azure", 
        "deepseek",
        "qwen",
        "moonshot",
        "zhipu"
    )
    
    override fun canHandle(request: ChatRequest): Boolean {
        val channel = request.channel.lowercase()
        val provider = request.provider.lowercase()
        
        if (supportedChannels.any { channel.contains(it) }) return true
        if (channel.contains("gemini")) return false
        
        return !provider.contains("gemini") && !request.model.contains("gemini", ignoreCase = true)
    }
    
    override suspend fun streamChat(
        request: ChatRequest,
        attachments: List<SelectedMediaItem>,
        context: Context
    ): Flow<AppStreamEvent> {
        return OpenAIDirectClient.streamChatDirect(httpClient, request)
    }
    
    override suspend fun getAvailableModels(apiUrl: String, apiKey: String): List<String> {
        return emptyList()
    }
}
