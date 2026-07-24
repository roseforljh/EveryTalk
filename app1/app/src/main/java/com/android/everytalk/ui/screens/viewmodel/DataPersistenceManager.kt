package com.android.everytalk.ui.screens.viewmodel
import com.android.everytalk.statecontroller.*

import android.content.Context
import android.util.Log
import com.android.everytalk.data.DataClass.ApiConfig
import java.io.File
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.database.RoomDataSource
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.statecontroller.ConversationScrollState
import com.android.everytalk.statecontroller.rethrowIfCancellation
import com.android.everytalk.statecontroller.safeApiConfigSummary
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.VoiceBackendConfig
import com.android.everytalk.ui.components.toRecoveredMarkdown
import com.android.everytalk.ui.components.MarkdownPart
import com.android.everytalk.util.ConversationNameHelper
import com.android.everytalk.util.message.findMarkdownImageReferences
import com.android.everytalk.util.message.replaceMarkdownImageSources
import com.android.everytalk.util.storage.FileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import coil3.ImageLoader
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.file.Files

internal val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
internal val URI_SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
internal const val MAX_LOCAL_MEDIA_SOURCE_CHARS = 8_192
internal const val MAX_TEMP_CAMERA_FILE_AGE_MS = 7L * 24L * 60L * 60L * 1000L

internal fun migrateApiConfigIds(
    mapping: Map<String, String>,
    idMigrations: Map<String, String>,
): Map<String, String> = mapping.mapValues { (_, configId) -> idMigrations[configId] ?: configId }

internal data class MediaDeletionCandidates(
    val localSources: Set<String>,
    val remoteUrls: Set<String>,
)

internal fun collectMediaDeletionCandidates(messages: Iterable<Message>): MediaDeletionCandidates {
    val localSources = linkedSetOf<String>()
    val remoteUrls = linkedSetOf<String>()

    fun addSource(source: String?) {
        val value = source?.trim()?.takeIf(String::isNotEmpty) ?: return
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            remoteUrls += value
        } else if (mediaSourceToLocalPath(value) != null) {
            localSources += value
        }
    }

    messages.forEach { message ->
        message.attachments.forEach { attachment ->
            val source = when (attachment) {
                is SelectedMediaItem.ImageFromUri -> attachment.filePath
                is SelectedMediaItem.GenericFile -> attachment.filePath
                is SelectedMediaItem.Audio -> null
                is SelectedMediaItem.ImageFromBitmap -> attachment.filePath
            }
            addSource(source)
        }
        message.imageUrls.orEmpty().forEach(::addSource)
    }

    return MediaDeletionCandidates(localSources, remoteUrls)
}

internal fun mediaSourceToLocalPath(source: String): String? {
    val value = source.trim()
    if (value.isEmpty() || value.length > MAX_LOCAL_MEDIA_SOURCE_CHARS) return null

    if (value.startsWith("file:", ignoreCase = true)) {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("file", ignoreCase = true) || uri.isOpaque || !uri.rawAuthority.isNullOrEmpty()) {
            return null
        }
        val path = uri.path?.takeIf(String::isNotBlank) ?: return null
        return if (File.separatorChar == '\\' && path.matches(Regex("^/[A-Za-z]:/.*"))) {
            path.drop(1)
        } else {
            path
        }
    }

    if (URI_SCHEME_PREFIX.containsMatchIn(value) && !WINDOWS_ABSOLUTE_PATH.matches(value)) return null
    return value.takeIf { File(it).isAbsolute || it.startsWith('/') || WINDOWS_ABSOLUTE_PATH.matches(it) }
}

