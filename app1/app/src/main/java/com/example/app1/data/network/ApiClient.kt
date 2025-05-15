package com.example.app1.data.network

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

import com.example.app1.data.DataClass.ChatRequest
import com.example.app1.data.DataClass.OpenAiStreamChunk
import com.example.app1.data.DataClass.OpenAiChoice
import com.example.app1.data.DataClass.OpenAiDelta
import kotlinx.coroutines.channels.Channel

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
                requestTimeoutMillis = 300_000
                connectTimeoutMillis = 60_000
                socketTimeoutMillis = 300_000
            }
        }
    }

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
    fun streamChatResponse(request: ChatRequest): Flow<OpenAiStreamChunk> = channelFlow {
        val backendProxyUrl = "https://backdaitalk-production.up.railway.app/chat"
        var response: HttpResponse? = null

        try {
            Log.d("ApiClient", "准备向 $backendProxyUrl 发起 POST 请求")
            client.preparePost(backendProxyUrl) {
                contentType(ContentType.Application.Json)
                setBody(request)
                header("X-API-Key", request.apiKey ?: "")
                accept(ContentType.Text.EventStream)
                timeout {
                    requestTimeoutMillis = 310_000
                }
            }.execute { receivedResponse ->
                response = receivedResponse
                Log.d("ApiClient", "收到响应状态: ${response?.status}")

                if (response?.status?.isSuccess() != true) {
                    val errorBody = try {
                        response?.bodyAsText() ?: "(无错误响应体)"
                    } catch (e: Exception) {
                        "(读取错误响应体失败: ${e.message})"
                    }
                    Log.e("ApiClient", "代理错误 ${response?.status}. 响应体: $errorBody")
                    close(IOException("代理错误: ${response?.status?.value} - $errorBody"))
                    return@execute
                }

                val channel: ByteReadChannel = response?.bodyAsChannel() ?: run {
                    Log.e("ApiClient", "错误 - 响应体通道为 null。")
                    close(IOException("获取响应体通道失败。"))
                    return@execute
                }
                Log.d("ApiClient", "开始读取流通道...")

                // ------ 优化数据读取，及时按字节累计处理 ------
                val buf = ByteArray(1024)
                val sb = StringBuilder()
                try {
                    while (isActive && !channel.isClosedForRead) {
                        val bytesRead = channel.readAvailable(buf, 0, buf.size)
                        if (bytesRead == -1) break
                        if (bytesRead > 0) {
                            sb.append(String(buf, 0, bytesRead, Charsets.UTF_8))
                            while (true) {
                                val idx = sb.indexOf("\n")
                                if (idx == -1) break
                                var line = sb.substring(0, idx).trim()
                                sb.delete(0, idx + 1)
                                if (line.isNotEmpty()) {
                                    if (line.startsWith("data:")) line = line.substring(5).trim()
                                    // 打印带时间戳的日志（便于你分析流速和“堵塞点”!!!）
                                    Log.d(
                                        "ApiClient",
                                        "[流块收到] ${System.currentTimeMillis() % 100000} $line"
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
                                            val sendResult = trySend(openAiChunk)
                                            if (!sendResult.isSuccess) {
                                                Log.w(
                                                    "ApiClient",
                                                    "下游收集器已关闭或失败。停止读取流。原因: $sendResult"
                                                )
                                                channel.cancel(CoroutineCancellationException("下游已关闭: ${sendResult.toString()}"))
                                                return@execute
                                            }
                                        } catch (e: SerializationException) {
                                            Log.e(
                                                "ApiClient",
                                                "错误 - 解析流 JSON 块失败。原始 JSON: '$line'。错误: ${e.message}"
                                            )
                                            close(
                                                IOException(
                                                    "解析流 JSON 块失败: '${line.take(100)}...'. 错误: ${e.message}",
                                                    e
                                                )
                                            )
                                            return@execute
                                        } catch (e: Exception) {
                                            Log.e(
                                                "ApiClient",
                                                "错误 - 处理块 '$line' 时发生意外错误。错误: ${e.message}",
                                                e
                                            )
                                            close(
                                                IOException(
                                                    "处理块时发生意外错误: ${e.message}",
                                                    e
                                                )
                                            )
                                            return@execute
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Log.d(
                        "ApiClient",
                        "完成读取流通道 (isActive=$isActive, isClosedForRead=${channel.isClosedForRead})."
                    )
                } catch (e: IOException) {
                    Log.e("ApiClient", "错误 - 读取流期间发生网络错误: ${e.message}")
                    if (!isClosedForSend) close(IOException("网络错误: 流读取中断。${e.message}", e))
                } catch (e: CoroutineCancellationException) {
                    Log.i("ApiClient", "流读取已取消。原因: ${e.message}")
                    throw e
                } catch (e: Exception) {
                    Log.e("ApiClient", "错误 - 读取流期间发生意外错误: ${e.message}", e)
                    if (!isClosedForSend) close(IOException("意外的流读取错误: ${e.message}", e))
                }
                // ------ 优化 END ------
            }
        } catch (e: CoroutineCancellationException) {
            Log.i("ApiClient", "请求设置已取消。原因: ${e.message}")
            throw e
        } catch (e: IOException) {
            Log.e("ApiClient", "错误 - 请求设置/执行期间发生网络错误: ${e.message}")
            if (!isClosedForSend) close(IOException("网络设置/执行错误: ${e.message}", e))
        } catch (e: Exception) {
            Log.e("ApiClient", "错误 - 请求设置/执行期间发生意外错误: ${e.message}", e)
            if (!isClosedForSend) {
                val statusInfo = response?.status?.let { " (状态: ${it.value})" } ?: ""
                close(IOException("意外的 API 客户端错误$statusInfo: ${e.message}", e))
            }
        }
    }
        .buffer(Channel.BUFFERED)
        .flowOn(Dispatchers.IO)
}
