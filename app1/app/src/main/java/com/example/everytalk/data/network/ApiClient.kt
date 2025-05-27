package com.example.everytalk.data.network

import android.util.Log
import com.example.everytalk.data.DataClass.ChatRequest
import com.example.everytalk.data.DataClass.AppStreamEvent
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import java.io.IOException
import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlinx.coroutines.CancellationException as CoroutineCancellationException

// 确保这些导入是存在的
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.builtins.serializer // 用于 String.serializer(), Boolean.serializer() 等

object ApiClient {

    // --- 将 appSerializersModule 定义在 jsonParser 外部，或者直接在 Json 配置中定义 ---
    private val appSerializersModule = SerializersModule {
        // 为 @Contextual Any 可能代表的基本类型提供上下文序列化器
        contextual(String::class) { String.serializer() }
        contextual(Boolean::class) { Boolean.serializer() } // <--- 关键是这一类注册
        contextual(Int::class) { Int.serializer() }
        contextual(Long::class) { Long.serializer() }
        contextual(Float::class) { Float.serializer() }
        contextual(Double::class) { Double.serializer() }
    }

    private val jsonParser: Json by lazy {
        Log.d("ApiClient", "创建 Json 解析器实例...")
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            serializersModule = appSerializersModule // <-- 使用上面定义的、包含具体类型注册的模块
        }
    }

    // ... 您文件的其余部分保持不变 ...
    private val client: HttpClient by lazy {
        Log.d("ApiClient", "创建 Ktor HttpClient 实例...")
        HttpClient(Android) {
            install(ContentNegotiation) {
                json(jsonParser) // This will now use the jsonParser with the SerializersModule
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 300_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 300_000
            }
        }
    }

    private val backendProxyUrls = listOf(
        //"https://uoseegiydwgx.us-west-1.clawcloudrun.com/chat",//claw could run
        "https://kunze999-backendai.hf.space/chat",//hugging face
        //"https://backdaitalk-production.up.railway.app/chat",//railway
        //"http://192.168.0.102:7860/chat" // 您的本地地址，请确保可访问
    )

    fun preWarm() {
        Log.d("ApiClient", "ApiClient.preWarm() 已调用。")
        val clientInstance = client
        Log.d("ApiClient", "Ktor HttpClient 实例已访问: $clientInstance")
        val jsonInstance = jsonParser
        Log.d("ApiClient", "Json 解析器实例已访问: $jsonInstance")
        try {
            @kotlinx.serialization.Serializable
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
    ): Flow<AppStreamEvent> = channelFlow {
        var response: HttpResponse? = null

        try {
            Log.d(
                "ApiClient",
                "准备向 $backendProxyUrl 发起 POST 请求, API Key: ${request.apiKey.takeLast(4)}"
            )
            client.preparePost(backendProxyUrl) {
                contentType(ContentType.Application.Json)
                setBody(request)
                accept(ContentType.Text.EventStream)
                timeout { requestTimeoutMillis = 310_000 }
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

                val buf = ByteArray(1024 * 8)
                val sb = StringBuilder()
                try {
                    while (isActive && !channel.isClosedForRead) {
                        val bytesRead = channel.readAvailable(buf, 0, buf.size)
                        if (bytesRead == -1) break
                        if (bytesRead > 0) {
                            sb.append(String(buf, 0, bytesRead, Charsets.UTF_8))
                            var lineBreakIndex: Int
                            while (sb.indexOf("\n").also { lineBreakIndex = it } != -1) {
                                var line = sb.substring(0, lineBreakIndex).trim()
                                sb.delete(0, lineBreakIndex + 1)

                                if (line.isEmpty()) continue
                                if (line.startsWith("data:")) {
                                    line = line.substring(5).trim()
                                } else if (line.startsWith(":")) {
                                    Log.v("ApiClientStream", "[SSE注释 $backendProxyUrl] $line")
                                    continue
                                }

                                Log.v(
                                    "ApiClientStream",
                                    "[流块收到 $backendProxyUrl] ${System.currentTimeMillis() % 100000} $line"
                                )

                                if (line.isNotEmpty()) {
                                    try {
                                        val appEvent = jsonParser.decodeFromString(
                                            AppStreamEvent.serializer(),
                                            line
                                        )
                                        val sendResult = trySend(appEvent)
                                        if (!sendResult.isSuccess) {
                                            Log.w(
                                                "ApiClient",
                                                "下游收集器 ($backendProxyUrl) 已关闭或失败。停止读取流。原因: $sendResult. Closed for send: $isClosedForSend"
                                            )
                                            if (!isClosedForSend) {
                                                channel.cancel(CoroutineCancellationException("下游 ($backendProxyUrl) 已关闭: $sendResult"))
                                            }
                                            return@execute
                                        }
                                    } catch (e: SerializationException) {
                                        Log.e(
                                            "ApiClient",
                                            "错误 - 解析来自 $backendProxyUrl 的流 JSON 块为 AppStreamEvent 失败。原始 JSON: '$line'。错误: ${e.message}",
                                            e
                                        )
                                    } catch (e: Exception) {
                                        Log.e(
                                            "ApiClient",
                                            "错误 - 处理来自 $backendProxyUrl 的块 '$line' 时发生意外错误。错误: ${e.message}",
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

                    if (sb.isNotEmpty() && isActive && !isClosedForSend) {
                        var line = sb.toString().trim()
                        if (line.startsWith("data:")) line = line.substring(5).trim()
                        Log.v(
                            "ApiClientStream",
                            "[流块收到 $backendProxyUrl EOF] ${System.currentTimeMillis() % 100000} $line"
                        )
                        if (line.isNotEmpty()) {
                            try {
                                val appEvent =
                                    jsonParser.decodeFromString(AppStreamEvent.serializer(), line)
                                trySend(appEvent)
                            } catch (e: SerializationException) {
                                Log.e(
                                    "ApiClient",
                                    "错误 - 解析来自 $backendProxyUrl 的流 JSON 块 (EOF) 为 AppStreamEvent 失败。原始 JSON: '$line'. 错误: ${e.message}"
                                )
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
                    throw e
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
                }
            }
        } catch (e: CoroutineCancellationException) {
            Log.i("ApiClient", "到 $backendProxyUrl 的请求设置已取消。原因: ${e.message}")
            throw e
        } catch (e: HttpRequestTimeoutException) {
            Log.e("ApiClient", "错误 - 到 $backendProxyUrl 的请求超时: ${e.message}")
            throw IOException("请求超时 ($backendProxyUrl): ${e.message}", e)
        } catch (e: ConnectTimeoutException) {
            Log.e("ApiClient", "错误 - 到 $backendProxyUrl 的连接超时: ${e.message}")
            throw IOException("连接超时 ($backendProxyUrl): ${e.message}", e)
        } catch (e: IOException) {
            Log.e(
                "ApiClient",
                "错误 - 到 $backendProxyUrl 的请求设置/执行期间发生网络错误: ${e.message}"
            )
            throw IOException("网络设置/执行错误 ($backendProxyUrl): ${e.message}", e)
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
            )
        }
        Log.d("ApiClient", "streamChatResponseInternal for $backendProxyUrl 正常完成。")
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    fun streamChatResponse(request: ChatRequest): Flow<AppStreamEvent> =
        flow {
            var lastError: Exception? = null
            val successfulUrls = mutableListOf<String>()

            if (backendProxyUrls.isEmpty()) {
                Log.e("ApiClient", "没有配置后端代理服务器 URL。")
                throw IOException("没有后端服务器URL可供尝试。")
            }

            for (url in backendProxyUrls) {
                Log.i("ApiClient", "尝试连接到后端: $url")
                try {
                    streamChatResponseInternal(
                        url,
                        request
                    ).collect { appEvent ->
                        if (successfulUrls.isEmpty()) {
                            Log.i("ApiClient", "成功从 $url 收到第一个数据块。将使用此服务器。")
                            successfulUrls.add(url)
                        }
                        emit(appEvent)
                    }
                    Log.i("ApiClient", "成功完成从 $url 的流式传输。")
                    return@flow
                } catch (e: CoroutineCancellationException) {
                    Log.i("ApiClient", "尝试 $url 时发生协程取消: ${e.message}")
                    throw e
                } catch (e: IOException) {
                    Log.w("ApiClient", "连接或流式传输 $url 失败 (IO): ${e.message}")
                    lastError = e
                } catch (e: Exception) {
                    Log.e("ApiClient", "连接或流式传输 $url 失败 (其他异常): ${e.message}", e)
                    lastError = e
                }
            }

            Log.e("ApiClient", "所有后端服务器均连接或流式传输失败。")
            if (lastError != null) {
                Log.e("ApiClient", "最后记录的错误: ${lastError.message}")
                throw lastError
            } else {
                Log.e("ApiClient", "所有后端服务器均尝试完毕，但没有记录到明确的错误。")
                throw IOException("无法连接到任何可用的后端服务器。")
            }
        }
            .buffer(Channel.BUFFERED)
            .flowOn(Dispatchers.IO)
}