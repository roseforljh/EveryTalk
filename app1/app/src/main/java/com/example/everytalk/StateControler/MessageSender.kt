package com.example.everytalk.StateControler

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.example.everytalk.data.DataClass.AbstractApiMessage
import com.example.everytalk.data.DataClass.ApiContentPart
import com.example.everytalk.data.DataClass.ChatRequest
import com.example.everytalk.data.DataClass.PartsApiMessage
import com.example.everytalk.data.DataClass.SimpleTextApiMessage
import com.example.everytalk.data.DataClass.Message as UiMessage
import com.example.everytalk.data.DataClass.Sender as UiSender
import com.example.everytalk.data.DataClass.ThinkingConfig
import com.example.everytalk.data.DataClass.GenerationConfig
import com.example.everytalk.ui.screens.MainScreen.chat.SelectedMedia
import com.example.everytalk.ui.screens.viewmodel.HistoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Contextual

class MessageSender(
    private val application: Application,
    private val viewModelScope: CoroutineScope,
    private val stateHolder: ViewModelStateHolder,
    private val apiHandler: ApiHandler,
    private val historyManager: HistoryManager,
    private val showSnackbar: (String) -> Unit,
    private val triggerScrollToBottom: () -> Unit
) {

    companion object {
        private const val TAG_MESSAGE_SENDER = "MessageSender"
        private const val MAX_IMAGE_SIZE_BYTES = 4 * 1024 * 1024 // 4MB
        private const val TARGET_IMAGE_WIDTH = 1024
        private const val TARGET_IMAGE_HEIGHT = 1024
        private const val JPEG_COMPRESSION_QUALITY = 80
        private const val CHAT_IMAGES_SUBDIR = "chat_images" // 图片保存的子目录名
    }

    // --- 辅助函数：复制 URI 指向的文件到应用内部存储，并返回新的 FileProvider URI ---
    private suspend fun copyUriToAppInternalStorage(
        context: Context,
        sourceUri: Uri,
        messageIdHint: String,
        imageIndex: Int
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val extension =
                    context.contentResolver.getType(sourceUri)?.substringAfterLast('/') ?: "jpg"
                val timeStamp: String =
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val uniqueFileName = "IMG_${messageIdHint}_${imageIndex}_${timeStamp}_${
                    UUID.randomUUID().toString().take(4)
                }.$extension"

                val imageDir = File(context.filesDir, CHAT_IMAGES_SUBDIR)
                if (!imageDir.exists()) {
                    imageDir.mkdirs()
                }
                val destinationFile = File(imageDir, uniqueFileName)

                context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d(
                    TAG_MESSAGE_SENDER,
                    "Image copied to internal storage: ${destinationFile.absolutePath}"
                )
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    destinationFile
                ).toString()
            } catch (e: Exception) {
                Log.e(
                    TAG_MESSAGE_SENDER,
                    "Error copying URI to app internal storage: $sourceUri",
                    e
                )
                null
            }
        }
    }

    // --- 辅助函数：将 Bitmap 保存到应用内部存储，并返回 FileProvider URI ---
    private suspend fun saveBitmapToAppInternalStorage(
        context: Context,
        bitmap: Bitmap,
        messageIdHint: String,
        imageIndex: Int
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                var processedBitmap = bitmap
                if (processedBitmap.isRecycled) {
                    Log.w(
                        TAG_MESSAGE_SENDER,
                        "saveBitmapToAppInternalStorage: Bitmap is already recycled."
                    )
                    return@withContext null
                }
                var needsRecycleLocal = false
                if (processedBitmap.width > TARGET_IMAGE_WIDTH || processedBitmap.height > TARGET_IMAGE_HEIGHT) {
                    val scale = Math.min(
                        TARGET_IMAGE_WIDTH.toFloat() / processedBitmap.width,
                        TARGET_IMAGE_HEIGHT.toFloat() / processedBitmap.height
                    )
                    val newWidth = (processedBitmap.width * scale).toInt()
                    val newHeight = (processedBitmap.height * scale).toInt()
                    if (newWidth > 0 && newHeight > 0) {
                        val scaledBitmap =
                            Bitmap.createScaledBitmap(processedBitmap, newWidth, newHeight, true)
                        processedBitmap = scaledBitmap
                        needsRecycleLocal = true
                    }
                }

                val outputStream = ByteArrayOutputStream()
                val actualMimeType: String
                val fileExtension: String
                val compressFormat = if (processedBitmap.hasAlpha()) {
                    actualMimeType = "image/png"; fileExtension = "png"; Bitmap.CompressFormat.PNG
                } else {
                    actualMimeType = "image/jpeg"; fileExtension = "jpg"; Bitmap.CompressFormat.JPEG
                }
                processedBitmap.compress(compressFormat, JPEG_COMPRESSION_QUALITY, outputStream)
                val bytes = outputStream.toByteArray()

                if (needsRecycleLocal && !processedBitmap.isRecycled && processedBitmap != bitmap) {
                    // processedBitmap.recycle() // 如果 scaledBitmap 是新创建的，在这里回收它。需要小心确保不再被引用。
                }

                if (bytes.size > MAX_IMAGE_SIZE_BYTES) {
                    Log.w(
                        TAG_MESSAGE_SENDER,
                        "Bitmap data too large after compression: ${bytes.size} bytes."
                    )
                    return@withContext null
                }

                val timeStamp: String =
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val uniqueFileName = "BMP_${messageIdHint}_${imageIndex}_${timeStamp}_${
                    UUID.randomUUID().toString().take(4)
                }.$fileExtension"
                val imageDir = File(context.filesDir, CHAT_IMAGES_SUBDIR)
                if (!imageDir.exists()) {
                    imageDir.mkdirs()
                }
                val destinationFile = File(imageDir, uniqueFileName)

                FileOutputStream(destinationFile).use { fos -> fos.write(bytes) }

                Log.d(
                    TAG_MESSAGE_SENDER,
                    "Bitmap saved to internal storage: ${destinationFile.absolutePath}"
                )
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    destinationFile
                ).toString()

            } catch (e: Exception) {
                Log.e(TAG_MESSAGE_SENDER, "Error saving Bitmap to app internal storage", e)
                null
            }
        }
    }


    fun sendMessage(
        messageText: String,
        isFromRegeneration: Boolean = false,
        images: List<SelectedMedia> = emptyList() // 接收原始的 SelectedMedia 列表
    ) {
        val textToActuallySend = messageText.trim()
        if (textToActuallySend.isBlank() && images.isEmpty()) {
            viewModelScope.launch { showSnackbar("请输入消息内容或选择图片") }
            return
        }

        val currentConfig = stateHolder._selectedApiConfig.value ?: run {
            viewModelScope.launch { showSnackbar("请先选择 API 配置") }
            return
        }

        viewModelScope.launch { // 主协程，用于处理 UI 更新和启动 IO 任务
            val modelIsGeminiType = currentConfig.model.lowercase().startsWith("gemini")
            val shouldUsePartsApiMessage =
                currentConfig.provider.equals("google", ignoreCase = true) && modelIsGeminiType
            val providerForRequestBackend = currentConfig.provider

            Log.d(
                TAG_MESSAGE_SENDER,
                "sendMessage -> Config: Model='${currentConfig.model}', UI Provider='${currentConfig.provider}'. Effective Backend Provider Target='${providerForRequestBackend}', ModelIsGeminiType=$modelIsGeminiType, ShouldUsePartsMessageBody=$shouldUsePartsApiMessage. Text='${
                    textToActuallySend.take(50)
                }', Original Images=${images.size}"
            )

            // --- 1. 处理图片，获取持久化的 URI 列表 ---
            val persistentImageUriStrings = mutableListOf<String>()
            if (images.isNotEmpty()) {
                val tempMessageIdForNaming = UUID.randomUUID().toString().take(8) // 用于文件名
                images.forEachIndexed { index, media ->
                    val persistentUriStr: String? = when (media) {
                        is SelectedMedia.FromUri -> {
                            // 对于相机拍摄的 FileProvider URI 或相册选择的 content URI，都复制到内部存储
                            copyUriToAppInternalStorage(
                                application,
                                media.uri,
                                tempMessageIdForNaming,
                                index
                            )
                        }

                        is SelectedMedia.FromBitmap -> {
                            saveBitmapToAppInternalStorage(
                                application,
                                media.bitmap,
                                tempMessageIdForNaming,
                                index
                            )
                        }
                    }
                    persistentUriStr?.let {
                        persistentImageUriStrings.add(it)
                    } ?: run {
                        showSnackbar("处理部分图片失败，该图片将不会发送。")
                    }
                }
            }
            Log.d(
                TAG_MESSAGE_SENDER,
                "Processed images. Persistent URIs count: ${persistentImageUriStrings.size}"
            )


            // --- 2. 创建 UI Message 对象，使用持久化的 URI ---
            val newUserMessageForUi = UiMessage(
                text = textToActuallySend,
                sender = UiSender.User,
                contentStarted = true,
                imageUrls = persistentImageUriStrings.ifEmpty { null } // 使用持久化 URI
            )

            // 在主线程更新 UI
            withContext(Dispatchers.Main.immediate) {
                stateHolder.messageAnimationStates[newUserMessageForUi.id] = true
                stateHolder.messages.add(newUserMessageForUi)
                if (!isFromRegeneration) stateHolder._text.value = ""
                triggerScrollToBottom()
            }

            // --- 3. 构建发送给后端的 API 消息 (IO 线程) ---
            withContext(Dispatchers.IO) {
                val apiMessagesForBackend = mutableListOf<AbstractApiMessage>()
                val maxHistoryMessages = 20
                var historyMessageCount = 0
                // 获取当前消息列表的快照。注意：newUserMessageForUi 此时应该已在 stateHolder.messages 中
                val messagesInChatUiSnapshot = stateHolder.messages.toList()

                // 构建历史消息列表 (不包含当前发送的用户消息)
                val historyUiMessages =
                    if (messagesInChatUiSnapshot.isNotEmpty() && messagesInChatUiSnapshot.lastOrNull()?.id == newUserMessageForUi.id) {
                        messagesInChatUiSnapshot.dropLast(1) // 排除最后一条（当前发送的）
                    } else {
                        // 如果当前消息不在列表末尾，或者列表为空，则取所有历史消息
                        messagesInChatUiSnapshot
                    }


                for (uiMsg in historyUiMessages.asReversed()) {
                    if (historyMessageCount >= maxHistoryMessages) break
                    val roleForHistory = when (uiMsg.sender) {
                        UiSender.User -> "user"; UiSender.AI -> "assistant"; UiSender.System -> "system"; UiSender.Tool -> "tool"; else -> null
                    } ?: continue

                    if (shouldUsePartsApiMessage) { // Google Gemini 官方路径
                        val historyParts = mutableListOf<ApiContentPart>()
                        if (uiMsg.text.isNotBlank()) historyParts.add(ApiContentPart.Text(uiMsg.text.trim()))

                        // 历史消息中的图片也需要转为 InlineData 发送给后端
                        uiMsg.imageUrls?.forEach { persistedUrl ->
                            try {
                                application.contentResolver.openInputStream(Uri.parse(persistedUrl))
                                    ?.use { inputStream ->
                                        val bytes = inputStream.readBytes()
                                        if (bytes.size <= MAX_IMAGE_SIZE_BYTES) {
                                            val base64Data =
                                                Base64.encodeToString(bytes, Base64.NO_WRAP)
                                            val mimeType = application.contentResolver.getType(
                                                Uri.parse(persistedUrl)
                                            ) ?: "image/jpeg"
                                            historyParts.add(
                                                ApiContentPart.InlineData(
                                                    base64Data,
                                                    mimeType
                                                )
                                            )
                                        } else {
                                            Log.w(
                                                TAG_MESSAGE_SENDER,
                                                "History image too large, skipping: $persistedUrl"
                                            )
                                        }
                                    }
                            } catch (e: Exception) {
                                Log.e(
                                    TAG_MESSAGE_SENDER,
                                    "Failed to process history image URI $persistedUrl",
                                    e
                                )
                            }
                        }
                        if (historyParts.isNotEmpty()) apiMessagesForBackend.add(
                            0,
                            PartsApiMessage(
                                role = roleForHistory,
                                parts = historyParts,
                                name = uiMsg.name
                            )
                        )
                    } else { // 非 Google Gemini 官方路径
                        // For SimpleTextApiMessage, only text and possibly tool calls are sent. Images are ignored.
                        val historyContent = uiMsg.text.trim()
                        if (historyContent.isNotBlank()) {
                            apiMessagesForBackend.add(
                                0,
                                SimpleTextApiMessage(
                                    role = roleForHistory,
                                    content = historyContent,
                                    name = uiMsg.name
                                )
                            )
                        } else if (uiMsg.sender == UiSender.Tool && uiMsg.name != null && uiMsg.text.isNotBlank()) {
                            // If it's a tool message, ensure it's added even if content is technically empty but has a name/result.
                            apiMessagesForBackend.add(
                                0,
                                SimpleTextApiMessage(
                                    role = roleForHistory,
                                    content = uiMsg.text.trim(),
                                    name = uiMsg.name
                                )
                            )
                        } else if (uiMsg.sender == UiSender.System && uiMsg.text.isNotBlank()) {
                            apiMessagesForBackend.add(
                                0,
                                SimpleTextApiMessage(
                                    role = roleForHistory,
                                    content = uiMsg.text.trim()
                                )
                            )
                        } else {
                            Log.d(
                                TAG_MESSAGE_SENDER,
                                "Skipping history message without content for SimpleText path: ${
                                    uiMsg.id.take(8)
                                }"
                            )
                        }
                    }
                    // Only count if a message was actually added
                    if (apiMessagesForBackend.firstOrNull()?.role == roleForHistory ||
                        (apiMessagesForBackend.firstOrNull() as? PartsApiMessage)?.parts?.isNotEmpty() == true ||
                        (apiMessagesForBackend.firstOrNull() as? SimpleTextApiMessage)?.content?.isNotBlank() == true
                    ) {
                        historyMessageCount++
                    }
                }


                // 当前用户消息 (转换为后端格式)
                if (shouldUsePartsApiMessage) {
                    val currentUserParts = mutableListOf<ApiContentPart>()
                    if (textToActuallySend.isNotBlank()) {
                        currentUserParts.add(ApiContentPart.Text(textToActuallySend))
                    }
                    persistentImageUriStrings.forEach { persistedUriString ->
                        try {
                            val imageUri = Uri.parse(persistedUriString)
                            val mimeType = application.contentResolver.getType(imageUri)
                                ?: "application/octet-stream"
                            application.contentResolver.openInputStream(imageUri)
                                ?.use { inputStream ->
                                    val bytes = inputStream.readBytes()
                                    // No need for size check here as it was done during initial save/copy
                                    val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                    currentUserParts.add(
                                        ApiContentPart.InlineData(
                                            base64Data,
                                            mimeType
                                        )
                                    )
                                    Log.d(
                                        TAG_MESSAGE_SENDER,
                                        "Added InlineData to current message from persisted URI: $persistedUriString, mime: $mimeType"
                                    )
                                }
                                ?: throw IOException("Cannot open input stream for persisted URI: $persistedUriString")
                        } catch (e: Exception) {
                            Log.e(
                                TAG_MESSAGE_SENDER,
                                "Failed to process persisted image URI for current message: $persistedUriString",
                                e
                            )
                            withContext(Dispatchers.Main.immediate) { showSnackbar("发送图片失败。") }
                        }
                    }
                    if (currentUserParts.isNotEmpty()) {
                        apiMessagesForBackend.add(
                            PartsApiMessage(
                                role = "user",
                                parts = currentUserParts
                            )
                        )
                    } else if (textToActuallySend.isBlank()) {
                        Log.w(
                            TAG_MESSAGE_SENDER,
                            "PartsApiMessage: No text and no valid image parts after processing persisted URIs. Cancelling send."
                        )
                        return@withContext
                    }
                } else { // SimpleTextApiMessage
                    if (textToActuallySend.isNotBlank()) {
                        apiMessagesForBackend.add(
                            SimpleTextApiMessage(
                                role = "user",
                                content = textToActuallySend
                            )
                        )
                    } else {
                        Log.w(
                            TAG_MESSAGE_SENDER,
                            "SimpleTextApiMessage: Text content is blank. Cancelling send."
                        )
                        return@withContext
                    }
                    if (images.isNotEmpty()) { // Images provided but not sent on SimpleText path
                        withContext(Dispatchers.Main.immediate) { showSnackbar("当前模型不支持发送图片，图片将被忽略。") }
                    }
                }

                while (apiMessagesForBackend.size > maxHistoryMessages && apiMessagesForBackend.isNotEmpty()) {
                    apiMessagesForBackend.removeAt(0)
                }

                if (apiMessagesForBackend.isEmpty()) {
                    Log.w(TAG_MESSAGE_SENDER, "Final API message list is empty. Cancelling send.")
                    return@withContext
                }

                var thinkingConfigForRequest: ThinkingConfig? = null
                if (modelIsGeminiType && shouldUsePartsApiMessage) {
                    val requestThoughtsFromUi = true
                    if (requestThoughtsFromUi) {
                        var tempThinkingConfig = ThinkingConfig(includeThoughts = true)
                        if (currentConfig.model.lowercase().contains("flash")) {
                            tempThinkingConfig = tempThinkingConfig.copy(thinkingBudget = 1024)
                        }
                        thinkingConfigForRequest = tempThinkingConfig
                    }
                }
                val generationConfigForRequest = GenerationConfig(
                    temperature = currentConfig.temperature,
                    topP = currentConfig.topP,
                    maxOutputTokens = currentConfig.maxTokens,
                    thinkingConfig = thinkingConfigForRequest
                ).let {
                    if (it.temperature != null || it.topP != null || it.maxOutputTokens != null || it.thinkingConfig != null) it else null
                }

                // --- Custom Model Parameters ---
                var finalCustomModelParameters: Map<String, @Contextual Any>? = null
                val modelNameLower = currentConfig.model.lowercase()
                val apiAddressLower = currentConfig.address?.lowercase() ?: ""
                val currentWebSearchEnabled = stateHolder._isWebSearchEnabled.value

                if (modelNameLower.contains("qwen")) {
                    val params = mutableMapOf<String, Any>()
                    if (currentWebSearchEnabled) {
                        params["enable_search"] = true
                    }
                    if (apiAddressLower.contains("api.siliconflow.cn") ||
                        (currentConfig.provider.equals("openai compatible", ignoreCase = true) &&
                                !apiAddressLower.contains("api.openai.com") &&
                                !apiAddressLower.contains("googleapis.com"))
                    ) {
                        if (modelNameLower.contains("qwen2") || modelNameLower.contains("qwen1.5")) {
                            // params["enable_thinking"] = false // Depends on API/model
                        }
                    } else if (apiAddressLower.contains("dashscope.aliyuncs.com")) { /* no special logic needed for enable_search here */
                    }
                    if (params.isNotEmpty()) {
                        finalCustomModelParameters =
                            (finalCustomModelParameters ?: emptyMap()) + params
                    }
                }

                val topLevelUseWebSearch = if (currentWebSearchEnabled) true else null

                val chatRequest = ChatRequest(
                    messages = apiMessagesForBackend,
                    provider = providerForRequestBackend,
                    apiAddress = currentConfig.address,
                    apiKey = currentConfig.key,
                    model = currentConfig.model,
                    useWebSearch = topLevelUseWebSearch,
                    generationConfig = generationConfigForRequest,
                    customModelParameters = finalCustomModelParameters,
                    tools = null, toolChoice = null, customExtraBody = null
                )

                Log.i(
                    TAG_MESSAGE_SENDER,
                    "Prepared ChatRequest -> Provider: ${chatRequest.provider}, Model: ${chatRequest.model}, MsgCount: ${chatRequest.messages.size}, useWebSearch: ${chatRequest.useWebSearch}, GenConfig: ${chatRequest.generationConfig != null}, Thinking: ${chatRequest.generationConfig?.thinkingConfig != null}, CustomParams: ${chatRequest.customModelParameters}"
                )

                apiHandler.streamChatResponse(
                    requestBody = chatRequest,
                    userMessageTextForContext = textToActuallySend,
                    afterUserMessageId = newUserMessageForUi.id,
                    onMessagesProcessed = {
                        viewModelScope.launch { historyManager.saveCurrentChatToHistoryIfNeeded() }
                    }
                )
            }
        }
    }

    private fun getFileName(contentResolver: ContentResolver, uri: Uri): String? {
        var fileName: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1 && !cursor.isNull(displayNameIndex)) {
                        fileName = cursor.getString(displayNameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG_MESSAGE_SENDER, "Failed to get filename for URI: $uri", e)
        }
        return fileName ?: uri.lastPathSegment
    }
}