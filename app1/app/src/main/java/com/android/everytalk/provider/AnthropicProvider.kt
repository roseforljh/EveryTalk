package com.android.everytalk.provider

import android.content.Context
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.network.AnthropicDirectClient
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.models.SelectedMediaItem
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

class AnthropicProvider(
    private val httpClient: HttpClient,
) : LLMProvider {
    override val providerName: String = "Anthropic"

    override val supportedChannels: List<String> = listOf("anthropic")

    override fun canHandle(request: ChatRequest): Boolean {
        val channel = request.channel.trim().lowercase()
        val provider = request.provider.trim().lowercase()
        return supportedChannels.any(channel::contains) || provider.contains("anthropic")
    }

    override suspend fun streamChat(
        request: ChatRequest,
        attachments: List<SelectedMediaItem>,
        context: Context,
    ): Flow<AppStreamEvent> = AnthropicDirectClient.streamChatDirect(httpClient, request)

    override suspend fun getAvailableModels(apiUrl: String, apiKey: String): List<String> = emptyList()
}
