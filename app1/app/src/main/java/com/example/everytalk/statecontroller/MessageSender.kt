package com.example.everytalk.statecontroller

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
import com.example.everytalk.models.SelectedMediaItem
import com.example.everytalk.data.DataClass.AbstractApiMessage
import com.example.everytalk.data.DataClass.ApiContentPart
import com.example.everytalk.data.DataClass.ChatRequest
import com.example.everytalk.data.DataClass.PartsApiMessage
import com.example.everytalk.data.DataClass.SimpleTextApiMessage
import com.example.everytalk.data.DataClass.Message as UiMessage
import com.example.everytalk.data.DataClass.Sender as UiSender
import com.example.everytalk.data.DataClass.ThinkingConfig
import com.example.everytalk.data.DataClass.ImageGenRequest
import com.example.everytalk.data.DataClass.GenerationConfig
import com.example.everytalk.ui.screens.viewmodel.HistoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private data class AttachmentProcessingResult(
    val success: Boolean,
    val processedAttachmentsForUi: List<SelectedMediaItem> = emptyList(),
    val imageUriStringsForUi: List<String> = emptyList(),
    val apiContentParts: List<ApiContentPart> = emptyList()
)
 class MessageSender(
     private val application: Application,
    private val viewModelScope: CoroutineScope,
    private val stateHolder: ViewModelStateHolder,
    private val apiHandler: ApiHandler,
    private val historyManager: HistoryManager,
    private val showSnackbar: (String) -> Unit,
    private val triggerScrollToBottom: () -> Unit,
    private val uriToBase64Encoder: (Uri) -> String?
) {

    companion object {
        private const val MAX_IMAGE_SIZE_BYTES = 4 * 1024 * 1024
        private const val MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024 // 50MB 最大文件大小
        private const val TARGET_IMAGE_WIDTH = 1024
        private const val TARGET_IMAGE_HEIGHT = 1024
        private const val JPEG_COMPRESSION_QUALITY = 80
        private const val CHAT_ATTACHMENTS_SUBDIR = "chat_attachments"
    }

    private suspend fun loadAndCompressBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return withContext(Dispatchers.IO) {
            var bitmap: Bitmap? = null
            try {
                if (uri == Uri.EMPTY) return@withContext null

                // 首先检查文件大小
                var fileSize = 0L
                try {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIndex != -1) {
                                fileSize = cursor.getLong(sizeIndex)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 继续处理，但要小心内存使用
                }

                if (fileSize > MAX_FILE_SIZE_BYTES) {
                    return@withContext null
                }

                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }

                // 计算合适的采样率以避免内存问题
                options.inSampleSize = calculateInSampleSize(options, TARGET_IMAGE_WIDTH, TARGET_IMAGE_HEIGHT)

                options.inJustDecodeBounds = false
                options.inMutable = true
                options.inPreferredConfig = Bitmap.Config.RGB_565 // 使用更少内存的配置

                bitmap = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }

                if (bitmap != null && (bitmap!!.width > TARGET_IMAGE_WIDTH || bitmap!!.height > TARGET_IMAGE_HEIGHT)) {
                    val aspectRatio = bitmap!!.width.toFloat() / bitmap!!.height.toFloat()
                    val newWidth: Int
                    val newHeight: Int
                    if (bitmap!!.width > bitmap!!.height) {
                        newWidth = TARGET_IMAGE_WIDTH
                        newHeight = (newWidth / aspectRatio).toInt()
                    } else {
                        newHeight = TARGET_IMAGE_HEIGHT
                        newWidth = (newHeight * aspectRatio).toInt()
                    }
                    if (newWidth > 0 && newHeight > 0) {
                        try {
                            val scaledBitmap = Bitmap.createScaledBitmap(bitmap!!, newWidth, newHeight, true)
                            if (scaledBitmap != bitmap) {
                                bitmap?.recycle()
                            }
                            bitmap = scaledBitmap
                        } catch (e: OutOfMemoryError) {
                            // 如果缩放失败，使用原图但记录警告
                            System.gc()
                        }
                    }
                }
                bitmap
            } catch (e: OutOfMemoryError) {
                bitmap?.recycle()
                System.gc() // 建议垃圾回收
                null
            } catch (e: Exception) {
                bitmap?.recycle()
                null
            }
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private suspend fun copyUriToAppInternalStorage(
        context: Context,
        sourceUri: Uri,
        messageIdHint: String,
        attachmentIndex: Int,
        originalFileName: String?
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                // 检查文件大小
                var fileSize = 0L
                try {
                    context.contentResolver.query(sourceUri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIndex != -1) {
                                fileSize = cursor.getLong(sizeIndex)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 如果无法获取文件大小，继续处理但要小心
                }

                if (fileSize > MAX_FILE_SIZE_BYTES) {
                    return@withContext null
                }

                val MimeTypeMap = android.webkit.MimeTypeMap.getSingleton()
                val contentType = context.contentResolver.getType(sourceUri)
                val extension = MimeTypeMap.getExtensionFromMimeType(contentType)
                    ?: originalFileName?.substringAfterLast('.', "")
                    ?: "bin"

                val timeStamp: String =
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val safeOriginalName =
                    originalFileName?.replace("[^a-zA-Z0-9._-]".toRegex(), "_")?.take(30) ?: "file"
                val uniqueFileName =
                    "${safeOriginalName}_${messageIdHint}_${attachmentIndex}_${timeStamp}_${
                        UUID.randomUUID().toString().take(4)
                    }.$extension"

                val attachmentDir = File(context.filesDir, CHAT_ATTACHMENTS_SUBDIR)
                if (!attachmentDir.exists() && !attachmentDir.mkdirs()) {
                    return@withContext null
                }

                val destinationFile = File(attachmentDir, uniqueFileName)
                
                // 使用缓冲区复制，避免一次性加载大文件到内存
                context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        val buffer = ByteArray(8192) // 8KB 缓冲区
                        var bytesRead: Int
                        var totalBytesRead = 0L
                        
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            
                            // 检查是否超过文件大小限制
                            if (totalBytesRead > MAX_FILE_SIZE_BYTES) {
                                destinationFile.delete()
                                return@withContext null
                            }
                        }
                    }
                } ?: run {
                    return@withContext null
                }

                if (!destinationFile.exists() || destinationFile.length() == 0L) {
                    if (destinationFile.exists()) destinationFile.delete()
                    return@withContext null
                }

                destinationFile.absolutePath
            } catch (e: OutOfMemoryError) {
                // 处理内存不足错误
                System.gc() // 建议垃圾回收
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun saveBitmapToAppInternalStorage(
        context: Context,
        bitmapToSave: Bitmap,
        messageIdHint: String,
        attachmentIndex: Int,
        originalFileNameHint: String? = null
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                if (bitmapToSave.isRecycled) {
                    return@withContext null
                }

                val outputStream = ByteArrayOutputStream()
                val fileExtension: String
                val compressFormat = if (bitmapToSave.hasAlpha()) {
                    fileExtension = "png"; Bitmap.CompressFormat.PNG
                } else {
                    fileExtension = "jpg"; Bitmap.CompressFormat.JPEG
                }
                bitmapToSave.compress(compressFormat, JPEG_COMPRESSION_QUALITY, outputStream)
                val bytes = outputStream.toByteArray()
                if (!bitmapToSave.isRecycled) {
                    bitmapToSave.recycle()
                }

                val timeStamp: String =
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val baseName = originalFileNameHint?.substringBeforeLast('.')
                    ?.replace("[^a-zA-Z0-9._-]".toRegex(), "_")?.take(20) ?: "IMG"
                val uniqueFileName =
                    "${baseName}_${messageIdHint}_${attachmentIndex}_${timeStamp}_${
                        UUID.randomUUID().toString().take(4)
                    }.$fileExtension"

                val attachmentDir = File(context.filesDir, CHAT_ATTACHMENTS_SUBDIR)
                if (!attachmentDir.exists() && !attachmentDir.mkdirs()) {
                    return@withContext null
                }

                val destinationFile = File(attachmentDir, uniqueFileName)
                FileOutputStream(destinationFile).use { it.write(bytes) }

                if (!destinationFile.exists() || destinationFile.length() == 0L) {
                    if (destinationFile.exists()) destinationFile.delete()
                    return@withContext null
                }
                destinationFile.absolutePath
            } catch (e: Exception) {
                if (!bitmapToSave.isRecycled) {
                    bitmapToSave.recycle()
                }
                null
            }
        }
    }

    private suspend fun processAttachments(
        attachments: List<SelectedMediaItem>,
        shouldUsePartsApiMessage: Boolean,
        textToActuallySend: String
    ): AttachmentProcessingResult = withContext(Dispatchers.IO) {
        if (attachments.isEmpty()) {
            return@withContext AttachmentProcessingResult(
                success = true,
                apiContentParts = if (shouldUsePartsApiMessage && textToActuallySend.isNotBlank()) listOf(ApiContentPart.Text(text = textToActuallySend)) else emptyList()
            )
        }

        val processedAttachmentsForUi = mutableListOf<SelectedMediaItem>()
        val imageUriStringsForUi = mutableListOf<String>()
        val apiContentParts = mutableListOf<ApiContentPart>()

        if (shouldUsePartsApiMessage) {
            if (textToActuallySend.isNotBlank() || attachments.isNotEmpty()) {
                apiContentParts.add(ApiContentPart.Text(text = textToActuallySend))
            }
        }

        val tempMessageIdForNaming = UUID.randomUUID().toString().take(8)

        for ((index, originalMediaItem) in attachments.withIndex()) {
            val itemUri = when (originalMediaItem) {
                is SelectedMediaItem.ImageFromUri -> originalMediaItem.uri
                is SelectedMediaItem.GenericFile -> originalMediaItem.uri
                is SelectedMediaItem.ImageFromBitmap -> Uri.EMPTY
                is SelectedMediaItem.Audio -> Uri.EMPTY
            }
            val originalFileNameForHint = (originalMediaItem as? SelectedMediaItem.GenericFile)?.displayName
                ?: getFileName(application.contentResolver, itemUri)
                ?: (if (originalMediaItem is SelectedMediaItem.ImageFromBitmap) "camera_shot" else "attachment")

            val persistentFilePath: String? = when (originalMediaItem) {
                is SelectedMediaItem.ImageFromUri -> {
                    val bitmap = loadAndCompressBitmapFromUri(application, originalMediaItem.uri)
                    if (bitmap != null) {
                        saveBitmapToAppInternalStorage(application, bitmap, tempMessageIdForNaming, index, originalFileNameForHint)
                    } else {
                        showSnackbar("无法加载或压缩图片: $originalFileNameForHint")
                        return@withContext AttachmentProcessingResult(success = false)
                    }
                }
                is SelectedMediaItem.ImageFromBitmap -> {
                    saveBitmapToAppInternalStorage(application, originalMediaItem.bitmap, tempMessageIdForNaming, index, originalFileNameForHint)
                }
                is SelectedMediaItem.GenericFile -> {
                    copyUriToAppInternalStorage(application, originalMediaItem.uri, tempMessageIdForNaming, index, originalMediaItem.displayName)
                }
                is SelectedMediaItem.Audio -> {
                    // 音频数据已为Base64，无需额外处理
                    null
                }
            }

            if (persistentFilePath == null && originalMediaItem !is SelectedMediaItem.Audio) {
                showSnackbar("无法处理附件: $originalFileNameForHint")
                return@withContext AttachmentProcessingResult(success = false)
            }

            val persistentFile = persistentFilePath?.let { File(it) }
            val authority = "${application.packageName}.provider"
            val persistentFileProviderUri = persistentFile?.let { FileProvider.getUriForFile(application, authority, it) }

            val processedItemForUi: SelectedMediaItem = when (originalMediaItem) {
                is SelectedMediaItem.ImageFromUri -> {
                    imageUriStringsForUi.add(persistentFileProviderUri.toString())
                    SelectedMediaItem.ImageFromUri(
                        uri = persistentFileProviderUri!!,
                        id = originalMediaItem.id,
                        filePath = persistentFilePath
                    )
                }
                is SelectedMediaItem.ImageFromBitmap -> {
                    imageUriStringsForUi.add(persistentFileProviderUri.toString())
                    SelectedMediaItem.ImageFromBitmap(
                        bitmap = originalMediaItem.bitmap,
                        id = originalMediaItem.id,
                        filePath = persistentFilePath
                    )
                }
                is SelectedMediaItem.GenericFile -> {
                    // The ApiClient now handles streaming, so we don't need to read the bytes here.
                    // We still add the item to the UI list.
                    SelectedMediaItem.GenericFile(
                        uri = persistentFileProviderUri!!,
                        id = originalMediaItem.id,
                        displayName = originalFileNameForHint,
                        mimeType = originalMediaItem.mimeType,
                        filePath = persistentFilePath
                    )
                }
                is SelectedMediaItem.Audio -> {
                    apiContentParts.add(ApiContentPart.InlineData(mimeType = originalMediaItem.mimeType, base64Data = originalMediaItem.data))
                    originalMediaItem
                }
            }
            processedAttachmentsForUi.add(processedItemForUi)

            // 为处理后的图片（现在拥有一个持久化的 URI）创建 API 内容部分
            if (shouldUsePartsApiMessage && (processedItemForUi is SelectedMediaItem.ImageFromUri || processedItemForUi is SelectedMediaItem.ImageFromBitmap)) {
                val imageUri = (processedItemForUi as? SelectedMediaItem.ImageFromUri)?.uri
                    ?: (processedItemForUi as? SelectedMediaItem.ImageFromBitmap)?.let {
                        // 对于 Bitmap，我们需要一个 URI 来编码
                        persistentFileProviderUri
                    }

                if (imageUri != null) {
                    val base64Data = uriToBase64Encoder(imageUri)
                    val mimeType = application.contentResolver.getType(imageUri) ?: "image/jpeg"
                    if (base64Data != null) {
                        apiContentParts.add(ApiContentPart.InlineData(mimeType = mimeType, base64Data = base64Data))
                    }
                }
            }
        }
        AttachmentProcessingResult(true, processedAttachmentsForUi, imageUriStringsForUi, apiContentParts)
    }

    fun sendMessage(
        messageText: String,
        isFromRegeneration: Boolean = false,
        attachments: List<SelectedMediaItem> = emptyList(),
        audioBase64: String? = null,
        mimeType: String? = null,
        systemPrompt: String? = null,
        isImageGeneration: Boolean = false
    ) {
        val textToActuallySend = messageText.trim()
        val allAttachments = attachments.toMutableList()
        if (audioBase64 != null) {
            allAttachments.add(SelectedMediaItem.Audio(id = "audio_${UUID.randomUUID()}", mimeType = mimeType ?: "audio/3gpp", data = audioBase64!!))
        }

        if (textToActuallySend.isBlank() && allAttachments.isEmpty()) {
            viewModelScope.launch { showSnackbar("请输入消息内容或选择项目") }
            return
        }
        val currentConfig = (if (isImageGeneration) stateHolder._selectedImageGenApiConfig.value else stateHolder._selectedApiConfig.value) ?: run {
            viewModelScope.launch { showSnackbar(if (isImageGeneration) "请先选择 图像生成 的API配置" else "请先选择 API 配置") }
            return
        }
        
        // 详细调试配置信息
        if (isImageGeneration) {
            Log.d("MessageSender", "=== IMAGE GEN CONFIG DEBUG ===")
            Log.d("MessageSender", "Selected config ID: ${currentConfig.id}")
            Log.d("MessageSender", "Model: ${currentConfig.model}")
            Log.d("MessageSender", "Provider: ${currentConfig.provider}")
            Log.d("MessageSender", "Channel: ${currentConfig.channel}")
            Log.d("MessageSender", "Address: ${currentConfig.address}")
            Log.d("MessageSender", "Key: ${currentConfig.key.take(10)}...")
            Log.d("MessageSender", "ModalityType: ${currentConfig.modalityType}")
        }

        viewModelScope.launch {
            val modelIsGeminiType = currentConfig.model.lowercase().startsWith("gemini")
            val shouldUsePartsApiMessage = modelIsGeminiType
            val providerForRequestBackend = currentConfig.provider

            val attachmentResult = processAttachments(allAttachments, shouldUsePartsApiMessage, textToActuallySend)
            if (!attachmentResult.success) {
                return@launch
            }

            // Always pass the attachments to the ApiClient.
            // The ApiClient will handle creating the multipart request.
            // The previous logic incorrectly sent an empty list for Gemini.
            val attachmentsForApiClient = attachmentResult.processedAttachmentsForUi

            val newUserMessageForUi = UiMessage(
                id = "user_${UUID.randomUUID()}", text = textToActuallySend, sender = UiSender.User,
                timestamp = System.currentTimeMillis(), contentStarted = true,
                imageUrls = attachmentResult.imageUriStringsForUi,
                attachments = attachmentResult.processedAttachmentsForUi
            )

            withContext(Dispatchers.Main.immediate) {
                val animationMap = if (isImageGeneration) stateHolder.imageMessageAnimationStates else stateHolder.textMessageAnimationStates
                animationMap[newUserMessageForUi.id] = true
                if (isImageGeneration) {
                    stateHolder.imageGenerationMessages.add(newUserMessageForUi)
                } else {
                    stateHolder.messages.add(newUserMessageForUi)
                }
                if (!isFromRegeneration) {
                   stateHolder._text.value = ""
                   stateHolder.clearSelectedMedia()
                }
                triggerScrollToBottom()
            }


            withContext(Dispatchers.IO) {
                val messagesInChatUiSnapshot = if (isImageGeneration) stateHolder.imageGenerationMessages.toList() else stateHolder.messages.toList()
                val historyEndIndex = messagesInChatUiSnapshot.indexOfFirst { it.id == newUserMessageForUi.id }
                val historyUiMessages = if (historyEndIndex != -1) messagesInChatUiSnapshot.subList(0, historyEndIndex) else messagesInChatUiSnapshot

                val apiMessagesForBackend = historyUiMessages.map { it.toApiMessage(uriToBase64Encoder) }.toMutableList()

                // Add the current user message with attachments
                apiMessagesForBackend.add(newUserMessageForUi.toApiMessage(uriToBase64Encoder))


                if (!systemPrompt.isNullOrBlank()) {
                    val systemMessage = SimpleTextApiMessage(role = "system", content = systemPrompt)
                    // a more robust way to handle system messages
                    val existingSystemMessageIndex = apiMessagesForBackend.indexOfFirst { it.role == "system" }
                    if (existingSystemMessageIndex != -1) {
                        apiMessagesForBackend[existingSystemMessageIndex] = systemMessage
                    } else {
                        apiMessagesForBackend.add(0, systemMessage)
                    }
                }

                if (apiMessagesForBackend.isEmpty() || apiMessagesForBackend.lastOrNull()?.role != "user") {
                    withContext(Dispatchers.Main.immediate) {
                        stateHolder.messages.remove(newUserMessageForUi)
                        val animationMap = if (isImageGeneration) stateHolder.imageMessageAnimationStates else stateHolder.textMessageAnimationStates
                        animationMap.remove(newUserMessageForUi.id)
                    }
                    return@withContext
                }

                // 规范化图像尺寸：为空或包含占位符时回退到 1024x1024
                val sanitizedImageSize = currentConfig.imageSize?.takeIf { it.isNotBlank() && !it.contains("<") } ?: "1024x1024"
                
                // 检查是否包含图像生成关键词
                if (isImageGeneration && apiHandler.hasImageGenerationKeywords(textToActuallySend)) {
                    // 重置重试计数
                    stateHolder._imageGenerationRetryCount.value = 0
                    stateHolder._imageGenerationError.value = null
                    stateHolder._shouldShowImageGenerationError.value = false
                }

                val chatRequestForApi = ChatRequest(
                    messages = apiMessagesForBackend,
                    provider = providerForRequestBackend,
                    channel = currentConfig.channel,
                    apiAddress = currentConfig.address,
                    apiKey = currentConfig.key,
                    model = currentConfig.model,
                    useWebSearch = stateHolder._isWebSearchEnabled.value,
                    generationConfig = GenerationConfig(
                        temperature = currentConfig.temperature,
                        topP = currentConfig.topP,
                        maxOutputTokens = currentConfig.maxTokens,
                        thinkingConfig = if (modelIsGeminiType) ThinkingConfig(
                            includeThoughts = true,
                            thinkingBudget = if (currentConfig.model.contains(
                                "flash",
                                ignoreCase = true
                            )
                            ) 1024 else null
                        ) else null
                    ).let { if (it.temperature != null || it.topP != null || it.maxOutputTokens != null || it.thinkingConfig != null) it else null },
                    qwenEnableSearch = if (currentConfig.model.lowercase().contains("qwen")) stateHolder._isWebSearchEnabled.value else null,
                    customModelParameters = if (modelIsGeminiType) {
                        // 为Gemini模型添加reasoning_effort参数
                        // 根据模型类型设置不同的思考级别
                        val reasoningEffort = when {
                            currentConfig.model.contains("flash", ignoreCase = true) -> "low"  // 对应1024个令牌
                            currentConfig.model.contains("pro", ignoreCase = true) -> "medium" // 对应8192个令牌
                            else -> "high" // 对应24576个令牌
                        }
                        mapOf("reasoning_effort" to reasoningEffort)
                    } else null,
                    imageGenRequest = if (isImageGeneration) {
                        // 调试信息：检查发送的配置
                        Log.d("MessageSender", "Image generation config - model: ${currentConfig.model}, channel: ${currentConfig.channel}, provider: ${currentConfig.provider}")
                        
                        // 计算上游完整图片生成端点
                        val upstreamBase = currentConfig.address.trim().trimEnd('/')
                        val upstreamApiForImageGen = if (upstreamBase.endsWith("/v1/images/generations")) {
                            upstreamBase
                        } else {
                            "$upstreamBase/v1/images/generations"
                        }
                        ImageGenRequest(
                            model = currentConfig.model,
                            prompt = textToActuallySend,
                            imageSize = sanitizedImageSize,
                            batchSize = 1,
                            numInferenceSteps = currentConfig.numInferenceSteps,
                            guidanceScale = currentConfig.guidanceScale,
                            apiAddress = upstreamApiForImageGen,
                            apiKey = currentConfig.key,
                            provider = currentConfig.channel
                        )
                    } else null
                )

                apiHandler.streamChatResponse(
                    requestBody = chatRequestForApi,
                    attachmentsToPassToApiClient = attachmentsForApiClient,
                    applicationContextForApiClient = application,
                    userMessageTextForContext = textToActuallySend,
                    afterUserMessageId = newUserMessageForUi.id,
                    onMessagesProcessed = {
                        // 避免图像模式在AI占位阶段过早入库，仅文本模式此处保存
                        if (!isImageGeneration) {
                            viewModelScope.launch {
                                historyManager.saveCurrentChatToHistoryIfNeeded(isImageGeneration = false)
                            }
                        }
                    },
                    onRequestFailed = { error ->
                        viewModelScope.launch(Dispatchers.Main) {
                            val errorMessage = "发送失败: ${error.message ?: "未知错误"}"
                            showSnackbar(errorMessage)
                        }
                    },
                    onNewAiMessageAdded = triggerScrollToBottom,
                    audioBase64 = audioBase64,
                    mimeType = mimeType,
                    isImageGeneration = isImageGeneration
                )
            }
        }
    }

private suspend fun readTextFromUri(context: Context, uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.bufferedReader().use { reader ->
                    reader?.readText()
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    private fun getFileName(contentResolver: ContentResolver, uri: Uri): String? {
        if (uri == Uri.EMPTY) return null
        var fileName: String? = null
        try {
            if (ContentResolver.SCHEME_CONTENT == uri.scheme) {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val displayNameIndex =
                                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                            fileName = cursor.getString(displayNameIndex)
                        }
                    }
            }
            if (fileName == null) {
                fileName = uri.lastPathSegment
            }
        } catch (e: Exception) {
            fileName = uri.lastPathSegment
        }
        return fileName ?: "file_${System.currentTimeMillis()}"
    }
}
