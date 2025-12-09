package com.android.everytalk.data.network

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ImageGenerationResponse
import com.android.everytalk.data.DataClass.GitHubRelease
import com.android.everytalk.models.SelectedMediaItem
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.cache.storage.FileStorage
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.ktor.utils.io.streams.asInput
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import android.graphics.Bitmap.CompressFormat
import android.util.Base64
import kotlinx.coroutines.CancellationException as CoroutineCancellationException

@Serializable
data class ModelInfo(val id: String)

@Serializable
data class ModelsResponse(val data: List<ModelInfo>)

object ApiClient {
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
                                com.android.everytalk.data.DataClass.WebSearchResult(
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
                "code_execution_result" -> {
                    val codeExecutionOutput = jsonObject["codeExecutionOutput"]?.jsonPrimitive?.contentOrNull
                    val codeExecutionOutcome = jsonObject["codeExecutionOutcome"]?.jsonPrimitive?.contentOrNull
                    val imageUrl = jsonObject["imageUrl"]?.jsonPrimitive?.contentOrNull
                    AppStreamEvent.CodeExecutionResult(codeExecutionOutput, codeExecutionOutcome, imageUrl)
                }
                "code_executable" -> {
                    val executableCode = jsonObject["executableCode"]?.jsonPrimitive?.contentOrNull
                    val codeLanguage = jsonObject["codeLanguage"]?.jsonPrimitive?.contentOrNull
                    AppStreamEvent.CodeExecutable(executableCode, codeLanguage)
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
                polymorphic(com.android.everytalk.data.DataClass.AbstractApiMessage::class) {
                    subclass(com.android.everytalk.data.DataClass.SimpleTextApiMessage::class)
                    subclass(com.android.everytalk.data.DataClass.PartsApiMessage::class)
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
                    subclass(AppStreamEvent.CodeExecutionResult::class)
                    subclass(AppStreamEvent.CodeExecutable::class)
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
            // 根据构建类型自动选择配置
            val cacheFile = File(context.cacheDir, "ktor_http_cache")
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
                install(HttpCache) {
                    // 更积极的缓存策略
                    publicStorage(FileStorage(cacheFile))
                    privateStorage(FileStorage(File(context.cacheDir, "ktor_private_cache")))
                }
                // WebSocket 支持（用于阿里云实时语音识别等）
                install(WebSockets) {
                    pingIntervalMillis = 30_000  // 30秒心跳
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
                val raw = channel.readUTF8Line()
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
                                    "处理数据块 (长度=${chunk.length}): '${chunk.take(100)}${if (chunk.length > 100) "..." else ""}'"
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
                                        // 顺序挂起发送，确保不丢尾部事件且保持事件顺序
                                        trySend(appEvent)
                                    } else {
                                        android.util.Log.w("ApiClient", "无法解析的流数据块: '$chunk'")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("ApiClientStream", "Exception during event processing for chunk: '$chunk'", e)
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
                            android.util.Log.d("ApiClient", "SSE data行: '$dataContent'")
                            if (lineBuffer.isNotEmpty()) lineBuffer.append('\n')
                            lineBuffer.append(dataContent)
                        }
                        line.startsWith("event:") -> {
                            // 如需按event类型区分可在此记录，但当前后端仅用 data
                            android.util.Log.d("ApiClient", "SSE event行: '${line.substring(6).trim()}'")
                        }
                        else -> {
                            // 仅当看起来确为JSON对象/数组时，才尝试非SSE直解析；否则忽略，避免再次因“:ok ...”等抛错
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
     * 根据渠道类型自动选择 GeminiDirectClient 或 OpenAIDirectClient
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun streamChatResponse(
        request: ChatRequest,
        attachments: List<SelectedMediaItem>,
        applicationContext: Context
    ): Flow<AppStreamEvent> = channelFlow {
        android.util.Log.i("ApiClient", "🔄 强制直连模式启动")

        // 1. 针对"默认"提供商进行配置注入（若字段为空）
        // 这样能确保即使是旧数据或未完整初始化的配置，也能使用 BuildConfig 中的默认值
        // 默认模型使用 Gemini 渠道，以支持 Google Search 原生工具
        val effectiveRequest = if (request.provider == "默认" || request.provider == "default") {
            request.copy(
                apiAddress = request.apiAddress.takeIf { !it.isNullOrBlank() }
                    ?: com.android.everytalk.BuildConfig.DEFAULT_TEXT_API_URL,
                apiKey = request.apiKey.takeIf { it.isNotBlank() }
                    ?: com.android.everytalk.BuildConfig.DEFAULT_TEXT_API_KEY,
                // 强制指定默认提供商使用 Gemini 渠道，以确保启用原生工具
                channel = "Gemini"
            )
        } else {
            request
        }

        // 2. 判断渠道类型
        // 修正逻辑：只有明确指定为 Gemini 渠道，或者模型名含 gemini 且非 OpenAI 兼容渠道时，才走 Gemini 原生协议
        // 这样可以支持通过 OpenAI 兼容接口（如 OneAPI/NewAPI）提供的 Gemini 模型
        val isGeminiRequest = (effectiveRequest.provider == "gemini" ||
                effectiveRequest.channel.lowercase().contains("gemini")) &&
                !effectiveRequest.channel.lowercase().contains("openai") ||
                (effectiveRequest.model.contains("gemini", ignoreCase = true) &&
                        !effectiveRequest.channel.lowercase().contains("openai") &&
                        effectiveRequest.provider != "默认" && effectiveRequest.provider != "default")

        // 构建多模态请求（注入图片附件）
        val requestForDirect = try {
            buildDirectMultimodalRequest(effectiveRequest, attachments, applicationContext)
        } catch (e: Exception) {
            android.util.Log.w("ApiClient", "构建直连多模态请求失败，使用原始请求: ${e.message}")
            effectiveRequest
        }
        
        try {
            when {
                isGeminiRequest -> {
                    android.util.Log.i("ApiClient", "🔄 使用 Gemini 直连模式 (model=${effectiveRequest.model})")
                    GeminiDirectClient.streamChatDirect(client, requestForDirect)
                        .collect { event -> send(event) }
                    android.util.Log.i("ApiClient", "✅ Gemini 直连完成")
                }
                else -> {
                    // OpenAI 兼容模式（包括 OpenAI、Azure、其他兼容 API）
                    android.util.Log.i("ApiClient", "🔄 使用 OpenAI 兼容直连模式 (model=${effectiveRequest.model})")
                    OpenAIDirectClient.streamChatDirect(client, requestForDirect)
                        .collect { event -> send(event) }
                    android.util.Log.i("ApiClient", "✅ OpenAI 兼容直连完成")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "❌ 直连失败", e)
            send(AppStreamEvent.Error("直连失败: ${e.message}", null))
            send(AppStreamEvent.Finish("direct_connection_failed"))
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

    suspend fun getLatestRelease(): GitHubRelease {
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
                    }.body<GitHubRelease>()
                    
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

    suspend fun getModels(apiUrl: String, apiKey: String, channel: String? = null): List<String> {
        if (!isInitialized) {
            throw IllegalStateException("ApiClient not initialized. Call initialize() first.")
        }
    
        // 统一去掉尾部 '#' 并清理 apiKey 中的换行符和多余空白
        val baseForModels = apiUrl.trim().removeSuffix("#")
        val cleanedApiKey = apiKey.trim().replace(Regex("[\\r\\n\\s]+"), "")
        val parsedUri = try { java.net.URI(baseForModels) } catch (_: Exception) { null }
        val hostLower = parsedUri?.host?.lowercase()
        val scheme = parsedUri?.scheme ?: "https"
    
        // 优化：当渠道为Gemini或为Google Gemini官方域名时，使用Gemini格式的API
        val isGeminiChannel = channel?.lowercase()?.trim() == "gemini"
        val isGoogleOfficialDomain = hostLower == "generativelanguage.googleapis.com" ||
                (hostLower?.endsWith("googleapis.com") == true &&
                 baseForModels.contains("generativelanguage", ignoreCase = true))
    
        // 对于 Gemini 反代，判断是否需要使用 Bearer Token 而非 ?key= 查询参数
        // 大多数反代服务器期望 Authorization: Bearer 头，而非 Google 官方的 ?key= 格式
        val useKeyQueryParam = isGoogleOfficialDomain // 只有 Google 官方域名才使用 ?key=
    
        val url = when {
            // 只有当是Google官方域名时才强制使用官方地址 + ?key=
            isGoogleOfficialDomain -> {
                val googleUrl = "$scheme://generativelanguage.googleapis.com/v1beta/models?key=$cleanedApiKey"
                android.util.Log.i("ApiClient", "检测到 Google Gemini 官方域名，使用官方模型列表端点: ${googleUrl.replace(cleanedApiKey, "***")}")
                googleUrl
            }
            // Gemini渠道但非官方域名(如反代),使用用户提供的地址 + Gemini路径，Bearer Token 认证
            isGeminiChannel -> {
                val geminiProxyUrl = "$baseForModels/v1beta/models"
                android.util.Log.i("ApiClient", "检测到 Gemini 渠道(反代)，使用代理地址 + Bearer Token: $geminiProxyUrl")
                geminiProxyUrl
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
                // Google 官方域名使用 ?key=（已在 URL 中）；其余所有情况（包括 Gemini 反代）使用 Bearer Token
                if (!useKeyQueryParam) {
                    header(HttpHeaders.Authorization, "Bearer $cleanedApiKey")
                }
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.UserAgent, "KunTalkwithAi/1.0")
            }
    
            val responseBody = response.bodyAsText()
    
            // Gemini格式响应优先解析(官方或反代)：{"models":[{"name":"models/gemini-1.5-pro", ...}, ...]}
            if (isGoogleOfficialDomain || isGeminiChannel) {
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
        android.util.Log.d("ApiClient", "Image generation request - Model: ${effectiveImgReq.model}")
        android.util.Log.d("ApiClient", "Image generation request - API Address: ${effectiveImgReq.apiAddress}")
        android.util.Log.d("ApiClient", "Image generation request - API Key: ${effectiveImgReq.apiKey.take(10)}...")
        android.util.Log.d("ApiClient", "Image generation request - Prompt: ${effectiveImgReq.prompt.take(100)}...")
        
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
                else -> ImageGenerationDirectClient.generateImageOpenAI(client, effectiveImgReq)
            }
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "❌ 图像生成直连失败", e)
            throw IOException("图像生成直连失败: ${e.message}", e)
        }
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
 * 将“当前会话的最后一条 user 消息”与图片附件整合为“直连可消费的多模态消息”
 * - Gemini: contents.parts -> text + inline_data
 * - OpenAI-compat: messages[].content -> [{"type":"text"}, {"type":"image_url"...}]
 * 实现方式：把最后一条 user SimpleTextApiMessage 升级为 PartsApiMessage 并注入 InlineData
 */
private suspend fun buildDirectMultimodalRequest(
    request: ChatRequest,
    attachments: List<com.android.everytalk.models.SelectedMediaItem>,
    context: Context
): ChatRequest {
    val inlineParts = mutableListOf<com.android.everytalk.data.DataClass.ApiContentPart.InlineData>()
    val documentTexts = mutableListOf<String>()

    attachments.forEach { item ->
        when (item) {
            is com.android.everytalk.models.SelectedMediaItem.ImageFromUri -> {
                val mime = context.contentResolver.getType(item.uri) ?: "image/jpeg"
                val bytes = runCatching {
                    context.contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
                }.getOrNull()
                if (bytes != null && isImageMime(mime)) {
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    inlineParts.add(
                        com.android.everytalk.data.DataClass.ApiContentPart.InlineData(
                            base64Data = b64,
                            mimeType = mime
                        )
                    )
                }
            }
            is com.android.everytalk.models.SelectedMediaItem.ImageFromBitmap -> {
                val hasAlpha = item.bitmap?.hasAlpha() == true
                val mime = if (hasAlpha) "image/png" else "image/jpeg"
                val baos = java.io.ByteArrayOutputStream()
                val ok = item.bitmap?.compress(
                    if (hasAlpha) CompressFormat.PNG else CompressFormat.JPEG,
                    if (hasAlpha) 100 else 85,
                    baos
                ) == true
                if (ok) {
                    val bytes = baos.toByteArray()
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    inlineParts.add(
                        com.android.everytalk.data.DataClass.ApiContentPart.InlineData(
                            base64Data = b64,
                            mimeType = mime
                        )
                    )
                }
            }
            is com.android.everytalk.models.SelectedMediaItem.Audio -> {
                // Audio item already contains base64 data
                val mime = item.mimeType ?: "audio/3gpp"
                inlineParts.add(
                    com.android.everytalk.data.DataClass.ApiContentPart.InlineData(
                        base64Data = item.data,
                        mimeType = mime
                    )
                )
            }
            is com.android.everytalk.models.SelectedMediaItem.GenericFile -> {
                val mime = item.mimeType ?: "application/octet-stream"
                if (isImageMime(mime) || isAudioMime(mime) || isVideoMime(mime)) {
                    val bytes = runCatching {
                        context.contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
                    }.getOrNull()
                    if (bytes != null) {
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        inlineParts.add(
                            com.android.everytalk.data.DataClass.ApiContentPart.InlineData(
                                base64Data = b64,
                                mimeType = mime
                            )
                        )
                    }
                } else {
                    // 尝试提取文档文本
                    val text = DocumentProcessor.extractText(context, item.uri, mime)
                    if (!text.isNullOrBlank()) {
                        val fileName = item.displayName ?: "Document"
                        documentTexts.add("--- Begin of document: $fileName ---\n$text\n--- End of document ---")
                    }
                }
            }
            else -> { /* ignore */ }
        }
    }

    if (inlineParts.isEmpty() && documentTexts.isEmpty()) return request

    val msgs = request.messages.toMutableList()
    val lastUserIdx = msgs.indexOfLast { it.role == "user" }
    if (lastUserIdx < 0) return request

    val lastMsg = msgs[lastUserIdx]
    
    // 构造文档文本部分
    val documentContentParts = documentTexts.map { 
        com.android.everytalk.data.DataClass.ApiContentPart.Text(it) 
    }

    val newParts = when (lastMsg) {
        is com.android.everytalk.data.DataClass.PartsApiMessage -> {
            val existing = lastMsg.parts.toMutableList()
            // 先放文档，再放原消息，最后放多媒体
            existing.addAll(0, documentContentParts)
            existing.addAll(inlineParts)
            existing.toList()
        }
        is com.android.everytalk.data.DataClass.SimpleTextApiMessage -> {
            val list = mutableListOf<com.android.everytalk.data.DataClass.ApiContentPart>()
            list.addAll(documentContentParts)
            if (lastMsg.content.isNotBlank()) {
                list.add(com.android.everytalk.data.DataClass.ApiContentPart.Text(lastMsg.content))
            }
            list.addAll(inlineParts)
            list.toList()
        }
        else -> {
            val list = mutableListOf<com.android.everytalk.data.DataClass.ApiContentPart>()
            list.addAll(documentContentParts)
            list.addAll(inlineParts)
            list.toList()
        }
    }

    val upgraded = com.android.everytalk.data.DataClass.PartsApiMessage(
        role = "user",
        parts = newParts
    )
    msgs[lastUserIdx] = upgraded
    return request.copy(messages = msgs)
}

private fun isImageMime(mime: String?): Boolean {
    if (mime == null) return false
    val m = mime.lowercase()
    return m.startsWith("image/")
}

private fun isAudioMime(mime: String?): Boolean {
    if (mime == null) return false
    val m = mime.lowercase()
    return m.startsWith("audio/")
}

private fun isVideoMime(mime: String?): Boolean {
    if (mime == null) return false
    val m = mime.lowercase()
    return m.startsWith("video/")
}