package com.example.app1.data.network

import com.example.app1.data.models.ChatRequest
import com.example.app1.data.models.StreamChunk
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
// 如果需要 Ktor 日志: import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.ktor.utils.io.errors.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable // 确保导入
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import kotlinx.coroutines.CancellationException // 导入 CancellationException
import kotlin.io.println // 或使用 Android Log: import android.util.Log

object ApiClient {

    // 创建 Json 解析器实例，方便复用和配置
    private val jsonParser = Json {
        ignoreUnknownKeys = true // 忽略后端可能发送的多余字段
        isLenient = true         // 允许一些不严格的 JSON 格式
        prettyPrint = false      // 生产环境禁用，减少带宽
        encodeDefaults = true    // 确保所有字段都被编码
    }

    // 配置 Ktor Client 实例
    private val client = HttpClient(Android) { // 你也可以换成 OkHttp 引擎
        // 安装内容协商插件，使用 Kotlinx Serialization
        install(ContentNegotiation) {
            json(jsonParser) // 使用上面配置的 Json 实例
        }
        // 安装超时插件
        install(HttpTimeout) {
            requestTimeoutMillis = 300000 // 总请求超时: 5 分钟
            connectTimeoutMillis = 30000  // 连接超时: 30 秒
            socketTimeoutMillis = 300000  // Socket 读取超时: 5 分钟
        }
        defaultRequest {
            // 可以设置通用的 header 等
        }
    }

    /**
     * 调用后端代理接口，以 Flow<StreamChunk> 的形式流式返回聊天响应。
     * 处理换行符分隔的 JSON 字符串流。
     */
    fun streamChatResponse(request: ChatRequest): Flow<StreamChunk> = channelFlow {
        // 后端代理 URL (对于 Android 模拟器，10.0.2.2 指向宿主机 localhost)
        val backendProxyUrl = "https://5601-54-219-66-202.ngrok-free.app/chat" // 如果后端在别处，请修改

        try {
            println("ApiClient: Preparing POST request to proxy: $backendProxyUrl")
            // 注意：避免在生产日志中记录包含 API Key 的完整请求体

            // 使用 preparePost 和 execute 处理流式响应
            client.preparePost(backendProxyUrl) {
                contentType(ContentType.Application.Json)
                setBody(request) // Ktor 会使用 ContentNegotiation 插件序列化 request 对象
                accept(ContentType.Any)
            }.execute { response ->
                println("ApiClient: Received response status: ${response.status}")

                // 检查后端代理返回的状态码
                if (!response.status.isSuccess()) {
                    // 处理代理服务器本身的错误 (例如 5xx, 404 等)
                    val errorBody = response.bodyAsText()
                    println("ApiClient: Backend proxy error response body: $errorBody")
                    val errorMessage = parseBackendError(response, errorBody)
                    trySend(StreamChunk(type = "error", text = errorMessage)).isSuccess
                    close(ResponseException(response, "Proxy error: ${response.status.value}"))
                    return@execute // 退出 execute 块
                }

                // --- VVVVVV 简化的流处理逻辑 (假设后端发送换行分隔的 JSON) VVVVVV ---
                val channel: ByteReadChannel = response.bodyAsChannel()
                println("ApiClient: Starting simplified stream processing (newline-separated JSON)...")

                while (!channel.isClosedForRead) { // 循环直到流关闭
                    try {
                        val line = channel.readUTF8Line() // 读取一行

                        if (line == null) { // 检查是否到达流末尾
                            println("ApiClient: Stream channel closed by remote (EOF).")
                            break // 正常退出循环
                        }

                        // 只要行非空，就尝试直接解析为 StreamChunk
                        if (line.isNotBlank()) {
                            // println("ApiClient: Received raw line: $line") // 调试
                            try {
                                // 直接解析该行为 JSON
                                val chunk = jsonParser.decodeFromString<StreamChunk>(line)
                                // println("ApiClient: Parsed chunk: type=${chunk.type}") // 调试
                                trySend(chunk).isSuccess // 发送解析后的 chunk
                            } catch (e: SerializationException) {
                                println("ApiClient: JSON Deserialization failed for line: '$line'. Error: ${e.message}")
                                trySend(StreamChunk(type = "error", text = "[前端错误：无法解析数据块]")).isSuccess
                            } catch (e: Exception) {
                                println("ApiClient: Error processing line: '$line'. Error: ${e.message}")
                                trySend(StreamChunk(type = "error", text = "[前端处理错误]")).isSuccess
                            }
                        } else {
                            // 忽略空行 (如果后端可能发送的话)
                            // println("ApiClient: Ignoring blank line.")
                        }

                    } catch (e: IOException) {
                        // 处理读取流时的 IO 错误
                        println("ApiClient: IO error reading stream channel: ${e.message}")
                        if (!isClosedForSend) {
                            trySend(StreamChunk(type = "error", text = "[网络错误：流读取中断]")).isSuccess
                        }
                        break // 退出循环
                    } catch (e: CancellationException) {
                        // 处理协程被取消的情况
                        println("ApiClient: Stream reading coroutine was cancelled.")
                        close(e) // 关闭 Flow 并传递取消异常
                        throw e // 重新抛出以确保外部能捕获
                    } catch (e: Exception) {
                        // 捕获循环内其他意外错误
                        println("ApiClient: Unexpected error in simplified reading loop: ${e::class.simpleName} - ${e.message}")
                        if (!isClosedForSend) {
                            trySend(StreamChunk(type = "error", text = "[前端内部流处理错误]")).isSuccess
                        }
                        close(e) // 关闭 Flow 并附带异常
                        break // 退出循环
                    }
                } // End of while loop

                println("ApiClient: Finished simplified stream reading loop.")
                // --- ^^^^^^ 简化的流处理逻辑 ^^^^^^ ---

            } // End of execute block
        } catch (e: Exception) {
            // 处理请求准备阶段或 execute 块之外的异常
            println("ApiClient: Exception during streaming request setup or execution: ${e::class.simpleName} - ${e.message}")
            e.printStackTrace() // 打印堆栈跟踪用于调试

            // 尝试通过 Flow 发送错误信息
            if (!isClosedForSend) {
                val errorText = formatGenericHttpError(e)
                trySend(StreamChunk(type = "error", text = errorText)).isSuccess
            }
            // 关闭 Flow 并附带异常
            close(e)
        } finally {
            // 这个 finally 块会在 channelFlow 的协程结束时执行
            println("ApiClient: Stream Flow processing finished (channelFlow finally). Closed for send: $isClosedForSend")
        }
    }.flowOn(Dispatchers.IO) // 确保所有网络和流处理操作在 IO 线程上执行

