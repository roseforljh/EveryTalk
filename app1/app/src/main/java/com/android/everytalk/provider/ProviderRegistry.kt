package com.android.everytalk.provider

import android.content.Context
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.models.SelectedMediaItem
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

class ProviderRegistry(
    private val context: Context,
    httpClient: HttpClient
) {
    private val providers: List<LLMProvider> = listOf(
        GeminiProvider(httpClient),
        OpenClawProvider(
            httpClient = httpClient,
            deviceIdentityManager = com.android.everytalk.data.network.openclaw.OpenClawDeviceIdentityManager(context)
        ),
        OpenAICompatibleProvider(httpClient)
    )
    
    fun getProvider(request: ChatRequest): LLMProvider {
        val matched = providers.find { it.canHandle(request) }
        android.util.Log.i(
            "ProviderRegistry",
            "resolved provider=${matched?.providerName ?: providers.last().providerName}, request.provider=${request.provider}, channel=${request.channel}, model=${request.model}"
        )
        return matched ?: providers.last()
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
