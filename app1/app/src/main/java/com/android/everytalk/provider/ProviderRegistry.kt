package com.android.everytalk.provider

import android.content.Context
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.models.SelectedMediaItem
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

class ProviderRegistry(
    httpClient: HttpClient
) {
    private val providers: List<LLMProvider> = listOf(
        GeminiProvider(httpClient),
        OpenAICompatibleProvider(httpClient)
    )
    
    fun getProvider(request: ChatRequest): LLMProvider {
        return providers.find { it.canHandle(request) }
            ?: providers.last()
    }
    
    suspend fun streamChat(
        request: ChatRequest,
        attachments: List<SelectedMediaItem>,
        context: Context
    ): Flow<AppStreamEvent> {
        val provider = getProvider(request)
        return provider.streamChat(request, attachments, context)
    }
    
    fun getAllProviderNames(): List<String> = providers.map { it.providerName }
}