internal fun resolveOwnedMediaFile(source: String, allowedDirectories: Iterable<File>): File? {
    val localPath = mediaSourceToLocalPath(source) ?: return null
    val rawCandidatePath = runCatching { File(localPath).toPath() }.getOrNull() ?: return null
    if (!rawCandidatePath.isAbsolute) return null
    val candidatePath = runCatching { rawCandidatePath.normalize() }.getOrNull()
        ?: return null

    for (allowedDirectory in allowedDirectories) {
        try {
            val allowedPath = allowedDirectory.toPath().toAbsolutePath().normalize()
            if (candidatePath == allowedPath || !candidatePath.startsWith(allowedPath)) continue

            val relativePath = allowedPath.relativize(candidatePath)
            var currentPath = allowedPath
            var containsSymbolicLink = false
            for (segment in relativePath) {
                currentPath = currentPath.resolve(segment)
                if (Files.isSymbolicLink(currentPath)) {
                    containsSymbolicLink = true
                    break
                }
            }
            if (containsSymbolicLink) continue

            val allowedRealPath = allowedPath.toRealPath()
            val candidateRealPath = candidatePath.toRealPath()
            val allowedCanonicalPath = allowedDirectory.canonicalFile.toPath()
            val candidateCanonicalPath = File(localPath).canonicalFile.toPath()
            if (!candidateRealPath.startsWith(allowedRealPath) ||
                !candidateCanonicalPath.startsWith(allowedCanonicalPath) ||
                !Files.isRegularFile(candidateRealPath)
            ) {
                continue
            }
            return candidateRealPath.toFile()
        } catch (_: Exception) {
            // 路径不存在、不可访问或无法规范化时一律不删除。
        }
    }
    return null
}

internal data class InlineImageMigrationResult(
    val messages: List<Message>,
    val changed: Boolean,
    val failed: Boolean,
    val persistedSources: Set<String> = emptySet(),
)

internal fun collectReferencedAttachmentPaths(messages: Iterable<Message>): Set<String> = buildSet {
    fun addLocalPath(source: String?) {
        source?.let(::mediaSourceToLocalPath)
            ?.let { runCatching { File(it).canonicalPath }.getOrNull() }
            ?.let(::add)
    }
    messages.forEach { message ->
        message.imageUrls.orEmpty().forEach(::addLocalPath)
        message.attachments.forEach { attachment ->
            val path = when (attachment) {
                is SelectedMediaItem.ImageFromUri -> attachment.filePath
                is SelectedMediaItem.GenericFile -> attachment.filePath
                is SelectedMediaItem.Audio -> null
                is SelectedMediaItem.ImageFromBitmap -> attachment.filePath
            }
            addLocalPath(path)
        }
    }
}

internal suspend fun migrateConversationInlineImages(
    messages: List<Message>,
    persistSource: suspend (source: String, messageId: String, index: Int) -> String?,
    deletePersistedSource: (String) -> Unit = {},
): InlineImageMigrationResult {
    val createdSources = linkedSetOf<String>()
    var completed = false
    try {
        val migratedMessages = mutableListOf<Message>()
        var changed = false

        messages.forEach { message ->
            val partSources = message.parts.filterIsInstance<MarkdownPart.InlineImage>()
                .map { part -> "data:${part.mimeType};base64,${part.base64Data}" }
            val sources = linkedSetOf<String>().apply {
                findMarkdownImageReferences(message.text)
                    .map { it.source }
                    .filterTo(this) { it.startsWith("data:image", ignoreCase = true) }
                message.imageUrls.orEmpty()
                    .filterTo(this) { it.startsWith("data:image", ignoreCase = true) }
                addAll(partSources)
            }
            if (sources.isEmpty()) {
                migratedMessages += message
                return@forEach
            }

            val replacements = linkedMapOf<String, String>()
            sources.forEachIndexed { index, source ->
                val persisted = persistSource(source, message.id, index)
                if (persisted.isNullOrBlank()) {
                    return InlineImageMigrationResult(messages, changed = false, failed = true)
                }
                replacements[source] = persisted
                createdSources += persisted
            }

            var migratedText = replaceMarkdownImageSources(message.text, replacements)
            partSources.forEach { source ->
                val persisted = replacements.getValue(source)
                if (!migratedText.contains(persisted)) {
                    migratedText = when {
                        migratedText.isBlank() -> "![Generated Image]($persisted)"
                        else -> migratedText.trimEnd() + "\n\n![Generated Image]($persisted)"
                    }
                }
            }
            val migratedParts = message.parts.mapNotNull { part ->
                when (part) {
                    is MarkdownPart.Text -> part.copy(
                        content = replaceMarkdownImageSources(part.content, replacements),
                    )
                    is MarkdownPart.InlineImage -> null
                    else -> part
                }
            }
            val migratedUrls = buildList {
                message.imageUrls.orEmpty().forEach { source -> add(replacements[source] ?: source) }
                replacements.values.forEach(::add)
            }.distinct()
            val migratedMessage = message.copy(
                text = migratedText,
                imageUrls = migratedUrls.takeIf { it.isNotEmpty() },
                parts = migratedParts,
            )
            changed = changed || migratedMessage != message
            migratedMessages += migratedMessage
        }

        completed = true
        return InlineImageMigrationResult(
            messages = migratedMessages,
            changed = changed,
            failed = false,
            persistedSources = createdSources,
        )
    } finally {
        if (!completed) {
            createdSources.forEach { source -> runCatching { deletePersistedSource(source) } }
        }
    }
}

