package com.example.everytalk.statecontroler

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
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
import com.example.everytalk.model.SelectedMediaItem
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
        private const val MAX_IMAGE_SIZE_BYTES = 4 * 1024 * 1024
        private const val TARGET_IMAGE_WIDTH = 1024
        private const val TARGET_IMAGE_HEIGHT = 1024
        private const val JPEG_COMPRESSION_QUALITY = 80
        private const val CHAT_ATTACHMENTS_SUBDIR = "chat_attachments"
    }

    private suspend fun loadAndCompressBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                if (uri == Uri.EMPTY) return@withContext null

                // First, decode with inJustDecodeBounds=true to check dimensions
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }

                // Calculate inSampleSize
                options.inSampleSize = calculateInSampleSize(options, TARGET_IMAGE_WIDTH, TARGET_IMAGE_HEIGHT)

                // Decode bitmap with inSampleSize set
                options.inJustDecodeBounds = false
                options.inMutable = true // Make it mutable for further scaling if needed

                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            } catch (e: Exception) {
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
                context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: run {
                    return@withContext null
                }

                if (!destinationFile.exists() || destinationFile.length() == 0L) {
                    if (destinationFile.exists()) destinationFile.delete()
                    return@withContext null
                }

                val authority = "${context.packageName}.provider"
                FileProvider.getUriForFile(context, authority, destinationFile).toString()
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
            var processedBitmap = bitmapToSave
            try {
                if (processedBitmap.isRecycled) {
                    return@withContext null
                }

                if (processedBitmap.width > TARGET_IMAGE_WIDTH || processedBitmap.height > TARGET_IMAGE_HEIGHT) {
                    val aspectRatio =
                        processedBitmap.width.toFloat() / processedBitmap.height.toFloat()
                    val newWidth: Int
                    val newHeight: Int
                    if (processedBitmap.width > processedBitmap.height) {
                        newWidth = TARGET_IMAGE_WIDTH
                        newHeight = (newWidth / aspectRatio).toInt()
                    } else {
                        newHeight = TARGET_IMAGE_HEIGHT
                        newWidth = (newHeight * aspectRatio).toInt()
                    }
                    if (newWidth > 0 && newHeight > 0) {
                        processedBitmap =
                            Bitmap.createScaledBitmap(processedBitmap, newWidth, newHeight, true)
                    }
                }

                val outputStream = ByteArrayOutputStream()
                val fileExtension: String
                val compressFormat = if (processedBitmap.hasAlpha()) {
                    fileExtension = "png"; Bitmap.CompressFormat.PNG
                } else {
                    fileExtension = "jpg"; Bitmap.CompressFormat.JPEG
                }
                processedBitmap.compress(compressFormat, JPEG_COMPRESSION_QUALITY, outputStream)
                val bytes = outputStream.toByteArray()

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
                val authority = "${context.packageName}.provider"
                FileProvider.getUriForFile(context, authority, destinationFile).toString()
            } catch (e: Exception) {
                null
            }
        }
    }

    fun sendMessage(
        messageText: String,
        isFromRegeneration: Boolean = false,
        attachments: List<SelectedMediaItem> = emptyList()
    ) {
        val textToActuallySend = messageText.trim()

        if (textToActuallySend.isBlank() && attachments.isEmpty()) {
            viewModelScope.launch { showSnackbar("请输入消息内容或选择项目") }
            return
        }
        val currentConfig = stateHolder._selectedApiConfig.value ?: run {
            viewModelScope.launch { showSnackbar("请先选择 API 配置") }
            return
        }

        viewModelScope.launch {
            val modelIsGeminiType = currentConfig.model.lowercase().startsWith("gemini")
            val shouldUsePartsApiMessage =
                currentConfig.provider.equals("google", ignoreCase = true) && modelIsGeminiType
            val providerForRequestBackend = currentConfig.provider

            val processedAttachmentsForUiMessage = mutableListOf<SelectedMediaItem>()
            val imageUriStringsForUiMessage = mutableListOf<String>()
            val apiContentPartsForCurrentUserMessage = mutableListOf<ApiContentPart>()
            val attachmentsForApiClient = attachments.toList()

            if (shouldUsePartsApiMessage && textToActuallySend.isNotBlank()) {
                apiContentPartsForCurrentUserMessage.add(ApiContentPart.Text(text = textToActuallySend))
            }

            if (attachments.isNotEmpty()) {
                val tempMessageIdForNaming = UUID.randomUUID().toString().take(8)
                attachments.forEachIndexed { index, originalMediaItem ->
                    var persistentUriStr: String? = null
                    val itemUri = when (originalMediaItem) {
                        is SelectedMediaItem.ImageFromUri -> originalMediaItem.uri
                        is SelectedMediaItem.GenericFile -> originalMediaItem.uri
                        is SelectedMediaItem.ImageFromBitmap -> Uri.EMPTY
                    }
                    val originalFileNameForHint = getFileName(application.contentResolver, itemUri)
                        ?: (originalMediaItem as? SelectedMediaItem.GenericFile)?.displayName
                        ?: (if (originalMediaItem is SelectedMediaItem.ImageFromBitmap) "camera_shot" else "attachment")


                    when (originalMediaItem) {
                        is SelectedMediaItem.ImageFromUri -> {
                            val bitmap = loadAndCompressBitmapFromUri(application, originalMediaItem.uri)
                            if (bitmap != null) {
                                persistentUriStr = saveBitmapToAppInternalStorage(
                                    application,
                                    bitmap,
                                    tempMessageIdForNaming,
                                    index,
                                    originalFileNameForHint
                                )
                            } else {
                                showSnackbar("无法加载或压缩图片: ${originalFileNameForHint}")
                            }
                        }

                        is SelectedMediaItem.ImageFromBitmap -> {
                            persistentUriStr = saveBitmapToAppInternalStorage(
                                application,
                                originalMediaItem.bitmap,
                                tempMessageIdForNaming,
                                index,
                                originalFileNameForHint
                            )
                        }

                        is SelectedMediaItem.GenericFile -> {
                            persistentUriStr = copyUriToAppInternalStorage(
                                application,
                                originalMediaItem.uri,
                                tempMessageIdForNaming,
                                index,
                                originalMediaItem.displayName
                            )
                        }
                    }

                    if (persistentUriStr != null) {
                        val persistentFileProviderUri = Uri.parse(persistentUriStr)
                        val processedItemForUi: SelectedMediaItem = when (originalMediaItem) {
                            is SelectedMediaItem.ImageFromUri, is SelectedMediaItem.ImageFromBitmap -> {
                                imageUriStringsForUiMessage.add(persistentUriStr)
                                SelectedMediaItem.ImageFromUri(
                                    persistentFileProviderUri,
                                    originalMediaItem.id
                                )
                            }

                            is SelectedMediaItem.GenericFile -> SelectedMediaItem.GenericFile(
                                uri = persistentFileProviderUri,
                                displayName = originalMediaItem.displayName,
                                mimeType = originalMediaItem.mimeType,
                                id = originalMediaItem.id
                            )
                        }
                        processedAttachmentsForUiMessage.add(processedItemForUi)

                        if (shouldUsePartsApiMessage) {
                            try {
                                val mimeTypeForApi =
                                    application.contentResolver.getType(persistentFileProviderUri)
                                        ?: (processedItemForUi as? SelectedMediaItem.GenericFile)?.mimeType
                                        ?: "application/octet-stream"

                                val supportedImageMimesForGemini = listOf(
                                    "image/png", "image/jpeg", "image/webp", "image/heic", "image/heif"
                                )
                                val supportedAudioMimesForGemini = listOf(
                                    "audio/mp3", "audio/mpeg", "audio/wav", "audio/x-wav", "audio/aac",
                                    "audio/ogg", "audio/opus", "audio/flac", "audio/amr", "audio/aiff", "audio/x-m4a"
                                )
                                val allSupportedInlineMimes = supportedImageMimesForGemini + supportedAudioMimesForGemini

                                if (mimeTypeForApi.lowercase() in allSupportedInlineMimes) {
                                    var fileSize = -1L
                                    try {
                                        application.contentResolver.openFileDescriptor(
                                            persistentFileProviderUri,
                                            "r"
                                        )?.use { pfd -> fileSize = pfd.statSize }
                                    } catch (e: Exception) {
                                    }

                                    if (fileSize != -1L && fileSize <= MAX_IMAGE_SIZE_BYTES) {
                                        application.contentResolver.openInputStream(
                                            persistentFileProviderUri
                                        )?.use { inputStream ->
                                            val bytes = inputStream.readBytes()
                                            apiContentPartsForCurrentUserMessage.add(
                                                ApiContentPart.InlineData(
                                                    base64Data = Base64.encodeToString(
                                                        bytes,
                                                        Base64.NO_WRAP
                                                    ), mimeType = mimeTypeForApi
                                                )
                                            )
                                        }
                                    } else {
                                        apiContentPartsForCurrentUserMessage.add(
                                            ApiContentPart.FileUri(
                                                uri = persistentUriStr,
                                                mimeType = mimeTypeForApi
                                            )
                                        )
                                    }
                                } else if (processedItemForUi is SelectedMediaItem.GenericFile) {
                                    apiContentPartsForCurrentUserMessage.add(
                                        ApiContentPart.FileUri(
                                            uri = persistentUriStr,
                                            mimeType = mimeTypeForApi
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                            }
                        }
                    } else {
                        showSnackbar("无法处理附件: ${originalFileNameForHint}")
                    }
                }
            }

            val newUserMessageForUi = UiMessage(
                id = "user_${UUID.randomUUID()}", text = textToActuallySend, sender = UiSender.User,
                timestamp = System.currentTimeMillis(), contentStarted = true,
                imageUrls = imageUriStringsForUiMessage.ifEmpty { null },
                attachments = processedAttachmentsForUiMessage.ifEmpty { null }
            )

            withContext(Dispatchers.Main.immediate) {
                stateHolder.messageAnimationStates[newUserMessageForUi.id] = true
                stateHolder.messages.add(newUserMessageForUi)
                if (!isFromRegeneration) stateHolder._text.value = ""
                stateHolder.clearSelectedMedia()
                triggerScrollToBottom()
            }

            withContext(Dispatchers.IO) {
                val apiMessagesForBackend = mutableListOf<AbstractApiMessage>()
                val messagesInChatUiSnapshot = stateHolder.messages.toList()
                val historyUiMessages =
                    if (messagesInChatUiSnapshot.lastOrNull()?.id == newUserMessageForUi.id) {
                        messagesInChatUiSnapshot.dropLast(1)
                    } else {
                        messagesInChatUiSnapshot
                    }

                var historyMessageCount = 0
                val maxHistoryMessages = 20
                for (uiMsg in historyUiMessages.asReversed()) {
                    if (historyMessageCount >= maxHistoryMessages) break
                    val roleForHistory = when (uiMsg.sender) {
                        UiSender.User -> "user"
                        UiSender.AI -> "assistant"
                        UiSender.System -> "system"
                        UiSender.Tool -> "tool"
                    }
                    if (uiMsg.text.isNotBlank()) {
                        if (shouldUsePartsApiMessage) {
                            apiMessagesForBackend.add(
                                0,
                                PartsApiMessage(
                                    role = roleForHistory,
                                    parts = listOf(ApiContentPart.Text(text = uiMsg.text.trim()))
                                )
                            )
                        } else {
                            apiMessagesForBackend.add(
                                0,
                                SimpleTextApiMessage(
                                    role = roleForHistory,
                                    content = uiMsg.text.trim()
                                )
                            )
                        }
                        historyMessageCount++
                    }
                }

                if (shouldUsePartsApiMessage) {
                    if (apiContentPartsForCurrentUserMessage.isNotEmpty()) {
                        apiMessagesForBackend.add(
                            PartsApiMessage(
                                role = "user",
                                parts = apiContentPartsForCurrentUserMessage
                            )
                        )
                    } else if (textToActuallySend.isNotBlank()) {
                        apiMessagesForBackend.add(
                            PartsApiMessage(
                                role = "user",
                                parts = listOf(ApiContentPart.Text(text = textToActuallySend))
                            )
                        )
                    } else if (attachments.isNotEmpty() && processedAttachmentsForUiMessage.isEmpty()) {
                        apiMessagesForBackend.add(
                            PartsApiMessage(
                                role = "user",
                                parts = listOf(ApiContentPart.Text(text = ""))
                            )
                        )
                    } else if (textToActuallySend.isBlank() && attachments.isEmpty()) {
                        withContext(Dispatchers.Main.immediate) {
                            stateHolder.messages.remove(newUserMessageForUi)
                            stateHolder.messageAnimationStates.remove(newUserMessageForUi.id)
                        }
                        return@withContext
                    }
                } else {
                    if (textToActuallySend.isNotBlank() || attachments.isNotEmpty()) {
                        apiMessagesForBackend.add(
                            SimpleTextApiMessage(
                                role = "user",
                                content = textToActuallySend
                            )
                        )
                    }
                }

                if (apiMessagesForBackend.isEmpty() || apiMessagesForBackend.lastOrNull()?.role != "user") {
                    viewModelScope.launch { showSnackbar("无法发送消息：内部准备错误。") }
                    withContext(Dispatchers.Main.immediate) {
                        stateHolder.messages.remove(newUserMessageForUi)
                        stateHolder.messageAnimationStates.remove(newUserMessageForUi.id)
                    }
                    return@withContext
                }

                val chatRequestForApi = ChatRequest(
                    messages = apiMessagesForBackend,
                    provider = providerForRequestBackend,
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
                    qwenEnableSearch = if (currentConfig.model.lowercase()
                            .contains("qwen")
                    ) stateHolder._isWebSearchEnabled.value else null,
                )

                apiHandler.streamChatResponse(
                    requestBody = chatRequestForApi,
                    attachmentsToPassToApiClient = attachmentsForApiClient,
                    applicationContextForApiClient = application,
                    userMessageTextForContext = textToActuallySend,
                    afterUserMessageId = newUserMessageForUi.id,
                    onMessagesProcessed = { viewModelScope.launch { historyManager.saveCurrentChatToHistoryIfNeeded() } },
                    onRequestFailed = {
                        viewModelScope.launch(Dispatchers.Main) {
                            stateHolder.messages.remove(newUserMessageForUi)
                            stateHolder.messageAnimationStates.remove(newUserMessageForUi.id)
                            showSnackbar("发送失败，请检查网络或API配置。")
                        }
                    }
                )
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