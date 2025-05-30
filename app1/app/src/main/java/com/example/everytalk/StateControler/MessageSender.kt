package com.example.everytalk.StateControler // 请确认包名与您的项目一致

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
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
import com.example.everytalk.model.SelectedMediaItem
import com.example.everytalk.ui.screens.viewmodel.HistoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
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
        private const val MAX_IMAGE_SIZE_BYTES = 4 * 1024 * 1024

        // MAX_OTHER_FILE_SIZE_BYTES 之前未被使用，如果需要文件大小限制，可以在ApiClient.kt中使用
        // private const val MAX_OTHER_FILE_SIZE_BYTES = 10 * 1024 * 1024
        private const val TARGET_IMAGE_WIDTH = 1024
        private const val TARGET_IMAGE_HEIGHT = 1024
        private const val JPEG_COMPRESSION_QUALITY = 80
        private const val CHAT_ATTACHMENTS_SUBDIR = "chat_attachments"
    }

    private suspend fun copyUriToAppInternalStorage(
        context: Context,
        sourceUri: Uri,
        messageIdHint: String,
        attachmentIndex: Int,
        originalFileName: String?
    ): String? {
        Log.d(
            TAG_MESSAGE_SENDER,
            "copyUriToAppInternalStorage: Called for sourceUri: $sourceUri, originalFileName: $originalFileName"
        )
        return withContext(Dispatchers.IO) {
            try {
                val MimeTypeMap = android.webkit.MimeTypeMap.getSingleton()
                val contentType = context.contentResolver.getType(sourceUri)
                Log.d(
                    TAG_MESSAGE_SENDER,
                    "copyUriToAppInternalStorage: Content type for $sourceUri is $contentType"
                )
                val extension = MimeTypeMap.getExtensionFromMimeType(contentType)
                    ?: originalFileName?.substringAfterLast('.', "")
                    ?: "bin"
                Log.d(
                    TAG_MESSAGE_SENDER,
                    "copyUriToAppInternalStorage: Determined extension: $extension"
                )

                val timeStamp: String =
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val safeOriginalName =
                    originalFileName?.replace("[^a-zA-Z0-9._-]".toRegex(), "_")?.take(30) ?: "file"
                val uniqueFileName =
                    "${safeOriginalName}_${messageIdHint}_${attachmentIndex}_${timeStamp}_${
                        UUID.randomUUID().toString().take(4)
                    }.$extension"
                Log.d(
                    TAG_MESSAGE_SENDER,
                    "copyUriToAppInternalStorage: Unique filename: $uniqueFileName"
                )

                val attachmentDir = File(context.filesDir, CHAT_ATTACHMENTS_SUBDIR)
                if (!attachmentDir.exists()) {
                    Log.d(
                        TAG_MESSAGE_SENDER,
                        "copyUriToAppInternalStorage: Attachment directory ${attachmentDir.absolutePath} does not exist, creating..."
                    )
                    if (attachmentDir.mkdirs()) {
                        Log.d(
                            TAG_MESSAGE_SENDER,
                            "copyUriToAppInternalStorage: Attachment directory created successfully."
                        )
                    } else {
                        Log.e(
                            TAG_MESSAGE_SENDER,
                            "copyUriToAppInternalStorage: Failed to create attachment directory."
                        )
                        return@withContext null
                    }
                } else {
                    Log.d(
                        TAG_MESSAGE_SENDER,
                        "copyUriToAppInternalStorage: Attachment directory ${attachmentDir.absolutePath} already exists."
                    )
                }
                val destinationFile = File(attachmentDir, uniqueFileName)
                Log.d(
                    TAG_MESSAGE_SENDER,
                    "copyUriToAppInternalStorage: Destination file path: ${destinationFile.absolutePath}"
                )

                context.contentResolver.openInputStream(sourceUri).use { inputStream ->
                    if (inputStream == null) {
                        Log.e(
                            TAG_MESSAGE_SENDER,
                            "copyUriToAppInternalStorage: Failed to open input stream for $sourceUri"
                        )
                        return@withContext null
                    }
                    FileOutputStream(destinationFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                if (!destinationFile.exists() || destinationFile.length() == 0L) {
                    Log.e(
                        TAG_MESSAGE_SENDER,
                        "copyUriToAppInternalStorage: Destination file does not exist or is empty after copy: ${destinationFile.absolutePath}"
                    )
                    if (destinationFile.exists()) destinationFile.delete()
                    return@withContext null
                }

                val authority = "${context.packageName}.provider"
                val fileProviderUri =
                    FileProvider.getUriForFile(context, authority, destinationFile)
                fileProviderUri.toString()
            } catch (e: Exception) {
                Log.e(
                    TAG_MESSAGE_SENDER,
                    "copyUriToAppInternalStorage: Error copying URI $sourceUri",
                    e
                )
                null
            }
        }
    }

    private suspend fun saveBitmapToAppInternalStorage(
        context: Context,
        bitmapToSave: Bitmap,
        messageIdHint: String,
        attachmentIndex: Int
    ): String? {
        return withContext(Dispatchers.IO) {
            var processedBitmap = bitmapToSave
            try {
                if (processedBitmap.isRecycled) return@withContext null

                if (processedBitmap.width > TARGET_IMAGE_WIDTH || processedBitmap.height > TARGET_IMAGE_HEIGHT) {
                    val scale = Math.min(
                        TARGET_IMAGE_WIDTH.toFloat() / processedBitmap.width,
                        TARGET_IMAGE_HEIGHT.toFloat() / processedBitmap.height
                    )
                    val newWidth = (processedBitmap.width * scale).toInt()
                    val newHeight = (processedBitmap.height * scale).toInt()
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

                if (bytes.size > MAX_IMAGE_SIZE_BYTES) return@withContext null

                val timeStamp: String =
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val uniqueFileName = "BMP_${messageIdHint}_${attachmentIndex}_${timeStamp}_${
                    UUID.randomUUID().toString().take(4)
                }.$fileExtension"

                val attachmentDir = File(context.filesDir, CHAT_ATTACHMENTS_SUBDIR)
                if (!attachmentDir.exists()) {
                    if (!attachmentDir.mkdirs()) return@withContext null
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
                Log.e(TAG_MESSAGE_SENDER, "saveBitmapToAppInternalStorage: Error saving Bitmap", e)
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
        Log.d(
            TAG_MESSAGE_SENDER,
            "sendMessage: Called. Text: '${textToActuallySend.take(50)}...', Attachments: ${attachments.size}"
        )
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
                    val persistentUriStr: String? = when (originalMediaItem) {
                        is SelectedMediaItem.ImageFromUri -> copyUriToAppInternalStorage(
                            application, originalMediaItem.uri, tempMessageIdForNaming, index,
                            getFileName(application.contentResolver, originalMediaItem.uri)
                        )

                        is SelectedMediaItem.ImageFromBitmap -> saveBitmapToAppInternalStorage(
                            application, originalMediaItem.bitmap, tempMessageIdForNaming, index
                        )

                        is SelectedMediaItem.GenericFile -> copyUriToAppInternalStorage(
                            application, originalMediaItem.uri, tempMessageIdForNaming, index,
                            originalMediaItem.displayName
                        )
                    }

                    if (persistentUriStr != null) {
                        val persistentFileProviderUri = Uri.parse(persistentUriStr)
                        val processedItemForUi: SelectedMediaItem = when (originalMediaItem) {
                            is SelectedMediaItem.ImageFromUri -> {
                                imageUriStringsForUiMessage.add(persistentUriStr)
                                SelectedMediaItem.ImageFromUri(
                                    persistentFileProviderUri,
                                    originalMediaItem.id
                                )
                            }

                            is SelectedMediaItem.ImageFromBitmap -> {
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
                                        ?: when (processedItemForUi) {
                                            is SelectedMediaItem.ImageFromUri -> "image/jpeg"
                                            is SelectedMediaItem.GenericFile -> processedItemForUi.mimeType
                                                ?: "application/octet-stream"

                                            else -> "application/octet-stream"
                                        }
                                val supportedInlineMimesForGemini = listOf(
                                    "image/png",
                                    "image/jpeg",
                                    "image/webp",
                                    "image/heic",
                                    "image/heif"
                                )
                                if (mimeTypeForApi.lowercase() in supportedInlineMimesForGemini) {
                                    var fileSize = -1L
                                    try {
                                        application.contentResolver.openFileDescriptor(
                                            persistentFileProviderUri,
                                            "r"
                                        )?.use { pfd -> fileSize = pfd.statSize }
                                    } catch (e: Exception) { /* Log error */
                                    }
                                    if (fileSize != -1L && fileSize <= MAX_IMAGE_SIZE_BYTES) {
                                        application.contentResolver.openInputStream(
                                            persistentFileProviderUri
                                        )?.use { inputStream ->
                                            val bytes = inputStream.readBytes()
                                            apiContentPartsForCurrentUserMessage.add(
                                                ApiContentPart.InlineData(
                                                    Base64.encodeToString(
                                                        bytes,
                                                        Base64.NO_WRAP
                                                    ), mimeTypeForApi
                                                )
                                            )
                                        }
                                    } else { /* Log warning: file too large for inline */
                                    }
                                } else { /* Log info: MIME not suitable for inline */
                                }
                            } catch (e: Exception) { /* Log error */
                            }
                        }
                    } else { /* Log error: UI persistence failed */
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
                        UiSender.User -> "user"; UiSender.AI -> "assistant"; else -> uiMsg.sender.toString()
                            .lowercase()
                    }
                    var messageAddedToHistory = false
                    if (shouldUsePartsApiMessage) {
                        val historyParts = mutableListOf<ApiContentPart>()
                        if (uiMsg.text.isNotBlank()) historyParts.add(ApiContentPart.Text(text = uiMsg.text.trim()))
                        if (historyParts.isNotEmpty()) {
                            apiMessagesForBackend.add(
                                0,
                                PartsApiMessage(
                                    role = roleForHistory,
                                    parts = historyParts,
                                    name = uiMsg.name
                                )
                            )
                            messageAddedToHistory = true
                        }
                    } else {
                        val historyContent = uiMsg.text.trim()
                        if (historyContent.isNotBlank() || (uiMsg.sender == UiSender.System && uiMsg.text.isNotBlank()) || (uiMsg.sender == UiSender.Tool && uiMsg.name != null)) {
                            apiMessagesForBackend.add(
                                0,
                                SimpleTextApiMessage(
                                    role = roleForHistory,
                                    content = historyContent,
                                    name = uiMsg.name
                                )
                            )
                            messageAddedToHistory = true
                        }
                    }
                    if (messageAddedToHistory) historyMessageCount++
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
                    } else if (attachments.isNotEmpty()) {
                        apiMessagesForBackend.add(
                            PartsApiMessage(
                                role = "user",
                                parts = listOf(ApiContentPart.Text(text = ""))
                            )
                        )
                    }
                } else {
                    if (textToActuallySend.isNotBlank()) {
                        apiMessagesForBackend.add(
                            SimpleTextApiMessage(
                                role = "user",
                                content = textToActuallySend
                            )
                        )
                        if (attachments.isNotEmpty()) viewModelScope.launch { showSnackbar("注意：当前模型配置可能不支持发送文件。") }
                    } else if (attachments.isNotEmpty()) {
                        apiMessagesForBackend.add(SimpleTextApiMessage(role = "user", content = ""))
                    }
                }

                if (apiMessagesForBackend.isEmpty() || apiMessagesForBackend.lastOrNull()?.role != "user") {
                    if (attachments.isNotEmpty() && textToActuallySend.isBlank()) {
                        if (shouldUsePartsApiMessage) apiMessagesForBackend.add(
                            PartsApiMessage(
                                role = "user",
                                parts = listOf(ApiContentPart.Text(text = ""))
                            )
                        )
                        else apiMessagesForBackend.add(
                            SimpleTextApiMessage(
                                role = "user",
                                content = ""
                            )
                        )
                    } else {
                        Log.e(
                            TAG_MESSAGE_SENDER,
                            "API messages list is empty or does not end with a user message. Aborting."
                        )
                        viewModelScope.launch { showSnackbar("无法发送消息：内部准备错误。") }
                        return@withContext
                    }
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
                    onMessagesProcessed = {
                        viewModelScope.launch {
                            historyManager.saveCurrentChatToHistoryIfNeeded()
                        }
                    }
                )
            }
        }
    }

    private fun getFileName(contentResolver: ContentResolver, uri: Uri): String? {
        if (uri == Uri.EMPTY) return "bitmap_image"
        var fileName: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val displayNameIndex =
                            cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                        fileName = cursor.getString(displayNameIndex)
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG_MESSAGE_SENDER, "Error getting file name from URI: $uri", e)
        }
        return fileName ?: uri.lastPathSegment
    }
}