class DataPersistenceManager(
    internal val context: Context,
    internal val stateHolder: ViewModelStateHolder,
    internal val viewModelScope: CoroutineScope,
    internal val imageLoader: ImageLoader
) {
    internal val TAG = "PersistenceManager"
    internal val conversationGroupsSaveMutex = kotlinx.coroutines.sync.Mutex()
    internal val conversationScrollStatesSaveMutex = kotlinx.coroutines.sync.Mutex()
    
    // Room 数据源
    internal val roomDataSource by lazy { RoomDataSource(context) }
    internal val fileManager by lazy { FileManager(context) }
    internal val protectedTextSessionIds = linkedSetOf<String>()
    internal val protectedImageSessionIds = linkedSetOf<String>()
    @Volatile internal var protectLastOpenTextSession = false
    @Volatile internal var protectLastOpenImageSession = false
    
    // 默认配置初始化标志位 (存储在 Room 数据库中)
    internal val KEY_DEFAULT_CONFIGS_INITIALIZED = "default_configs_initialized_v1"
    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun loadCustomProviders(): Set<String> {
        return withContext(Dispatchers.IO) {
            roomDataSource.loadCustomProviders()
        }
    }

    suspend fun saveCustomProviders(providers: Set<String>) {
        withContext(Dispatchers.IO) {
            roomDataSource.saveCustomProviders(providers)
        }
    }

    suspend fun loadExternalWebSearchConfigs() = withContext(Dispatchers.IO) {
        roomDataSource.loadExternalWebSearchConfigs()
    }

    suspend fun saveExternalWebSearchConfigs(configs: List<com.android.everytalk.data.network.ExternalWebSearchProviderConfig>) {
        withContext(Dispatchers.IO) {
            roomDataSource.saveExternalWebSearchConfigs(configs)
        }
    }

    suspend fun loadSelectedExternalWebSearchProviderId(): String? = withContext(Dispatchers.IO) {
        roomDataSource.loadSelectedExternalWebSearchProviderId()
    }

    suspend fun saveSelectedExternalWebSearchProviderId(providerId: String?) {
        withContext(Dispatchers.IO) {
            roomDataSource.saveSelectedExternalWebSearchProviderId(providerId)
        }
    }

    suspend fun persistMessageImageSource(
        source: String,
        messageId: String,
        index: Int,
    ): String? {
        return if (source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true)
        ) {
            withTimeoutOrNull(15_000L) {
                fileManager.persistMessageImageSource(source, messageId, index)
            }
        } else {
            fileManager.persistMessageImageSource(source, messageId, index)
        }
    }

    /** 将历史消息中的图片来源统一归档；失败时保留原记录，交由后续迁移重试。 */
    internal suspend fun persistInlineAndRemoteImages(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return messages
        return messages.map { message ->
            val originalUrls = message.imageUrls.orEmpty()
            if (originalUrls.isEmpty()) return@map message

            val persistedUrls = originalUrls.mapIndexed { index, source ->
                when {
                    source.startsWith("file://", ignoreCase = true) || source.startsWith("/") -> source
                    source.startsWith("data:image", ignoreCase = true) ||
                        source.startsWith("http://", ignoreCase = true) ||
                        source.startsWith("https://", ignoreCase = true) ||
                        source.startsWith("content://", ignoreCase = true) -> {
                        persistMessageImageSource(source, message.id, index) ?: source
                    }
                    else -> source
                }
            }
            if (persistedUrls == originalUrls) message else message.copy(imageUrls = persistedUrls)
        }
    }

    internal fun protectedSessionIds(isImageGeneration: Boolean): Set<String> = synchronized(this) {
        if (isImageGeneration) protectedImageSessionIds.toSet() else protectedTextSessionIds.toSet()
    }

    internal fun protectSessions(isImageGeneration: Boolean, sessionIds: Collection<String>) {
        synchronized(this) {
            val target = if (isImageGeneration) protectedImageSessionIds else protectedTextSessionIds
            target += sessionIds
        }
    }

    internal fun unprotectSession(isImageGeneration: Boolean, sessionId: String) {
        synchronized(this) {
            val target = if (isImageGeneration) protectedImageSessionIds else protectedTextSessionIds
            target -= sessionId
        }
    }

    internal fun deleteMigratedImageFile(path: String) {
        runCatching {
            val attachmentDir = File(context.filesDir, "chat_attachments").canonicalFile
            val file = File(path).canonicalFile
            if (file.path.startsWith(attachmentDir.path + File.separator)) file.delete()
        }
    }

    internal suspend fun migrateLoadedHistorySessions(
        loadResult: com.android.everytalk.data.database.SessionHistoryLoadResult,
        isImageGeneration: Boolean,
        onLoadWarning: suspend (String) -> Unit,
    ): List<List<Message>> {
        if (loadResult.failedSessionIds.isNotEmpty()) {
            protectSessions(isImageGeneration, loadResult.failedSessionIds)
            Log.w(
                TAG,
                "History load failed sessions: mode=${if (isImageGeneration) "image" else "text"}, ids=${loadResult.failedSessionIds}",
            )
            onLoadWarning("部分历史加载失败，原数据已保留")
        }

        var migrationWarningRequired = false
        return loadResult.sessions.map { loadedSession ->
            val migration = migrateConversationInlineImages(
                messages = loadedSession.messages,
                persistSource = ::persistMessageImageSource,
                deletePersistedSource = ::deleteMigratedImageFile,
            )
            if (migration.failed) {
                unprotectSession(isImageGeneration, loadedSession.sessionId)
                migrationWarningRequired = true
                loadedSession.messages
            } else {
                if (migration.changed) {
                    try {
                        roomDataSource.saveLoadedHistorySession(
                            sessionId = loadedSession.sessionId,
                            messages = migration.messages,
                            isImageGeneration = isImageGeneration,
                        )
                        unprotectSession(isImageGeneration, loadedSession.sessionId)
                        migration.messages
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        migration.persistedSources.forEach(::deleteMigratedImageFile)
                        unprotectSession(isImageGeneration, loadedSession.sessionId)
                        migrationWarningRequired = true
                        Log.w(
                            TAG,
                            "History image migration writeback failed: sessionId=${loadedSession.sessionId}, type=${exception::class.simpleName}",
                        )
                        loadedSession.messages
                    }
                } else {
                    unprotectSession(isImageGeneration, loadedSession.sessionId)
                    migration.messages
                }
            }
        }.also {
            if (migrationWarningRequired) {
                onLoadWarning("部分历史图片迁移失败，原数据已保留")
            }
        }
    }


    fun loadInitialData(
        loadLastChat: Boolean = true,
        onLoadWarning: (String) -> Unit = {},
        onLoadingComplete: (initialConfigPresent: Boolean, initialHistoryPresent: Boolean) -> Unit
    ) {
        loadInitialDataInternal(loadLastChat, onLoadWarning, onLoadingComplete)
    }
    suspend fun clearAllChatHistory() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "clearAllChatHistory: 请求清除聊天历史...")
            // 清除 Room 数据库中的历史
            roomDataSource.clearChatHistory()
            roomDataSource.clearImageGenerationHistory()
            synchronized(this@DataPersistenceManager) {
                protectedTextSessionIds.clear()
                protectedImageSessionIds.clear()
            }
            protectLastOpenTextSession = false
            protectLastOpenImageSession = false
            Log.i(TAG, "clearAllChatHistory: Room 和 SP 中的聊天历史已清除。")
        }
    }

    suspend fun clearHistoryExplicitly(isImageGeneration: Boolean) {
        withContext(Dispatchers.IO) {
            if (isImageGeneration) {
                roomDataSource.clearImageGenerationHistory()
                roomDataSource.saveLastOpenImageGenerationChat(emptyList())
                synchronized(this@DataPersistenceManager) { protectedImageSessionIds.clear() }
                protectLastOpenImageSession = false
            } else {
                roomDataSource.clearChatHistory()
                roomDataSource.saveLastOpenChat(emptyList())
                synchronized(this@DataPersistenceManager) { protectedTextSessionIds.clear() }
                protectLastOpenTextSession = false
            }
        }
    }

    suspend fun saveApiConfigs(configsToSave: List<ApiConfig>, isImageGen: Boolean = false) {
        withContext(Dispatchers.IO) {
            if (isImageGen) {
                Log.d(TAG, "saveApiConfigs: 保存 ${configsToSave.size} 个图像生成 API 配置到 RoomDataSource...")
                roomDataSource.saveImageGenApiConfigs(configsToSave)
                Log.i(TAG, "saveApiConfigs: 图像生成 API 配置已通过 RoomDataSource 保存。")
            } else {
                Log.d(TAG, "saveApiConfigs: 保存 ${configsToSave.size} 个 API 配置到 RoomDataSource...")
                roomDataSource.saveApiConfigs(configsToSave)
                Log.i(TAG, "saveApiConfigs: API 配置已通过 RoomDataSource 保存。")
            }
        }
    }

    suspend fun saveChatHistory(historyToSave: List<List<Message>>, isImageGeneration: Boolean = false) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "saveChatHistory: 保存 ${historyToSave.size} 条对话到 Room...")
            val finalHistory = if (isImageGeneration) {
                // 将 data:image 与 http(s) 图片先落盘，替换为本地路径，避免远端URL过期
                historyToSave.map { conv -> persistInlineAndRemoteImages(conv) }
            } else {
                historyToSave
            }
            // 使用 Room 保存历史
            if (isImageGeneration) {
                roomDataSource.saveImageGenerationHistory(
                    history = finalHistory,
                    protectedSessionIds = protectedSessionIds(isImageGeneration = true),
                )
            } else {
                roomDataSource.saveChatHistory(
                    history = finalHistory,
                    protectedSessionIds = protectedSessionIds(isImageGeneration = false),
                )
            }
            Log.i(TAG, "saveChatHistory: 聊天历史已通过 Room 保存。")
        }
    }

    internal fun cleanupExpiredCameraFiles() {
        val directory = File(context.filesDir, "chat_images_temp")
        val cutoff = System.currentTimeMillis() - MAX_TEMP_CAMERA_FILE_AGE_MS
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() in 1..cutoff) {
                runCatching { file.delete() }
                    .onFailure { Log.w(TAG, "Failed to delete expired camera file: ${file.absolutePath}", it) }
            }
        }
    }

    suspend fun saveHistorySession(
        sessionId: String,
        messages: List<Message>,
        isImageGeneration: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            val finalMessages = if (isImageGeneration) persistInlineAndRemoteImages(messages) else messages
            roomDataSource.saveLoadedHistorySession(sessionId, finalMessages, isImageGeneration)
        }
    }

    suspend fun deleteHistorySession(sessionId: String) {
        withContext(Dispatchers.IO) {
            roomDataSource.deleteHistorySession(sessionId)
        }
    }


    suspend fun saveSelectedConfigIdentifier(configId: String?, isImageGen: Boolean = false) {
        withContext(Dispatchers.IO) {
            if (isImageGen) {
                Log.d(TAG, "saveSelectedConfigIdentifier: 保存选中的图像生成配置ID '$configId' 到 RoomDataSource...")
                roomDataSource.saveSelectedImageGenConfigId(configId)
                Log.i(TAG, "saveSelectedConfigIdentifier: 选中的图像生成配置ID已通过 RoomDataSource 保存。")
            } else {
                Log.d(TAG, "saveSelectedConfigIdentifier: 保存选中配置ID '$configId' 到 RoomDataSource...")
                roomDataSource.saveSelectedConfigId(configId)
                Log.i(TAG, "saveSelectedConfigIdentifier: 选中配置ID已通过 RoomDataSource 保存。")
            }
        }
    }

    suspend fun saveConversationFunctionToggleStates(
        states: Map<String, com.android.everytalk.statecontroller.ConversationFunctionToggleState>
    ) {
        withContext(Dispatchers.IO) {
            val jsonString = json.encodeToString(
                kotlinx.serialization.builtins.MapSerializer(
                    String.serializer(),
                    com.android.everytalk.statecontroller.ConversationFunctionToggleState.serializer()
                ),
                states
            )
            roomDataSource.setSetting("conversation_function_toggle_states", jsonString)
        }
    }

    suspend fun loadConversationFunctionToggleStates(): Map<String, com.android.everytalk.statecontroller.ConversationFunctionToggleState> {
        return withContext(Dispatchers.IO) {
            val jsonString = roomDataSource.getSetting("conversation_function_toggle_states")
            if (jsonString.isNullOrBlank()) {
                emptyMap()
            } else {
                try {
                    json.decodeFromString(
                        kotlinx.serialization.builtins.MapSerializer(
                            String.serializer(),
                            com.android.everytalk.statecontroller.ConversationFunctionToggleState.serializer()
                        ),
                        jsonString
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "loadConversationFunctionToggleStates 失败", e)
                    emptyMap()
                }
            }
        }
    }
    
    // 新增：持久化保存"会话ID -> GenerationConfig"映射
    suspend fun saveConversationParameters(parameters: Map<String, GenerationConfig>) {
        withContext(Dispatchers.IO) {
            try {
                roomDataSource.saveConversationParameters(parameters)
                Log.d(TAG, "saveConversationParameters: 已持久化 ${parameters.size} 个会话参数映射")
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Log.e(TAG, "saveConversationParameters 失败", e)
            }
        }
    }

    suspend fun loadConversationParameters(): Map<String, GenerationConfig> {
        return withContext(Dispatchers.IO) {
            try {
                roomDataSource.loadConversationParameters()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Log.e(TAG, "loadConversationParameters 失败", e)
                emptyMap()
            }
        }
    }

    suspend fun saveConversationApiConfigIds(mapping: Map<String, String>) {
        withContext(Dispatchers.IO) {
            try {
                roomDataSource.saveConversationApiConfigIds(mapping)
                Log.d(TAG, "saveConversationApiConfigIds: 已持久化 ${mapping.size} 个会话配置映射")
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Log.e(TAG, "saveConversationApiConfigIds 失败", e)
            }
        }
    }

    suspend fun clearAllApiConfigData(isImageGen: Boolean? = null) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "clearAllApiConfigData: 清除配置，模态=${isImageGen ?: "全部"}")
            if (isImageGen != true) {
                roomDataSource.clearApiConfigs()
                roomDataSource.saveSelectedConfigId(null)
            }
            if (isImageGen != false) {
                roomDataSource.clearImageGenApiConfigs()
                roomDataSource.saveSelectedImageGenConfigId(null)
            }
            Log.i(TAG, "clearAllApiConfigData: API配置数据已通过 RoomDataSource 清除。")
        }
    }
    suspend fun saveLastOpenChat(messages: List<Message>, isImageGeneration: Boolean = false) {
        if (isImageGeneration && protectLastOpenImageSession) return
        if (!isImageGeneration && protectLastOpenTextSession) return
        android.util.Log.d("DataPersistenceManager", "=== SAVE LAST OPEN CHAT START ===")
        android.util.Log.d("DataPersistenceManager", "Saving ${messages.size} messages, isImageGeneration: $isImageGeneration")
        
        messages.forEachIndexed { index, message -> 
            android.util.Log.d("DataPersistenceManager", "Message $index (${message.id}): text length=${message.text.length}, parts=${message.parts.size}, contentStarted=${message.contentStarted}")
            android.util.Log.d("DataPersistenceManager", "  Sender: ${message.sender}, IsError: ${message.isError}")
            message.parts.forEachIndexed { partIndex, part -> 
                android.util.Log.d("DataPersistenceManager", "  Part $partIndex: ${part::class.simpleName}")
            }
        }
        
        // 修复：确保AI消息的文本内容不会丢失
        val processedMessages = messages.map { message -> 
            if (message.sender == com.android.everytalk.data.DataClass.Sender.AI &&
                message.contentStarted &&
                message.text.isBlank() &&
                message.parts.isNotEmpty()) {
                
                android.util.Log.w("DataPersistenceManager", "Fixing AI message with blank text but has parts: ${message.id}")
                
                // 尝试从parts重建文本内容
                val rebuiltText = message.parts.toRecoveredMarkdown()
                
                if (rebuiltText.isNotBlank()) {
                    android.util.Log.d("DataPersistenceManager", "Rebuilt text from parts: length=${rebuiltText.length}")
                    message.copy(text = rebuiltText)
                } else {
                    // 如果无法重建，至少保留一个占位符
                    android.util.Log.w("DataPersistenceManager", "Could not rebuild text from parts, using placeholder")
                    message.copy(text = "...")
                }
            } else {
                message
            }
        }
        
        withContext(Dispatchers.IO) {
            Log.d(TAG, "saveLastOpenChat: Saving ${processedMessages.size} messages for isImageGen=$isImageGeneration to Room")
            try {
                val finalMessages = if (isImageGeneration) {
                    // 对"最后打开的图像会话"统一进行 data:image 与 http(s) 落盘与替换
                    persistInlineAndRemoteImages(processedMessages)
                } else {
                    processedMessages
                }
                // 使用 Room 保存最后打开的会话
                if (isImageGeneration) {
                    roomDataSource.saveLastOpenImageGenerationChat(finalMessages)
                    android.util.Log.d("DataPersistenceManager", "Image chat saved to Room successfully")
                } else {
                    roomDataSource.saveLastOpenChat(finalMessages)
                    android.util.Log.d("DataPersistenceManager", "Text chat saved to Room successfully")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("DataPersistenceManager", "Failed to save last open chat to Room", e)
            }
        }
        android.util.Log.d("DataPersistenceManager", "=== SAVE LAST OPEN CHAT END ===")
    }

    suspend fun clearLastOpenChat(isImageGeneration: Boolean = false) {
        if (isImageGeneration && protectLastOpenImageSession) return
        if (!isImageGeneration && protectLastOpenTextSession) return
        withContext(Dispatchers.IO) {
            // 使用 Room 清除最后打开的会话
            if (isImageGeneration) {
                roomDataSource.saveLastOpenImageGenerationChat(emptyList())
            } else {
                roomDataSource.saveLastOpenChat(emptyList())
            }
            Log.d(TAG, "Cleared last open chat for isImageGeneration=$isImageGeneration from Room")
        }
    }

    suspend fun deleteMediaFilesForMessages(conversations: List<List<Message>>) =
        deleteMediaFilesForMessagesInternal(conversations)

    suspend fun cleanupOrphanedAttachments(vacuumDatabase: Boolean = false) =
        cleanupOrphanedAttachmentsInternal(vacuumDatabase)
    suspend fun savePinnedIds(ids: Set<String>, isImageGeneration: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                // 使用 Room 保存置顶状态
                if (isImageGeneration) {
                    roomDataSource.savePinnedImageIds(ids)
                } else {
                    roomDataSource.savePinnedTextIds(ids)
                }
                Log.d(TAG, "savePinnedIds: saved ${ids.size} ids for isImageGen=$isImageGeneration to Room")
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Log.e(TAG, "savePinnedIds failed", e)
            }
        }
    }
    
    /**
     * 保存分组信息。使用 Mutex 确保并发安全。
     * 已迁移到 Room 数据库
     */
    suspend fun saveConversationGroups(groups: Map<String, List<String>>) {
        conversationGroupsSaveMutex.withLock {
            withContext(Dispatchers.IO) {
                roomDataSource.saveConversationGroups(groups)
            }
        }
    }

    /**
     * 加载分组信息。
     * 已迁移到 Room 数据库
     */
    suspend fun loadConversationGroups(): Map<String, List<String>> {
        return withContext(Dispatchers.IO) {
            roomDataSource.loadConversationGroups()
        }
    }

    /**
     * 原子性地更新分组信息。
     * 此方法确保更新操作是串行的，避免并发修改导致的数据丢失。
     * @param updateLambda 一个接收当前分组 Map 并返回新分组 Map 的函数。
     * @return 更新后的分组 Map。
     */
    suspend fun updateConversationGroups(updateLambda: (Map<String, List<String>>) -> Map<String, List<String>>): Map<String, List<String>> {
        return conversationGroupsSaveMutex.withLock {
            val currentGroups = loadConversationGroups()
            val updatedGroups = updateLambda(currentGroups)
            withContext(Dispatchers.IO) {
                roomDataSource.saveConversationGroups(updatedGroups)
            }
            updatedGroups
        }
    }

    suspend fun loadPinnedIds(isImageGeneration: Boolean): Set<String> {
        return withContext(Dispatchers.IO) {
            try {
                // 使用 Room 加载置顶状态
                if (isImageGeneration) {
                    roomDataSource.loadPinnedImageIds()
                } else {
                    roomDataSource.loadPinnedTextIds()
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Log.e(TAG, "loadPinnedIds failed", e)
                emptySet()
            }
        }
    }

    suspend fun saveConversationScrollStates(states: Map<String, ConversationScrollState>) {
        conversationScrollStatesSaveMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val serialized = json.encodeToString(states)
                    roomDataSource.setSetting("conversation_scroll_states_v1", serialized)
                    Log.d(TAG, "saveConversationScrollStates: saved ${states.size} items")
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    Log.e(TAG, "saveConversationScrollStates failed", e)
                }
            }
        }
    }

    suspend fun loadConversationScrollStates(): Map<String, ConversationScrollState> {
        return withContext(Dispatchers.IO) {
            try {
                val raw = roomDataSource.getSetting("conversation_scroll_states_v1", "") ?: ""
                if (raw.isBlank()) return@withContext emptyMap()
                json.decodeFromString<Map<String, ConversationScrollState>>(raw)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Log.e(TAG, "loadConversationScrollStates failed", e)
                emptyMap()
            }
        }
    }

    // ========= 分组展开状态 =========

    /**
     * 保存分组展开状态
     * 已迁移到 Room 数据库
     */
    suspend fun saveExpandedGroupKeys(keys: Set<String>) {
        withContext(Dispatchers.IO) {
            try {
                roomDataSource.saveExpandedGroupKeys(keys)
                Log.d(TAG, "saveExpandedGroupKeys: saved ${keys.size} expanded group keys to Room")
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Log.e(TAG, "saveExpandedGroupKeys failed", e)
            }
        }
    }
    
    /**
     * 加载分组展开状态
     * 已迁移到 Room 数据库
     */
    suspend fun loadExpandedGroupKeys(): Set<String> {
        return withContext(Dispatchers.IO) {
            try {
                roomDataSource.loadExpandedGroupKeys()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Log.e(TAG, "loadExpandedGroupKeys failed", e)
                emptySet()
            }
        }
    }

    // ========= 语音配置 =========

    /**
     * 保存语音后端配置列表
     */
    suspend fun saveVoiceBackendConfigs(configs: List<VoiceBackendConfig>) {
        withContext(Dispatchers.IO) {
            try {
                roomDataSource.saveVoiceBackendConfigs(configs)
                Log.d(TAG, "saveVoiceBackendConfigs: 已保存 ${configs.size} 个语音配置")
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Log.e(TAG, "saveVoiceBackendConfigs 失败", e)
            }
        }
    }

    /**
     * 加载语音后端配置列表
     */
    suspend fun loadVoiceBackendConfigs(): List<VoiceBackendConfig> {
        return withContext(Dispatchers.IO) {
            try {
                roomDataSource.loadVoiceBackendConfigs()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Log.e(TAG, "loadVoiceBackendConfigs 失败", e)
                emptyList()
            }
        }
    }

    /**
     * 保存当前选中的语音配置ID
     */
    suspend fun saveSelectedVoiceConfigId(configId: String?) {
        withContext(Dispatchers.IO) {
            try {
                roomDataSource.saveSelectedVoiceConfigId(configId)
                Log.d(TAG, "saveSelectedVoiceConfigId: 已保存选中的语音配置ID '$configId'")
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Log.e(TAG, "saveSelectedVoiceConfigId 失败", e)
            }
        }
    }

    /**
     * 加载当前选中的语音配置ID
     */
    suspend fun loadSelectedVoiceConfigId(): String? {
        return withContext(Dispatchers.IO) {
            try {
                roomDataSource.loadSelectedVoiceConfigId()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Log.e(TAG, "loadSelectedVoiceConfigId 失败", e)
                null
            }
        }
    }

    /**
     * 清除所有语音配置
     */
    suspend fun clearVoiceBackendConfigs() {
        withContext(Dispatchers.IO) {
            try {
                roomDataSource.clearVoiceBackendConfigs()
                roomDataSource.saveSelectedVoiceConfigId(null)
                Log.d(TAG, "clearVoiceBackendConfigs: 已清除所有语音配置")
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Log.e(TAG, "clearVoiceBackendConfigs 失败", e)
            }
        }
    }
}
