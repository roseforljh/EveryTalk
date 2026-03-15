package com.android.everytalk.provider

import android.content.Context
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.openclaw.OpenClawChatTransport
import com.android.everytalk.data.network.openclaw.OpenClawDeviceIdentityManager
import com.android.everytalk.data.network.openclaw.OpenClawGatewayClient
import com.android.everytalk.models.SelectedMediaItem
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

open class OpenClawProvider(
    private val httpClient: HttpClient,
    private val deviceIdentityManager: OpenClawDeviceIdentityManager
) : LLMProvider {

    override val providerName: String = "OpenClaw"

    override val supportedChannels: List<String> = listOf("openclaw")

    override fun canHandle(request: ChatRequest): Boolean {
        val channel = request.channel.lowercase()
        val provider = request.provider.lowercase()
        val model = request.model.lowercase()

        return supportedChannels.any { channel.contains(it) } ||
            provider.contains("openclaw") ||
            model.contains("openclaw")
    }

    override suspend fun streamChat(
        request: ChatRequest,
        attachments: List<SelectedMediaItem>,
        context: Context
    ): Flow<AppStreamEvent> {
        return resolveTransport(request).streamChat(request)
    }

    protected open fun resolveTransport(request: ChatRequest): OpenClawChatTransport {
        return OpenClawGatewayClient(
            httpClient = httpClient,
            deviceIdentityManager = deviceIdentityManager
        )
    }

    override suspend fun getAvailableModels(apiUrl: String, apiKey: String): List<String> {
        return emptyList()
    }
}

