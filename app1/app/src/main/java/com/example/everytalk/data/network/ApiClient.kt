package com.example.everytalk.data.network

import android.content.ContentResolver // 用于 Uri 处理
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns // 用于从 Uri 获取文件名
import android.util.Log
import com.example.everytalk.data.DataClass.ChatRequest
import com.example.everytalk.data.DataClass.AppStreamEvent
import com.example.everytalk.model.SelectedMediaItem // 确保导入你的 SelectedMediaItem
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
// import io.ktor.http.content.* // PartData 通常是从 forms 中来
import io.ktor.serialization.kotlinx.json.*
// import io.ktor.util.cio.* // For writeTo, Ktor 2.x 中可能不再直接需要
import io.ktor.utils.io.* // ByteReadChannel, etc.
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.InputStream
// 使用 Ktor 提供的标准超时异常
import io.ktor.client.plugins.HttpRequestTimeoutException
// ConnectTimeoutException 可能也来自 plugins，或者特定于引擎
// import io.ktor.client.network.sockets.ConnectTimeoutException // 如果还报错，可以尝试移除或查找正确包名
import kotlinx.coroutines.CancellationException as CoroutineCancellationException
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.builtins.serializer

object ApiClient {

    // ... (appSerializersModule, jsonParser, client, backendProxyUrls, preWarm, getFileNameFromUri 保持和你提供的一致) ...
    private val appSerializersModule = SerializersModule {
        contextual(String::class) { String.serializer() }
        contextual(Boolean::class) { Boolean.serializer() }
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
            serializersModule = appSerializersModule
        }
    }

    private val client: HttpClient by lazy {
        Log.d("ApiClient", "创建 Ktor HttpClient 实例...")
        HttpClient(Android) {
            install(ContentNegotiation) {
                json(jsonParser)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 300_000 // 5 minutes for the entire request
                connectTimeoutMillis = 20_000  // 20 seconds to connect
                socketTimeoutMillis = 300_000  // 5 minutes for socket operations (after connection)
            }
            // expectSuccess = false // 如果你想手动检查 response.status.isSuccess()
        }
    }

    private val backendProxyUrls = listOf(
        "https://uoseegiydwgx.us-west-1.clawcloudrun.com/chat",//claw could run
        "https://kunze999-backendai.hf.space/chat",//hugging face
        "https://backdaitalk-production.up.railway.app/chat",//railway
        "http://192.168.0.1:7860/chat" // 您的本地地址，请确保可访问
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

    private fun getFileNameFromUri(context: Context, uri: Uri): String {
        var fileName: String? = null
        if (ContentResolver.SCHEME_CONTENT == uri.scheme) {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val displayNameIndex =
                            cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                        fileName = cursor.getString(displayNameIndex)
                    }
                }
            } catch (e: Exception) {
                Log.w("ApiClient", "Error getting file name from content URI: $uri", e)
            }
        }
        return fileName ?: uri.lastPathSegment ?: "unknown_file_${System.currentTimeMillis()}"
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    private fun streamChatResponseInternal(
        backendProxyUrl: String,
        chatRequest: ChatRequest,
        attachmentsToUpload: List<SelectedMediaItem>,
        applicationContext: Context
    ): Flow<AppStreamEvent> = channelFlow {
        var response: HttpResponse? = null
        val logTag = "ApiClientStream"

        try {
            Log.d(logTag, "准备向 $backendProxyUrl 发起请求. API Key: ${chatRequest.apiKey.takeLast(4)}, Attachments: ${attachmentsToUpload.size}")

            val chatRequestJsonString = jsonParser.encodeToString(ChatRequest.serializer(), chatRequest)
            Log.d(logTag, "Serialized chat_request_json (length ${chatRequestJsonString.length}): ${chatRequestJsonString.take(500)}...")

            // --- 修改点：始终构建 multipart/form-data ---
            val multiPartData = MultiPartFormDataContent(
                formData {
                    // 1. 始终添加 chat_request_json 作为表单项
                    append(
                        key = "chat_request_json",
                        value = chatRequestJsonString,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        }
                    )
                    Log.i(logTag, "已添加 'chat_request_json' 到表单数据。")

                    // 2. 如果有附件，添加文件部分
                    if (attachmentsToUpload.isNotEmpty()) {
                        Log.d(logTag, "正在处理 ${attachmentsToUpload.size} 个附件。")
                        attachmentsToUpload.forEachIndexed { index, mediaItem ->
                            val fileUri: Uri?
                            val originalFileNameFromMediaItem: String
                            val mimeTypeFromMediaItem: String?

                            when (mediaItem) {
                                is SelectedMediaItem.ImageFromUri -> {
                                    fileUri = mediaItem.uri
                                    originalFileNameFromMediaItem = getFileNameFromUri(applicationContext, mediaItem.uri)
                                    mimeTypeFromMediaItem = applicationContext.contentResolver.getType(mediaItem.uri)
                                }
                                is SelectedMediaItem.GenericFile -> {
                                    fileUri = mediaItem.uri
                                    originalFileNameFromMediaItem = mediaItem.displayName ?: getFileNameFromUri(applicationContext, mediaItem.uri)
                                    mimeTypeFromMediaItem = mediaItem.mimeType ?: applicationContext.contentResolver.getType(mediaItem.uri)
                                }
                                is SelectedMediaItem.ImageFromBitmap -> {
                                    Log.w(logTag, "ImageFromBitmap 在索引 $index。理想情况是预处理为Uri。")
                                    fileUri = null
                                    originalFileNameFromMediaItem = "bitmap_image_$index.${if(mediaItem.bitmap.hasAlpha()) "png" else "jpeg"}"
                                    mimeTypeFromMediaItem = if(mediaItem.bitmap.hasAlpha()) ContentType.Image.PNG.toString() else ContentType.Image.JPEG.toString()
                                    // 如果要直接上传Bitmap的bytes:
                                    // val bos = java.io.ByteArrayOutputStream()
                                    // val format = if(mediaItem.bitmap.hasAlpha()) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG
                                    // mediaItem.bitmap.compress(format, 80, bos)
                                    // val bitmapBytes = bos.toByteArray()
                                    // append("uploaded_documents", bitmapBytes, Headers.build {
                                    //    append(HttpHeaders.ContentDisposition, "filename=\"$originalFileNameFromMediaItem\"")
                                    //    append(HttpHeaders.ContentType, mimeTypeFromMediaItem)
                                    // })
                                    // continue // 如果直接上传了 bitmapBytes，则跳过 fileUri 逻辑
                                }
                            }

                            if (fileUri != null) {
                                val finalMimeType = mimeTypeFromMediaItem ?: ContentType.Application.OctetStream.toString()
                                Log.d(logTag, "准备文件部分 $index: '$originalFileNameFromMediaItem', MIME: '$finalMimeType', URI: $fileUri")
                                try {
                                    applicationContext.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                                        val fileBytes = inputStream.readBytes()
                                        append(
                                            key = "uploaded_documents",
                                            value = fileBytes,
                                            headers = Headers.build {
                                                append(HttpHeaders.ContentDisposition, "filename=\"$originalFileNameFromMediaItem\"")
                                                append(HttpHeaders.ContentType, finalMimeType)
                                            }
                                        )
                                        Log.i(logTag, "已添加文件 '$originalFileNameFromMediaItem' (size: ${fileBytes.size} B) 到表单数据。")
                                    } ?: Log.e(logTag, "打开 URI 的 InputStream 失败: $fileUri")
                                } catch (e: Exception) {
                                    Log.e(logTag, "为 URI $fileUri 准备文件部分时出错: ${e.message}", e)
                                }
                            }
                        }
                    } else {
                        Log.d(logTag, "无附件上传，但仍作为 multipart 发送，包含 chat_request_json。")
                    }
                }
            )
            // --- multipart/form-data 构建结束 ---


            client.preparePost(backendProxyUrl) {
                accept(ContentType.Text.EventStream)
                timeout {
                    requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                    connectTimeoutMillis = 20_000
                    socketTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                }
                // Ktor 会根据 setBody 的内容自动设置 Content-Type 为 multipart/form-data
                setBody(multiPartData)
                Log.d(logTag, "已设置 multipart 表单数据请求体。")

            }.execute { receivedResponse -> // .execute 是 suspend 函数，应在协程中调用
                response = receivedResponse
                Log.i(logTag, "收到来自 $backendProxyUrl 的响应状态: ${response?.status}")

                if (response?.status?.isSuccess() != true) {
                    val errorBody = try { response?.bodyAsText() ?: "(无错误响应体)" } catch (e: Exception) { "(读取错误响应体失败: ${e.message})" }
                    Log.e(logTag, "代理错误 $backendProxyUrl ${response?.status}. 响应体: $errorBody")
                    throw IOException("代理错误 ($backendProxyUrl): ${response?.status?.value} - $errorBody")
                }

                val channel: ByteReadChannel = response?.bodyAsChannel() ?: run {
                    Log.e(logTag, "错误 - 来自 $backendProxyUrl 的响应体通道为 null。")
                    throw IOException("获取来自 $backendProxyUrl 的响应体通道失败。")
                }
                Log.d(logTag, "开始从 $backendProxyUrl 读取流通道...")

                // --- SSE 流解析逻辑 (与你提供的一致，但注意 isActive 和 channel.isClosedForRead) ---
                val buf = ByteArray(1024 * 8)
                val sb = StringBuilder()
                try {
                    // Ktor 的 ByteReadChannel 在流结束时 readAvailable 会返回 -1
                    // 或者在通道被取消/关闭时抛出异常
                    while (isActive && !channel.isClosedForRead) { // isActive 来自 channelFlow
                        val bytesRead = channel.readAvailable(buf, 0, buf.size)
                        if (bytesRead == -1) {
                            Log.d(logTag, "通道读取返回 -1，流结束。"); break
                        }
                        if (bytesRead > 0) {
                            sb.append(String(buf, 0, bytesRead, Charsets.UTF_8))
                            var lineBreakIndex: Int
                            while (sb.indexOf("\n").also { lineBreakIndex = it } != -1) {
                                var line = sb.substring(0, lineBreakIndex).trim()
                                sb.delete(0, lineBreakIndex + 1)

                                if (line.isEmpty()) continue
                                if (line.startsWith("data:")) line = line.substring(5).trim()
                                else if (line.startsWith(":")) { Log.v(logTag, "[SSE注释 $backendProxyUrl] $line"); continue }

                                Log.v(logTag, "[流块收到 $backendProxyUrl] ${line.take(100)}")

                                if (line.isNotEmpty()) {
                                    try {
                                        if (line.equals("[DONE]", ignoreCase = true)) { // 处理可能的 [DONE] 标记
                                            Log.d(logTag, "收到显式的 [DONE] 标记，取消通道并中断。")
                                            channel.cancel(CoroutineCancellationException("[DONE] marker received"))
                                            break // 跳出内部 while
                                        }
                                        val appEvent = jsonParser.decodeFromString(AppStreamEvent.serializer(), line)
                                        val sendResult = trySend(appEvent)
                                        if (!sendResult.isSuccess) {
                                            Log.w(logTag, "下游收集器 ($backendProxyUrl) 已关闭或失败。停止读取流。原因: $sendResult. isClosedForSend: $isClosedForSend")
                                            if (!isClosedForSend && !channel.isClosedForRead) channel.cancel(CoroutineCancellationException("下游 ($backendProxyUrl) 已关闭: $sendResult"))
                                            return@execute
                                        }
                                    } catch (e: SerializationException) { Log.e(logTag, "JSON 解析错误 ($backendProxyUrl): '$line'. ${e.message}", e) }
                                    catch (e: Exception) { Log.e(logTag, "处理块错误 ($backendProxyUrl): '$line'. ${e.message}", e) }
                                }
                            }
                        } else { // bytesRead == 0, channel not closed, yield to prevent busy loop
                            yield() // 避免在没有数据时空转 CPU
                        }
                    }
                    // 处理缓冲区中剩余的最后一行 (如果流在换行符之前结束)
                    if (sb.isNotEmpty() && isActive && !isClosedForSend) {
                        var line = sb.toString().trim()
                        if (line.startsWith("data:")) line = line.substring(5).trim()
                        if (line.isNotEmpty() && !line.equals("[DONE]", ignoreCase = true)) {
                            Log.v(logTag, "[流块收到 $backendProxyUrl EOF] ${line.take(100)}")
                            try {
                                val appEvent = jsonParser.decodeFromString(AppStreamEvent.serializer(), line)
                                trySend(appEvent)
                            } catch (e: Exception) { Log.e(logTag, "EOF JSON 解析错误 ($backendProxyUrl): '$line'. ${e.message}", e)}
                        }
                    }
                    Log.d(logTag, "完成从 $backendProxyUrl 读取流通道。")
                } catch (e: IOException) { Log.e(logTag, "读取流IO错误 ($backendProxyUrl): ${e.message}"); if (!isClosedForSend) throw e } // Rethrow if channel still open
                catch (e: CoroutineCancellationException) { Log.i(logTag, "流读取取消 ($backendProxyUrl): ${e.message}"); throw e }
                catch (e: Exception) { Log.e(logTag, "读取流意外错误 ($backendProxyUrl): ${e.message}", e); if (!isClosedForSend) throw IOException("意外流错误 ($backendProxyUrl): ${e.message}", e) }
                finally { Log.d(logTag, "读取 $backendProxyUrl 流的内部 try-catch-finally 完成。") }
            }
        } catch (e: CoroutineCancellationException) { Log.i(logTag, "请求 $backendProxyUrl 已取消: ${e.message}"); throw e }
        catch (e: HttpRequestTimeoutException) { Log.e(logTag, "请求超时 $backendProxyUrl: ${e.message}"); throw IOException("请求超时 ($backendProxyUrl): ${e.message}", e) }
        // Ktor的ConnectTimeoutException可能需要特定引擎的导入，或者被HttpRequestTimeoutException覆盖
        // catch (e: ConnectTimeoutException) { Log.e(logTag, "连接超时 $backendProxyUrl: ${e.message}"); throw IOException("连接超时 ($backendProxyUrl): ${e.message}", e) }
        catch (e: ResponseException) { val errorBody = try { e.response.bodyAsText() } catch (ex: Exception) { "(无法读取错误体)"}; Log.e(logTag, "HTTP 错误 $backendProxyUrl: ${e.response.status}. Body: $errorBody", e); throw IOException("HTTP 错误 ($backendProxyUrl): ${e.response.status} - $errorBody", e) }
        catch (e: IOException) { Log.e(logTag, "网络IO错误 $backendProxyUrl: ${e.message}", e); throw e }
        catch (e: Exception) { Log.e(logTag, "未知客户端错误 $backendProxyUrl: ${e.message}", e); val statusInfo = response?.status?.let { " (状态: ${it.value})" } ?: ""; throw IOException("未知客户端错误 ($backendProxyUrl)$statusInfo: ${e.message}", e) }
        Log.d(logTag, "streamChatResponseInternal for $backendProxyUrl 完成。")
    }

    // streamChatResponse (public) 函数 (与你提供的一致，调用 internal 的循环逻辑)
    @OptIn(ExperimentalCoroutinesApi::class)
    fun streamChatResponse(
        request: ChatRequest,
        attachments: List<SelectedMediaItem>,
        applicationContext: Context
    ): Flow<AppStreamEvent> =
        flow {
            var lastError: Exception? = null
            val successfulUrls = mutableListOf<String>() // Track successful URLs
            val logTag = "ApiClientPublic"

            if (backendProxyUrls.isEmpty()) {
                Log.e(logTag, "没有配置后端代理服务器 URL。")
                throw IOException("没有后端服务器URL可供尝试。")
            }

            for (url in backendProxyUrls) {
                Log.i(logTag, "尝试连接到后端: $url")
                try {
                    streamChatResponseInternal(url, request, attachments, applicationContext)
                        .collect { appEvent ->
                            if (successfulUrls.isEmpty()) { // Mark success on first received event
                                Log.i(logTag, "成功从 $url 收到第一个数据块。将使用此服务器。")
                                successfulUrls.add(url) // Not strictly necessary anymore if we return@flow
                            }
                            emit(appEvent)
                        }
                    Log.i(logTag, "成功完成从 $url 的流式传输。")
                    return@flow // Exit after successful stream from one URL
                } catch (e: CoroutineCancellationException) {
                    Log.i(logTag, "尝试 $url 时发生协程取消: ${e.message}")
                    throw e // Propagate cancellation immediately
                } catch (e: IOException) { // Catch specific IO/network related exceptions first
                    Log.w(logTag, "连接或流式传输 $url 失败 (IO): ${e.message}")
                    lastError = e
                } catch (e: Exception) { // Catch other Ktor/serialization exceptions
                    Log.e(logTag, "连接或流式传输 $url 失败 (其他异常): ${e.message}", e)
                    lastError = e
                }
            }

            // If loop finishes, all URLs failed
            Log.e(logTag, "所有后端服务器均连接或流式传输失败。")
            if (lastError != null) {
                Log.e(logTag, "最后记录的错误: ${lastError.message}")
                throw lastError // Throw the last recorded error
            } else {
                // This case should ideally not be reached if backendProxyUrls is not empty
                Log.e(logTag, "所有后端服务器均尝试完毕，但没有记录到明确的错误。")
                throw IOException("无法连接到任何可用的后端服务器。")
            }
        }
            .buffer(Channel.BUFFERED)
            .flowOn(Dispatchers.IO)
}