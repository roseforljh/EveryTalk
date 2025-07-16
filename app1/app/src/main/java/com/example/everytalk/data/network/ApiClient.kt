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
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import com.example.everytalk.data.DataClass.GithubRelease
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.File
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.utils.io.streams.asInput
import kotlinx.coroutines.CancellationException as CoroutineCancellationException
import java.util.concurrent.atomic.AtomicBoolean

object ApiClient {

    private const val GITHUB_API_BASE_URL = "https://api.github.com/"

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
            }
        }
    }

    private lateinit var client: HttpClient
    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            val cacheFile = File(context.cacheDir, "ktor_http_cache")
            client = HttpClient(Android) {
                install(ContentNegotiation) {
                    json(jsonParser)
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 300_000
                    connectTimeoutMillis = 20_000
                    socketTimeoutMillis = 300_000
                }
                install(HttpCache) {
                    publicStorage(FileStorage(cacheFile))
                }
            }
            isInitialized = true
        }
    }

    private val backendProxyUrls = listOf(
        //"http://192.168.0.100:7860/chat", // Attempting with a common LAN IP
        "https://kunze999-backend.hf.space/chat",
        //"https://uoseegiydwgx.us-west-1.clawcloudrun.com/chat",
        //"https://dbykoynmqkkq.cloud.cloudcat.one:443/chat",
        //"https://backdaitalk.onrender.com/chat"
    )


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


            client.preparePost(backendProxyUrl) {
                accept(ContentType.Text.EventStream)
                timeout {
                    requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                    connectTimeoutMillis = 20_000
                    socketTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                }
                setBody(multiPartData)

            }.execute { receivedResponse ->
                response = receivedResponse

                if (response?.status?.isSuccess() != true) {
                    val errorBody = try {
                        response?.bodyAsText() ?: "(无错误响应体)"
                    } catch (e: Exception) {
                        "(读取错误响应体失败: ${e.message})"
                    }
                    throw IOException("代理错误 ($backendProxyUrl): ${response?.status?.value} - $errorBody")
                }

                val channel: ByteReadChannel = response?.bodyAsChannel() ?: run {
                    throw IOException("获取来自 $backendProxyUrl 的响应体通道失败。")
                }

                try {
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: continue

                        if (line.isEmpty()) continue
                        
                        val processedLine = if (line.startsWith("data:")) {
                            line.substring(5).trim()
                        } else {
                            line
                        }

                        if (processedLine.isEmpty() || processedLine.startsWith(":")) {
                            continue
                        }

                        if (processedLine.equals("[DONE]", ignoreCase = true)) {
                            channel.cancel(CoroutineCancellationException("[DONE] marker received"))
                            break
                        }

                        try {
                            val appEvent = jsonParser.decodeFromString<AppStreamEvent>(
                                processedLine
                            )
                            val sendResult = trySend(appEvent)
                            if (!sendResult.isSuccess) {
                                if (!isClosedForSend && !channel.isClosedForRead) {
                                    channel.cancel(CoroutineCancellationException("Downstream channel closed: $sendResult"))
                                }
                                return@execute
                            }
                        } catch (e: SerializationException) {
                            android.util.Log.e("ApiClientStream", "Serialization failed for line: '$processedLine'", e)
                        } catch (e: Exception) {
                            android.util.Log.e("ApiClientStream", "Exception during event processing for line: '$processedLine'", e)
                        }
                    }
                } catch (e: IOException) {
                    if (!isClosedForSend) throw e
                }
                catch (e: CoroutineCancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isClosedForSend) throw IOException(
                        "意外流错误 ($backendProxyUrl): ${e.message}",
                        e
                    )
                } finally {
                }
            }
        } catch (e: CoroutineCancellationException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            throw IOException("请求超时 ($backendProxyUrl): ${e.message}", e)
        }
        catch (e: ResponseException) {
            val errorBody = try {
                e.response.bodyAsText()
            } catch (ex: Exception) {
                "(无法读取错误体)"
            }; throw IOException(
                "HTTP 错误 ($backendProxyUrl): ${e.response.status} - $errorBody",
                e
            )
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            val statusInfo = response?.status?.let { " (状态: ${it.value})" }
                ?: ""; throw IOException(
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
                                send(event)
                            }
                    } catch (e: Exception) {
                        if (e !is CoroutineCancellationException) {
                            android.util.Log.e("ApiClient", "Error connecting to $url", e)
                            synchronized(errors) {
                                errors.add(e)
                            }
                            
                        }
                    }
                }
                jobs.add(job)
            }
        }

        if (!winnerFound.get()) {
            if (errors.isNotEmpty()) {
                throw errors.last()
            } else {
                throw IOException("无法连接到任何可用的后端服务器，且没有报告具体错误。")
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