package com.example.everytalk.data.network

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import java.io.IOException

import com.example.everytalk.data.DataClass.ChatRequest
import com.example.everytalk.data.DataClass.OpenAiStreamChunk
import com.example.everytalk.data.DataClass.OpenAiChoice
import com.example.everytalk.data.DataClass.OpenAiDelta
import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlinx.coroutines.channels.Channel

// 重命名以避免与 java.util.concurrent.CancellationException 混淆
import kotlinx.coroutines.CancellationException as CoroutineCancellationException

object ApiClient {

    private val jsonParser: Json by lazy {
        Log.d("ApiClient", "创建 Json 解析器实例...")
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }

    private val client: HttpClient by lazy {
        Log.d("ApiClient", "创建 Ktor HttpClient 实例...")
        HttpClient(Android) {
            install(ContentNegotiation) {
                json(jsonParser)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 300_000 // 整体请求超时（包括流传输完成）
                connectTimeoutMillis = 15_000   // 连接超时缩短，以便更快地尝试下一个服务器
                socketTimeoutMillis = 300_000  // socket 读取超时（单个数据包之间）
            }
        }
    }

    // --- 新增：后端服务器 URL 列表 ---
    private val backendProxyUrls = listOf(
        "https://kunze999-backendai.hf.space/chat",//hugging face
        "https://backdaitalk-production.up.railway.app/chat",//railway
        "http://192.168.0.2:8000/chat"
    )
    // --- 新增结束 ---

    @Serializable
    data class BackendStreamChunk(
        val type: String? = null,
        val text: String? = null,
        @SerialName("finish_reason")
        val finishReason: String? = null
    )