    /**
     * 辅助函数，用于解析后端代理可能返回的错误响应体。
     */
    private fun parseBackendError(response: HttpResponse, errorBody: String): String {
        try {
            // 优先尝试解析自定义的 BackendErrorResponse 结构
            val errorJson = jsonParser.decodeFromString<BackendErrorResponse>(errorBody)
            return errorJson.error_message ?: "代理服务器错误: ${response.status.value} (无详情)"
        } catch (e1: Exception) {
            // 如果解析失败，检查是否是 FastAPI 的验证错误 (422)
            if (response.status == HttpStatusCode.UnprocessableEntity) {
                try {
                    val validationError = jsonParser.decodeFromString<FastApiValidationError>(errorBody)
                    val firstError = validationError.detail.firstOrNull()
                    val errorMsg = firstError?.msg ?: "请求验证失败"
                    val errorLoc = firstError?.loc?.joinToString(".") ?: "未知字段"
                    // 返回更具体的验证错误信息
                    return "请求数据无效: 字段 '${errorLoc}' - ${errorMsg}"
                } catch (e2: Exception) {
                    // 解析验证错误详情也失败了
                    println("ApiClient: Failed to parse FastAPI validation error detail: ${e2.message}")
                    // 回退到通用验证错误消息
                    return "请求数据无效 (解析错误详情失败)"
                }
            } else {
                // 对于其他错误码或无法解析的情况，返回通用错误
                val truncatedBody = errorBody.take(150) // 取前150个字符
                return "代理服务器错误 ${response.status.value}: $truncatedBody${if (errorBody.length > 150) "..." else ""}"
            }
        }
    }

    /**
     * 辅助函数，格式化通用的 HTTP 或网络相关异常信息。
     */
    private fun formatGenericHttpError(e: Exception): String {
        return when (e) {
            is HttpRequestTimeoutException -> "[网络超时]"
            is NoTransformationFoundException -> "[请求序列化错误]"
            is ClientRequestException -> "[客户端请求错误: ${e.response.status.value}]"
            is ServerResponseException -> "[代理服务器错误: ${e.response.status.value}]"
            is IOException -> "[网络连接错误: ${e.message ?: "IO 错误"}]"
            else -> "[网络/流错误: ${e.message ?: e::class.simpleName ?: "未知错误"}]"
        }
    }


    // --- Helper data classes for error parsing ---
    // 将这些类设为 internal 或 private，因为它们只在 ApiClient 内部使用
    @Serializable
    internal data class BackendErrorResponse(val success: Boolean? = null, val error_message: String? = null)

    @Serializable
    internal data class FastApiValidationError(val detail: List<ValidationErrorDetail>)

    @Serializable
    internal data class ValidationErrorDetail(val loc: List<String>, val msg: String, val type: String)
}