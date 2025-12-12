package com.android.everytalk.statecontroller

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.util.storage.FileManager
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.util.image.ImageScaleCalculator
import com.android.everytalk.data.DataClass.Message as UiMessage
import com.android.everytalk.data.DataClass.Sender as UiSender
import com.android.everytalk.data.DataClass.ThinkingConfig
import com.android.everytalk.data.DataClass.ImageGenRequest
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

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

    private val fileManager: FileManager by lazy { FileManager(application) }

    companion object {
        private const val MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024 // 50MB 最大文件大小
        private const val CHAT_ATTACHMENTS_SUBDIR = "chat_attachments"
        
        // 保留兼容性的常量，但标记为过时
        @Deprecated("Use ImageScaleConfig instead", ReplaceWith("ImageScaleConfig.CHAT_MODE.maxFileSize"))
        private const val MAX_IMAGE_SIZE_BYTES = 4 * 1024 * 1024
        @Deprecated("Use ImageScaleConfig instead", ReplaceWith("ImageScaleConfig.CHAT_MODE.maxDimension"))
        private const val TARGET_IMAGE_WIDTH = 1024
        @Deprecated("Use ImageScaleConfig instead", ReplaceWith("ImageScaleConfig.CHAT_MODE.maxDimension"))
        private const val TARGET_IMAGE_HEIGHT = 1024
        @Deprecated("Use ImageScaleConfig instead", ReplaceWith("ImageScaleConfig.CHAT_MODE.compressionQuality"))
        private const val JPEG_COMPRESSION_QUALITY = 80
    }

    /**
     * 从Uri加载并压缩位图 - 新版本支持等比缩放
     * @param context 上下文
     * @param uri 图片Uri
     * @param isImageGeneration 是否为图像生成模式
     * @return 压缩后的位图，如果加载失败则返回null
     */
    private suspend fun loadAndCompressBitmapFromUri(
        context: Context, 
        uri: Uri,
        isImageGeneration: Boolean = false
    ): Bitmap? {
        return fileManager.loadAndCompressBitmapFromUri(uri = uri, isImageGeneration = isImageGeneration)
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
        return fileManager.copyUriToAppInternalStorage(
            sourceUri = sourceUri,
            messageIdHint = messageIdHint,
            attachmentIndex = attachmentIndex,
            originalFileName = originalFileName
        )
    }

    private suspend fun saveBitmapToAppInternalStorage(
        context: Context,
        bitmapToSave: Bitmap,
        messageIdHint: String,
        attachmentIndex: Int,
        originalFileNameHint: String? = null,
        isImageGeneration: Boolean = false
    ): String? {
        return fileManager.saveBitmapToAppInternalStorage(
            bitmapToSave = bitmapToSave,
            messageIdHint = messageIdHint,
            attachmentIndex = attachmentIndex,
            originalFileNameHint = originalFileNameHint,
            isImageGeneration = isImageGeneration
        )
    }

    private suspend fun processAttachments(
        attachments: List<SelectedMediaItem>,
        shouldUsePartsApiMessage: Boolean,
        textToActuallySend: String,
        isImageGeneration: Boolean = false
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
                    val bitmap = loadAndCompressBitmapFromUri(application, originalMediaItem.uri, isImageGeneration)
                    if (bitmap != null) {
                        saveBitmapToAppInternalStorage(application, bitmap, tempMessageIdForNaming, index, originalFileNameForHint, isImageGeneration)
                    } else {
                        showSnackbar("无法加载或压缩图片: $originalFileNameForHint")
                        return@withContext AttachmentProcessingResult(success = false)
                    }
                }
                is SelectedMediaItem.ImageFromBitmap -> {
                    originalMediaItem.bitmap?.let { bitmap ->
                        val prefs = com.android.everytalk.config.ImageCompressionPreferences(application)
                        val config = if (isImageGeneration) prefs.getImageGenerationModeConfig() else prefs.getChatModeConfig()
                        val (targetW, targetH) = ImageScaleCalculator.calculateProportionalScale(bitmap.width, bitmap.height, config)
                        val safeBitmap = try {
                            if ((targetW > 0 && targetH > 0) && (targetW != bitmap.width || targetH != bitmap.height)) {
                                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                            } else {
                                bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
                            }
                        } catch (_: OutOfMemoryError) {
                            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
                        }
                        saveBitmapToAppInternalStorage(application, safeBitmap, tempMessageIdForNaming, index, originalFileNameForHint, isImageGeneration)
                    }
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
                    originalMediaItem.bitmap?.let { bitmap ->
                        SelectedMediaItem.ImageFromBitmap.fromBitmap(
                            bitmap = bitmap,
                            id = originalMediaItem.id,
                            filePath = persistentFilePath
                        )
                    } ?: originalMediaItem // 如果 bitmap 为 null，返回原始对象
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
        isImageGeneration: Boolean = false,
        manualMessageId: String? = null
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
        
        // 🔥 关键调试：检查配置状态
        Log.d("MessageSender", "=== SEND MESSAGE DEBUG ===")
        Log.d("MessageSender", "isImageGeneration: $isImageGeneration")
        Log.d("MessageSender", "selectedImageGenApiConfig: ${stateHolder._selectedImageGenApiConfig.value}")
        Log.d("MessageSender", "selectedApiConfig: ${stateHolder._selectedApiConfig.value}")
        Log.d("MessageSender", "imageGenerationMessages.size: ${stateHolder.imageGenerationMessages.size}")
        Log.d("MessageSender", "messages.size: ${stateHolder.messages.size}")
        
        val currentConfig = (if (isImageGeneration) stateHolder._selectedImageGenApiConfig.value else stateHolder._selectedApiConfig.value) ?: run {
            Log.e("MessageSender", "❌ No API config selected! isImageGeneration=$isImageGeneration")
            viewModelScope.launch { showSnackbar(if (isImageGeneration) "请先选择 图像生成 的API配置" else "请先选择 API 配置") }
            return
        }

        // 记录会话使用的配置ID
        if (!isImageGeneration) {
            val conversationId = stateHolder._currentConversationId.value
            val currentMap = stateHolder.conversationApiConfigIds.value.toMutableMap()
            if (currentMap[conversationId] != currentConfig.id) {
                currentMap[conversationId] = currentConfig.id
                stateHolder.conversationApiConfigIds.value = currentMap
                // 这里仅更新内存状态，HistoryManager.saveCurrentChatToHistoryIfNeededInternal 会负责持久化
            }
        } else {
            // 图像模式：绑定当前图像会话ID与配置ID
            val conversationId = stateHolder._currentImageGenerationConversationId.value
            val currentMap = stateHolder.conversationApiConfigIds.value.toMutableMap()
            if (currentMap[conversationId] != currentConfig.id) {
                currentMap[conversationId] = currentConfig.id
                stateHolder.conversationApiConfigIds.value = currentMap
                // 这里仅更新内存状态，HistoryManager 会负责持久化
            }
        }
        
        Log.d("MessageSender", "✅ Using config: ${currentConfig.model} (${currentConfig.provider})")
        Log.d("MessageSender", "=== END SEND MESSAGE DEBUG ===")

        
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
            val isDefaultProvider = currentConfig.provider.trim().lowercase() in listOf("默认", "default")

            // 自动注入"上一轮AI出图"作为参考，以支持"在上一张基础上修改"等编辑语义
            if (isImageGeneration && allAttachments.isEmpty()) {
                val t = textToActuallySend.lowercase()
                if (hasImageEditKeywords(t)) {
                    try {
                        // 找到最近一条包含图片的AI消息
                        val lastAiWithImage = stateHolder.imageGenerationMessages.lastOrNull {
                            it.sender == UiSender.AI && !it.imageUrls.isNullOrEmpty()
                        }
                        val refImageUrl = lastAiWithImage?.imageUrls?.lastOrNull()
                        if (!refImageUrl.isNullOrBlank()) {
                            // 下载并等比压缩该图片，作为位图附件加入
                            val fm = FileManager(application)
                            val refBitmap = fm.loadAndCompressBitmapFromUrl(refImageUrl, isImageGeneration = true)
                            if (refBitmap != null) {
                                allAttachments.add(
                                    SelectedMediaItem.ImageFromBitmap.fromBitmap(
                                        bitmap = refBitmap,
                                        id = "ref_${UUID.randomUUID()}"
                                    )
                                )
                                Log.d("MessageSender", "已自动附带上一轮AI图片作为参考: $refImageUrl")
                            } else {
                                Log.w("MessageSender", "未能下载上一轮AI图片，跳过自动引用")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("MessageSender", "自动引用上一轮AI图片失败: ${e.message}")
                    }
                }
            }

            val attachmentResult = processAttachments(allAttachments, shouldUsePartsApiMessage, textToActuallySend, isImageGeneration)
            if (!attachmentResult.success) {
                return@launch
            }

            // Always pass the attachments to the ApiClient.
            // The ApiClient will handle creating the multipart request.
            // The previous logic incorrectly sent an empty list for Gemini.
            val attachmentsForApiClient = attachmentResult.processedAttachmentsForUi

            val newUserMessageForUi = UiMessage(
                id = manualMessageId ?: "user_${UUID.randomUUID()}", text = textToActuallySend, sender = UiSender.User,
                timestamp = System.currentTimeMillis(), contentStarted = true,
                imageUrls = attachmentResult.imageUriStringsForUi,
                attachments = attachmentResult.processedAttachmentsForUi,
                modelName = currentConfig.model,
                providerName = currentConfig.provider
            )

            withContext(Dispatchers.Main.immediate) {
                val animationMap = if (isImageGeneration) stateHolder.imageMessageAnimationStates else stateHolder.textMessageAnimationStates
                animationMap[newUserMessageForUi.id] = true
                if (isImageGeneration) {
                    stateHolder.imageGenerationMessages.add(newUserMessageForUi)
                } else {
                    stateHolder.messages.add(newUserMessageForUi)
                    // 首条消息产生后（文本模式），将"待应用参数"落库，满足：空会话不保存；非空会话保存
                    stateHolder.persistPendingParamsIfNeeded(isImageGeneration = false)
                }
                if (!isFromRegeneration) {
                   stateHolder._text.value = ""
                   stateHolder.clearSelectedMedia()
                }
                // 注意：不再在此处调用 triggerScrollToBottom()
                // ChatScreen 和 ImageGenerationScreen 各自有更好的滚动逻辑（scrollItemToTop），
                // 会在 onSendMessage 回调后执行带动画的平滑滚动
            }

            // 🔥 新增：当在新会话中发送第一条消息时，立即将其添加到历史记录中，以便在抽屉中即时可见
            val isNewTextChatFirstMessage = !isImageGeneration &&
                    stateHolder.messages.size == 1 &&
                    stateHolder._loadedHistoryIndex.value == null

            val isNewImageChatFirstMessage = isImageGeneration &&
                    stateHolder.imageGenerationMessages.size == 1 &&
                    stateHolder._loadedImageGenerationHistoryIndex.value == null

            if (isNewTextChatFirstMessage || isNewImageChatFirstMessage) {
                withContext(Dispatchers.IO) {
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = isImageGeneration)
                }
            }

            withContext(Dispatchers.IO) {
                val messagesInChatUiSnapshot = if (isImageGeneration) stateHolder.imageGenerationMessages.toList() else stateHolder.messages.toList()
                val historyEndIndex = messagesInChatUiSnapshot.indexOfFirst { it.id == newUserMessageForUi.id }
                val historyUiMessagesRaw = if (historyEndIndex != -1) messagesInChatUiSnapshot.subList(0, historyEndIndex) else messagesInChatUiSnapshot

                // 当"系统提示接入"处于暂停状态时，过滤掉会话历史中的系统消息，避免仍然将 Prompt 注入到请求
                val engagedForThisConversation = stateHolder.systemPromptEngagedState[stateHolder._currentConversationId.value] ?: false
                val historyUiMessages = if (engagedForThisConversation) {
                    historyUiMessagesRaw
                } else {
                    historyUiMessagesRaw.filter { msg ->
                        // 保留非系统消息；若有系统占位标题(isPlaceholderName)可选择保留，这里彻底禁用非占位系统提示
                        !(msg.sender == UiSender.System && !msg.isPlaceholderName)
                    }
                }

                // 图像会话的稳定会话ID规则：
                // 第一次消息（historyEndIndex==0 且非从历史加载）时，用"首条用户消息ID"作为 conversationId，
                // 这样重启后根据第一条消息ID恢复，后端会话可继续（与 SimpleModeManager.loadImageHistory 的写法严格一致）。
                if (isImageGeneration) {
                    val isFirstMessageInThisSession = historyEndIndex == 0
                    val notFromHistory = stateHolder._loadedImageGenerationHistoryIndex.value == null
                    if (isFirstMessageInThisSession && notFromHistory) {
                        stateHolder._currentImageGenerationConversationId.value = newUserMessageForUi.id
                    }
                }

                // 🔥 修复：使用带Context的toApiMessage方法获取真实MIME类型
                val apiMessagesForBackend = historyUiMessages.map { it.toApiMessage(uriToBase64Encoder, application) }.toMutableList()

                // Add the current user message with attachments
                // 🔥 修复：使用带Context的toApiMessage方法获取真实MIME类型
                apiMessagesForBackend.add(newUserMessageForUi.toApiMessage(uriToBase64Encoder, application))


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

                // 规范化图像尺寸：为空或包含占位符时回退到 1024x1024（基础兜底）
                val baseSanitizedImageSize = currentConfig.imageSize?.takeIf { it.isNotBlank() && !it.contains("<") } ?: "1024x1024"
                
                // 根据模型家族 + 所选比例，推导 Kolors/Qwen 的精确分辨率（image_size）
                // - Kolors: 使用映射表或精确选择（含 3:4 的两个选项）
                // - Qwen-Image: 必须指定推荐分辨率；Qwen-Image-Edit 不支持 image_size（保持 null）
                val detectedFamilyForImage = com.android.everytalk.ui.components.ImageGenCapabilities.detectFamily(
                    modelName = currentConfig.model,
                    provider = currentConfig.provider,
                    apiAddress = currentConfig.address
                )
                val isQwenEditModel = currentConfig.model.contains("Image-Edit", ignoreCase = true)
                val selectedRatioForImage = stateHolder._selectedImageRatio.value
                
                val familyBasedImageSize: String? = when (detectedFamilyForImage) {
                    com.android.everytalk.ui.components.ImageGenCapabilities.ModelFamily.KOLORS -> {
                        val labelFromRatio = "${selectedRatioForImage.width}x${selectedRatioForImage.height}"
                        val mapped = com.android.everytalk.ui.components.ImageGenCapabilities
                            .getKolorsSizesByRatio(selectedRatioForImage.displayName)
                            .firstOrNull()?.label
                        if (mapped.isNullOrBlank()) labelFromRatio else mapped
                    }
                    com.android.everytalk.ui.components.ImageGenCapabilities.ModelFamily.QWEN -> {
                        if (isQwenEditModel) {
                            null // 按文档：Qwen-Image-Edit 不支持 image_size
                        } else {
                            val mapped = com.android.everytalk.ui.components.ImageGenCapabilities
                                .getQwenSizesByRatio(selectedRatioForImage.displayName)
                            (mapped.firstOrNull()?.label ?: "1328x1328")
                        }
                    }
                    else -> null
                }
                
                val finalImageSize = familyBasedImageSize ?: baseSanitizedImageSize
                // 最终用于请求的 image_size（Qwen-Image-Edit 必须禁用）
                val imageSizeForRequest: String? = if (detectedFamilyForImage == com.android.everytalk.ui.components.ImageGenCapabilities.ModelFamily.QWEN && isQwenEditModel) {
                    null
                } else {
                    finalImageSize
                }
                // 检查是否包含图像生成关键词
                if (isImageGeneration && hasImageGenerationKeywords(textToActuallySend)) {
                    // 重置重试计数
                    stateHolder._imageGenerationRetryCount.value = 0
                    stateHolder._imageGenerationError.value = null
                    stateHolder._shouldShowImageGenerationError.value = false
                }

                // 检查是否为Gemini渠道且开启了联网搜索
                // 增强检测：要求渠道为 Gemini 且模型名称包含 Gemini，才视为原生 Gemini 支持环境
                val isGeminiChannel = currentConfig.channel.lowercase().contains("gemini") &&
                                      currentConfig.model.lowercase().contains("gemini")
                val shouldEnableGoogleSearch = isGeminiChannel && stateHolder._isWebSearchEnabled.value
                
                // 添加调试日志
                Log.d("MessageSender", "Channel: ${currentConfig.channel}, isGeminiChannel: $isGeminiChannel, webSearchEnabled: ${stateHolder._isWebSearchEnabled.value}, shouldEnableGoogleSearch: $shouldEnableGoogleSearch")

                // 3. 代码执行启用逻辑 - 用户全权控制
                val enableCodeExecutionForRequest: Boolean? =
                    if (!isGeminiChannel) {
                        null // 非 Gemini 渠道，不支持原生 code_execution，且不显示开关
                    } else {
                        // Gemini 渠道下，完全由 UI 开关决定：
                        // ON -> true (注入工具)
                        // OFF -> false (不注入)
                        // 这样就覆盖了底层的自动意图检测逻辑
                        stateHolder._isCodeExecutionEnabled.value
                    }

                val chatRequestForApi = ChatRequest(
                    messages = apiMessagesForBackend,
                    provider = providerForRequestBackend,
                    channel = currentConfig.channel,
                    apiAddress = currentConfig.address,
                    apiKey = currentConfig.key,
                    model = currentConfig.model,
                    deviceId = com.android.everytalk.util.DeviceIdManager.getDeviceId(application),
                    useWebSearch = stateHolder._isWebSearchEnabled.value,
                    // 显式传递代码执行开关状态
                    enableCodeExecution = enableCodeExecutionForRequest,
                    // 新会话未设置时，只回落温度/TopP；maxTokens 一律保持关闭（null）
                    generationConfig = stateHolder.getCurrentConversationConfig() ?: GenerationConfig(
                        temperature = currentConfig.temperature,
                        topP = currentConfig.topP,
                        maxOutputTokens = null,
                        thinkingConfig = if (modelIsGeminiType) {
                            // 为 2.5 系列明确设置思考预算：Flash≈低、Pro≈中、其他≈高
                            val modelLower = currentConfig.model.lowercase()
                            val budget = when {
                                "flash" in modelLower -> 1024
                                "pro" in modelLower -> 8192
                                else -> 24576
                            }
                            ThinkingConfig(
                                includeThoughts = true,
                                thinkingBudget = budget
                            )
                        } else null
                    ).let { if (it.temperature != null || it.topP != null || it.maxOutputTokens != null || it.thinkingConfig != null) it else null },
                    qwenEnableSearch = if (currentConfig.model.lowercase().contains("qwen") && stateHolder._isWebSearchEnabled.value) true else null,
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
                    // 工具注入逻辑
                    tools = run {
                        val toolsList = mutableListOf<Map<String, Any>>()
                        
                        // 1. 用户自定义工具
                        if (!currentConfig.toolsJson.isNullOrBlank()) {
                            try {
                                val jsonElement = Json.parseToJsonElement(currentConfig.toolsJson!!)
                                if (jsonElement is JsonArray) {
                                    jsonElement.forEach { element: JsonElement ->
                                        if (element is JsonObject) {
                                            // 递归转换 JsonObject 为 Map
                                            val map = jsonObjectToMap(element)
                                            toolsList.add(map)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("MessageSender", "Failed to parse custom tools JSON", e)
                            }
                        }
                        
                        // 2. 联网搜索 (Gemini Native)
                        if (shouldEnableGoogleSearch) {
                            Log.d("MessageSender", "启用Google搜索工具用于Gemini渠道")
                            toolsList.add(mapOf("googleSearch" to emptyMap<String, Any>()))
                        }
                        
                        // 3. 代码执行 (Gemini Native)
                        // 修复：显式注入 code_execution 工具，确保通过代理/后端请求时生效
                        // (GeminiDirectClient 会忽略此 tools 列表自行构建，因此不会冲突)
                        if (isGeminiChannel && stateHolder._isCodeExecutionEnabled.value) {
                            Log.d("MessageSender", "启用代码执行工具 (code_execution)")
                            toolsList.add(mapOf("code_execution" to emptyMap<String, Any>()))
                        }
                        
                        toolsList.ifEmpty { null }
                    },
                    imageGenRequest = if (isImageGeneration) {
                        // 调试信息：检查发送的配置
                        Log.d("MessageSender", "Image generation config - model: ${currentConfig.model}, channel: ${currentConfig.channel}, provider: ${currentConfig.provider}")
                        
                        // 计算上游完整图片生成端点（默认平台交由后端注入，避免相对路径）
                        val upstreamApiForImageGen = if (isDefaultProvider) {
                            ""
                        } else {
                            val upstreamBase = currentConfig.address.trim().trimEnd('/')
                            if (upstreamBase.endsWith("/v1/images/generations")) {
                                upstreamBase
                            } else {
                                "$upstreamBase/v1/images/generations"
                            }
                        }

                        // 构建"无状态历史摘要"，保证每个会话自带记忆（即使后端会话未命中）
                        // 仅提取纯文本轮次（user/model），避免把图片当作历史内容。
                        val historyForStatelessMemory: List<Map<String, String>> = run {
                            val maxTurns = 6 // 最近6轮（user/model合计），可按需调整
                            val turns = mutableListOf<Map<String, String>>()
                            historyUiMessages
                                .asReversed() // 从末尾向前
                                .asSequence()
                                .filter { it.text.isNotBlank() }
                                .map { msg ->
                                    val role = if (msg.sender == UiSender.User) "user" else "model"
                                    role to msg.text.trim()
                                }
                                .filter { (_, text) -> text.isNotBlank() }
                                .take(maxTurns)
                                .toList()
                                .asReversed() // 恢复正序
                                .forEach { (role, text) ->
                                    turns.add(mapOf("role" to role, "text" to text))
                                }
                            turns
                        }

                        // 依据文档：通过 config.response_modalities 与 image_config.aspect_ratio 控制输出
                        ImageGenRequest(
                            model = currentConfig.model,
                            prompt = textToActuallySend,
                            imageSize = imageSizeForRequest, // Kolors/Qwen 生效；Qwen-Image-Edit 禁用
                            batchSize = 1,
                            numInferenceSteps = currentConfig.numInferenceSteps,
                            guidanceScale = currentConfig.guidanceScale,
                            // 默认平台：apiAddress/apiKey 留空，由后端从 .env 注入
                            apiAddress = if (isDefaultProvider) "" else upstreamApiForImageGen,
                            apiKey = if (isDefaultProvider) "" else currentConfig.key,
                            // 渠道控制路由：默认平台传"默认"，非默认按"渠道"字段（OpenAI兼容/Gemini）
                            provider = if (isDefaultProvider) currentConfig.provider else currentConfig.channel,
                            responseModalities = listOf("Image"),
                            aspectRatio = stateHolder._selectedImageRatio.value.let { r ->
                                if (r.isAuto) null else r.displayName
                            },
                            // 严格会话隔离：把当前图像历史项ID透传到后端
                            conversationId = stateHolder._currentImageGenerationConversationId.value,
                            // 额外兜底：把最近若干轮文本摘要也发给后端，确保"该会话独立记忆"不依赖服务端状态
                            history = historyForStatelessMemory.ifEmpty { null },
                            // 禁用水印（针对 Seedream 直连）
                            watermark = false,
                            // 将配置中的 imageSize (1K/2K/4K) 传递给 Gemini 专用字段
                            geminiImageSize = if (modelIsGeminiType) currentConfig.imageSize else null
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
    
    private fun hasImageGenerationKeywords(text: String?): Boolean {
        // 委托给 ApiHandler 中的实现，避免重复代码
        return apiHandler.hasImageGenerationKeywords(text)
    }

    // 识别"编辑/基于上一张修改"的语义，用于自动附带上一轮AI图片
    private fun hasImageEditKeywords(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.lowercase().trim()
        val editKeywords = listOf(
            "改成", "换成", "替换", "修改", "调整", "改为", "基于上一张", "在上一张基础上",
            "把", "改一下", "修一下", "换一下", "同一张", "同这张", "继续修改",
            // 英文常见编辑意图
            "replace", "change to", "edit", "modify", "adjust", "based on previous", "on the previous image"
        )
        return editKeywords.any { k -> t.contains(k) }
    }

    // 辅助函数：递归将 JsonObject 转换为 Map<String, Any>
    private fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        jsonObject.entries.forEach { (key, value) ->
            jsonElementToAny(value)?.let { map[key] = it }
        }
        return map
    }

    // 辅助函数：递归将 JsonArray 转换为 List<Any>
    private fun jsonArrayToList(jsonArray: JsonArray): List<Any> {
        val list = mutableListOf<Any>()
        jsonArray.forEach { element ->
            jsonElementToAny(element)?.let { list.add(it) }
        }
        return list
    }

    // 辅助函数：将 JsonElement 转换为 Any
    private fun jsonElementToAny(element: JsonElement): Any? {
        return when (element) {
            is JsonObject -> jsonObjectToMap(element)
            is JsonArray -> jsonArrayToList(element)
            is JsonPrimitive -> {
                if (element.isString) {
                    element.content
                } else {
                    element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.contentOrNull
                }
            }
            else -> null
        }
    }
}