    fun preWarm() {
        Log.d("ApiClient", "ApiClient.preWarm() 已调用。")
        val clientInstance = client
        Log.d("ApiClient", "Ktor HttpClient 实例已访问: $clientInstance")
        val jsonInstance = jsonParser
        Log.d("ApiClient", "Json 解析器实例已访问: $jsonInstance")
        try {
            @Serializable
            data class PreWarmTestData(val status: String)

            val testJsonString = """{"status":"ok"}"""
            val decoded =
                jsonInstance.decodeFromString(PreWarmTestData.serializer(), testJsonString)
            Log.d("ApiClient", "Json 预热解码测试成功: $decoded")
        } catch (e: Exception) {
            Log.w("ApiClient", "Json 预热解码测试失败", e)
        }
        Log.d("ApiClient", "ApiClient.preWarm() 执行完毕。")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun streamChatResponseInternal(
        backendProxyUrl: String,
        request: ChatRequest
    ): Flow<OpenAiStreamChunk> = channelFlow {
        var response: HttpResponse? = null

        try {
            Log.d(
                "ApiClient",
                "准备向 $backendProxyUrl 发起 POST 请求, API Key: ${request.apiKey?.takeLast(4)}"
            )
            client.preparePost(backendProxyUrl) {
                contentType(ContentType.Application.Json)
                setBody(request) // request 包含了 apiKey
                accept(ContentType.Text.EventStream)
                timeout {

                    requestTimeoutMillis = 310_000
                }
            }.execute { receivedResponse ->
                response = receivedResponse
                Log.d("ApiClient", "收到来自 $backendProxyUrl 的响应状态: ${response?.status}")

                if (response?.status?.isSuccess() != true) {
                    val errorBody = try {
                        response?.bodyAsText() ?: "(无错误响应体)"
                    } catch (e: Exception) {
                        "(读取错误响应体失败: ${e.message})"
                    }
                    Log.e(
                        "ApiClient",
                        "代理错误 $backendProxyUrl ${response?.status}. 响应体: $errorBody"
                    )
                    throw IOException("代理错误 ($backendProxyUrl): ${response?.status?.value} - $errorBody")
                }

                val channel: ByteReadChannel = response?.bodyAsChannel() ?: run {
                    Log.e("ApiClient", "错误 - 来自 $backendProxyUrl 的响应体通道为 null。")
                    throw IOException("获取来自 $backendProxyUrl 的响应体通道失败。")
                }
                Log.d("ApiClient", "开始从 $backendProxyUrl 读取流通道...")

                val buf = ByteArray(1024)
                val sb = StringBuilder()
                try {
                    while (isActive && !channel.isClosedForRead) {
                        val bytesRead = channel.readAvailable(buf, 0, buf.size)
                        if (bytesRead == -1) break // End of stream
                        if (bytesRead > 0) {
                            sb.append(String(buf, 0, bytesRead, Charsets.UTF_8))
                            var lineBreakIndex: Int
                            while (sb.indexOf("\n").also { lineBreakIndex = it } != -1) {
                                var line = sb.substring(0, lineBreakIndex).trim()
                                sb.delete(0, lineBreakIndex + 1)

                                if (line.isEmpty()) continue // Skip empty lines (e.g., consecutive newlines)

                                if (line.startsWith("data:")) {
                                    line = line.substring(5).trim()
                                }

                                Log.v(
                                    "ApiClientStream", // 使用更详细的日志标签
                                    "[流块收到 $backendProxyUrl] ${System.currentTimeMillis() % 100000} $line"
                                )

                                if (line.isNotEmpty()) { // 确保 "data: " 去掉后还有内容
                                    try {
                                        val backendChunk = jsonParser.decodeFromString(
                                            BackendStreamChunk.serializer(),
                                            line
                                        )
                                        val openAiChunk = OpenAiStreamChunk(
                                            choices = listOf(
                                                OpenAiChoice(
                                                    index = 0,
                                                    delta = OpenAiDelta(
                                                        content = if (backendChunk.type == "content") backendChunk.text else null,
                                                        reasoningContent = if (backendChunk.type == "reasoning") backendChunk.text else null
                                                    ),
                                                    finishReason = backendChunk.finishReason
                                                )
                                            )
                                        )
                                        val sendResult = trySend(openAiChunk)
                                        if (!sendResult.isSuccess) {
                                            Log.w(
                                                "ApiClient",
                                                "下游收集器 ($backendProxyUrl) 已关闭或失败。停止读取流。原因: ${sendResult}. Closed for send: ${isClosedForSend}"
                                            )
                                            if (!isClosedForSend) { // 避免在已经关闭时再次尝试关闭或取消
                                                channel.cancel(CoroutineCancellationException("下游 ($backendProxyUrl) 已关闭: $sendResult"))
                                            }
                                            return@execute // 退出 execute 块
                                        }
                                    } catch (e: SerializationException) {
                                        Log.e(
                                            "ApiClient",
                                            "错误 - 解析来自 $backendProxyUrl 的流 JSON 块失败。原始 JSON: '$line'。错误: ${e.message}"
                                        )
                                        // 抛出异常，让外部的 streamChatResponse 知道此URL失败
                                        throw IOException(
                                            "解析来自 $backendProxyUrl 的流 JSON 块失败: '${
                                                line.take(
                                                    100
                                                )
                                            }...'. 错误: ${e.message}",
                                            e
                                        )
                                    } catch (e: Exception) { // 捕获所有其他在处理块时可能发生的异常
                                        Log.e(
                                            "ApiClient",
                                            "错误 - 处理来自 $backendProxyUrl 的块 '$line' 时发生意外错误。错误: ${e.message}",
                                            e
                                        )
                                        throw IOException(
                                            "处理来自 $backendProxyUrl 的块时发生意外错误: ${e.message}",
                                            e
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Log.d(
                        "ApiClient",
                        "完成从 $backendProxyUrl 读取流通道 (isActive=$isActive, isClosedForRead=${channel.isClosedForRead}, sb_remaining='${
                            sb.toString().take(50)
                        }')."
                    )
                    // 处理 StringBuilder 中可能剩余的最后一部分数据（如果没有以\n结尾）
                    if (sb.isNotEmpty() && isActive && !isClosedForSend) {
                        var line = sb.toString().trim()
                        if (line.startsWith("data:")) line = line.substring(5).trim()
                        Log.v(
                            "ApiClientStream",
                            "[流块收到 $backendProxyUrl EOF] ${System.currentTimeMillis() % 100000} $line"
                        )
                        if (line.isNotEmpty()) {
                            try {
                                val backendChunk = jsonParser.decodeFromString(
                                    BackendStreamChunk.serializer(),
                                    line
                                )
                                val openAiChunk = OpenAiStreamChunk(
                                    choices = listOf(
                                        OpenAiChoice(
                                            index = 0,
                                            delta = OpenAiDelta(
                                                content = if (backendChunk.type == "content") backendChunk.text else null,
                                                reasoningContent = if (backendChunk.type == "reasoning") backendChunk.text else null
                                            ),
                                            finishReason = backendChunk.finishReason
                                        )
                                    )
                                )
                                trySend(openAiChunk) // 不检查结果，因为这可能是最后一条
                            } catch (e: SerializationException) {
                                Log.e(
                                    "ApiClient",
                                    "错误 - 解析来自 $backendProxyUrl 的流 JSON 块 (EOF) 失败。原始 JSON: '$line'. 错误: ${e.message}"
                                )
                                // 不再抛出，因为流可能已结束
                            }
                        }
                    }

                } catch (e: IOException) {
                    Log.e(
                        "ApiClient",
                        "错误 - 从 $backendProxyUrl 读取流期间发生网络错误: ${e.message}"
                    )
                    if (!isClosedForSend) throw IOException(
                        "网络错误 ($backendProxyUrl): 流读取中断。${e.message}",
                        e
                    )
                } catch (e: CoroutineCancellationException) {
                    Log.i("ApiClient", "从 $backendProxyUrl 的流读取已取消。原因: ${e.message}")
                    throw e // 重新抛出，由外部协程处理
                } catch (e: Exception) {
                    Log.e(
                        "ApiClient",
                        "错误 - 从 $backendProxyUrl 读取流期间发生意外错误: ${e.message}",
                        e
                    )
                    if (!isClosedForSend) throw IOException(
                        "意外的流读取错误 ($backendProxyUrl): ${e.message}",
                        e
                    )
                } finally {
                    Log.d("ApiClient", "读取 $backendProxyUrl 流的 execute 块完成或异常退出。")
                    // channelFlow 会在协程体完成时自动关闭通道
                }
            }
        } catch (e: CoroutineCancellationException) {
            Log.i("ApiClient", "到 $backendProxyUrl 的请求设置已取消。原因: ${e.message}")
            throw e // 重新抛出
        } catch (e: HttpRequestTimeoutException) {
            Log.e("ApiClient", "错误 - 到 $backendProxyUrl 的请求超时: ${e.message}")
            throw IOException("请求超时 ($backendProxyUrl): ${e.message}", e) // 包装成 IOException
        } catch (e: ConnectTimeoutException) {
            Log.e("ApiClient", "错误 - 到 $backendProxyUrl 的连接超时: ${e.message}")
            throw IOException("连接超时 ($backendProxyUrl): ${e.message}", e) // 包装成 IOException
        } catch (e: IOException) {
            Log.e(
                "ApiClient",
                "错误 - 到 $backendProxyUrl 的请求设置/执行期间发生网络错误: ${e.message}"
            )
            throw IOException("网络设置/执行错误 ($backendProxyUrl): ${e.message}", e) // 重新抛出
        } catch (e: Exception) {
            Log.e(
                "ApiClient",
                "错误 - 到 $backendProxyUrl 的请求设置/执行期间发生意外错误: ${e.message}",
                e
            )
            val statusInfo = response?.status?.let { " (状态: ${it.value})" } ?: ""
            throw IOException(
                "意外的 API 客户端错误 ($backendProxyUrl)$statusInfo: ${e.message}",
                e
            ) // 重新抛出
        }
        // channelFlow 会在协程体正常完成或因异常退出时自动关闭。
        // 如果成功执行到这里，表示流已正常结束。
        Log.d("ApiClient", "streamChatResponseInternal for $backendProxyUrl 正常完成。")
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    fun streamChatResponse(request: ChatRequest): Flow<OpenAiStreamChunk> = flow {
        var lastError: Exception? = null
        val successfulUrls = mutableListOf<String>() // 用于记录成功连接的URL（可选）

        if (backendProxyUrls.isEmpty()) {
            Log.e("ApiClient", "没有配置后端代理服务器 URL。")
            throw IOException("没有后端服务器URL可供尝试。")
        }

        for (url in backendProxyUrls) {
            Log.i("ApiClient", "尝试连接到后端: $url")
            try {
                // 调用内部方法，尝试从此 URL 获取流
                // emitAll 会收集内部流并重新发射其元素。
                // 如果 streamChatResponseInternal 成功开始发射数据，此循环将在此处“暂停”直到流完成。
                // 如果 streamChatResponseInternal 抛出异常（例如连接失败），则会被 catch 块捕获。
                streamChatResponseInternal(url, request).collect { chunk ->
                    if (successfulUrls.isEmpty()) { // 第一次成功收到数据块
                        Log.i("ApiClient", "成功从 $url 收到第一个数据块。将使用此服务器。")
                        successfulUrls.add(url)
                    }
                    emit(chunk)
                }
                // 如果 collect 完成没有抛出异常，意味着这个 URL 的流成功完成了
                Log.i("ApiClient", "成功完成从 $url 的流式传输。")
                return@flow // 成功，不再尝试其他 URL
            } catch (e: CoroutineCancellationException) {
                Log.i("ApiClient", "尝试 $url 时发生协程取消: ${e.message}")
                throw e // 立即重新抛出取消异常，停止所有尝试
            } catch (e: IOException) { // 主要捕获网络相关和特定业务逻辑的IO异常
                Log.w("ApiClient", "连接或流式传输 $url 失败 (IO): ${e.message}")
                lastError = e
                // 继续尝试下一个 URL
            } catch (e: Exception) { // 捕获其他意外异常
                Log.e("ApiClient", "连接或流式传输 $url 失败 (其他异常): ${e.message}", e)
                lastError = e
                // 继续尝试下一个 URL
            }
        }

        // 如果循环完成，表示所有 URL 都尝试失败
        Log.e("ApiClient", "所有后端服务器均连接或流式传输失败。")
        if (lastError != null) {
            Log.e("ApiClient", "最后记录的错误: ${lastError.message}")
            throw lastError // 抛出最后遇到的错误
        } else {
            // 理论上不应该到这里，除非 backendProxyUrls 为空（已在前面处理）
            // 或者所有尝试都因 CoroutineCancellationException 失败（已重新抛出）
            Log.e("ApiClient", "所有后端服务器均尝试完毕，但没有记录到明确的IO或常规异常。")
            throw IOException("无法连接到任何可用的后端服务器。")
        }
    }
        .buffer(Channel.BUFFERED) // 保持原有的 buffer
        .flowOn(Dispatchers.IO) // 在IO线程执行网络操作和流处理
}