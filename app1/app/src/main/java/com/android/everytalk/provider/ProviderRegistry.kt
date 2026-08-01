package com.android.everytalk.provider

import android.content.Context
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.modelParameterProtocol
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.models.SelectedMediaItem
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

class ProviderRegistry(
    httpClient: HttpClient
) {
    private val geminiProvider = GeminiProvider(httpClient)
    private val anthropicProvider = AnthropicProvider(httpClient)
    private val openAICompatibleProvider = OpenAICompatibleProvider(httpClient)
    private val providers: List<LLMProvider> = listOf(geminiProvider, anthropicProvider, openAICompatibleProvider)
    
    fun getProvider(request: ChatRequest): LLMProvider {
        val matched = when (modelParameterProtocol(request.channel)) {
            ModelParameterProtocol.GEMINI -> geminiProvider
            ModelParameterProtocol.ANTHROPIC -> anthropicProvider
            ModelParameterProtocol.CODEX,
            ModelParameterProtocol.OPENAI_COMPATIBLE -> openAICompatibleProvider
        }
        android.util.Log.i(
            "ProviderRegistry",
            "resolved provider=${matched.providerName}, request.provider=${request.provider}, channel=${request.channel}, model=${request.model}"
        )
        return matched
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
