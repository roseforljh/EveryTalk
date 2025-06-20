package com.example.everytalk.data.network

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.everytalk.data.DataClass.ChatRequest
import com.example.everytalk.data.DataClass.AppStreamEvent
import com.example.everytalk.model.SelectedMediaItem
import io.ktor.client.*
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
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.File
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException as CoroutineCancellationException
import java.util.concurrent.atomic.AtomicBoolean

object ApiClient {

    private val jsonParser: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
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
        "http://backendcentrol.everytalk.dpdns.org:8880/chat",
        "http://backendwest.everytalk.dpdns.org:2052/chat",
        "http://backend.everytalk.dpdns.org:8080/chat",
        "https://backdatalk-717323967862.europe-west1.run.app/chat",
        "https://kunze999-backendai.hf.space/chat",
    )

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

                    val hasInlineData = chatRequest.messages.any { msg ->
                        (msg as? com.example.everytalk.data.DataClass.PartsApiMessage)?.parts?.any { part ->
                            part is com.example.everytalk.data.DataClass.ApiContentPart.InlineData
                        } == true
                    }

                    if (attachmentsToUpload.isNotEmpty() || hasInlineData) {
                        if (attachmentsToUpload.isEmpty()) {
                            append(
                                key = "uploaded_documents",
                                value = ByteArray(0),
                                headers = Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"placeholder.bin\"")
                                    append(HttpHeaders.ContentType, ContentType.Application.OctetStream)
                                }
                            )
                        } else {
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
                                }

                                if (fileUri != null) {
                                    val finalMimeType = mimeTypeFromMediaItem
                                        ?: ContentType.Application.OctetStream.toString()
                                    try {
                                        applicationContext.contentResolver.openInputStream(fileUri)
                                            ?.use { inputStream ->
                                                val fileBytes = inputStream.readBytes()
                                                append(
                                                    key = "uploaded_documents",
                                                    value = fileBytes,
                                                    headers = Headers.build {
                                                        append(
                                                            HttpHeaders.ContentDisposition,
                                                            "filename=\"$originalFileNameFromMediaItem\""
                                                        )
                                                        append(HttpHeaders.ContentType, finalMimeType)
                                                    }
                                                )
                                            }
                                    } catch (e: Exception) {
                                    }
                                }
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

                val buf = ByteArray(1024 * 8)
                val sb = StringBuilder()
                try {
                    while (isActive && !channel.isClosedForRead) {
                        val bytesRead = channel.readAvailable(buf, 0, buf.size)
                        if (bytesRead == -1) {
                            break
                        }
                        if (bytesRead > 0) {
                            sb.append(String(buf, 0, bytesRead, Charsets.UTF_8))
                            var lineBreakIndex: Int
                            while (sb.indexOf("\n").also { lineBreakIndex = it } != -1) {
                                var line = sb.substring(0, lineBreakIndex).trim()
                                sb.delete(0, lineBreakIndex + 1)

                                if (line.isEmpty()) continue
                                if (line.startsWith("data:")) line = line.substring(5).trim()
                                else if (line.startsWith(":")) {
                                    continue
                                }


                                if (line.isNotEmpty()) {
                                    try {
                                        if (line.equals(
                                                "[DONE]",
                                                ignoreCase = true
                                            )
                                        ) {
                                            channel.cancel(CoroutineCancellationException("[DONE] marker received"))
                                            break
                                        }
                                        val appEvent = jsonParser.decodeFromString(
                                            AppStreamEvent.serializer(),
                                            line
                                        )
                                        val sendResult = trySend(appEvent)
                                        if (!sendResult.isSuccess) {
                                            if (!isClosedForSend && !channel.isClosedForRead) channel.cancel(
                                                CoroutineCancellationException("下游 ($backendProxyUrl) 已关闭: $sendResult")
                                            )
                                            return@execute
                                        }
                                    } catch (e: SerializationException) {
                                    } catch (e: Exception) {
                                    }
                                }
                            }
                        } else {
                            yield()
                        }
                    }
                    if (sb.isNotEmpty() && isActive && !isClosedForSend) {
                        var line = sb.toString().trim()
                        if (line.startsWith("data:")) line = line.substring(5).trim()
                        if (line.isNotEmpty() && !line.equals("[DONE]", ignoreCase = true)) {
                            try {
                                val appEvent =
                                    jsonParser.decodeFromString(AppStreamEvent.serializer(), line)
                                trySend(appEvent)
                            } catch (e: Exception) {
                            }
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
                                    // We are the winner, cancel other jobs
                                    jobs.forEach {
                                        if (it != coroutineContext[Job]) {
                                            it.cancel(CoroutineCancellationException("Another stream responded faster."))
                                        }
                                    }
                                }
                                // Only the winner will proceed to send events
                                send(event)
                            }
                    } catch (e: Exception) {
                        if (e !is CoroutineCancellationException) {
                            synchronized(errors) {
                                errors.add(e)
                            }
                        }
                    }
                }
                jobs.add(job)
            }
        }

        // After supervisorScope, all jobs are complete (successfully, failed, or cancelled)
        if (!winnerFound.get()) {
            if (errors.isNotEmpty()) {
                throw errors.last()
            } else {
                // This can happen if all requests are cancelled before producing anything,
                // or if there are no URLs. The empty check is at the top.
                throw IOException("无法连接到任何可用的后端服务器，且没有报告具体错误。")
            }
        }
    }.buffer(Channel.BUFFERED).flowOn(Dispatchers.IO)
}