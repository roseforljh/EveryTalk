package com.example.app1.data.network

import android.util.Log // 导入 Logcat 日志工具
import io.ktor.client.*
import io.ktor.client.engine.android.* // 使用 Ktor 的 Android 引擎
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.* // 用于 ByteReadChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import java.io.IOException // 使用 java.io.IOException

// 导入项目特定的数据模型
import com.example.app1.data.models.ChatRequest
import com.example.app1.data.models.OpenAiStreamChunk
import com.example.app1.data.models.OpenAiChoice
import com.example.app1.data.models.OpenAiDelta

// 显式使用 kotlinx.coroutines.CancellationException 以避免歧义
import kotlinx.coroutines.CancellationException as CoroutineCancellationException

object ApiClient {

    // Ktor 客户端和后端流解析所用的 JSON 解析器配置
    private val jsonParser: Json by lazy { // 使用 lazy 初始化，以便可以被预热
        Log.d("ApiClient", "创建 Json 解析器实例...")
        Json {
            ignoreUnknownKeys = true // 容忍后端/API返回的多余字段
            isLenient = true       // 如果可能，允许轻微格式错误的JSON（谨慎使用）
            encodeDefaults = true  // 确保请求中发送默认值
        }
    }

    // Ktor HTTP 客户端设置
    private val client: HttpClient by lazy { // 使用 lazy 初始化
        Log.d("ApiClient", "创建 Ktor HttpClient 实例...")
        HttpClient(Android) { // 使用 Android 引擎
            // 内容协商，用于自动 JSON 序列化/反序列化
            install(ContentNegotiation) {
                json(jsonParser) // 使用我们上面配置的 jsonParser
            }
            // 超时配置
            install(HttpTimeout) {
                requestTimeoutMillis = 300_000 // 整个请求的超时时间为 5 分钟
                connectTimeoutMillis = 60_000  // 建立连接的超时时间为 1 分钟
                socketTimeoutMillis = 300_000  // 数据传输不活动的超时时间为 5 分钟
            }
            // 可选：日志记录 (用于调试)
            // install(Logging) {
            //     logger = object : Logger { // 自定义日志记录器或使用 Logger.DEFAULT
            //         override fun log(message: String) {
            //             Log.v("KtorHttpClient", message)
            //         }
            //     }
            //     level = LogLevel.ALL // 记录请求头和请求体 (注意敏感数据)
            // }
            // 可选：默认请求配置 (例如，通用的请求头)
            // defaultRequest {
            //     header("X-App-Version", "1.0.0") // 示例：应用版本号
            // }
        }
    }

    // 表示从 *你的* 后端代理期望接收的 JSON 块结构的数据类
    @Serializable
    data class BackendStreamChunk(
        val type: String? = null,        // 例如："content", "reasoning", "status", "error"
        val text: String? = null,        // 对于 "content" 或 "reasoning" 类型的实际文本内容
        @SerialName("finish_reason")     // 匹配后端 JSON 中的字段名
        val finishReason: String? = null // 例如："stop", "length", "error", "cancelled"
        // 添加你的后端可能在每个块中发送的其他字段，例如：
        // val message_id: String? = null
        // val sequence_id: Int? = null
        // val error_code: String? = null
    )

    /**
     * 预热 ApiClient 中的懒加载组件。
     * 目标是在实际使用前触发 Ktor HttpClient 和 Json 解析器的初始化。
     * 应在应用启动的早期阶段，在后台线程调用此方法。
     */
    fun preWarm() {
        Log.d("ApiClient", "ApiClient.preWarm() 已调用。")
        // 1. 触发 HttpClient 的创建和配置
        // 只需访问 client 属性即可触发其 lazy 初始化块
        val clientInstance = client
        Log.d("ApiClient", "Ktor HttpClient 实例已访问: $clientInstance")

        // 2. 触发 Json 解析器的创建
        val jsonInstance = jsonParser
        Log.d("ApiClient", "Json 解析器实例已访问: $jsonInstance")

        // 可选：可以尝试用 jsonParser 解析一个非常小的、预定义的 JSON 字符串
        // 这可以帮助触发 Kotlinx Serialization 内部关于特定类型序列化器的查找和缓存机制
        try {
            @kotlinx.serialization.Serializable
            data class PreWarmTestData(val status: String)

            val testJsonString = """{"status":"ok"}"""
            val decoded =
                jsonInstance.decodeFromString(PreWarmTestData.serializer(), testJsonString)
            Log.d("ApiClient", "Json 预热解码测试成功: $decoded")
        } catch (e: Exception) {
            Log.w("ApiClient", "Json 预热解码测试失败 (这可能正常，仅为预热目的)", e)
        }
        Log.d("ApiClient", "ApiClient.preWarm() 执行完毕。")
    }


