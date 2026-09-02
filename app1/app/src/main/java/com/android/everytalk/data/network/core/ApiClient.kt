package com.android.everytalk.data.network

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ImageGenerationResponse
import com.android.everytalk.data.DataClass.ModelCapabilityCandidate
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.util.image.ImageHandlingLimits
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.ktor.utils.io.streams.asInput
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import android.util.Base64
import kotlinx.coroutines.CancellationException as CoroutineCancellationException

internal const val MAX_INLINE_NON_IMAGE_BYTES = 10L * 1024L * 1024L

private class AttachmentTooLargeException(message: String) : IllegalStateException(message)

private fun formatInlineAttachmentSize(size: Long): String {
    return when {
        size < 1024L -> "${size}B"
        size < 1024L * 1024L -> "${size / 1024L}KB"
        else -> "${size / (1024L * 1024L)}MB"
    }
}

private fun ContentResolver.getDeclaredLength(uri: Uri): Long? {
    query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex != -1) {
                val size = cursor.getLong(sizeIndex)
                if (size > 0L) return size
            }
        }
    }
    return runCatching {
        openFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.statSize.takeIf { it > 0L }
        }
    }.getOrNull()
}

private fun throwInlineAttachmentTooLarge(displayName: String, size: Long, maxBytes: Long): Nothing {
    throw AttachmentTooLargeException(
        "附件“$displayName”大小为 ${formatInlineAttachmentSize(size)}，超过最大 ${formatInlineAttachmentSize(maxBytes)} 限制"
    )
}

internal suspend fun readInlineAttachmentBytes(
    context: Context,
    uri: Uri,
    displayName: String,
    maxBytes: Long = MAX_INLINE_NON_IMAGE_BYTES,
): ByteArray? = withContext(Dispatchers.IO) {
    context.contentResolver.getDeclaredLength(uri)?.let { declaredSize ->
        if (declaredSize > maxBytes) {
            throwInlineAttachmentTooLarge(displayName, declaredSize, maxBytes)
        }
    }
    context.contentResolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                throwInlineAttachmentTooLarge(displayName, total, maxBytes)
            }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }
}

internal fun ensureInlineAttachmentSize(
    displayName: String,
    rawSize: Long,
    maxBytes: Long = MAX_INLINE_NON_IMAGE_BYTES,
) {
    if (rawSize > maxBytes) {
        throwInlineAttachmentTooLarge(displayName, rawSize, maxBytes)
    }
}

