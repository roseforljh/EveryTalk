package com.example.everytalk.data.network

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.everytalk.data.DataClass.ChatRequest
import com.example.everytalk.models.SelectedMediaItem
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.cache.storage.FileStorage
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import com.example.everytalk.data.DataClass.GithubRelease
import kotlinx.serialization.Serializable
import com.example.everytalk.data.local.SharedPreferencesDataSource
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.File
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.utils.io.streams.asInput
import kotlinx.coroutines.CancellationException as CoroutineCancellationException
import java.util.concurrent.atomic.AtomicBoolean

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
            val cacheFile = File(context.cacheDir, "ktor_http_cache")
            client = HttpClient(Android) {
                install(ContentNegotiation) {
                    json(jsonParser)
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 300_000
                    connectTimeoutMillis = 60_000  // 增加连接超时到60秒
                    socketTimeoutMillis = 300_000
                }
                install(HttpCache) {
                    publicStorage(FileStorage(cacheFile))
                }
            }
            isInitialized = true
        }
    }

    private fun getBackendUrls(): List<String> {
        return listOf(
            "http://192.168.0.103:7860/chat",  // 原始配置作为备用
            //"https://backdaitalk.onrender.com/chat",
            //"https://kunzzz003-my-backend-code.hf.space/chat"
        )
    }


    private const val GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"

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
            throw IOException(
                "服务器错误 $statusCode ($statusDescription): $errorBody",
                e
            )
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
        val backendProxyUrls = getBackendUrls()
        if (backendProxyUrls.isEmpty()) {
            throw IOException("没有后端服务器URL可供尝试。")
        }

        val errors = mutableListOf<Throwable>()
        val jobs = mutableListOf<Job>()
        val winnerFound = AtomicBoolean(false)

        supervisorScope {
            for (url in backendProxyUrls) {
                val job = launch {
                    try {
                        streamChatResponseInternal(url, request, attachments, applicationContext)
                            .collect { event ->
                                if (winnerFound.compareAndSet(false, true)) {
                                    jobs.forEach {
                                        if (it != coroutineContext[Job]) {
                                            it.cancel(CoroutineCancellationException("Another stream responded faster."))
                                        }
                                    }
                                }
                                if (winnerFound.get()) {
                                    synchronized(errors) {
                                        errors.clear()
                                    }
                                }
                                send(event)
                            }
                    } catch (e: Exception) {
                        if (e !is CoroutineCancellationException) {
                            android.util.Log.e("ApiClient", "详细错误信息 - URL: $url, 错误类型: ${e.javaClass.simpleName}, 消息: ${e.message}", e)
                            synchronized(errors) {
                                if (!winnerFound.get()) {
                                    errors.add(e)
                                    android.util.Log.d("ApiClient", "Error added to collection for $url: ${e.message}")
                                }
                            }
                        } else {
                            android.util.Log.d("ApiClient", "Cancellation exception for $url: ${e.message}")
                            // 对于取消异常，检查是否是由于真实错误导致的取消
                            val cause = e.cause
                            if (cause != null && cause !is CoroutineCancellationException) {
                                android.util.Log.e("ApiClient", "Cancellation caused by error for $url", cause)
                                synchronized(errors) {
                                    if (!winnerFound.get()) {
                                        errors.add(cause)
                                        android.util.Log.d("ApiClient", "Cancellation cause added to collection for $url: ${cause.message}")
                                    }
                                }
                            } else {
                                // 即使是普通的取消异常，也需要记录以便调试
                                android.util.Log.d("ApiClient", "Pure cancellation for $url, no underlying error")
                                synchronized(errors) {
                                    if (!winnerFound.get() && errors.isEmpty()) {
                                        // 如果没有其他错误，记录取消异常以避免"no errors collected"
                                        errors.add(IOException("连接被取消: ${e.message}", e))
                                        android.util.Log.d("ApiClient", "Cancellation recorded as fallback error for $url")
                                    }
                                }
                            }
                        }
                    }
                }
                jobs.add(job)
            }
        }

        if (!winnerFound.get()) {
            synchronized(errors) {
                android.util.Log.d("ApiClient", "Final error collection status: ${errors.size} errors collected")
                errors.forEachIndexed { index, error ->
                    android.util.Log.d("ApiClient", "Error $index: ${error.javaClass.simpleName}: ${error.message}")
                }
                
                if (errors.isNotEmpty()) {
                    // 抛出最具体的错误信息
                    val lastError = errors.last()
                    android.util.Log.d("ApiClient", "Throwing collected error: ${lastError.javaClass.simpleName}: ${lastError.message}")
                    throw lastError
                } else {
                    android.util.Log.w("ApiClient", "No errors collected, but no winner found. URLs: $backendProxyUrls")
                    android.util.Log.w("ApiClient", "This usually indicates all connections were cancelled without proper error reporting")
                    throw IOException("无法连接到任何可用的后端服务器。可能的原因：网络连接问题、服务器不可达或连接超时。")
                }
            }
        }
    }.buffer(Channel.BUFFERED).flowOn(Dispatchers.IO)

    private const val GEMINI_UPLOAD_URL = "https://generativelanguage.googleapis.com/v1beta/files"

    @Serializable
    data class FileUploadInitialResponse(val file: FileMetadata)

    @Serializable
    data class FileMetadata(val name: String, val uri: String, @kotlinx.serialization.SerialName("upload_uri") val uploadUri: String)

    private suspend fun uploadFile(apiKey: String, mimeType: String, audioBytes: ByteArray): com.example.everytalk.data.DataClass.Part.FileUri {
        if (!isInitialized) {
            throw IllegalStateException("ApiClient not initialized. Call initialize() first.")
        }

        // Step 1: Get the upload URI
        val initialResponse = client.post(GEMINI_UPLOAD_URL) {
            parameter("key", apiKey)
            header(HttpHeaders.ContentType, "application/json")
            setBody(mapOf("file" to mapOf("mime_type" to mimeType)))
        }.body<FileUploadInitialResponse>()

        // Step 2: Upload the file to the upload URI
        val uploadResponse = client.post(initialResponse.file.uploadUri) {
            header(HttpHeaders.ContentType, mimeType)
            setBody(audioBytes)
        }
    
        if (!uploadResponse.status.isSuccess()) {
            throw IOException("Failed to upload file to ${initialResponse.file.uploadUri}: ${uploadResponse.status}")
        }

        return com.example.everytalk.data.DataClass.Part.FileUri(initialResponse.file.uri)
    }

    suspend fun generateContent(apiKey: String, request: com.example.everytalk.data.DataClass.GeminiApiRequest, audioBase64: String? = null, mimeType: String? = "audio/3gpp"): com.example.everytalk.data.DataClass.GeminiApiResponse {
        if (!isInitialized) {
            throw IllegalStateException("ApiClient not initialized. Call initialize() first.")
        }

        val finalRequest = if (audioBase64 != null) {
            val audioBytes = android.util.Base64.decode(audioBase64, android.util.Base64.DEFAULT)
            val audioPart = if (audioBytes.size > 19 * 1024 * 1024) { // 19MB to be safe
                uploadFile(apiKey, mimeType ?: "audio/3gpp", audioBytes)
            } else {
                com.example.everytalk.data.DataClass.Part.InlineData(mimeType = mimeType ?: "audio/3gpp", data = audioBase64)
            }

            val updatedContents = request.contents.map { content ->
                val newParts = content.parts.toMutableList()
                newParts.add(audioPart)
                content.copy(parts = newParts)
            }
            request.copy(contents = updatedContents)
        } else {
            request
        }

        return client.post {
            url(GEMINI_API_URL)
            parameter("key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(finalRequest)
        }.body<com.example.everytalk.data.DataClass.GeminiApiResponse>()
    }

    suspend fun getLatestRelease(): GithubRelease {
        if (!isInitialized) {
            throw IllegalStateException("ApiClient not initialized. Call initialize() first.")
        }
        return client.get {
            url(GITHUB_API_BASE_URL + "repos/roseforljh/KunTalkwithAi/releases/latest")
            header(HttpHeaders.Accept, "application/vnd.github.v3+json")
            // Add headers to bypass cache
            header(HttpHeaders.CacheControl, "no-cache")
            header(HttpHeaders.Pragma, "no-cache")
        }.body<GithubRelease>()
    }
}