package com.android.everytalk.data.network.openclaw

import android.content.Context
import android.util.Log
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.network.AppStreamEvent
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json

open class OpenClawRuntimeStatusService(
    private val context: Context,
    private val httpClient: HttpClient,
    private val json: Json
) {
    protected open suspend fun streamModelStatusCommand(request: ChatRequest) = OpenClawGatewayClient(
        httpClient = httpClient,
        deviceIdentityManager = OpenClawDeviceIdentityManager(context),
        json = json
    ).streamChat(request)

    private class ModelCommandCompleted : CancellationException()

    private fun logSlashModel(message: String) {
        runCatching { Log.d("SlashCommand", message) }
    }

    suspend fun proxyModelStatusCommand(request: ChatRequest): String {
        val reply = StringBuilder()
        var finalError: String? = null
        var finalReceived = false
        var currentRunId: String? = null

        try {
            streamModelStatusCommand(request).collect { event ->
                when (event) {
                    is AppStreamEvent.StatusUpdate -> {
                        event.stage.substringAfter("chat_run:", "")
                            .takeIf { it.isNotBlank() && it != event.stage }
                            ?.let { currentRunId = it }

                        if (event.stage != "connected" && !event.stage.startsWith("chat_run:")) {
                            logSlashModel("ignoring non-chat event while waiting for model result: ${event.stage}")
                        }
                    }
                    is AppStreamEvent.OpenClawRuntimeFinal -> {
                        val matchesCurrentRun = currentRunId?.let { it == event.runId } ?: true
                        if (matchesCurrentRun && event.state == "final") {
                            reply.clear()
                            reply.append(event.text)
                            finalReceived = true
                            logSlashModel("model final matched runId=${event.runId} -> completing immediately")
                            logSlashModel("model final text length=${event.text.length}")
                            throw ModelCommandCompleted()
                        }
                        logSlashModel("ignoring model final for runId=${event.runId}, currentRunId=${currentRunId.orEmpty()}")
                    }
                    is AppStreamEvent.Text -> reply.append(event.text)
                    is AppStreamEvent.Content -> reply.append(event.text)
                    is AppStreamEvent.ContentFinal -> {
                        reply.append(event.text)
                        finalReceived = true
                        logSlashModel("model content final fallback -> completing immediately")
                        logSlashModel("model final text length=${event.text.length}")
                        throw ModelCommandCompleted()
                    }
                    is AppStreamEvent.Error -> {
                        finalError = event.message.ifBlank { "未知错误" }
                    }
                    else -> Unit
                }
            }
        } catch (_: ModelCommandCompleted) {
            // final 到达即返回，不等待 flow/socket 自然结束
        }

        if (!finalReceived) {
            finalError?.let { throw IllegalStateException(it) }
        }
        return reply.toString().trim().ifBlank {
            throw IllegalStateException("后端未返回 /model status 文本结果")
        }
    }

    suspend fun queryModelsCatalog(request: ChatRequest, provider: String? = null): ModelsCatalogQueryResult {
        val gatewayClient = OpenClawGatewayClient(
            httpClient = httpClient,
            deviceIdentityManager = OpenClawDeviceIdentityManager(context),
            json = json
        )
        return gatewayClient.queryModelsCatalog(request, provider)
    }
}