object ApiClient {
    /**
     * Parse backend stream event JSON format and convert to AppStreamEvent
     * 委托到 StreamEventParser 处理，保持向后兼容
     */
    private fun parseBackendStreamEvent(jsonChunk: String): AppStreamEvent? {
        return com.android.everytalk.data.network.parser.StreamEventParser.parseBackendStreamEvent(jsonChunk)
    }
    
    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            serializersModule = SerializersModule {
                contextual(Any::class, AnySerializer)
                polymorphic(com.android.everytalk.data.DataClass.AbstractApiMessage::class) {
                    subclass(com.android.everytalk.data.DataClass.SimpleTextApiMessage::class)
                    subclass(com.android.everytalk.data.DataClass.PartsApiMessage::class)
                    subclass(com.android.everytalk.data.DataClass.AgentAssistantApiMessage::class)
                    subclass(com.android.everytalk.data.DataClass.AgentToolResultApiMessage::class)
                }
                polymorphic(AppStreamEvent::class) {
                    subclass(AppStreamEvent.Text::class)
                    subclass(AppStreamEvent.Content::class)
                    subclass(AppStreamEvent.ContentFinal::class)
                    subclass(AppStreamEvent.Reasoning::class)
                    subclass(AppStreamEvent.ReasoningFinish::class)
                    subclass(AppStreamEvent.Usage::class)
                    subclass(AppStreamEvent.StreamEnd::class)
                    subclass(AppStreamEvent.WebSearchStatus::class)
                    subclass(AppStreamEvent.WebSearchResults::class)
                    subclass(AppStreamEvent.StatusUpdate::class)
                    subclass(AppStreamEvent.ExecutionStatusUpdate::class)
                    subclass(AppStreamEvent.ToolCall::class)
                    subclass(AppStreamEvent.Error::class)
                    subclass(AppStreamEvent.Finish::class)
                    subclass(AppStreamEvent.ImageGeneration::class)
                    subclass(AppStreamEvent.CodeExecutionResult::class)
                    subclass(AppStreamEvent.CodeExecutable::class)
                }
            }
        }
    }

    private lateinit var client: HttpClient
    private lateinit var modelCatalogService: ModelCatalogService
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
            client = HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
                engine {
                    // 跨境延迟优化配置
                    config {
                        // 超时配置：跨境场景适当增加
                        connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)   // 5分钟，适合长时间流式响应
                        writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)  // 2分钟，适合大文件上传
                        
                        // 连接池配置：复用连接减少握手延迟
                        connectionPool(okhttp3.ConnectionPool(
                            10,  // 最大空闲连接数
                            5,   // 连接保持活跃时间
                            java.util.concurrent.TimeUnit.MINUTES
                        ))
                        
                        // 启用 HTTP/2 + HTTP/1.1 回退（OkHttp 默认支持）
                        protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
                    }
                }
                install(ContentNegotiation) {
                    json(jsonParser)
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 1800_000
                    connectTimeoutMillis = 60_000  // VPN环境下增加连接超时到120秒
                    socketTimeoutMillis = 1800_000
                }
                // WebSocket 支持（用于阿里云实时语音识别等）
                install(WebSockets) {
                    pingIntervalMillis = 30_000  // 30秒心跳
                    maxFrameSize = MAX_WEBSOCKET_FRAME_BYTES
                }
                // 添加更详细的日志记录
                install(io.ktor.client.plugins.logging.Logging) {
                    logger = object : io.ktor.client.plugins.logging.Logger {
                        override fun log(message: String) {
                            // Gemini 等协议把 Key 放在 URL 查询参数中，sanitizeHeader 覆盖不到。
                            android.util.Log.d("ApiClient-HTTP", NetworkUtils.sanitizeMessage(message))
                        }
                    }
                    level = io.ktor.client.plugins.logging.LogLevel.INFO
                    sanitizeHeader { header ->
                        header.equals(HttpHeaders.Authorization, ignoreCase = true) ||
                            header.equals("x-api-key", ignoreCase = true) ||
                            header.equals("x-goog-api-key", ignoreCase = true)
                    }
                }
            }
            modelCatalogService = ModelCatalogService(
                client = client,
                endpointCache = ModelCapabilityCache(
                    context.applicationContext.cacheDir.resolve("model-capabilities-v1.json")
                ),
                modelsDevCatalog = ModelsDevCatalog(
                    context.applicationContext.cacheDir.resolve("models-dev-v1.json")
                ),
            )
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
            val boundedLines = BoundedSseLineReader(channel)
            while (!channel.isClosedForRead) {
                val raw = boundedLines.readLine()
                lineCount++

                if (lineCount <= 10) {
                    android.util.Log.d("ApiClient", "读取行 #$lineCount: '${raw ?: "NULL"}'")
                } else if (lineCount % 50 == 0) {
                    android.util.Log.d(
                        "ApiClient",
                        "已读取 $lineCount 行，当前行: '${raw?.take(50) ?: "NULL"}'"
                    )
                }

                // 严格保留 SSE 一行一帧的语义；禁止把 JSON 内部的 "\\n" 还原为真实换行，避免打断 JSON
                // 上游会将文本中的换行以转义序列输出（例如 "\\n"），如果这里替换成真实 '\n' 再 split 会把一条 data 事件拆成多行碎片，导致 JSON 解析失败。
                val normalizedLines: List<String?> = when {
                    raw == null -> listOf<String?>(null)
                    else -> listOf(raw)
                }

                suspend fun handleOneLine(line: String?) {
                    when {
                        line.isNullOrEmpty() -> {
                            // 空行表示一个SSE事件结束，尝试解析累积的 data: 负载
                            val chunk = lineBuffer.toString().trim()
                            if (chunk.isNotEmpty()) {
                                android.util.Log.d(
                                    "ApiClient",
                                    "处理数据块 (长度=${chunk.length})"
                                )

                                if (chunk.equals("[DONE]", ignoreCase = true)) {
                                    android.util.Log.d("ApiClient", "收到[DONE]标记，结束流处理")
                                    channel.cancel(CoroutineCancellationException("[DONE] marker received"))
                                    return
                                }
                                try {
                                    val appEvent = parseBackendStreamEvent(chunk)
                                    if (appEvent != null) {
                                        eventCount++
                                        when (appEvent) {
                                            is AppStreamEvent.Content -> android.util.Log.i("ApiClientEvent", "Content len=${appEvent.text.length}")
                                            is AppStreamEvent.ContentFinal -> android.util.Log.i("ApiClientEvent", "ContentFinal len=${appEvent.text.length}")
                                            is AppStreamEvent.Text -> android.util.Log.i("ApiClientEvent", "Text len=${appEvent.text.length}")
                                            is AppStreamEvent.Finish -> android.util.Log.w("ApiClientEvent", "Finish reason=${appEvent.reason}")
                                            is AppStreamEvent.Error -> android.util.Log.e("ApiClientEvent", "Error upstreamStatus=${appEvent.upstreamStatus} msg=${appEvent.message}")
                                            else -> android.util.Log.d("ApiClientEvent", "Other event=${appEvent.javaClass.simpleName}")
                                        }
                                        if (eventCount <= 5) {
                                            android.util.Log.d("ApiClient", "解析到流事件 #$eventCount: ${appEvent.javaClass.simpleName}")
                                        } else if (eventCount % 10 == 0) {
                                            android.util.Log.d("ApiClient", "已处理 $eventCount 个流事件")
                                        }
                                        // 顺序挂起发送，确保不丢尾部事件且保持事件顺序
                                        trySend(appEvent)
                                    } else {
                                        android.util.Log.w("ApiClient", "无法解析的流数据块: chars=${chunk.length}")
                                    }
                                } catch (e: CoroutineCancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    android.util.Log.e("ApiClientStream", "Exception during event processing: chars=${chunk.length}", e)
                                    // 在这里添加容错逻辑，而不是让整个流失败
                                    // 例如，可以发送一个错误事件，或者简单地忽略这个损坏的数据块
                                    // runBlocking { trySend(AppStreamEvent.Error("无效的数据块: $chunk", null)) }
                                }
                            } else {
                                android.util.Log.d("ApiClient", "遇到空行，但lineBuffer为空")
                            }
                            lineBuffer.clear()
                        }
                        line.startsWith(":") -> {
                            // SSE注释/心跳，忽略（修复 :ok 触发的误解析）
                            android.util.Log.d("ApiClient", "SSE 注释行（忽略）: '$line'")
                        }
                        line.startsWith("data:") -> {
                            val dataContent = line.substring(5).trim()
                            android.util.Log.d("ApiClient", "SSE data行: chars=${dataContent.length}")
                            if (lineBuffer.isNotEmpty()) lineBuffer.append('\n')
                            lineBuffer.append(dataContent)
                        }
                        line.startsWith("event:") -> {
                            // 如需按event类型区分可在此记录，但当前后端仅用 data
                            android.util.Log.d("ApiClient", "SSE event行: '${line.substring(6).trim()}'")
                        }
                        else -> {
                            // 仅当看起来确为JSON对象/数组时，才尝试非SSE直解析；否则忽略，避免再次因" :ok ... "等抛错
                            val trimmed = line.trim()
                            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                                android.util.Log.d("ApiClient", "非SSE格式行（JSON回退）: '$trimmed'")
                                try {
                                    val appEvent = parseBackendStreamEvent(trimmed)
                                    if (appEvent != null) {
                                        eventCount++
                                        android.util.Log.d(
                                            "ApiClient",
                                            "非SSE格式解析到事件 #$eventCount: ${appEvent.javaClass.simpleName}"
                                        )
                                        // 顺序挂起发送，确保不丢尾部事件且保持事件顺序
                                        trySend(appEvent)
                                    }
                                } catch (e: CoroutineCancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    android.util.Log.e("ApiClient", "非SSE格式解析失败: '$trimmed'", e)
                                }
                            } else {
                                // 其他行忽略
                                android.util.Log.d("ApiClient", "忽略非SSE且非JSON的行: '$line'")
                            }
                        }
                    }
                }

                // 逐条子行处理
                for (sub in normalizedLines) {
                    handleOneLine(sub)
                    // 如果上一条在空行时触发了 DONE 并取消了通道，直接退出外层循环
                    if (channel.isClosedForRead) break
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

    /**
     * 强制直连模式 - 直接调用 API 提供商，不经过后端代理
     * 使用 ProviderRegistry 自动选择合适的 Provider
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun streamChatResponse(
        request: ChatRequest,
        attachments: List<SelectedMediaItem>,
        applicationContext: Context
    ): Flow<AppStreamEvent> = channelFlow {
        android.util.Log.i("ApiClient", "Direct mode started")

        // 构建多模态请求（注入图片附件）
        val requestForDirect = try {
            buildDirectMultimodalRequest(request, attachments, applicationContext)
        } catch (e: AttachmentTooLargeException) {
            android.util.Log.w("ApiClient", "Inline attachment rejected: ${e.message}")
            send(AppStreamEvent.Error(e.message ?: "附件过大", null))
            send(AppStreamEvent.Finish("attachment_too_large"))
            return@channelFlow
        } catch (e: CoroutineCancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ApiClient", "Failed to build multimodal request, using original: ${e.message}")
            request
        }
        
        try {
            val providerRegistry = org.koin.java.KoinJavaComponent.getKoin().get<com.android.everytalk.provider.ProviderRegistry>()
            val provider = providerRegistry.getProvider(requestForDirect)
            android.util.Log.i(
                "ApiClient",
                "Using provider: ${provider.providerName} (requestProviderChars=${requestForDirect.provider.length}, channelChars=${requestForDirect.channel.length}, modelChars=${requestForDirect.model.length})"
            )
            
            providerRegistry.streamChat(requestForDirect, attachments, applicationContext)
                .collect { event -> send(event) }
            
            android.util.Log.i("ApiClient", "Provider ${provider.providerName} completed")
        } catch (e: CancellationException) {
            if (e.message?.startsWith("Stream finished with event:") == true) {
                android.util.Log.i("ApiClient", "Direct stream completed normally: ${e.message}")
            } else {
                throw e
            }
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "Direct connection failed", e)
            send(AppStreamEvent.Error("Direct connection failed: ${NetworkUtils.sanitizeMessage(e.message)}", null))
            send(AppStreamEvent.Finish("direct_connection_failed"))
        }
    }

    /** AgentLoop 专用的一次模型请求入口。这里不会执行工具或启动下一轮。 */
    fun streamModelTurn(request: ChatRequest): Flow<AppStreamEvent> {
        if (!isInitialized) {
            return flowOf(
                AppStreamEvent.Error("ApiClient 尚未初始化"),
                AppStreamEvent.Finish("client_not_initialized"),
            )
        }
        return when (com.android.everytalk.data.DataClass.modelParameterProtocol(request.channel)) {
            com.android.everytalk.data.DataClass.ModelParameterProtocol.GEMINI ->
                GeminiDirectClient.streamSingleTurn(client, request)
            com.android.everytalk.data.DataClass.ModelParameterProtocol.ANTHROPIC ->
                AnthropicDirectClient.streamSingleTurn(client, request)
            com.android.everytalk.data.DataClass.ModelParameterProtocol.CODEX ->
                OpenAIResponsesClient.streamSingleTurn(client, request)
            com.android.everytalk.data.DataClass.ModelParameterProtocol.OPENAI_COMPATIBLE ->
                OpenAIDirectClient.streamSingleTurn(client, request)
        }
    }


    suspend fun getModels(apiUrl: String, apiKey: String, channel: String? = null): List<String> =
        getModelCatalog(apiUrl, apiKey, channel).map(ModelCapabilityCandidate::modelId)

    suspend fun getModelCatalog(
        apiUrl: String,
        apiKey: String,
        channel: String? = null,
    ): List<ModelCapabilityCandidate> {
        if (!isInitialized) {
            throw IllegalStateException("ApiClient not initialized. Call initialize() first.")
        }
        return modelCatalogService.getCatalog(apiUrl, apiKey, channel)
    }

    suspend fun getModelCapabilities(
        apiUrl: String,
        apiKey: String,
        channel: String?,
        modelId: String,
        providerHint: String,
    ): List<ModelCapabilityCandidate> {
        if (!isInitialized) {
            throw IllegalStateException("ApiClient not initialized. Call initialize() first.")
        }
        return modelCatalogService.getCapabilities(
            apiUrl = apiUrl,
            apiKey = apiKey,
            channel = channel,
            modelId = modelId,
            providerHint = providerHint,
        )
    }
    /**
     * 强制直连模式 - 图像生成直接调用 API 提供商
     * 根据模型类型自动选择 Gemini 或 OpenAI 兼容的直连客户端
     */
    suspend fun generateImage(chatRequest: ChatRequest): ImageGenerationResponse {
        if (!isInitialized) {
            throw IllegalStateException("ApiClient not initialized. Call initialize() first.")
        }
        
        val imgReq = chatRequest.imageGenRequest
            ?: throw IOException("缺少 imageGenRequest 配置，无法发起图像生成。")

        android.util.Log.i("ApiClient", "🔄 图像生成强制直连模式启动")
        
        // 判断是否为"默认"提供商（需要注入 SiliconFlow 配置）
        val isDefaultProvider = imgReq.provider?.trim()?.lowercase() in listOf("默认", "default", "") ||
                                imgReq.provider.isNullOrBlank()
        
        // 判断是 Gemini 还是 OpenAI 兼容
        val isGemini = imgReq.provider?.lowercase()?.contains("gemini") == true ||
                       imgReq.model.contains("gemini", ignoreCase = true) ||
                       imgReq.model.contains("imagen", ignoreCase = true)
        
        // 增加 Seedream 判断
        val isSeedream = imgReq.provider?.lowercase()?.contains("seedream") == true ||
                         imgReq.model.contains("doubao", ignoreCase = true) ||
                         imgReq.model.contains("seedream", ignoreCase = true)

        // 增加 SiliconFlow 判断（显式指定或通过 API 地址识别）
        val isSiliconFlow = imgReq.provider?.lowercase()?.contains("silicon") == true ||
                            imgReq.apiAddress.contains("siliconflow.cn")

        // 增加 Qwen 图像编辑判断（Modal 部署的 Qwen 图像编辑 API）
        val isQwenEdit = imgReq.model.contains("qwen-image-edit", ignoreCase = true) ||
                         imgReq.model.contains("qwen-edit", ignoreCase = true) ||
                         imgReq.model.contains("qwen_edit", ignoreCase = true)

        // 增加 Modal Z-Image-Turbo 判断（无需密钥）
        val isModalZImage = imgReq.model.contains("z-image-turbo", ignoreCase = true) ||
                            imgReq.model.contains("z_image_turbo", ignoreCase = true) ||
                            imgReq.apiAddress.contains("z-image-turbo", ignoreCase = true)

        // 配置注入逻辑：
        // 1. Modal Z-Image-Turbo / Qwen Edit -> 不注入配置，使用 BuildConfig 中的 URL
        // 2. "默认"提供商 -> 注入 SiliconFlow 配置（默认图像生成服务）
        // 3. SiliconFlow 提供商 -> 注入 SiliconFlow 配置
        // 4. 其他提供商 -> 使用原始配置
        val effectiveImgReq = when {
            // Modal Z-Image-Turbo 和 Qwen Edit 不需要配置注入，使用原始配置
            isModalZImage || isQwenEdit -> {
                android.util.Log.i("ApiClient", "🔧 检测到 Modal 部署模型，跳过配置注入")
                imgReq
            }
            // 默认提供商：注入 SiliconFlow 配置
            isDefaultProvider -> {
                android.util.Log.i("ApiClient", "🔧 检测到默认提供商，注入 SiliconFlow 配置")
                imgReq.copy(
                    apiAddress = imgReq.apiAddress.takeIf { it.isNotBlank() }
                        ?: com.android.everytalk.BuildConfig.SILICONFLOW_IMAGE_API_URL,
                    apiKey = imgReq.apiKey.takeIf { it.isNotBlank() }
                        ?: com.android.everytalk.BuildConfig.SILICONFLOW_API_KEY,
                    model = imgReq.model.takeIf { it.isNotBlank() }
                        ?: com.android.everytalk.BuildConfig.SILICONFLOW_DEFAULT_IMAGE_MODEL
                )
            }
            // SiliconFlow 提供商
            isSiliconFlow -> {
                android.util.Log.i("ApiClient", "🔧 检测到 SiliconFlow 提供商，注入默认配置")
                imgReq.copy(
                    apiAddress = imgReq.apiAddress.takeIf { it.isNotBlank() }
                        ?: com.android.everytalk.BuildConfig.SILICONFLOW_IMAGE_API_URL,
                    apiKey = imgReq.apiKey.takeIf { it.isNotBlank() }
                        ?: com.android.everytalk.BuildConfig.SILICONFLOW_API_KEY,
                    model = imgReq.model.takeIf { it.isNotBlank() }
                        ?: com.android.everytalk.BuildConfig.SILICONFLOW_DEFAULT_IMAGE_MODEL
                )
            }
            // 其他提供商：使用原始配置
            else -> imgReq
        }

        val providerName = when {
            isModalZImage -> "Modal Z-Image-Turbo"
            isQwenEdit -> "Qwen Edit (Modal)"
            isGemini -> "Gemini"
            isSeedream -> "Seedream"
            isDefaultProvider || isSiliconFlow -> "SiliconFlow"
            else -> "OpenAI兼容"
        }
        
        android.util.Log.i("ApiClient", "🔄 图像生成使用直连模式 ($providerName)")
        android.util.Log.d("ApiClient", "Image generation request - modelChars=${effectiveImgReq.model.length}")
        android.util.Log.d("ApiClient", "Image generation request - API Address: ${effectiveImgReq.apiAddress.substringBefore("://", missingDelimiterValue = "").takeIf { it.isNotBlank() }?.plus("://***") ?: "***"}")
        android.util.Log.d("ApiClient", "Image generation request - API Key: ${if (effectiveImgReq.apiKey.isNotBlank()) "[CONFIGURED]" else "[EMPTY]"}")
        android.util.Log.d("ApiClient", "Image generation request - Prompt chars: ${effectiveImgReq.prompt.length}")
        
        return try {
            when {
                isModalZImage -> {
                    // Modal Z-Image-Turbo 无需密钥，使用 GET 请求
                    val modalUrls = com.android.everytalk.BuildConfig.VITE_API_URLS
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    
                    android.util.Log.i("ApiClient", "🔄 Modal Z-Image-Turbo: 使用 ${modalUrls.size} 个 Modal URL")
                    ImageGenerationDirectClient.generateImageModal(
                        client, effectiveImgReq, modalUrls
                    )
                }
                isQwenEdit -> {
                    // Qwen 图像编辑需要输入图片和专用 Modal API
                    val qwenUrls = com.android.everytalk.BuildConfig.QWEN_EDIT_API_URLS
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    val qwenSecret = com.android.everytalk.BuildConfig.QWEN_EDIT_API_SECRET
                    
                    // 从 chatRequest 中提取输入图片的 Base64
                    val inputImageBase64 = extractInputImageBase64(chatRequest)
                    
                    if (inputImageBase64.isNullOrBlank()) {
                        throw IOException("Qwen 图像编辑需要提供输入图片")
                    }
                    
                    android.util.Log.i("ApiClient", "🔄 Qwen 图像编辑: 使用 ${qwenUrls.size} 个 Modal URL")
                    ImageGenerationDirectClient.generateImageQwenEdit(
                        client, effectiveImgReq, inputImageBase64, qwenUrls, qwenSecret
                    )
                }
                isGemini -> {
                    // Check if there are input images for editing
                    val inputImages = extractInputImages(chatRequest)
                    if (inputImages.isNotEmpty()) {
                        // Use the first image for Gemini editing (Gemini supports single reference image)
                        val (base64, mimeType) = inputImages.first()
                        android.util.Log.i("ApiClient", "🔄 Gemini 图像编辑模式: 检测到 ${inputImages.size} 张输入图片")
                        ImageGenerationDirectClient.generateImageGeminiWithReference(
                            client, effectiveImgReq, base64, mimeType
                        )
                    } else {
                        ImageGenerationDirectClient.generateImageGemini(client, effectiveImgReq)
                    }
                }
                isSeedream -> {
                    // Check if there are input images for editing
                    val inputImages = extractInputImages(chatRequest)
                    if (inputImages.isNotEmpty()) {
                        // Convert to data URIs for Seedream
                        val dataUris = inputImages.map { (base64, mimeType) -> 
                            "data:$mimeType;base64,$base64"
                        }
                        android.util.Log.i("ApiClient", "🔄 Seedream 图像编辑模式: 检测到 ${inputImages.size} 张输入图片")
                        ImageGenerationDirectClient.generateImageSeedreamWithReference(
                            client, effectiveImgReq, dataUris
                        )
                    } else {
                        ImageGenerationDirectClient.generateImageSeedream(client, effectiveImgReq)
                    }
                }
                else -> {
                    val inputImages = extractInputImages(chatRequest)
                    if (inputImages.isNotEmpty() && supportsOpenAIImageEdit(effectiveImgReq.model)) {
                        android.util.Log.i("ApiClient", "🔄 OpenAI 图像编辑模式: 检测到 ${inputImages.size} 张输入图片")
                        ImageGenerationDirectClient.generateImageOpenAIWithReference(
                            client, effectiveImgReq, inputImages
                        )
                    } else {
                        ImageGenerationDirectClient.generateImageOpenAI(client, effectiveImgReq)
                    }
                }
            }
        } catch (e: CoroutineCancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "❌ 图像生成直连失败", e)
            throw IOException("图像生成直连失败: ${e.message}", e)
        }
    }

    private fun supportsOpenAIImageEdit(model: String): Boolean {
        val normalized = model.lowercase()
        return normalized.contains("gpt-image") ||
            normalized.contains("chatgpt-image") ||
            normalized.contains("dall-e-2")
    }

    /**
     * 从 ChatRequest 中提取输入图片的 Base64 数据
     * 用于 Qwen 图像编辑等需要输入图片的场景
     */
    private fun extractInputImageBase64(chatRequest: ChatRequest): String? {
        // 遍历消息，查找包含图片的 PartsApiMessage（从最后一条开始）
        for (msg in chatRequest.messages.reversed()) {
            if (msg is com.android.everytalk.data.DataClass.PartsApiMessage && msg.role == "user") {
                for (part in msg.parts) {
                    if (part is com.android.everytalk.data.DataClass.ApiContentPart.InlineData) {
                        if (part.mimeType.startsWith("image/")) {
                            android.util.Log.d("ApiClient", "找到输入图片: mimeType=${part.mimeType}, base64长度=${part.base64Data.length}")
                            return part.base64Data
                        }
                    }
                }
            }
        }
        android.util.Log.w("ApiClient", "未找到输入图片")
        return null
    }

    /**
     * 从 ChatRequest 中提取所有输入图片的 Base64 数据和 MIME 类型
     * 用于 Gemini 和 Seedream 图像编辑等需要输入图片的场景
     * @return List of Pair(base64Data, mimeType)
     */
    private fun extractInputImages(chatRequest: ChatRequest): List<Pair<String, String>> {
        val images = mutableListOf<Pair<String, String>>()
        
        // 遍历消息，查找包含图片的 PartsApiMessage（从最后一条开始）
        for (msg in chatRequest.messages.reversed()) {
            if (msg is com.android.everytalk.data.DataClass.PartsApiMessage && msg.role == "user") {
                for (part in msg.parts) {
                    if (part is com.android.everytalk.data.DataClass.ApiContentPart.InlineData) {
                        if (part.mimeType.startsWith("image/")) {
                            android.util.Log.d("ApiClient", "找到输入图片: mimeType=${part.mimeType}, base64长度=${part.base64Data.length}")
                            images.add(Pair(part.base64Data, part.mimeType))
                        }
                    }
                }
                // 只处理最后一条用户消息中的图片
                if (images.isNotEmpty()) {
                    break
                }
            }
        }
        
        if (images.isEmpty()) {
            android.util.Log.d("ApiClient", "未找到输入图片")
        } else {
            android.util.Log.i("ApiClient", "共找到 ${images.size} 张输入图片")
        }
        
        return images
    }
}

/**
 * 将"当前会话的最后一条 user 消息"与图片附件整合为"直连可消费的多模态消息"
 * - Gemini: contents.parts -> text + inline_data
 * - OpenAI-compat: messages[].content -> [{"type":"text"}, {"type":"image_url"...}]
 * 实现方式：把最后一条 user SimpleTextApiMessage 升级为 PartsApiMessage 并注入 InlineData
 */
