package com.example.everytalk.data.network

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.everytalk.BuildConfig
import com.example.everytalk.config.BackendConfig
import com.example.everytalk.data.DataClass.ChatRequest
import com.example.everytalk.data.DataClass.ImageGenerationResponse
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
import kotlinx.serialization.encodeToString
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
                    val outputType = jsonObject["output_type"]?.jsonPrimitive?.content
                    val blockType = jsonObject["block_type"]?.jsonPrimitive?.content
                    AppStreamEvent.Content(text, outputType, blockType)
                }
                "text" -> {
                    val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
                    AppStreamEvent.Text(text)
                }
                "content_final" -> {
                    val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
                    val outputType = jsonObject["output_type"]?.jsonPrimitive?.content
                    val blockType = jsonObject["block_type"]?.jsonPrimitive?.content
                    AppStreamEvent.ContentFinal(text, outputType, blockType)
                }
                "reasoning" -> {
                    val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
                    AppStreamEvent.Reasoning(text)
                }
                "reasoning_finish" -> {
                    val ts = jsonObject["timestamp"]?.jsonPrimitive?.content
                    AppStreamEvent.ReasoningFinish(ts)
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
                "status_update" -> {
                    val stage = jsonObject["stage"]?.jsonPrimitive?.content ?: ""
                    AppStreamEvent.StatusUpdate(stage)
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
                "image_generation" -> {
                    val imageUrl = jsonObject["imageUrl"]?.jsonPrimitive?.content ?: ""
                    AppStreamEvent.ImageGeneration(imageUrl)
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
                    subclass(AppStreamEvent.ContentFinal::class)
                    subclass(AppStreamEvent.Reasoning::class)
                    subclass(AppStreamEvent.ReasoningFinish::class)
                    subclass(AppStreamEvent.StreamEnd::class)
                    subclass(AppStreamEvent.WebSearchStatus::class)
                    subclass(AppStreamEvent.WebSearchResults::class)
                    subclass(AppStreamEvent.StatusUpdate::class)
                    subclass(AppStreamEvent.ToolCall::class)
                    subclass(AppStreamEvent.Error::class)
                    subclass(AppStreamEvent.Finish::class)
                    subclass(AppStreamEvent.ImageGeneration::class)
                }
            }
        }
    }

    private lateinit var client: HttpClient
    private var isInitialized = false

    // 将 localhost/127.0.0.1 识别为本机地址（在真机上通常不可达），用于回退排序
    private fun isLocalHostUrl(raw: String): Boolean {
        return try {
            val host = java.net.URI(raw).host?.lowercase() ?: return false
            host == "127.0.0.1" || host == "localhost"
        } catch (_: Exception) {
            false
        }
    }

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
                    // 更积极的缓存策略
                    publicStorage(FileStorage(cacheFile))
                    privateStorage(FileStorage(File(context.cacheDir, "ktor_private_cache")))
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




    private fun buildFinalUrl(baseAddress: String, defaultPath: String): String {
        val trimmedAddress = baseAddress.trim()
        var finalAddress = when {
            trimmedAddress.endsWith("#") -> trimmedAddress.removeSuffix("#")
            trimmedAddress.endsWith("/") -> trimmedAddress.removeSuffix("/")
            else -> trimmedAddress
        }

        // 所有构建均保留 http，避免将明文后端误升为 https
        if (finalAddress.startsWith("http://")) {
            android.util.Log.i("ApiClient", "Keeping HTTP endpoint: $finalAddress")
        }

        return finalAddress + defaultPath
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
                jsonParser.encodeToString(chatRequest)

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
                                val bitmap = mediaItem.bitmap
                                originalFileNameFromMediaItem =
                                    "bitmap_image_$index.${if (bitmap?.hasAlpha() == true) "png" else "jpeg"}"
                                mimeTypeFromMediaItem =
                                    if (bitmap?.hasAlpha() == true) ContentType.Image.PNG.toString() else ContentType.Image.JPEG.toString()
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

                val processingCompleted = CompletableDeferred<Unit>()
                val processJob = launch(Dispatchers.IO) {
                    try {
                        processChannel(channel, backendProxyUrl) { event ->
                            trySend(event)
                            if (event is AppStreamEvent.Finish && event.reason == "stream_end") {
                                processingCompleted.complete(Unit)
                            }
                        }
                    } finally {
                        processingCompleted.complete(Unit) // 确保在任何情况下都能完成
                    }
                }
                
                processingCompleted.await() // 等待 processChannel 完成
                processJob.join()
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

    private suspend fun CoroutineScope.processChannel(
        channel: ByteReadChannel,
        backendProxyUrl: String,
        trySend: suspend (AppStreamEvent) -> Unit
    ) {
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
                    android.util.Log.d(
                        "ApiClient",
                        "已读取 $lineCount 行，当前行: '${line?.take(50) ?: "NULL"}'"
                    )
                }

                if (line.isNullOrEmpty()) {
                    val chunk = lineBuffer.toString().trim()
                    if (chunk.isNotEmpty()) {
                        android.util.Log.d(
                            "ApiClient",
                            "处理数据块 (长度=${chunk.length}): '${chunk.take(100)}${if (chunk.length > 100) "..." else ""}'"
                        )

                        if (chunk.equals("[DONE]", ignoreCase = true)) {
                            android.util.Log.d("ApiClient", "收到[DONE]标记，结束流处理")
                            channel.cancel(CoroutineCancellationException("[DONE] marker received"))
                            break
                        }
                        try {
                            val appEvent = parseBackendStreamEvent(chunk)
                            if (appEvent != null) {
                                eventCount++
                                when (appEvent) {
                                    is AppStreamEvent.Content -> android.util.Log.i("ApiClientEvent", "Content len=${appEvent.text.length} preview=${appEvent.text.take(120)}")
                                    is AppStreamEvent.ContentFinal -> android.util.Log.i("ApiClientEvent", "ContentFinal len=${appEvent.text.length} preview=${appEvent.text.take(120)}")
                                    is AppStreamEvent.Text -> android.util.Log.i("ApiClientEvent", "Text len=${appEvent.text.length} preview=${appEvent.text.take(120)}")
                                    is AppStreamEvent.Finish -> android.util.Log.w("ApiClientEvent", "Finish reason=${appEvent.reason}")
                                    is AppStreamEvent.Error -> android.util.Log.e("ApiClientEvent", "Error upstreamStatus=${appEvent.upstreamStatus} msg=${appEvent.message}")
                                    else -> android.util.Log.d("ApiClientEvent", "Other event=${appEvent.javaClass.simpleName}")
                                }
                                if (eventCount <= 5) {
                                    android.util.Log.d("ApiClient", "解析到流事件 #$eventCount: ${appEvent.javaClass.simpleName}")
                                } else if (eventCount % 10 == 0) {
                                    android.util.Log.d("ApiClient", "已处理 $eventCount 个流事件")
                                }
                                trySend(appEvent)
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
                    android.util.Log.d("ApiClient", "非SSE格式行，直接处理: '$line'")
                    if (line.trim().isNotEmpty()) {
                        try {
                            val appEvent = parseBackendStreamEvent(line.trim())
                            if (appEvent != null) {
                                eventCount++
                                android.util.Log.d(
                                    "ApiClient",
                                    "非SSE格式解析到事件 #$eventCount: ${appEvent.javaClass.simpleName}"
                                )
                                trySend(appEvent)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ApiClient", "非SSE格式解析失败: '$line'", e)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            android.util.Log.e("ApiClient", "流读取IO异常 ($backendProxyUrl)", e)
            throw e
        } catch (e: CoroutineCancellationException) {
            android.util.Log.d("ApiClient", "流读取被取消 ($backendProxyUrl): ${e.message}")
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "流读取意外异常 ($backendProxyUrl)", e)
            throw IOException("意外流错误 ($backendProxyUrl): ${e.message}", e)
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
                    android.util.Log.e(
                        "ApiClientStream",
                        "Serialization failed for final chunk: '$chunk'",
                        e
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun streamChatResponse(
        request: ChatRequest,
        attachments: List<SelectedMediaItem>,
        applicationContext: Context
    ): Flow<AppStreamEvent> = channelFlow {
        // 尝试按顺序连接所有已配置的后端URL，首个成功即使用，失败则自动回退到下一个
        val backendUrls = BackendConfig.backendUrls
        if (backendUrls.isEmpty()) {
            throw IOException("未配置后端代理服务器URL（BackendConfig.backendUrls 为空）。")
        }

        // 优先尝试非本机地址，最后再尝试 127.0.0.1/localhost，提升真机可连接性
        val sortedBackends = backendUrls.sortedBy { isLocalHostUrl(it) }
        android.util.Log.d("ApiClient", "后端地址尝试顺序: $sortedBackends")

        var lastError: Exception? = null
        var connected = false

        for (raw in sortedBackends) {
            var base = raw.trimEnd('/')
            // 兼容错误/混淆配置：统一剥离尾部后再拼接 /chat
            if (base.endsWith("/chat")) {
                base = base.removeSuffix("/chat").trimEnd('/')
            }
            if (base.endsWith("/v1/images/generations")) {
                base = base.removeSuffix("/v1/images/generations").trimEnd('/')
            }
            if (base.endsWith("/chat/v1/images/generations")) {
                base = base.removeSuffix("/chat/v1/images/generations").trimEnd('/')
            }
            val backendProxyUrl = buildFinalUrl(base, "/chat")

            try {
                android.util.Log.d("ApiClient", "尝试连接后端: $backendProxyUrl (原始地址: $raw)")
                streamChatResponseInternal(backendProxyUrl, request, attachments, applicationContext)
                    .collect { event ->
                        send(event)
                    }
                connected = true
                break
            } catch (e: Exception) {
                lastError = if (e is Exception) e else Exception(e)
                android.util.Log.w("ApiClient", "连接后端失败，尝试下一个: $backendProxyUrl, 错误: ${e.message}")
                // 继续尝试下一个地址
            }
        }

        if (!connected) {
            throw IOException("所有后端均连接失败。最后错误: ${lastError?.message}", lastError)
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
    
        // 统一去掉尾部 '#'
        val baseForModels = apiUrl.trim().removeSuffix("#")
        val parsedUri = try { java.net.URI(baseForModels) } catch (_: Exception) { null }
        val hostLower = parsedUri?.host?.lowercase()
        val scheme = parsedUri?.scheme ?: "https"
    
        // 优化：当为 Google Gemini 官方域名时，使用官方的 models 列表端点，而不是 OpenAI 兼容的 /v1/models
        val isGoogleOfficial = hostLower == "generativelanguage.googleapis.com" ||
                (hostLower?.endsWith("googleapis.com") == true &&
                 baseForModels.contains("generativelanguage", ignoreCase = true))
    
        val url = when {
            isGoogleOfficial -> {
                val googleUrl = "$scheme://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                android.util.Log.i("ApiClient", "检测到 Google Gemini 官方API，改用官方模型列表端点: $googleUrl")
                googleUrl
            }
            // 智谱 BigModel 官方特判
            hostLower?.contains("open.bigmodel.cn") == true -> {
                val zhipu = "$scheme://open.bigmodel.cn/api/paas/v4/models"
                android.util.Log.i("ApiClient", "检测到智谱 BigModel，改用官方模型列表端点: $zhipu")
                zhipu
            }
            else -> {
                buildFinalUrl(baseForModels, "/v1/models")
            }
        }
        android.util.Log.d("ApiClient", "获取模型列表 - 原始URL: '$apiUrl', 最终请求URL: '$url'")
    
        return try {
            val response = client.get {
                url(url)
                // Google 官方端点使用 ?key=API_KEY，不需要 Authorization 头；其余保持 Bearer 头
                if (!isGoogleOfficial) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                }
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.UserAgent, "KunTalkwithAi/1.0")
            }
    
            val responseBody = response.bodyAsText()
    
            // Google 官方响应优先解析：{"models":[{"name":"models/gemini-1.5-pro", ...}, ...]}
            if (isGoogleOfficial) {
                try {
                    val root = jsonParser.parseToJsonElement(responseBody)
                    if (root is JsonObject && root["models"] is JsonArray) {
                        val arr = root["models"]!!.jsonArray
                        val ids = arr.mapNotNull { el ->
                            try {
                                val name = el.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.trim()
                                name?.removePrefix("models/")?.takeIf { it.isNotEmpty() }
                            } catch (_: Exception) { null }
                        }.distinct()
                        if (ids.isNotEmpty()) return ids
                    }
                } catch (_: Exception) {
                    // 继续走下方通用解析
                }
            }
    
            // 通用解析 1: {"data":[{"id": "..."}]}
            try {
                val modelsResponse = jsonParser.decodeFromString<ModelsResponse>(responseBody)
                return modelsResponse.data.map { it.id }
            } catch (_: SerializationException) {
                // 通用解析 2: [{"id": "..."}]
                try {
                    val modelsList = jsonParser.decodeFromString<List<ModelInfo>>(responseBody)
                    return modelsList.map { it.id }
                } catch (_: SerializationException) {
                    // 兜底解析：尝试从常见容器字段中提取（data/models），并兼容 name/model/id 等字段
                    try {
                        val root = jsonParser.parseToJsonElement(responseBody)
                        fun extractIdFromObj(obj: JsonObject): String? {
                            val candidates = listOf("id", "model", "name", "identifier")
                            for (k in candidates) {
                                obj[k]?.jsonPrimitive?.contentOrNull?.let { s ->
                                    val v = s.trim()
                                    if (v.isNotEmpty()) {
                                        // 若为 Google 风格 "models/xxx"，统一去掉前缀
                                        return v.removePrefix("models/")
                                    }
                                }
                            }
                            return null
                        }
                        fun extractFromArray(arr: JsonArray): List<String> {
                            return arr.mapNotNull { el ->
                                when {
                                    el is JsonObject -> extractIdFromObj(el)
                                    else -> el.jsonPrimitive.contentOrNull?.trim()
                                        ?.removePrefix("models/")
                                        ?.takeIf { it.isNotEmpty() }
                                }
                            }.distinct()
                        }
    
                        val ids: List<String> = when {
                            root is JsonObject && root["data"] is JsonArray ->
                                extractFromArray(root["data"]!!.jsonArray)
                            root is JsonObject && root["models"] is JsonArray ->
                                extractFromArray(root["models"]!!.jsonArray)
                            root is JsonArray ->
                                extractFromArray(root)
                            else -> emptyList()
                        }
    
                        if (ids.isNotEmpty()) {
                            return ids
                        } else {
                            throw IOException("无法解析模型列表的响应。请检查API端点返回的数据格式是否正确。")
                        }
                    } catch (e3: Exception) {
                        throw IOException("无法解析模型列表的响应。请检查API端点返回的数据格式是否正确。", e3)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "从 $url 获取模型列表失败", e)
            throw IOException("从 $url 获取模型列表失败: ${e.message}", e)
        }
    }
    suspend fun generateImage(chatRequest: ChatRequest): ImageGenerationResponse {
        if (!isInitialized) {
            throw IllegalStateException("ApiClient not initialized. Call initialize() first.")
        }
        val imgReq = chatRequest.imageGenRequest
            ?: throw IOException("缺少 imageGenRequest 配置，无法发起图像生成。")

        val backendUrls = BackendConfig.backendUrls
            if (backendUrls.isEmpty()) {
                throw IOException("No backend URL configured.")
            }
        android.util.Log.d("ApiClient", "All backend URLs: ${backendUrls}")
        // 为图像生成构建候选URL列表（剥离错误尾巴后统一为 /v1/images/generations）
        val sortedBackends = backendUrls.sortedBy { isLocalHostUrl(it) }
        val candidateImageUrls = mutableListOf<String>()
        sortedBackends.forEach { raw ->
            var base = raw.trimEnd('/')
            if (base.endsWith("/chat")) {
                base = base.removeSuffix("/chat").trimEnd('/')
            }
            if (base.endsWith("/v1/images/generations")) {
                base = base.removeSuffix("/v1/images/generations").trimEnd('/')
            }
            if (base.endsWith("/chat/v1/images/generations")) {
                base = base.removeSuffix("/chat/v1/images/generations").trimEnd('/')
            }
            candidateImageUrls.add("$base/v1/images/generations")
        }
        android.util.Log.d("ApiClient", "Image generation candidate URLs: $candidateImageUrls")

        val promptFromMsg = try {
            chatRequest.messages.lastOrNull { it.role == "user" }?.let { msg ->
                when (msg) {
                    is com.example.everytalk.data.DataClass.SimpleTextApiMessage -> msg.content
                    is com.example.everytalk.data.DataClass.PartsApiMessage -> msg.parts
                        .filterIsInstance<com.example.everytalk.data.DataClass.ApiContentPart.Text>()
                        .joinToString(" ") { it.text }
                    else -> null
                }
            }
        } catch (_: Exception) { null }

        val finalPrompt = (imgReq.prompt.ifBlank { promptFromMsg ?: "" }).ifBlank {
            throw IOException("Prompt 为空，无法发起图像生成。")
        }

        val payload = buildJsonObject {
            put("model", imgReq.model)
            put("prompt", finalPrompt)

            // 检查并添加图片附件，用于图文编辑
            val imageAttachments = chatRequest.messages
                .lastOrNull { it.role == "user" }
                ?.let { msg ->
                    (msg as? com.example.everytalk.data.DataClass.PartsApiMessage)?.parts
                        ?.filterIsInstance<com.example.everytalk.data.DataClass.ApiContentPart.InlineData>()
                }

            if (!imageAttachments.isNullOrEmpty()) {
                val contentsArray = buildJsonArray {
                    // Gemini's multimodal format requires a list of parts
                    val textPart = buildJsonObject { put("text", finalPrompt) }
                    add(textPart)

                    imageAttachments.forEach { attachment ->
                        val imagePart = buildJsonObject {
                            put("inline_data", buildJsonObject {
                                put("mime_type", attachment.mimeType)
                                put("data", attachment.base64Data)
                            })
                        }
                        add(imagePart)
                    }
                }
                put("contents", contentsArray)
            }

            imgReq.imageSize?.let { put("image_size", it) }
            imgReq.batchSize?.let { put("batch_size", it) }
            imgReq.numInferenceSteps?.let { put("num_inference_steps", it) }
            imgReq.guidanceScale?.let { put("guidance_scale", it) }
            // 新增：可选配置，适配 Google Gemini 文档 + 与后端模型字段对齐（顶层也传）
            imgReq.responseModalities?.let { list ->
                if (list.isNotEmpty()) {
                    // 顶层字段（供后端 Pydantic 直接解析）
                    put("response_modalities", buildJsonArray { list.forEach { add(it) } })
                    // 同时在 generationConfig 中重复一份（供直连上游）
                    put("generationConfig", buildJsonObject {
                        put("responseModalities", buildJsonArray { list.forEach { add(it) } })
                        imgReq.aspectRatio?.let { ar ->
                            put("imageConfig", buildJsonObject { put("aspectRatio", ar) })
                        }
                    })
                }
            } ?: run {
                // 未设置 response_modalities 时，若仅有宽高比也写入 generationConfig
                imgReq.aspectRatio?.let { ar ->
                    put("generationConfig", buildJsonObject {
                        put("imageConfig", buildJsonObject { put("aspectRatio", ar) })
                    })
                }
            }
            // 顶层也传递 aspect_ratio，便于后端直接取用
            imgReq.aspectRatio?.let { ar -> put("aspect_ratio", ar) }
            // 将上游地址与密钥交由后端代理转发与规范化
            put("apiAddress", imgReq.apiAddress)
            put("apiKey", imgReq.apiKey)
            imgReq.provider?.let { put("provider", it) }
            imgReq.conversationId?.let { put("conversationId", it) }
        }

        // 逐个候选地址尝试，首个成功即返回
        var lastError: Exception? = null
        for (url in candidateImageUrls) {
            try {
                android.util.Log.d("ApiClient", "Image generation request - URL: $url")
                android.util.Log.d("ApiClient", "Image generation request - Model: ${imgReq.model}")
                android.util.Log.d("ApiClient", "Image generation request - API Key: ${imgReq.apiKey.take(10)}...")
                android.util.Log.d("ApiClient", "Image generation request - Payload: ${payload.toString().take(200)}...")
                val response = client.post(url) {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer ${imgReq.apiKey}")
                    header(HttpHeaders.Accept, "application/json")
                    setBody(payload)
                }
                if (!response.status.isSuccess()) {
                    val errTxt = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
                    android.util.Log.e("ApiClient", "Image generation HTTP ${response.status.value}: $errTxt")
                    throw IOException("上游错误 ${response.status.value}: ${errTxt.take(300)}")
                }
                val bodyText = response.bodyAsText()
                try {
                    return jsonParser.decodeFromString<ImageGenerationResponse>(bodyText)
                } catch (e: SerializationException) {
                    android.util.Log.e("ApiClient", "ImageGenerationResponse 解析失败，原始响应: ${bodyText.take(500)}", e)
                    throw IOException("响应解析失败: ${e.message}")
                }
            } catch (e: Exception) {
                android.util.Log.e("ApiClient", "Image generation attempt failed for $url", e)
                lastError = if (e is Exception) e else Exception(e)
                // 尝试下一个候选
            }
        }
        throw IOException("Image generation failed on all backends: ${lastError?.message}", lastError)
    }
}