package com.example.everytalk.data.network

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.everytalk.BuildConfig
import com.example.everytalk.config.BackendConfig
import com.example.everytalk.data.DataClass.ChatRequest
import com.example.everytalk.data.DataClass.GithubRelease
import com.example.everytalk.data.local.SharedPreferencesDataSource
import com.example.everytalk.models.SelectedMediaItem
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.cache.storage.FileStorage
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.ktor.utils.io.streams.asInput
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.coroutines.CancellationException as CoroutineCancellationException

@Serializable
data class ModelInfo(val id: String)

@Serializable
data class ModelsResponse(val data: List<ModelInfo>)

object ApiClient {
    private var sharedPreferencesDataSource: SharedPreferencesDataSource? = null
    private const val GITHUB_API_BASE_URL = "https://api.github.com/"
    
    /**
     * Parse backend stream event JSON format and convert to AppStreamEvent
     */
    private fun parseBackendStreamEvent(jsonChunk: String): AppStreamEvent? {
        try {
            // Parse as JsonObject to avoid AnySerializer deserialization issues
            val jsonObject = Json.parseToJsonElement(jsonChunk).jsonObject
            
            val type = jsonObject["type"]?.jsonPrimitive?.content
            
            return when (type) {
                "content" -> {
                    val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
                    AppStreamEvent.Content(text)
                }
                "text" -> {
                    val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
                    AppStreamEvent.Text(text)
                }
                "reasoning" -> {
                    val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
                    AppStreamEvent.Reasoning(text)
                }
                "stream_end" -> {
                    val messageId = jsonObject["messageId"]?.jsonPrimitive?.content ?: ""
                    AppStreamEvent.StreamEnd(messageId)
                }
                "web_search_status" -> {
                    val stage = jsonObject["stage"]?.jsonPrimitive?.content ?: ""
                    AppStreamEvent.WebSearchStatus(stage)
                }
                "web_search_results" -> {
                    val results = try {
                        val resultsList = jsonObject["results"]?.jsonArray ?: JsonArray(emptyList())
                        resultsList.mapIndexed { index, resultElement ->
                            try {
                                val resultObject = resultElement.jsonObject
                                com.example.everytalk.data.DataClass.WebSearchResult(
                                    index = index,
                                    title = resultObject["title"]?.jsonPrimitive?.content ?: "",
                                    snippet = resultObject["snippet"]?.jsonPrimitive?.content ?: "",
                                    href = resultObject["href"]?.jsonPrimitive?.content ?: ""
                                )
                            } catch (e: Exception) {
                                null
                            }
                        }.filterNotNull()
                    } catch (e: Exception) {
                        emptyList()
                    }
                    AppStreamEvent.WebSearchResults(results)
                }
                "tool_call" -> {
                    val id = jsonObject["id"]?.jsonPrimitive?.content ?: ""
                    val name = jsonObject["name"]?.jsonPrimitive?.content ?: ""
                    val argumentsObj = try {
                        jsonObject["argumentsObj"]?.jsonObject ?: buildJsonObject { }
                    } catch (e: Exception) {
                        buildJsonObject { }
                    }
                    val isReasoningStep = jsonObject["isReasoningStep"]?.jsonPrimitive?.booleanOrNull
                    AppStreamEvent.ToolCall(id, name, argumentsObj, isReasoningStep)
                }
                "error" -> {
                    val message = jsonObject["message"]?.jsonPrimitive?.content ?: ""
                    val upstreamStatus = jsonObject["upstreamStatus"]?.jsonPrimitive?.intOrNull
                    AppStreamEvent.Error(message, upstreamStatus)
                }
                "finish" -> {
                    val reason = jsonObject["reason"]?.jsonPrimitive?.content ?: ""
                    AppStreamEvent.Finish(reason)
                }
                else -> {
                    android.util.Log.w("ApiClient", "Unknown stream event type: $type")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "Failed to parse backend stream event: $jsonChunk", e)
            return null
        }
    }

    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            serializersModule = SerializersModule {
                contextual(Any::class, AnySerializer)
                polymorphic(com.example.everytalk.data.DataClass.AbstractApiMessage::class) {
                    subclass(com.example.everytalk.data.DataClass.SimpleTextApiMessage::class)
                    subclass(com.example.everytalk.data.DataClass.PartsApiMessage::class)
                }
                polymorphic(AppStreamEvent::class) {
                    subclass(AppStreamEvent.Text::class)
                    subclass(AppStreamEvent.Content::class)
                    subclass(AppStreamEvent.Reasoning::class)
                    subclass(AppStreamEvent.StreamEnd::class)
                    subclass(AppStreamEvent.WebSearchStatus::class)
                    subclass(AppStreamEvent.WebSearchResults::class)
                    subclass(AppStreamEvent.ToolCall::class)
                    subclass(AppStreamEvent.Error::class)
                    subclass(AppStreamEvent.Finish::class)
                }
            }
        }
    }

    private lateinit var client: HttpClient
    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            sharedPreferencesDataSource = SharedPreferencesDataSource(context)
            // 根据构建类型自动选择配置
            val cacheFile = File(context.cacheDir, "ktor_http_cache")
            client = HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
                engine {
                    // 允许所有主机名验证（用于本地开发）
                    config {
                        connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                        writeTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
                install(ContentNegotiation) {
                    json(jsonParser)
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 300_000
                    connectTimeoutMillis = 60_000  // VPN环境下增加连接超时到120秒
                    socketTimeoutMillis = 300_000
                }
                install(HttpCache) {
                    publicStorage(FileStorage(cacheFile))
                }
                // 添加更详细的日志记录
                install(io.ktor.client.plugins.logging.Logging) {
                    logger = object : io.ktor.client.plugins.logging.Logger {
                        override fun log(message: String) {
                            android.util.Log.d("ApiClient-HTTP", message)
                        }
                    }
                    level = io.ktor.client.plugins.logging.LogLevel.INFO
                }
            }
            isInitialized = true
        }
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
                android.util.Log.e("ApiClient", "Error getting file name from URI: $uri", e)
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

        try {
            val chatRequestJsonString =
                jsonParser.encodeToString(ChatRequest.serializer(), chatRequest)

            val multiPartData = MultiPartFormDataContent(
                formData {
                    append(
                        key = "chat_request_json",
                        value = chatRequestJsonString,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        }
                    )

                    attachmentsToUpload.forEachIndexed { index, mediaItem ->
                        val fileUri: Uri?
                        val originalFileNameFromMediaItem: String
                        val mimeTypeFromMediaItem: String?

                        when (mediaItem) {
                            is SelectedMediaItem.ImageFromUri -> {
                                fileUri = mediaItem.uri
                                originalFileNameFromMediaItem =
                                    getFileNameFromUri(applicationContext, mediaItem.uri)
                                mimeTypeFromMediaItem =
                                    applicationContext.contentResolver.getType(mediaItem.uri)
                            }

                            is SelectedMediaItem.GenericFile -> {
                                fileUri = mediaItem.uri
                                originalFileNameFromMediaItem =
                                    mediaItem.displayName ?: getFileNameFromUri(
                                        applicationContext,
                                        mediaItem.uri
                                    )
                                mimeTypeFromMediaItem = mediaItem.mimeType
                                    ?: applicationContext.contentResolver.getType(mediaItem.uri)
                            }

                            is SelectedMediaItem.ImageFromBitmap -> {
                                fileUri = null
                                originalFileNameFromMediaItem =
                                    "bitmap_image_$index.${if (mediaItem.bitmap.hasAlpha()) "png" else "jpeg"}"
                                mimeTypeFromMediaItem =
                                    if (mediaItem.bitmap.hasAlpha()) ContentType.Image.PNG.toString() else ContentType.Image.JPEG.toString()
                            }
                            is SelectedMediaItem.Audio -> {
                                fileUri = null
                                originalFileNameFromMediaItem = "audio_record.3gp"
                                mimeTypeFromMediaItem = mediaItem.mimeType
                                append(
                                    key = "inline_data_content",
                                    value = mediaItem.data,
                                    headers = Headers.build {
                                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                                    }
                                )
                            }
                        }

                        if (fileUri != null) {
                            val finalMimeType = mimeTypeFromMediaItem
                                ?: ContentType.Application.OctetStream.toString()
                            try {
                                applicationContext.contentResolver.openInputStream(fileUri)
                                    ?.use { inputStream ->
                                        val bytes = inputStream.readBytes()
                                        appendInput(
                                            key = "uploaded_documents",
                                            headers = Headers.build {
                                                append(HttpHeaders.ContentDisposition, "filename=\"$originalFileNameFromMediaItem\"")
                                                append(HttpHeaders.ContentType, finalMimeType)
                                            }
                                        ) { bytes.inputStream().asInput() }
                                    }
                            } catch (e: Exception) {
                                android.util.Log.e("ApiClient", "Error reading file for upload: $fileUri", e)
                            }
                        }
                    }
                }
            )


            android.util.Log.d("ApiClient", "尝试连接到: $backendProxyUrl")
            
            android.util.Log.d("ApiClient", "开始执行POST请求到: $backendProxyUrl")
            
            client.preparePost(backendProxyUrl) {
                accept(ContentType.Text.EventStream)
                timeout {
                    requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                    connectTimeoutMillis = 60_000  // 增加连接超时到60秒
                    socketTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                }
                setBody(multiPartData)

            }.execute { receivedResponse ->
                android.util.Log.d("ApiClient", "收到响应，状态码: ${receivedResponse.status.value}")
                response = receivedResponse

                if (response?.status?.isSuccess() != true) {
                    val errorBody = try {
                        response?.bodyAsText() ?: "(无错误响应体)"
                    } catch (e: Exception) {
                        "(读取错误响应体失败: ${e.message})"
                    }
                    android.util.Log.e("ApiClient", "HTTP错误响应 ($backendProxyUrl): ${response?.status?.value} - $errorBody")
                    throw IOException("代理错误 ($backendProxyUrl): ${response?.status?.value} - $errorBody")
                }
                
                android.util.Log.d("ApiClient", "HTTP响应成功，开始读取流数据")

                val channel: ByteReadChannel = response?.bodyAsChannel() ?: run {
                    throw IOException("获取来自 $backendProxyUrl 的响应体通道失败。")
                }

                val lineBuffer = StringBuilder()
                var eventCount = 0
                var lineCount = 0
                try {
                    android.util.Log.d("ApiClient", "开始读取流数据通道")
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line()
                        lineCount++
                        
                        if (lineCount <= 10) {
                            android.util.Log.d("ApiClient", "读取行 #$lineCount: '${line ?: "NULL"}'")
                        } else if (lineCount % 50 == 0) {
                            android.util.Log.d("ApiClient", "已读取 $lineCount 行，当前行: '${line?.take(50) ?: "NULL"}'")
                        }
                        
                        if (line.isNullOrEmpty()) {
                            val chunk = lineBuffer.toString().trim()
                            if (chunk.isNotEmpty()) {
                                android.util.Log.d("ApiClient", "处理数据块 (长度=${chunk.length}): '${chunk.take(100)}${if(chunk.length > 100) "..." else ""}'")
                                
                                if (chunk.equals("[DONE]", ignoreCase = true)) {
                                    android.util.Log.d("ApiClient", "收到[DONE]标记，结束流处理")
                                    channel.cancel(CoroutineCancellationException("[DONE] marker received"))
                                    break
                                }
                                try {
                                    // Parse the backend JSON format and convert to AppStreamEvent
                                    val appEvent = parseBackendStreamEvent(chunk)
                                    if (appEvent != null) {
                                        eventCount++
                                        if (eventCount <= 5) {
                                            android.util.Log.d("ApiClient", "解析到流事件 #$eventCount: ${appEvent.javaClass.simpleName}")
                                        } else if (eventCount % 10 == 0) {
                                            android.util.Log.d("ApiClient", "已处理 $eventCount 个流事件")
                                        }
                                        val sendResult = trySend(appEvent)
                                        if (!sendResult.isSuccess) {
                                            android.util.Log.w("ApiClient", "流事件发送失败: $sendResult")
                                            if (!isClosedForSend && !channel.isClosedForRead) {
                                                channel.cancel(CoroutineCancellationException("Downstream channel closed: $sendResult"))
                                            }
                                            return@execute
                                        }
                                    } else {
                                        android.util.Log.w("ApiClient", "无法解析的流数据块: '$chunk'")
                                    }
                                } catch (e: SerializationException) {
                                    android.util.Log.e("ApiClientStream", "Serialization failed for chunk: '$chunk'", e)
                                } catch (e: Exception) {
                                    android.util.Log.e("ApiClientStream", "Exception during event processing for chunk: '$chunk'", e)
                                }
                            } else {
                                android.util.Log.d("ApiClient", "遇到空行，但lineBuffer为空")
                            }
                            lineBuffer.clear()
                        } else if (line.startsWith("data:")) {
                            val dataContent = line.substring(5).trim()
                            android.util.Log.d("ApiClient", "SSE data行: '$dataContent'")
                            lineBuffer.append(dataContent)
                        } else {
                            // 不是SSE格式，可能是直接的JSON流
                            android.util.Log.d("ApiClient", "非SSE格式行，直接处理: '$line'")
                            if (line.trim().isNotEmpty()) {
                                try {
                                    val appEvent = parseBackendStreamEvent(line.trim())
                                    if (appEvent != null) {
                                        eventCount++
                                        android.util.Log.d("ApiClient", "非SSE格式解析到事件 #$eventCount: ${appEvent.javaClass.simpleName}")
                                        val sendResult = trySend(appEvent)
                                        if (!sendResult.isSuccess) {
                                            android.util.Log.w("ApiClient", "非SSE事件发送失败: $sendResult")
                                            if (!isClosedForSend && !channel.isClosedForRead) {
                                                channel.cancel(CoroutineCancellationException("Downstream channel closed: $sendResult"))
                                            }
                                            return@execute
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("ApiClient", "非SSE格式解析失败: '$line'", e)
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    android.util.Log.e("ApiClient", "流读取IO异常 ($backendProxyUrl)", e)
                    if (!isClosedForSend) throw e
                } catch (e: CoroutineCancellationException) {
                    android.util.Log.d("ApiClient", "流读取被取消 ($backendProxyUrl): ${e.message}")
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("ApiClient", "流读取意外异常 ($backendProxyUrl)", e)
                    if (!isClosedForSend) throw IOException(
                        "意外流错误 ($backendProxyUrl): ${e.message}",
                        e
                    )
                } finally {
                    android.util.Log.d("ApiClient", "流处理结束，共读取 $lineCount 行，处理 $eventCount 个事件")
                    if (lineCount == 0) {
                        android.util.Log.w("ApiClient", "警告：没有读取到任何数据行！")
                    }
                    val chunk = lineBuffer.toString().trim()
                    if (chunk.isNotEmpty()) {
                        try {
                            val appEvent = parseBackendStreamEvent(chunk)
                            if (appEvent != null) {
                                trySend(appEvent)
                            }
                        } catch (e: SerializationException) {
                            android.util.Log.e("ApiClientStream", "Serialization failed for final chunk: '$chunk'", e)
                        }
                    }
                }
            }
        } catch (e: CoroutineCancellationException) {
            android.util.Log.d("ApiClient", "Connection cancelled for $backendProxyUrl: ${e.message}")
            throw e
        } catch (e: HttpRequestTimeoutException) {
            android.util.Log.e("ApiClient", "Request timeout for $backendProxyUrl", e)
            throw IOException("请求超时 ($backendProxyUrl): ${e.message}", e)
        } catch (e: ResponseException) {
            val errorBody = try {
                e.response.bodyAsText()
            } catch (ex: Exception) {
                "(无法读取错误体)"
            }
            val statusCode = e.response.status.value
            val statusDescription = e.response.status.description
            android.util.Log.e("ApiClient", "Response error for $backendProxyUrl: $statusCode $statusDescription", e)
            
            // 为特定HTTP状态码提供更友好的错误信息
            val friendlyErrorMessage = when (statusCode) {
                429 -> "请求频率过高 (429 Too Many Requests)，请稍后重试。服务器暂时限制了请求频率。"
                401 -> "身份验证失败 (401 Unauthorized)，请检查API密钥配置。"
                403 -> "访问被拒绝 (403 Forbidden)，请检查权限设置。"
                404 -> "服务端点未找到 (404 Not Found)，请检查服务器配置。"
                500 -> "服务器内部错误 (500 Internal Server Error)，请稍后重试。"
                502 -> "网关错误 (502 Bad Gateway)，服务器可能暂时不可用。"
                503 -> "服务不可用 (503 Service Unavailable)，服务器正在维护中。"
                else -> "服务器错误 $statusCode ($statusDescription): $errorBody"
            }
            
            throw IOException(friendlyErrorMessage, e)
        } catch (e: IOException) {
            android.util.Log.e("ApiClient", "IO error for $backendProxyUrl", e)
            throw e
        } catch (e: Exception) {
            val statusInfo = response?.status?.let { " (状态: ${it.value})" }
                ?: ""
            android.util.Log.e("ApiClient", "Unknown error for $backendProxyUrl$statusInfo", e)
            throw IOException(
                "未知客户端错误 ($backendProxyUrl)$statusInfo: ${e.message}",
                e
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun streamChatResponse(
        request: ChatRequest,
        attachments: List<SelectedMediaItem>,
        applicationContext: Context
    ): Flow<AppStreamEvent> = channelFlow {
        val backendProxyUrls = BackendConfig.backendUrls
        if (backendProxyUrls.isEmpty()) {
            throw IOException("未配置后端服务器URL。请检查项目根目录的 'local.properties' 文件是否已根据 'local.properties.example' 模板正确配置。")
        }

        // 检查是否启用并发请求
        val isConcurrentEnabled = BackendConfig.isConcurrentRequestEnabled
        val raceTimeoutMs = BackendConfig.RACE_TIMEOUT_MS
        
        if (!isConcurrentEnabled || backendProxyUrls.size == 1) {
            // 顺序请求模式：逐个尝试URL直到成功
            var lastException: Exception? = null
            for (url in backendProxyUrls) {
                try {
                    android.util.Log.d("ApiClient", "尝试连接到: $url")
                    streamChatResponseInternal(url, request, attachments, applicationContext)
                        .collect { event -> send(event) }
                    return@channelFlow // 成功则退出
                } catch (e: Exception) {
                    android.util.Log.w("ApiClient", "URL $url 连接失败: ${e.message}")
                    lastException = e
                    // 继续尝试下一个URL
                }
            }
            // 所有URL都失败了
            throw lastException ?: IOException("所有后端服务器都无法连接")
        } else {
            // 并发竞速模式：同时请求所有URL，谁先响应就用谁
            android.util.Log.d("ApiClient", "启动并发请求模式，竞速超时: ${raceTimeoutMs}ms")
            
            val errors = mutableListOf<Throwable>()
            val jobs = mutableListOf<Job>()
            val winnerFound = AtomicBoolean(false)
            val firstResponseReceived = AtomicBoolean(false)

            supervisorScope {
                // 为每个URL启动一个协程
                for (url in backendProxyUrls) {
                    val job = launch {
                        try {
                            android.util.Log.d("ApiClient", "并发请求启动: $url")
                            streamChatResponseInternal(url, request, attachments, applicationContext)
                                .collect { event ->
                                    // 第一个响应的获胜
                                    if (firstResponseReceived.compareAndSet(false, true)) {
                                        android.util.Log.d("ApiClient", "获胜者: $url")
                                        winnerFound.set(true)
                                        // 取消其他所有请求
                                        jobs.forEach { otherJob ->
                                            if (otherJob != coroutineContext[Job]) {
                                                otherJob.cancel(CoroutineCancellationException("另一个服务器响应更快"))
                                            }
                                        }
                                        // 清空错误列表，因为已经有成功的响应
                                        synchronized(errors) { errors.clear() }
                                    }
                                    
                                    // 只有获胜者才能发送事件
                                    if (winnerFound.get()) {
                                        send(event)
                                    }
                                }
                        } catch (e: Exception) {
                            if (e !is CoroutineCancellationException) {
                                android.util.Log.w("ApiClient", "URL $url 请求失败: ${e.javaClass.simpleName}: ${e.message}")
                                synchronized(errors) {
                                    if (!winnerFound.get()) {
                                        errors.add(e)
                                    }
                                }
                            } else {
                                android.util.Log.d("ApiClient", "URL $url 请求被取消: ${e.message}")
                                // 检查取消的原因
                                val cause = e.cause
                                if (cause != null && cause !is CoroutineCancellationException) {
                                    synchronized(errors) {
                                        if (!winnerFound.get()) {
                                            errors.add(cause)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    jobs.add(job)
                }
                
                // 等待竞速超时或所有任务完成
                try {
                    withTimeout(raceTimeoutMs) {
                        jobs.joinAll()
                    }
                } catch (e: TimeoutCancellationException) {
                    android.util.Log.w("ApiClient", "竞速超时 (${raceTimeoutMs}ms)，取消所有请求")
                    jobs.forEach { it.cancel() }
                }
            }

            // 如果没有获胜者，抛出错误
            if (!winnerFound.get()) {
                synchronized(errors) {
                    android.util.Log.e("ApiClient", "所有并发请求都失败了，错误数量: ${errors.size}")
                    errors.forEachIndexed { index, error ->
                        android.util.Log.e("ApiClient", "错误 $index: ${error.javaClass.simpleName}: ${error.message}")
                    }
                    
                    if (errors.isNotEmpty()) {
                        // 抛出最后一个错误
                        val lastError = errors.last()
                        throw lastError
                    } else {
                        throw IOException("无法连接到任何后端服务器 (${backendProxyUrls.size} 个URL都失败了)")
                    }
                }
            }
        }
    }.buffer(Channel.BUFFERED).flowOn(Dispatchers.IO)


    private fun getUpdateUrls(): List<String> {
        return listOf(
            GITHUB_API_BASE_URL + "repos/roseforljh/KunTalkwithAi/releases/latest",
            "https://kuntalk-update-checker.onrender.com/latest",
            "https://kuntalk-backup-updater.vercel.app/latest",
            // 使用不同的GitHub镜像站点
            "https://hub.fastgit.xyz/api/repos/roseforljh/KunTalkwithAi/releases/latest",
            "https://github.com.cnpmjs.org/api/repos/roseforljh/KunTalkwithAi/releases/latest"
        )
    }

    suspend fun getLatestRelease(): GithubRelease {
        if (!isInitialized) {
            throw IllegalStateException("ApiClient not initialized. Call initialize() first.")
        }

        val urls = getUpdateUrls()
        var lastException: Exception? = null
        val maxRetries = 2  // VPN环境下增加重试次数

        for (url in urls) {
            repeat(maxRetries + 1) { attempt ->
                try {
                    android.util.Log.d("ApiClient", "尝试获取更新信息 - URL: $url, 尝试次数: ${attempt + 1}")
                    
                    val response = client.get {
                        url(url)
                        header(HttpHeaders.Accept, "application/vnd.github.v3+json")
                        header(HttpHeaders.CacheControl, "no-cache")
                        header(HttpHeaders.Pragma, "no-cache")
                        header(HttpHeaders.UserAgent, "KunTalkAI/1.3.7")
                        
                        // VPN环境下的特殊超时配置
                        timeout {
                            requestTimeoutMillis = 60_000
                            connectTimeoutMillis = 30_000
                            socketTimeoutMillis = 60_000
                        }
                    }.body<GithubRelease>()
                    
                    android.util.Log.d("ApiClient", "成功获取更新信息从: $url")
                    return response
                    
                } catch (e: Exception) {
                    lastException = e
                    val isLastAttempt = attempt == maxRetries
                    val isLastUrl = url == urls.last()
                    
                    android.util.Log.w("ApiClient",
                        "获取更新失败 - URL: $url, 尝试: ${attempt + 1}/$maxRetries, 错误: ${e.message}", e)
                    
                    if (!isLastAttempt && !isLastUrl) {
                        // 在VPN环境下，在重试前增加延迟
                        kotlinx.coroutines.delay(1000L * (attempt + 1))
                        android.util.Log.d("ApiClient", "等待 ${1000L * (attempt + 1)}ms 后重试...")
                    }
                    
                    if (isLastAttempt) {
                        return@repeat  // 这个URL的所有重试都失败了，尝试下一个URL
                    }
                }
            }
        }

        throw IOException("从所有可用源检查更新失败。可能的原因：网络连接问题、VPN干扰、或服务器不可达。", lastException)
    }

    suspend fun getModels(apiUrl: String, apiKey: String): List<String> {
        if (!isInitialized) {
            throw IllegalStateException("ApiClient not initialized. Call initialize() first.")
        }

        val url = apiUrl.removeSuffix("/") + "/v1/models"

        return try {
            val response = client.get {
                url(url)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.UserAgent, "KunTalkwithAi/1.0")
            }

            val responseBody = response.bodyAsText()

            // 尝试解析第一种格式: {"data": [...]}
            try {
                val modelsResponse = jsonParser.decodeFromString<ModelsResponse>(responseBody)
                return modelsResponse.data.map { it.id }
            } catch (e: SerializationException) {
                // 如果第一种格式解析失败，尝试第二种格式: [...]
                try {
                    val modelsList = jsonParser.decodeFromString<List<ModelInfo>>(responseBody)
                    return modelsList.map { it.id }
                } catch (e2: SerializationException) {
                    throw IOException("无法解析模型列表的响应。请检查API端点返回的数据格式是否正确。", e2)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "从 $url 获取模型列表失败", e)
            throw IOException("从 $url 获取模型列表失败: ${e.message}", e)
        }
    }
}