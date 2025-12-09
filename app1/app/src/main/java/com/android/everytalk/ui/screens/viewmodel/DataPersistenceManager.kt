package com.android.everytalk.ui.screens.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import com.android.everytalk.data.DataClass.ApiConfig
import java.io.File
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.database.RoomDataSource
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.VoiceBackendConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import coil3.ImageLoader
import android.util.Base64
import java.io.FileOutputStream
import java.util.Locale

class DataPersistenceManager(
    private val context: Context,
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope,
    private val imageLoader: ImageLoader
) {
    private val TAG = "PersistenceManager"
    private val conversationGroupsSaveMutex = kotlinx.coroutines.sync.Mutex()
    
    // Room 数据源
    private val roomDataSource by lazy { RoomDataSource(context) }
    
    // 默认配置初始化标志位 (存储在 Room 数据库中)
    private val KEY_DEFAULT_CONFIGS_INITIALIZED = "default_configs_initialized_v1"

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

    /**
     * 将消息中的 data:image;base64,... 图片落盘为本地文件，并将 URL 替换为 file:// 或绝对路径
     * 这样可避免把巨大 Base64 串写入 SharedPreferences 导致超限/丢失，重启后可稳定恢复。
     */
    private fun persistInlineAndRemoteImages(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return messages
        val tempDir = File(context.filesDir, "chat_attachments").apply { mkdirs() }
 
        fun extFromMime(mime: String?): String {
            val m = (mime ?: "").lowercase(Locale.ROOT)
            return when {
                m.contains("png") -> "png"
                m.contains("jpeg") || m.contains("jpg") -> "jpg"
                m.contains("webp") -> "webp"
                m.contains("heic") -> "heic"
                m.contains("heif") -> "heif"
                else -> "png"
            }
        }

        fun saveDataUri(dataUri: String, fileNameHint: String): String? {
            return try {
                // 解析形如 data:image/png;base64,AAAA... 的数据
                val commaIndex = dataUri.indexOf(',')
                if (commaIndex <= 0) return null
                // 跳过前缀 "data:"
                val header = dataUri.substring(5, commaIndex)
                val mimePart = header.substringBefore(';', "")
                val mime = if (mimePart.isNotBlank()) mimePart else "image/png"
                val isBase64 = header.contains("base64", ignoreCase = true)
                val payload = dataUri.substring(commaIndex + 1)

                val bytes: ByteArray = if (isBase64) {
                    Base64.decode(payload, Base64.DEFAULT)
                } else {
                    // 非 base64 的 data:payload（较少见），按 URL 编码处理
                    Uri.decode(payload).toByteArray()
                }

                val ext = extFromMime(mime)
                val file = File(tempDir, "${fileNameHint}_${System.currentTimeMillis()}.$ext")
                FileOutputStream(file).use { fos ->
                    fos.write(bytes)
                }
                if (file.exists() && file.length() > 0) file.absolutePath else null
            } catch (e: Exception) {
                Log.w(TAG, "persistImages: failed to save bytes for $fileNameHint", e)
                null
            }
        }

        fun tryDownload(url: String): Pair<ByteArray, String?>? {
            return try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                }
                conn.connect()
                if (conn.responseCode !in 200..299) return null
                val mime = conn.contentType
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                bytes to mime
            } catch (e: Exception) {
                Log.w(TAG, "persistImages: download failed for $url", e)
                null
            }
        }

        return messages.map { msg ->
            if (msg.imageUrls.isNullOrEmpty()) {
                msg
            } else {
                // 统一保存到 chat_attachments，通过保留期控制清理时机
                val currentDir = tempDir
                currentDir.mkdirs()

                val newUrls = msg.imageUrls.mapIndexed { idx, url ->
                    if (url.startsWith("data:image", ignoreCase = true)) {
                        val saved = saveDataUri(url, "img_${msg.id}_${idx}")
                        saved ?: url
                    } else if (url.startsWith("http", ignoreCase = true)) {
                        // 修复：对于 http/https 图片，尝试下载并保存为本地文件
                        // 这样可以避免远端 URL 过期导致图片无法显示
                        val pair = tryDownload(url)
                        if (pair != null) {
                            val (bytes, mime) = pair
                            val ext = extFromMime(mime)
                            val fileName = "img_${msg.id}_${idx}_${System.currentTimeMillis()}.$ext"
                            val file = File(tempDir, fileName)
                            try {
                                FileOutputStream(file).use { fos ->
                                    fos.write(bytes)
                                }
                                if (file.exists() && file.length() > 0) {
                                    Log.i(TAG, "Downloaded remote image to ${file.absolutePath}")
                                    file.absolutePath
                                } else {
                                    url
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to save downloaded image", e)
                                url
                            }
                        } else {
                            url
                        }
                    } else {
                        url
                    }
                }
                if (newUrls == msg.imageUrls) msg else msg.copy(imageUrls = newUrls)
            }
        }
    }

    /**
     * 即时保存单张图片（data URI）到本地文件，用于流式响应时的即时落地
     */
    suspend fun persistImageImmediate(dataUri: String, messageId: String, index: Int): String? {
        return withContext(Dispatchers.IO) {
            try {
                if (!dataUri.startsWith("data:image", ignoreCase = true)) {
                    return@withContext null
                }
                
                val tempDir = File(context.filesDir, "chat_attachments").apply { mkdirs() }
                
                // 解析形如 data:image/png;base64,AAAA... 的数据
                val commaIndex = dataUri.indexOf(',')
                if (commaIndex <= 0) return@withContext null
                
                val header = dataUri.substring(5, commaIndex)
                val mimePart = header.substringBefore(';', "")
                val mime = if (mimePart.isNotBlank()) mimePart else "image/png"
                val isBase64 = header.contains("base64", ignoreCase = true)
                val payload = dataUri.substring(commaIndex + 1)

                val bytes: ByteArray = if (isBase64) {
                    Base64.decode(payload, Base64.DEFAULT)
                } else {
                    Uri.decode(payload).toByteArray()
                }

                fun extFromMime(m: String): String {
                    val ml = m.lowercase(Locale.ROOT)
                    return when {
                        ml.contains("png") -> "png"
                        ml.contains("jpeg") || ml.contains("jpg") -> "jpg"
                        ml.contains("webp") -> "webp"
                        else -> "png"
                    }
                }

                val ext = extFromMime(mime)
                // 使用确定性的文件名，避免重复保存
                val fileName = "img_${messageId}_${index}_${System.currentTimeMillis()}.$ext"
                val file = File(tempDir, fileName)
                
                FileOutputStream(file).use { fos ->
                    fos.write(bytes)
                }
                
                if (file.exists() && file.length() > 0) {
                    Log.i(TAG, "persistImageImmediate: Saved image to ${file.absolutePath}")
                    file.absolutePath 
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "persistImageImmediate: Failed", e)
                null
            }
        }
    }

    fun loadInitialData(
        loadLastChat: Boolean = true,
        onLoadingComplete: (initialConfigPresent: Boolean, initialHistoryPresent: Boolean) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "loadInitialData: 开始加载初始数据 (IO Thread)... loadLastChat: $loadLastChat")
            var initialConfigPresent = false
            var initialHistoryPresent = false

            try {
                // 第一阶段：快速加载API配置（优先级最高）
                Log.d(TAG, "loadInitialData: 阶段1 - 加载API配置...")
                var loadedConfigs: List<ApiConfig> = if (stateHolder._apiConfigs.value.isEmpty()) {
                    Log.d(TAG, "loadInitialData: API配置缓存未命中。从RoomDataSource加载...")
                    roomDataSource.loadApiConfigs()
                } else {
                    Log.d(TAG, "loadInitialData: API配置缓存命中。使用现有数据。")
                    stateHolder._apiConfigs.value
                }
                
                // 检查是否已初始化过默认配置 (从 Room 数据库读取)
                val defaultConfigsInitialized = roomDataSource.getSetting(KEY_DEFAULT_CONFIGS_INITIALIZED, "false") == "true"
                
                // 自动创建默认文本配置（如果不存在且未初始化过）
                val hasDefaultTextConfig = loadedConfigs.any {
                    it.provider.trim().lowercase() in listOf("默认", "default") &&
                    it.modalityType == com.android.everytalk.data.DataClass.ModalityType.TEXT
                }
                
                if (!hasDefaultTextConfig && !defaultConfigsInitialized) {
                    Log.i(TAG, "loadInitialData: 未找到默认文本配置且首次初始化，自动创建...")
                    // 从 BuildConfig 获取默认密钥
                    val defaultApiKey = com.android.everytalk.BuildConfig.DEFAULT_TEXT_API_KEY
                    val defaultApiUrl = com.android.everytalk.BuildConfig.DEFAULT_TEXT_API_URL
                    // 解析模型列表 (逗号分隔)
                    val rawModels = com.android.everytalk.BuildConfig.DEFAULT_TEXT_MODELS
                    val defaultTextModels = if (rawModels.isNotBlank()) {
                        rawModels.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    } else {
                        listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-flash-lite-latest")
                    }
                    
                    val newDefaultConfigs = defaultTextModels.map { modelName ->
                        ApiConfig(
                            id = java.util.UUID.randomUUID().toString(),
                            name = modelName,
                            provider = "默认",
                            address = defaultApiUrl,
                            key = defaultApiKey,
                            model = modelName,
                            modalityType = com.android.everytalk.data.DataClass.ModalityType.TEXT,
                            channel = "OpenAI兼容", // 默认使用 OpenAI 兼容渠道
                            isValid = true
                        )
                    }
                    loadedConfigs = loadedConfigs + newDefaultConfigs
                    // 立即保存到持久化存储
                    roomDataSource.saveApiConfigs(loadedConfigs)
                    Log.i(TAG, "loadInitialData: 已创建并保存 ${newDefaultConfigs.size} 个默认文本配置")
                }
                
                // 检查并修复旧的默认配置（如果存在旧模型名称，更新为新名称）
                val updatedConfigs = loadedConfigs.map { config ->
                    if (config.provider.trim().lowercase() in listOf("默认", "default") &&
                        config.modalityType == com.android.everytalk.data.DataClass.ModalityType.TEXT &&
                        config.model == "gemini-2.5-pro-1M") {
                        Log.i(TAG, "loadInitialData: 自动更新过期的默认模型配置: gemini-2.5-pro-1M -> gemini-2.5-pro")
                        config.copy(
                            name = "gemini-2.5-pro",
                            model = "gemini-2.5-pro"
                        )
                    } else {
                        config
                    }
                }

                if (updatedConfigs != loadedConfigs) {
                    loadedConfigs = updatedConfigs
                    roomDataSource.saveApiConfigs(loadedConfigs)
                    Log.i(TAG, "loadInitialData: 已保存更新后的API配置")
                }

                initialConfigPresent = loadedConfigs.isNotEmpty()

                Log.d(TAG, "loadInitialData: 调用 roomDataSource.loadSelectedConfigId()...")
                val selectedConfigId: String? = roomDataSource.loadSelectedConfigId()
                var selectedConfigFromDataSource: ApiConfig? = null
                if (selectedConfigId != null) {
                    selectedConfigFromDataSource = loadedConfigs.find { it.id == selectedConfigId }
                    if (selectedConfigFromDataSource == null && loadedConfigs.isNotEmpty()) {
                        Log.w(TAG, "loadInitialData: 持久化的选中配置ID '$selectedConfigId' 在当前配置列表中未找到。将清除持久化的选中ID。")
                        roomDataSource.saveSelectedConfigId(null)
                    }
                }

                var finalSelectedConfig = selectedConfigFromDataSource
                if (finalSelectedConfig == null && loadedConfigs.isNotEmpty()) {
                    finalSelectedConfig = loadedConfigs.first()
                    Log.i(TAG, "loadInitialData: 无有效选中配置或之前未选中，默认选择第一个: ID='${finalSelectedConfig.id}', 模型='${finalSelectedConfig.model}'。将保存此选择。")
                    roomDataSource.saveSelectedConfigId(finalSelectedConfig.id)
                }

                // 立即更新API配置到UI，让用户可以开始使用
                withContext(Dispatchers.Main.immediate) {
                    Log.d(TAG, "loadInitialData: 阶段1完成 - 更新API配置到UI...")
                    stateHolder._apiConfigs.value = loadedConfigs
                    stateHolder._selectedApiConfig.value = finalSelectedConfig
                }

                // Load image generation configs
                var loadedImageGenConfigs: List<ApiConfig> = roomDataSource.loadImageGenApiConfigs()
                
                // 自动创建默认图像配置（如果不存在且未初始化过）
                val hasDefaultImageConfig = loadedImageGenConfigs.any {
                    it.provider.trim().lowercase() in listOf("默认", "default") &&
                    it.modalityType == com.android.everytalk.data.DataClass.ModalityType.IMAGE &&
                    it.model == "Kwai-Kolors/Kolors"
                }
                if (!hasDefaultImageConfig && !defaultConfigsInitialized) {
                    Log.i(TAG, "loadInitialData: 未找到默认快手图像配置且首次初始化，自动创建...")
                    // 从 BuildConfig 获取 SiliconFlow 配置
                    val siliconFlowKey = com.android.everytalk.BuildConfig.SILICONFLOW_API_KEY
                    // 优先使用 BuildConfig 中的配置，回退到默认值
                    val siliconFlowUrlRaw = com.android.everytalk.BuildConfig.SILICONFLOW_IMAGE_API_URL
                    // 如果 URL 包含 /v1/images/generations，需要修剪，因为 ImageGenerationDirectClient 会拼接
                    // ImageGenerationDirectClient 目前是硬编码路径逻辑，我们需要确保传入的是 Base URL
                    // OpenAI 兼容模式通常期望 Base URL (不带 /v1/...)，或者带 /v1 但不带具体的 endpoint
                    // 这里我们为了稳妥，传入完整的 Base URL，让 Client 去处理拼接
                    // 观察 ImageGenerationDirectClient.kt，它会拼接 /v1/images/generations
                    // 所以我们传入 https://api.siliconflow.cn 即可
                    
                    val siliconFlowUrl = if (siliconFlowUrlRaw.contains("/v1/images/generations")) {
                        siliconFlowUrlRaw.substringBefore("/v1/images/generations")
                    } else {
                        "https://api.siliconflow.cn"
                    }
                    
                    val siliconFlowModel = com.android.everytalk.BuildConfig.SILICONFLOW_DEFAULT_IMAGE_MODEL.ifBlank { "Kwai-Kolors/Kolors" }
                    
                    val defaultImageConfig = ApiConfig(
                        id = java.util.UUID.randomUUID().toString(),
                        name = siliconFlowModel,
                        provider = "默认",
                        address = siliconFlowUrl,
                        key = siliconFlowKey,
                        model = siliconFlowModel,
                        modalityType = com.android.everytalk.data.DataClass.ModalityType.IMAGE,
                        channel = "OpenAI兼容", // SiliconFlow 兼容 OpenAI 格式
                        isValid = true
                    )
                    loadedImageGenConfigs = loadedImageGenConfigs + listOf(defaultImageConfig)
                    Log.i(TAG, "loadInitialData: 已创建默认快手图像配置")
                }
                
                // 自动创建 Modal Z-Image-Turbo 和 Qwen-Image-Edit 默认配置（仅首次初始化时）
                // 修复：不再强制对齐已存在配置的分组字段，避免用户自定义配置被错误归并
                
                // 1. 先找到现有的快手配置，以获取默认分组字段 (address/key/channel)
                val kolorsConfig = loadedImageGenConfigs.find {
                    it.model == "Kwai-Kolors/Kolors" &&
                    it.modalityType == com.android.everytalk.data.DataClass.ModalityType.IMAGE &&
                    it.provider.trim().lowercase() in listOf("默认", "default")
                }
                
                val targetAddress = kolorsConfig?.address ?: ""
                val targetKey = kolorsConfig?.key ?: ""
                val targetChannel = kolorsConfig?.channel ?: ""
                val targetProvider = "默认"

                var configsChanged = false
                val mutableConfigs = loadedImageGenConfigs.toMutableList()

                // === Modal Z-Image-Turbo 处理 ===
                // 仅在首次初始化时创建，不再强制修改已存在的配置
                // 修复：使用更严格的查找条件，避免重复创建
                val existingModalConfig = mutableConfigs.find {
                    it.model == "z-image-turbo-modal" &&
                    it.modalityType == com.android.everytalk.data.DataClass.ModalityType.IMAGE
                }

                if (existingModalConfig == null && !defaultConfigsInitialized) {
                    Log.i(TAG, "loadInitialData: 未找到 Modal 图像配置且首次初始化，自动创建...")
                    val modalImageConfig = ApiConfig(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "Z-Image-Turbo (Modal)",
                        provider = targetProvider,
                        address = targetAddress,
                        key = targetKey,
                        model = "z-image-turbo-modal",
                        modalityType = com.android.everytalk.data.DataClass.ModalityType.IMAGE,
                        channel = targetChannel,
                        isValid = true,
                        numInferenceSteps = 4 // Modal 默认步数
                    )
                    mutableConfigs.add(modalImageConfig)
                    configsChanged = true
                } else if (existingModalConfig != null && existingModalConfig.numInferenceSteps == null) {
                    // 仅补齐默认步数，不修改其他字段
                    val index = mutableConfigs.indexOf(existingModalConfig)
                    if (index != -1) {
                        mutableConfigs[index] = existingModalConfig.copy(numInferenceSteps = 4)
                        configsChanged = true
                        Log.i(TAG, "loadInitialData: 为 Modal 配置补齐默认步数")
                    }
                }

                // === Modal Qwen-Image-Edit 处理 ===
                // 仅在首次初始化时创建，不再强制修改已存在的配置
                val existingQwenConfig = mutableConfigs.find {
                    it.model == "qwen-image-edit-modal" &&
                    it.modalityType == com.android.everytalk.data.DataClass.ModalityType.IMAGE
                }

                if (existingQwenConfig == null && !defaultConfigsInitialized) {
                    Log.i(TAG, "loadInitialData: 未找到 Qwen Image Edit 配置且首次初始化，自动创建...")
                    val qwenConfig = ApiConfig(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "Qwen Image Edit",
                        provider = targetProvider,
                        address = targetAddress,
                        key = targetKey,
                        model = "qwen-image-edit-modal",
                        modalityType = com.android.everytalk.data.DataClass.ModalityType.IMAGE,
                        channel = targetChannel,
                        isValid = true,
                        numInferenceSteps = 30 // Qwen 默认步数
                    )
                    mutableConfigs.add(qwenConfig)
                    configsChanged = true
                } else if (existingQwenConfig != null && existingQwenConfig.numInferenceSteps == null) {
                    // 仅补齐默认步数，不修改其他字段
                    val index = mutableConfigs.indexOf(existingQwenConfig)
                    if (index != -1) {
                        mutableConfigs[index] = existingQwenConfig.copy(numInferenceSteps = 30)
                        configsChanged = true
                        Log.i(TAG, "loadInitialData: 为 Qwen 配置补齐默认步数")
                    }
                }
                
                // 修复：去重逻辑，移除完全重复的配置（除了ID不同）
                // 这可以解决之前版本重复创建导致的配置堆积问题
                if (mutableConfigs.size > loadedImageGenConfigs.size) {
                    val uniqueConfigs = mutableConfigs.distinctBy {
                        "${it.provider}|${it.address}|${it.key}|${it.model}|${it.channel}|${it.modalityType}"
                    }
                    if (uniqueConfigs.size < mutableConfigs.size) {
                        Log.i(TAG, "loadInitialData: 移除 ${mutableConfigs.size - uniqueConfigs.size} 个重复的图像配置")
                        mutableConfigs.clear()
                        mutableConfigs.addAll(uniqueConfigs)
                        configsChanged = true
                    }
                }
                
                loadedImageGenConfigs = mutableConfigs.toList()

                // 统一保存所有图像配置（包括新增的）
                if ((!hasDefaultImageConfig && !defaultConfigsInitialized) || configsChanged) {
                    roomDataSource.saveImageGenApiConfigs(loadedImageGenConfigs)
                    Log.i(TAG, "loadInitialData: 已保存更新后的图像配置列表")
                }
                
                // 标记默认配置已初始化 (保存到 Room 数据库)
                if (!defaultConfigsInitialized) {
                    roomDataSource.setSetting(KEY_DEFAULT_CONFIGS_INITIALIZED, "true")
                }
                
                val selectedImageGenConfigId: String? = roomDataSource.loadSelectedImageGenConfigId()
                var selectedImageGenConfig: ApiConfig? = null
                if (selectedImageGenConfigId != null) {
                    selectedImageGenConfig = loadedImageGenConfigs.find { it.id == selectedImageGenConfigId }
                }
                if (selectedImageGenConfig == null && loadedImageGenConfigs.isNotEmpty()) {
                    selectedImageGenConfig = loadedImageGenConfigs.first()
                    roomDataSource.saveSelectedImageGenConfigId(selectedImageGenConfig.id)
                }
 
                 withContext(Dispatchers.Main.immediate) {
                     stateHolder._imageGenApiConfigs.value = loadedImageGenConfigs
                     stateHolder._selectedImageGenApiConfig.value = selectedImageGenConfig
                 }

                // 加载语音后端配置
                var loadedVoiceConfigs = roomDataSource.loadVoiceBackendConfigs()
                
                // 自动创建默认语音配置（如果不存在且未初始化过）
                // 注意：语音配置暂时复用同一个初始化标志位，或者如果需要独立控制可以新加
                val hasDefaultVoiceConfig = loadedVoiceConfigs.any {
                    it.provider.trim().lowercase() in listOf("默认", "default")
                }
                if (!hasDefaultVoiceConfig && !defaultConfigsInitialized) {
                    Log.i(TAG, "loadInitialData: 未找到默认语音配置且首次初始化，自动创建...")
                    val defaultVoiceConfig = com.android.everytalk.data.DataClass.VoiceBackendConfig.createDefault()
                    loadedVoiceConfigs = loadedVoiceConfigs + listOf(defaultVoiceConfig)
                    roomDataSource.saveVoiceBackendConfigs(loadedVoiceConfigs)
                    Log.i(TAG, "loadInitialData: 已创建默认语音配置")
                }
                
                val selectedVoiceConfigId: String? = roomDataSource.loadSelectedVoiceConfigId()
                var selectedVoiceConfig: com.android.everytalk.data.DataClass.VoiceBackendConfig? = null
                if (selectedVoiceConfigId != null) {
                    selectedVoiceConfig = loadedVoiceConfigs.find { it.id == selectedVoiceConfigId }
                }
                if (selectedVoiceConfig == null && loadedVoiceConfigs.isNotEmpty()) {
                    selectedVoiceConfig = loadedVoiceConfigs.first()
                    roomDataSource.saveSelectedVoiceConfigId(selectedVoiceConfig.id)
                }
                
                withContext(Dispatchers.Main.immediate) {
                    stateHolder._voiceBackendConfigs.value = loadedVoiceConfigs
                    stateHolder._selectedVoiceConfig.value = selectedVoiceConfig
                }
                Log.i(TAG, "loadInitialData: 已加载 ${loadedVoiceConfigs.size} 个语音配置")

                // 第二阶段：异步加载历史数据（延迟加载）
                launch {
                    Log.d(TAG, "loadInitialData: 阶段2 - 开始异步加载历史数据...")
                    
                    // 设置加载状态
                    withContext(Dispatchers.Main.immediate) {
                        stateHolder._isLoadingHistoryData.value = true
                    }
                    
                    try {
                        // 检查是否需要加载历史数据
                        val shouldLoadHistory = stateHolder._historicalConversations.value.isEmpty()
                        val loadedHistory = if (shouldLoadHistory) {
                            Log.d(TAG, "loadInitialData: 从 Room 加载历史数据...")
                            // 优先从 Room 加载
                            var historyRaw = roomDataSource.loadChatHistory()
                            
                            // 分批处理历史数据，避免一次性处理大量数据
                            historyRaw.chunked(10).flatMap { chunk ->
                                chunk.map { conversation ->
                                    conversation.map { message -> message }
                                }
                            }
                        } else {
                            Log.d(TAG, "loadInitialData: 使用缓存的历史数据。")
                            stateHolder._historicalConversations.value
                        }
                        
                        initialHistoryPresent = loadedHistory.isNotEmpty()
                        Log.i(TAG, "loadInitialData: 历史数据加载完成。数量: ${loadedHistory.size}")

                        // 加载会话配置映射
                        try {
                            val mapping = roomDataSource.loadConversationApiConfigIds()
                            withContext(Dispatchers.Main.immediate) {
                                stateHolder.conversationApiConfigIds.value = mapping
                            }
                            Log.d(TAG, "loadInitialData: 会话配置映射已加载 - 共 ${mapping.size} 条")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load conversation api config mapping", e)
                        }

                        // 自动修复消息parts - 检查并修复有问题的AI消息
                        val repairedHistory = loadedHistory.map { conversation ->
                            conversation.map { message ->
                                if (message.sender == com.android.everytalk.data.DataClass.Sender.AI &&
                                    message.text.isNotBlank() && 
                                    (message.parts.isEmpty() || 
                                     !message.parts.any { part ->
                                         when (part) {
                                             is com.android.everytalk.ui.components.MarkdownPart.Text -> part.content.isNotBlank()
                                             is com.android.everytalk.ui.components.MarkdownPart.CodeBlock -> part.content.isNotBlank()
                                             // Math blocks removed
                                             // is com.android.everytalk.ui.components.MarkdownPart.Table -> part.tableData.headers.isNotEmpty()
                                             else -> false
                                         }
                                     })) {
                                    // 需要修复的消息
                                    Log.d(TAG, "自动修复消息parts: messageId=${message.id}")
                                    // 这里可以调用MessageProcessor.finalizeMessageProcessing
                                    // 暂时先标记，稍后在渲染时修复
                                    message
                                } else {
                                    message
                                }
                            }
                        }

                        // 更新历史数据到UI
                        withContext(Dispatchers.Main.immediate) {
                            Log.d(TAG, "loadInitialData: 阶段2完成 - 更新历史数据到UI...")
                            stateHolder._historicalConversations.value = repairedHistory
                            repairedHistory.forEach { conversation ->
                                val id = conversation.firstOrNull()?.id
                                if (id != null) {
                                    val prompt = conversation.firstOrNull { it.sender == com.android.everytalk.data.DataClass.Sender.System }?.text ?: ""
                                    stateHolder.systemPrompts[id] = prompt
                                }
                            }
                            stateHolder._isLoadingHistoryData.value = false
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "loadInitialData: 加载历史数据时发生错误", e)
                        withContext(Dispatchers.Main.immediate) {
                            stateHolder._historicalConversations.value = emptyList()
                            stateHolder._isLoadingHistoryData.value = false
                        }
                    }

                   // Load image generation history
                   // 优先从 Room 加载
                   var loadedImageGenHistory = roomDataSource.loadImageGenerationHistory()
                   
                   // 增强：启动时完整性检查与修复
                   // 1. 将历史中的 data:image 与 http(s) 图片统一落盘并替换为本地路径
                   // 2. 检查本地文件是否存在，如果不存在则尝试恢复或标记
                   val convertedImageGenHistory = loadedImageGenHistory.map { conv ->
                       // 先执行标准的归档逻辑
                       val persistedConv = persistInlineAndRemoteImages(conv)
                       
                       // 再执行完整性检查
                       persistedConv.map { msg ->
                           if (!msg.imageUrls.isNullOrEmpty()) {
                               val validatedUrls = msg.imageUrls.map { url ->
                                   if (url.startsWith("/") || url.startsWith("file://")) {
                                       val path = url.removePrefix("file://")
                                       val file = File(path)
                                       if (!file.exists()) {
                                           Log.w(TAG, "Image file missing for message ${msg.id}: $path")
                                           // 如果文件丢失，我们暂时保留路径，或许后续可以恢复
                                           // 或者可以替换为一个错误占位图 URL
                                           url
                                       } else {
                                           url
                                       }
                                   } else {
                                       url
                                   }
                               }
                               if (validatedUrls != msg.imageUrls) {
                                   msg.copy(imageUrls = validatedUrls)
                               } else {
                                   msg
                               }
                           } else {
                               msg
                           }
                       }
                   }
                   
                   // 异步回写修复后的历史，避免后续重复转换
                   launch(Dispatchers.IO) {
                       try {
                           roomDataSource.saveImageGenerationHistory(convertedImageGenHistory)
                           Log.i(TAG, "Image generation history integrity check and persistence completed")
                       } catch (e: Exception) {
                           Log.w(TAG, "Failed to persist converted image generation history", e)
                       }
                   }
                   withContext(Dispatchers.Main.immediate) {
                       stateHolder._imageGenerationHistoricalConversations.value = convertedImageGenHistory
                   }
                }

                // Phase 3: Load last open chats if needed
                if (loadLastChat) {
                    Log.d(TAG, "loadInitialData: Phase 3 - Loading last open chats...")
                    // 从 Room 加载最后打开的会话
                    var lastOpenChat = roomDataSource.loadLastOpenChat()
                    
                    var lastOpenImageGenChat = roomDataSource.loadLastOpenImageGenerationChat()
                    
                    // 将“最后打开的图像会话”里的 data:image 与 http(s) 转为本地文件并替换
                    val finalLastOpenImageGen = persistInlineAndRemoteImages(lastOpenImageGenChat)
                    // 异步回写，确保下次启动直接使用文件路径
                    launch(Dispatchers.IO) {
                        try {
                            roomDataSource.saveLastOpenImageGenerationChat(finalLastOpenImageGen)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to persist converted last-open image chat", e)
                        }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        // 恢复消息列表
                        stateHolder.messages.clear()
                        stateHolder.messages.addAll(lastOpenChat)
                        stateHolder.imageGenerationMessages.clear()
                        stateHolder.imageGenerationMessages.addAll(finalLastOpenImageGen)

                        // 修复：为已恢复的对话补齐推理完成映射，保证“小白点”可见
                        // 文本模式
                        stateHolder.textReasoningCompleteMap.clear()
                        stateHolder.messages.forEach { msg ->
                            if (msg.sender == com.android.everytalk.data.DataClass.Sender.AI &&
                                !msg.reasoning.isNullOrBlank()) {
                                stateHolder.textReasoningCompleteMap[msg.id] = true
                            }
                        }
                        // 图像模式
                        stateHolder.imageReasoningCompleteMap.clear()
                        stateHolder.imageGenerationMessages.forEach { msg ->
                            if (msg.sender == com.android.everytalk.data.DataClass.Sender.AI &&
                                !msg.reasoning.isNullOrBlank()) {
                                stateHolder.imageReasoningCompleteMap[msg.id] = true
                            }
                        }

                        // 为“文本模式/图像模式”恢复稳定的会话ID，保证后端多轮会话可延续
                        val textConvId = lastOpenChat.firstOrNull()?.id ?: "new_chat_${System.currentTimeMillis()}"
                        val imageConvId = lastOpenImageGenChat.firstOrNull()?.id ?: "image_resume_${System.currentTimeMillis()}"
                        stateHolder._currentConversationId.value = textConvId
                        stateHolder._currentImageGenerationConversationId.value = imageConvId

                        // 清空历史索引（处于“继续未存档会话”的状态）
                        stateHolder._loadedHistoryIndex.value = null
                        stateHolder._loadedImageGenerationHistoryIndex.value = null
                    }
                    Log.i(TAG, "loadInitialData: Last open chats loaded. Text: ${lastOpenChat.size}, Image: ${lastOpenImageGenChat.size}")
                } else {
                    withContext(Dispatchers.Main.immediate) {
                        stateHolder.messages.clear()
                        stateHolder.imageGenerationMessages.clear()
                        stateHolder._loadedHistoryIndex.value = null
                        stateHolder._loadedImageGenerationHistoryIndex.value = null
                        // 若未加载“last open chat”，也重置推理完成映射
                        stateHolder.textReasoningCompleteMap.clear()
                        stateHolder.imageReasoningCompleteMap.clear()
                    }
                    Log.i(TAG, "loadInitialData: Skipped loading last open chats.")
                }
                onLoadingComplete(initialConfigPresent, initialHistoryPresent)

            } catch (e: Exception) {
                Log.e(TAG, "loadInitialData: 加载初始数据时发生严重错误", e)
                withContext(Dispatchers.Main.immediate) {
                    stateHolder._apiConfigs.value = emptyList()
                    stateHolder._selectedApiConfig.value = null
                    stateHolder._historicalConversations.value = emptyList()
                    stateHolder.messages.clear()
                    stateHolder._loadedHistoryIndex.value = null
                    onLoadingComplete(false, false)
                }
            } finally {
                Log.d(TAG, "loadInitialData: 初始数据加载的IO线程任务结束。")
            }
        }
    }


    suspend fun clearAllChatHistory() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "clearAllChatHistory: 请求清除聊天历史...")
            // 清除 Room 数据库中的历史
            roomDataSource.clearChatHistory()
            roomDataSource.clearImageGenerationHistory()
            Log.i(TAG, "clearAllChatHistory: Room 和 SP 中的聊天历史已清除。")
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
                roomDataSource.saveImageGenerationHistory(finalHistory)
            } else {
                roomDataSource.saveChatHistory(finalHistory)
            }
            Log.i(TAG, "saveChatHistory: 聊天历史已通过 Room 保存。")
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
    
    // 新增：持久化保存“会话ID -> GenerationConfig”映射
    suspend fun saveConversationParameters(parameters: Map<String, GenerationConfig>) {
        withContext(Dispatchers.IO) {
            try {
                roomDataSource.saveConversationParameters(parameters)
                Log.d(TAG, "saveConversationParameters: 已持久化 ${parameters.size} 个会话参数映射")
            } catch (e: Exception) {
                Log.e(TAG, "saveConversationParameters 失败", e)
            }
        }
    }

    suspend fun loadConversationParameters(): Map<String, GenerationConfig> {
        return withContext(Dispatchers.IO) {
            try {
                roomDataSource.loadConversationParameters()
            } catch (e: Exception) {
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
                Log.e(TAG, "saveConversationApiConfigIds 失败", e)
            }
        }
    }

    suspend fun clearAllApiConfigData() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "clearAllApiConfigData: 请求 RoomDataSource 清除API配置并取消选中...")
            roomDataSource.clearApiConfigs()
            roomDataSource.saveSelectedConfigId(null) // 确保选中的也被清掉
            roomDataSource.clearImageGenApiConfigs()
            roomDataSource.saveSelectedImageGenConfigId(null)
            Log.i(TAG, "clearAllApiConfigData: API配置数据已通过 RoomDataSource 清除。")
        }
    }
    suspend fun saveLastOpenChat(messages: List<Message>, isImageGeneration: Boolean = false) {
        android.util.Log.d("DataPersistenceManager", "=== SAVE LAST OPEN CHAT START ===")
        android.util.Log.d("DataPersistenceManager", "Saving ${messages.size} messages, isImageGeneration: $isImageGeneration")
        
        messages.forEachIndexed { index, message ->
            android.util.Log.d("DataPersistenceManager", "Message $index (${message.id}): text length=${message.text.length}, parts=${message.parts.size}, contentStarted=${message.contentStarted}")
            android.util.Log.d("DataPersistenceManager", "  Text preview: '${message.text.take(50)}${if (message.text.length > 50) "..." else ""}'")
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
                val rebuiltText = message.parts.filterIsInstance<com.android.everytalk.ui.components.MarkdownPart.Text>()
                    .joinToString("") { it.content }
                
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
            } catch (e: Exception) {
                android.util.Log.e("DataPersistenceManager", "Failed to save last open chat to Room", e)
            }
        }
        android.util.Log.d("DataPersistenceManager", "=== SAVE LAST OPEN CHAT END ===")
    }

    suspend fun clearLastOpenChat(isImageGeneration: Boolean = false) {
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
    suspend fun deleteMediaFilesForMessages(conversations: List<List<Message>>) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting deletion of media files for ${conversations.size} conversations.")
            var deletedFilesCount = 0
            val allFilePathsToDelete = mutableSetOf<String>()
            val allHttpUrisToClearFromCache = mutableSetOf<String>()
            val chatAttachmentsDirPath = File(context.filesDir, "chat_attachments").absolutePath

            conversations.forEach { conversation ->
                conversation.forEach { message ->
                    message.attachments.forEach { attachment ->
                        val path = when (attachment) {
                            is SelectedMediaItem.ImageFromUri -> attachment.filePath
                            is SelectedMediaItem.GenericFile -> attachment.filePath
                            is SelectedMediaItem.Audio -> attachment.data
                            is SelectedMediaItem.ImageFromBitmap -> attachment.filePath
                        }
                        if (!path.isNullOrBlank()) {
                            // 用户触发删除：始终释放占用空间
                            allFilePathsToDelete.add(path)
                        }
                    }

                    // 处理消息中的图片URL
                    message.imageUrls?.forEach { urlString ->
                        try {
                            val uri = Uri.parse(urlString)
                            if (uri.scheme == "http" || uri.scheme == "https") {
                                allHttpUrisToClearFromCache.add(urlString)
                            } else {
                                val path = uri.path
                                if (path != null) {
                                    // 用户触发删除：始终释放占用空间
                                    allFilePathsToDelete.add(path)
                                }
                            }
                        } catch (e: Exception) {
                            // Fallback for non-URI strings that might be file paths
                            val file = File(urlString)
                            if (file.exists()) {
                                // 用户触发删除：始终释放占用空间
                                allFilePathsToDelete.add(urlString)
                            }
                        }
                    }
                    
                    // 增强：处理消息中可能包含的其他媒体文件路径
                    // 检查消息文本中是否包含本地文件路径
                    val localFilePattern = Regex("file://[^\\s]+|/data/data/[^\\s]+|/storage/[^\\s]+")
                    localFilePattern.findAll(message.text).forEach { match ->
                        val filePath = match.value.removePrefix("file://")
                        val file = File(filePath)
                        if (file.exists() && (file.name.contains("chat_attachments") ||
                            filePath.contains(context.filesDir.absolutePath))) {
                            // 用户触发删除：始终释放占用空间
                            allFilePathsToDelete.add(filePath)
                        }
                    }
                }
            }

            // 删除文件
            allFilePathsToDelete.forEach { path ->
                try {
                    val file = File(path)
                    if (file.exists()) {
                        if (file.delete()) {
                            Log.d(TAG, "Successfully deleted media file: $path")
                            deletedFilesCount++
                        } else {
                            Log.w(TAG, "Failed to delete media file: $path")
                        }
                    } else {
                        Log.w(TAG, "Media file to delete does not exist: $path")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception deleting media file: $path", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting media file: $path", e)
                }
            }

            // 清理图片缓存
            allFilePathsToDelete.forEach { path ->
                imageLoader.diskCache?.remove(path)
                imageLoader.diskCache?.remove("file://$path")
            }

            allHttpUrisToClearFromCache.forEach { url ->
                imageLoader.diskCache?.remove(url)
            }

            Log.d(TAG, "Finished media file deletion. Total files deleted: $deletedFilesCount")
        }
    }

    /**
     * 清理孤立的附件文件与临时缓存（已删除会话但文件仍存在的情况），并回收图片缓存
     *
     * 覆盖范围：
     * - cacheDir/preview_cache 预览生成的临时文件
     * - cacheDir/share_images 分享生成的临时文件
     * - Coil 内存/磁盘缓存（在清空历史或大批删除后统一清理，防止残留占用）
     *
     * 注意：不再自动删除 filesDir/chat_attachments 下的文件（包括AI生成图片）。
     * 这些文件仅在“用户删除会话/历史”时释放，以符合“仅手动删除才清理”的预期。
     */
    suspend fun cleanupOrphanedAttachments() {
        withContext(Dispatchers.IO) {
            try {
                val chatAttachmentsDir = File(context.filesDir, "chat_attachments")
                val previewCacheDir = File(context.cacheDir, "preview_cache")
                val shareImagesDir = File(context.cacheDir, "share_images")

                // 1) [DISABLED] 统计当前仍被引用的附件路径
                // 2) [DISABLED] 执行 chat_attachments 的孤儿文件清理
                // 修改说明：已禁用自动清理孤儿文件，以防止因数据加载延迟或匹配错误导致的图片误删。
                // 图片文件现在仅在用户显式删除会话或清空历史时才会删除。
                
                val orphanedCount = 0
                Log.i(TAG, "cleanupOrphanedAttachments: Orphan cleanup for chat_attachments is DISABLED. Skipping.")

                // 3) 清空预览/分享产生的临时缓存（cacheDir），这些文件不持久化引用，直接安全删除
                fun clearCacheDir(dir: File, label: String): Int {
                    if (!dir.exists()) return 0
                    var count = 0
                    dir.listFiles()?.forEach { f ->
                        try {
                            if (f.isFile) {
                                if (f.delete()) count++
                            } else {
                                if (f.deleteRecursively()) count++
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete cache file in $label: ${f.absolutePath}", e)
                        }
                    }
                    Log.d(TAG, "Cleared $count files from $label")
                    return count
                }
                val clearedPreview = clearCacheDir(previewCacheDir, "preview_cache")
                val clearedShare = clearCacheDir(shareImagesDir, "share_images")

                // 4) 统一清理 Coil 内存/磁盘缓存，避免 URL/请求键不匹配导致的残留
                runCatching {
                    imageLoader.memoryCache?.clear()
                    Log.d(TAG, "Coil memory cache cleared")
                }.onFailure { e -> Log.w(TAG, "Failed to clear Coil memory cache", e) }

                runCatching {
                    imageLoader.diskCache?.clear()
                    Log.d(TAG, "Coil disk cache cleared")
                }.onFailure { e -> Log.w(TAG, "Failed to clear Coil disk cache", e) }

                Log.i(TAG, "Cleanup completed. Deleted $orphanedCount orphaned files. Cleared preview=$clearedPreview, share=$clearedShare cache files.")
            } catch (e: Exception) {
                Log.e(TAG, "Error during orphaned file cleanup", e)
            }
        }
    }

    // ========= 置顶集合：文本与图像 =========
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
                Log.e(TAG, "loadPinnedIds failed", e)
                emptySet()
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
                Log.e(TAG, "clearVoiceBackendConfigs 失败", e)
            }
        }
    }
}