    /**
     * 从后端代理流式传输聊天响应。
     * 处理 HTTP 请求并逐行解析服务器发送事件 (SSE) 流。
     * 将后端的块格式映射到 ViewModel 期望的 `OpenAiStreamChunk` 格式。
     *
     * @param request 包含消息、配置等的 ChatRequest 对象。
     * @return 一个 Flow，当接收和解析到 `OpenAiStreamChunk` 对象时发出它们。
     *         当流结束时，Flow 正常完成；或者发出错误 (IOException, CoroutineCancellationException)。
     */
    @OptIn(ExperimentalCoroutinesApi::class) // 用于 channelFlow
    fun streamChatResponse(request: ChatRequest): Flow<OpenAiStreamChunk> = channelFlow {
        // TODO: 替换为你的实际后端代理 URL
        val backendProxyUrl = "https://backdaitalk-production.up.railway.app/chat" // 示例 Ngrok URL
        var response: HttpResponse? = null // 保存响应对象，以便在出错时可以访问其信息

        try {
            Log.d("ApiClient", "准备向 $backendProxyUrl 发起 POST 请求")
            client.preparePost(backendProxyUrl) {
                contentType(ContentType.Application.Json) // 设置请求内容类型为 JSON
                setBody(request) // 将 ChatRequest 对象作为 JSON 请求体发送
                // 在请求头中包含 API 密钥 (如果你的代理需要，请调整请求头名称)
                header("X-API-Key", request.apiKey ?: "")
                // 如果你的代理需要其他请求头，在此处添加
                // header("Authorization", "Bearer ${request.apiKey}")
                accept(ContentType.Text.EventStream) // 显式接受 SSE 流
                timeout { // 可以为特定请求覆盖默认超时设置
                    requestTimeoutMillis = 310_000 // 对此特定调用设置稍长一点的超时时间
                }

            }.execute { receivedResponse -> // 执行请求并处理响应
                response = receivedResponse // 存储响应对象
                Log.d("ApiClient", "收到响应状态: ${response?.status}")

                // --- 检查响应状态 ---
                if (response?.status?.isSuccess() != true) {
                    val errorBody = try {
                        response?.bodyAsText() ?: "(无错误响应体)"
                    } catch (e: Exception) {
                        "(读取错误响应体失败: ${e.message})"
                    }
                    Log.e("ApiClient", "代理错误 ${response?.status}. 响应体: $errorBody")
                    // 使用错误关闭 Flow
                    close(IOException("代理错误: ${response?.status?.value} - $errorBody"))
                    return@execute // 停止处理
                }

                // --- 处理成功的流 ---
                val channel: ByteReadChannel = response?.bodyAsChannel() ?: run {
                    Log.e("ApiClient", "错误 - 响应体通道为 null。")
                    close(IOException("获取响应体通道失败。"))
                    return@execute
                }
                Log.d("ApiClient", "开始读取流通道...")

                try {
                    // 当 Flow 收集器处于活动状态且通道有数据时循环
                    while (isActive && !channel.isClosedForRead) {
                        // 读取行直到 EOF 或通道关闭
                        val line = channel.readUTF8Line()

                        if (line == null) {
                            Log.d("ApiClient", "流通道 EOF 已到达。")
                            break // 流结束
                        }

                        val messageString = line.trim()
                        // Log.v("ApiClient", "原始行: '$messageString'") // 调试原始行

                        // 处理非空行 (忽略潜在的保持连接的空行)
                        if (messageString.isNotEmpty()) {
                            // SSE 格式通常以 "data: " 为数据行前缀
                            val jsonData = if (messageString.startsWith("data:")) {
                                messageString.substring(5).trim()
                            } else {
                                // 如果不以 "data:" 开头，是否将整行视为 JSON？
                                // 或记录警告，具体取决于后端 SSE 格式。
                                Log.w(
                                    "ApiClient",
                                    "警告 - 收到没有 'data:' 前缀的行: '$messageString'。尝试直接解析。"
                                )
                                messageString
                            }

                            if (jsonData.isNotEmpty()) {
                                try {
                                    // 使用 BackendStreamChunk 结构解析 JSON 数据
                                    val backendChunk = jsonParser.decodeFromString(
                                        BackendStreamChunk.serializer(),
                                        jsonData
                                    )

                                    // --- 将后端块映射到 OpenAiStreamChunk ---
                                    val openAiChunk = OpenAiStreamChunk(
                                        // 为简单起见，假设后端每个块只有一个 choice
                                        choices = listOf(
                                            OpenAiChoice(
                                                index = 0,
                                                delta = OpenAiDelta(
                                                    // 根据后端的 'type' 字段进行映射
                                                    content = if (backendChunk.type == "content") backendChunk.text else null,
                                                    reasoningContent = if (backendChunk.type == "reasoning") backendChunk.text else null
                                                    // 如果需要，添加其他 delta 字段 (role, tool_calls)
                                                ),
                                                finishReason = backendChunk.finishReason // 直接传递 finish reason
                                            )
                                        )
                                        // 如果需要，添加其他顶层的 OpenAiStreamChunk 字段 (id, created, model)
                                    )
                                    // --- 结束映射 ---

                                    // 将映射后的块发送给 Flow 收集器
                                    // 使用 trySend 处理背压并检查下游是否仍在监听
                                    val sendResult = trySend(openAiChunk)
                                    if (!sendResult.isSuccess) {
                                        Log.w(
                                            "ApiClient",
                                            "下游收集器已关闭或失败。停止读取流。原因: ${sendResult}"
                                        )
                                        channel.cancel(CoroutineCancellationException("下游已关闭"))
                                        return@execute // 停止处理
                                    }
                                    // else {
                                    //    Log.v("ApiClient", "已发送块: Type=${backendChunk.type}, FinishReason=${backendChunk.finishReason}")
                                    // }

                                } catch (e: SerializationException) {
                                    // 处理特定块的 JSON 解析错误
                                    Log.e(
                                        "ApiClient",
                                        "错误 - 解析流 JSON 块失败。原始 JSON: '$jsonData'。错误: ${e.message}"
                                    )
                                    // 使用包含问题数据的错误关闭 Flow
                                    close(
                                        IOException(
                                            "解析流 JSON 块失败: '${
                                                jsonData.take(
                                                    100
                                                )
                                            }...'. 错误: ${e.message}", e
                                        )
                                    )
                                    return@execute // 停止处理
                                } catch (e: Exception) {
                                    // 捕获块处理期间的意外错误
                                    Log.e(
                                        "ApiClient",
                                        "错误 - 处理块 '$jsonData' 时发生意外错误。错误: ${e.message}",
                                        e
                                    )
                                    // e.printStackTrace() // 调试时可以打印堆栈跟踪
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
                    } // 结束 while 循环 (读取行)
                    Log.d(
                        "ApiClient",
                        "完成读取流通道 (isActive=$isActive, isClosedForRead=${channel.isClosedForRead})."
                    )

                } catch (e: IOException) {
                    // 处理流读取期间的网络错误
                    Log.e("ApiClient", "错误 - 读取流期间发生网络错误: ${e.message}")
                    if (!isClosedForSend) { // 检查 Flow 是否已关闭
                        close(
                            IOException(
                                "网络错误: 流读取中断。${e.message}",
                                e
                            )
                        )
                    }
                } catch (e: CoroutineCancellationException) {
                    // 捕获取消信号 (可能来自下游收集器或超时)
                    Log.i("ApiClient", "流读取已取消。原因: ${e.message}")
                    // 重新抛出取消异常，以便 channelFlow 正确处理
                    throw e
                } catch (e: Exception) {
                    // 捕获读取循环期间的任何其他意外错误
                    Log.e("ApiClient", "错误 - 读取流期间发生意外错误: ${e.message}", e)
                    // e.printStackTrace()
                    if (!isClosedForSend) {
                        close(IOException("意外的流读取错误: ${e.message}", e))
                    }
                } finally {
                    Log.d("ApiClient", "退出响应执行块。")
                    // Ktor 的 execute 块应处理响应的关闭。
                }
            } // 结束 execute 块
        } catch (e: CoroutineCancellationException) {
            // 捕获请求设置/连接阶段的取消
            Log.i("ApiClient", "请求设置已取消。原因: ${e.message}")
            // 重新抛出取消异常，以便调用者正确处理
            throw e
        } catch (e: IOException) {
            // 捕获请求设置/连接期间的网络错误 (例如，DNS 解析、连接被拒)
            Log.e("ApiClient", "错误 - 请求设置/执行期间发生网络错误: ${e.message}")
            if (!isClosedForSend) {
                close(IOException("网络设置/执行错误: ${e.message}", e))
            }
        } catch (e: Exception) {
            // 捕获请求设置/执行期间的其他意外错误
            Log.e("ApiClient", "错误 - 请求设置/执行期间发生意外错误: ${e.message}", e)
            // e.printStackTrace()
            if (!isClosedForSend) {
                // 如果可用，包含响应状态信息
                val statusInfo = response?.status?.let { " (状态: ${it.value})" } ?: ""
                close(IOException("意外的 API 客户端错误$statusInfo: ${e.message}", e))
            }
        } finally {
            Log.d("ApiClient", "退出 channelFlow 块。")
            // channelFlow 确保在块退出或发生错误时关闭通道。
        }
    }.flowOn(Dispatchers.IO) // 确保网络操作在 IO 调度器上运行
}