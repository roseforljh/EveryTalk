package com.example.everytalk.StateControler // 您的包名

import android.util.Log
import androidx.compose.material3.DrawerState // 确保 DrawerState 正确导入
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.everytalk.data.local.SharedPreferencesDataSource // SharedPreferences 数据源
import com.example.everytalk.data.DataClass.ApiConfig // API 配置数据类
import com.example.everytalk.data.DataClass.Message // UI 层消息数据类
import com.example.everytalk.data.DataClass.Sender // 消息发送者枚举
import com.example.everytalk.data.DataClass.ChatRequest // API 请求数据类
import com.example.everytalk.data.DataClass.ApiMessage as DataClassApiMessage // 为请求中的ApiMessage重命名以避免冲突
import com.example.everytalk.data.DataClass.WebSearchResult // Web搜索结果数据类

import com.example.everytalk.data.network.ApiClient // API 客户端 (假设存在)
import com.example.everytalk.ui.screens.viewmodel.ConfigManager // 配置管理器
import com.example.everytalk.ui.screens.viewmodel.DataPersistenceManager // 数据持久化管理器
import com.example.everytalk.ui.screens.viewmodel.HistoryManager // 历史记录管理器
import kotlinx.coroutines.Dispatchers // 协程调度器
import kotlinx.coroutines.flow.* // Flow API
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch // 启动协程
import kotlinx.coroutines.withContext // 切换协程上下文
import java.util.UUID // 用于生成唯一ID
import kotlinx.serialization.Contextual // 确保 @Contextual 被导入，如果 ChatRequest 中的 Map 值需要
import kotlinx.serialization.json.JsonElement

class AppViewModel(private val dataSource: SharedPreferencesDataSource) : ViewModel() {

    private val instanceId = UUID.randomUUID().toString().take(8) // ViewModel实例的唯一ID（用于调试）

    // --- 依赖注入和管理器初始化 ---
    internal val stateHolder = ViewModelStateHolder() // ViewModel的状态容器实例
    private val persistenceManager =
        DataPersistenceManager(dataSource, stateHolder, viewModelScope) // 数据持久化管理器

    private val historyManager: HistoryManager = // 聊天历史管理器
        HistoryManager(
            stateHolder,
            persistenceManager,
            ::areMessageListsEffectivelyEqual
        ) // ::areMessageListsEffectivelyEqual 是比较函数引用
    private val apiHandler: ApiHandler by lazy { // API请求处理器 (懒加载)
        ApiHandler(
            stateHolder,
            viewModelScope,
            historyManager
        )
    }
    private val configManager: ConfigManager by lazy { // API配置管理器 (懒加载)
        ConfigManager(
            stateHolder,
            persistenceManager,
            apiHandler, // ConfigManager 可能需要取消API调用
            viewModelScope
        )
    }

    // --- UI State Flows 和可观察属性 ---
    val drawerState: DrawerState get() = stateHolder.drawerState // 侧边抽屉状态
    val text: StateFlow<String> get() = stateHolder._text.asStateFlow() // 用户当前输入文本
    val messages: SnapshotStateList<Message> get() = stateHolder.messages // 当前聊天界面的消息列表 (可组合快照列表)

    val historicalConversations: StateFlow<List<List<Message>>> get() = stateHolder._historicalConversations.asStateFlow() // 历史对话列表
    val loadedHistoryIndex: StateFlow<Int?> get() = stateHolder._loadedHistoryIndex.asStateFlow() // 当前加载的历史对话在列表中的索引
    val apiConfigs: StateFlow<List<ApiConfig>> get() = stateHolder._apiConfigs.asStateFlow() // 所有API配置列表
    val selectedApiConfig: StateFlow<ApiConfig?> get() = stateHolder._selectedApiConfig.asStateFlow() // 当前选中的API配置
    val isApiCalling: StateFlow<Boolean> get() = stateHolder._isApiCalling.asStateFlow() // API是否正在调用中
    val currentStreamingAiMessageId: StateFlow<String?> get() = stateHolder._currentStreamingAiMessageId.asStateFlow() // 当前正在流式输出的AI消息ID
    val reasoningCompleteMap: Map<String, Boolean> get() = stateHolder.reasoningCompleteMap // 存储每条AI消息的思考过程是否已完成的状态
    val expandedReasoningStates: MutableMap<String, Boolean> get() = stateHolder.expandedReasoningStates // 存储思考过程UI是否展开的状态 (如果UI支持手动展开/折叠)

    val snackbarMessage: SharedFlow<String> get() = stateHolder._snackbarMessage.asSharedFlow() // 用于显示 Snackbar 提示消息
    val scrollToBottomEvent: SharedFlow<Unit> get() = stateHolder._scrollToBottomEvent.asSharedFlow() // 触发滚动到底部事件

    // 编辑消息对话框状态
    private val _showEditDialog = MutableStateFlow(false) // 是否显示编辑对话框
    val showEditDialog: StateFlow<Boolean> = _showEditDialog.asStateFlow()
    private val _editingMessageId = MutableStateFlow<String?>(null) // 正在编辑的消息ID
    val editDialogInputText: StateFlow<String> get() = stateHolder._editDialogInputText.asStateFlow() // 编辑对话框中的输入文本

    // 重命名历史对话框状态
    private val _showRenameDialogState = MutableStateFlow(false) // 是否显示重命名对话框
    val showRenameDialogState: StateFlow<Boolean> = _showRenameDialogState.asStateFlow()
    private val _renamingIndexState = MutableStateFlow<Int?>(null) // 正在重命名的历史对话索引
    val renamingIndexState: StateFlow<Int?> = _renamingIndexState.asStateFlow()
    val renameInputText: StateFlow<String> get() = stateHolder._renameInputText.asStateFlow() // 重命名对话框中的输入文本

    // 抽屉内搜索状态
    private val _isSearchActiveInDrawer = MutableStateFlow(false) // 抽屉内搜索是否激活
    val isSearchActiveInDrawer: StateFlow<Boolean> = _isSearchActiveInDrawer.asStateFlow()
    private val _searchQueryInDrawer = MutableStateFlow("") // 抽屉内搜索查询文本
    val searchQueryInDrawer: StateFlow<String> = _searchQueryInDrawer.asStateFlow()

    // 模型提供商列表 (包含预设和用户自定义)
    private val _customProviders = MutableStateFlow<Set<String>>(emptySet()) // 用户自定义的提供商名称集合
    val allProviders: StateFlow<List<String>> = combine( // 合并预设和自定义提供商，并排序
        _customProviders
    ) { customsParam ->
        val customs = customsParam[0] // combine的参数是一个数组，这里只有一个flow，所以取第一个元素
        val predefinedPlatforms = // 预设的平台/提供商名称列表
            listOf(
                "openai compatible",
                "google",
                "硅基流动",
                "阿里云百炼",
                "火山引擎",
                "深度求索"
            ) // 您的预设列表
        val combinedList = (predefinedPlatforms + customs.toList()).distinct() // 合并并去重
        val predefinedOrderMap =
            predefinedPlatforms.withIndex().associate { it.value to it.index } // 用于预设项排序的映射

        combinedList.sortedWith(compareBy<String> { platform -> // 自定义排序逻辑
            predefinedOrderMap[platform]
                ?: (predefinedPlatforms.size + customs.indexOf(platform) // 预设项在前，然后是自定义项
                    .let { if (it == -1) Int.MAX_VALUE else it }) // 处理可能的-1索引
        }.thenBy { it }) // 按名称字母排序（作为次要排序规则）
    }.stateIn( // 将Flow转换为StateFlow
        viewModelScope,
        SharingStarted.Eagerly, // 立即开始共享
        listOf(
            "openai compatible",
            "google",
            "硅基流动",
            "阿里云百炼",
            "火山引擎",
            "深度求索"
        ) // 初始值
    )

    // 联网搜索状态
    val isWebSearchEnabled: StateFlow<Boolean> get() = stateHolder._isWebSearchEnabled.asStateFlow() // 联网搜索是否启用

    // "查看来源"对话框状态
    val showSourcesDialog: StateFlow<Boolean> get() = stateHolder._showSourcesDialog.asStateFlow() // 是否显示查看来源对话框
    val sourcesForDialog: StateFlow<List<WebSearchResult>> get() = stateHolder._sourcesForDialog.asStateFlow() // 对话框中显示的Web搜索结果

    // --- 初始化块 ---
    init {
        Log.d(TAG_APP_VIEW_MODEL, "[ID:$instanceId] ViewModel 初始化开始")
        viewModelScope.launch(Dispatchers.IO) { // 在IO线程执行一些预热操作
            ApiClient.preWarm() // 假设ApiClient有预热方法
            apiHandler // 确保懒加载的apiHandler被创建
            configManager // 确保懒加载的configManager被创建
            Log.d(TAG_APP_VIEW_MODEL, "[ID:$instanceId] ViewModel IO预热完成")
        }
        // 加载初始数据 (配置、历史等)
        persistenceManager.loadInitialData { initialConfigPresent, historyPresent ->
            viewModelScope.launch(Dispatchers.IO) { // 在IO线程加载自定义提供商
                val loadedCustomProviders = dataSource.loadCustomProviders()
                _customProviders.value = loadedCustomProviders
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "[ID:$instanceId] 自定义提供商加载完成，数量: ${loadedCustomProviders.size}"
                )
            }

            val currentChatMessagesFromPersistence = stateHolder.messages.toList() // 从持久化加载的当前聊天消息
            val historicalConversationsFromPersistence =
                stateHolder._historicalConversations.value // 从持久化加载的历史对话

            if (currentChatMessagesFromPersistence.isNotEmpty() && historyPresent) {
                val matchedIndex =
                    historicalConversationsFromPersistence.indexOfFirst { historicalChat ->
                        areMessageListsEffectivelyEqual(
                            currentChatMessagesFromPersistence,
                            historicalChat
                        )
                    }
                stateHolder._loadedHistoryIndex.value =
                    if (matchedIndex != -1) matchedIndex else null
                if (matchedIndex != -1) {
                    Log.w(TAG_APP_VIEW_MODEL_INIT, "恢复的当前聊天匹配了历史索引 $matchedIndex。")
                } else {
                    Log.w(TAG_APP_VIEW_MODEL_INIT, "恢复的当前聊天非空，但未匹配任何历史对话。")
                }
            } else { // 如果没有恢复的当前聊天，或者没有历史记录
                stateHolder._loadedHistoryIndex.value = null // 没有加载任何特定历史对话
            }

            viewModelScope.launch(Dispatchers.Main) { // 在主线程处理UI相关的初始逻辑
                if (!initialConfigPresent && stateHolder._apiConfigs.value.isEmpty()) {
                    showSnackbar("请添加 API 配置") // 如果没有配置，提示用户添加
                } else if (stateHolder._selectedApiConfig.value == null && stateHolder._apiConfigs.value.isNotEmpty()) {
                    val configToSelect =
                        stateHolder._apiConfigs.value.firstOrNull { it.isValid }
                            ?: stateHolder._apiConfigs.value.firstOrNull()
                    configToSelect?.let { selectConfig(it) }
                }
                Log.d(TAG_APP_VIEW_MODEL_INIT, "[ID:$instanceId] 初始化UI相关逻辑完成。")
            }
        }
        Log.d(TAG_APP_VIEW_MODEL, "[ID:$instanceId] ViewModel 初始化逻辑结束.")
    }

    /**
     * 比较两个消息列表是否在内容上有效相等（用于历史记录匹配等）。
     */
    private fun areMessageListsEffectivelyEqual(
        list1: List<Message>?,
        list2: List<Message>?
    ): Boolean {
        if (list1 == null && list2 == null) return true
        if (list1 == null || list2 == null) return false
        val filteredList1 = filterMessagesForComparison(list1)
        val filteredList2 = filterMessagesForComparison(list2)
        if (filteredList1.size != filteredList2.size) return false
        for (i in filteredList1.indices) {
            val msg1 = filteredList1[i]
            val msg2 = filteredList2[i]
            if (msg1.id != msg2.id ||
                msg1.sender != msg2.sender ||
                msg1.text.trim() != msg2.text.trim() ||
                msg1.reasoning?.trim() != msg2.reasoning?.trim() ||
                msg1.isError != msg2.isError
            ) return false
        }
        return true
    }

    /**
     * 过滤消息列表，仅保留用于比较的有效消息。
     */
    private fun filterMessagesForComparison(messagesToFilter: List<Message>): List<Message> {
        return messagesToFilter.filter { msg ->
            (msg.sender != Sender.System || msg.isPlaceholderName) &&
                    (msg.sender == Sender.User ||
                            (msg.sender == Sender.AI && (msg.contentStarted || msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())) ||
                            (msg.sender == Sender.System && msg.isPlaceholderName) ||
                            msg.isError)
        }.toList()
    }

    /**
     * 切换联网搜索模式。
     */
    fun toggleWebSearchMode(enabled: Boolean) {
        stateHolder._isWebSearchEnabled.value = enabled
        showSnackbar("联网搜索已 ${if (enabled) "开启" else "关闭"}")
        Log.d(TAG_APP_VIEW_MODEL, "联网搜索模式切换为: $enabled")
    }

    /**
     * 添加用户自定义的模型提供商名称。
     */
    fun addProvider(providerName: String) {
        val trimmedName = providerName.trim()
        if (trimmedName.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                val currentCustomProviders = _customProviders.value.toMutableSet()
                val predefinedForCheck = listOf(
                    "openai compatible", "google", "硅基流动", "阿里云百炼", "火山引擎", "深度求索"
                ).map { it.lowercase() }

                if (predefinedForCheck.contains(trimmedName.lowercase())) {
                    withContext(Dispatchers.Main) { showSnackbar("平台名称 '$trimmedName' 是预设名称，无法添加。") }
                    return@launch
                }
                if (currentCustomProviders.any {
                        it.equals(
                            trimmedName,
                            ignoreCase = true
                        )
                    }) {
                    withContext(Dispatchers.Main) { showSnackbar("模型平台 '$trimmedName' 已存在") }
                    return@launch
                }

                currentCustomProviders.add(trimmedName)
                _customProviders.value = currentCustomProviders.toSet()
                dataSource.saveCustomProviders(currentCustomProviders.toSet())
                withContext(Dispatchers.Main) { showSnackbar("模型平台 '$trimmedName' 已添加") }
                Log.i(TAG_APP_VIEW_MODEL, "添加了新的自定义提供商: $trimmedName")
            }
        } else {
            showSnackbar("平台名称不能为空")
        }
    }

    /**
     * 显示 Snackbar 提示消息。
     */
    fun showSnackbar(message: String) {
        stateHolder._snackbarMessage.tryEmit(message)
    }

    /**
     * 设置抽屉内搜索功能的激活状态。
     */
    fun setSearchActiveInDrawer(isActive: Boolean) {
        _isSearchActiveInDrawer.value = isActive
        if (!isActive) _searchQueryInDrawer.value = ""
        Log.d(TAG_APP_VIEW_MODEL, "抽屉内搜索状态设置为: $isActive")
    }

    /**
     * 当抽屉内搜索查询文本变化时的回调。
     */
    fun onDrawerSearchQueryChange(query: String) {
        _searchQueryInDrawer.value = query
    }

    /**
     * 当用户输入框文本变化时的回调。
     */
    fun onTextChange(newText: String) {
        stateHolder._text.value = newText
    }

    /**
     * 发送消息。
     * @param messageText 要发送的消息文本。
     * @param isFromRegeneration 是否是由于“重新生成”操作触发的发送。
     */
    fun onSendMessage(messageText: String, isFromRegeneration: Boolean = false) {
        val textToActuallySend = messageText.trim()
        if (textToActuallySend.isEmpty()) {
            if (!isFromRegeneration) showSnackbar("请输入消息内容")
            return
        }
        val currentConfig = stateHolder._selectedApiConfig.value ?: run {
            showSnackbar("请先选择 API 配置")
            return
        }
        val currentWebSearchEnabled = stateHolder._isWebSearchEnabled.value

        viewModelScope.launch(Dispatchers.Main.immediate) {
            val newUserMessage =
                Message(text = textToActuallySend, sender = Sender.User, contentStarted = true)
            stateHolder.messages.add(newUserMessage)
            Log.d(
                TAG_APP_VIEW_MODEL,
                "onSendMessage: 用户消息已添加 (ID: ${newUserMessage.id.take(8)}), isFromRegeneration: $isFromRegeneration"
            )

            if (!isFromRegeneration) {
                stateHolder._text.value = ""
            }
            triggerScrollToBottom()

            val apiHistoryMessages = mutableListOf<DataClassApiMessage>()
            val messagesSnapshotForHistory = stateHolder.messages.toList()
            var historyMessageCount = 0
            val maxHistoryMessages = 20

            for (msg in messagesSnapshotForHistory.asReversed()) {
                if (historyMessageCount >= maxHistoryMessages) break
                val apiMsgToAdd: DataClassApiMessage? = when {
                    msg.sender == Sender.User && msg.text.isNotBlank() ->
                        DataClassApiMessage(role = "user", content = msg.text.trim())

                    msg.sender == Sender.AI && !msg.isError && (msg.contentStarted || msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank()) -> {
                        val contentForApi =
                            if (msg.text.isNotBlank()) msg.text.trim() else msg.reasoning?.trim()
                                ?: ""
                        if (contentForApi.isNotBlank()) DataClassApiMessage(
                            role = "assistant",
                            content = contentForApi
                        ) else null
                    }

                    else -> null
                }
                apiMsgToAdd?.let {
                    apiHistoryMessages.add(0, it)
                    historyMessageCount++
                }
            }
            if (apiHistoryMessages.isEmpty() || apiHistoryMessages.last().role != "user" || apiHistoryMessages.last().content != textToActuallySend) {
                Log.w(
                    TAG_APP_VIEW_MODEL,
                    "onSendMessage: apiHistoryMessages 构建异常，末尾不是预期的用户消息。将强制修正。"
                )
                if (apiHistoryMessages.isNotEmpty() && apiHistoryMessages.last().role == "user") {
                    apiHistoryMessages.removeAt(apiHistoryMessages.lastIndex)
                }
                apiHistoryMessages.add(
                    DataClassApiMessage(
                        role = "user",
                        content = textToActuallySend
                    )
                )
                while (apiHistoryMessages.size > maxHistoryMessages && apiHistoryMessages.isNotEmpty()) {
                    apiHistoryMessages.removeAt(0)
                }
            }

            // --- START: 新增的逻辑，用于决定 customModelParameters 和 customExtraBody ---
            var customParams: Map<String, @Contextual Any>? = null
            var customExtra: Map<String, @Contextual Any>? = null

            val modelNameLower = currentConfig.model.lowercase()
            val apiAddressLower = currentConfig.address?.lowercase() ?: ""

            if (modelNameLower.contains("qwen3")) {
                if (apiAddressLower.contains("api.siliconflow.cn")) {
                    customParams = mapOf("enable_thinking" to false)
                    Log.d(
                        TAG_APP_VIEW_MODEL,
                        "为 SiliconFlow Qwen3 模型 ('${currentConfig.model}') 设置顶层参数 'enable_thinking=false'"
                    )
                } else if (apiAddressLower.contains("aliyuncs.com")) {
                    // 对于阿里云 DashScope 上的 Qwen3:
                    // 您需要根据 DashScope 的文档确认 'enable_thinking' 参数的正确传递方式。
                    // 1. 如果 DashScope Qwen3 也接受顶层参数 'enable_thinking':
                    // customParams = mapOf("enable_thinking" to false)
                    // Log.d(TAG_APP_VIEW_MODEL, "为 DashScope Qwen3 模型 ('${currentConfig.model}') 设置顶层参数 'enable_thinking=false' (请确认此方式是否正确)")
                    // 2. 或者如果 DashScope Qwen3 需要通过 'extra_body' 传递:
                    // customExtra = mapOf("enable_thinking" to false)
                    // Log.d(TAG_APP_VIEW_MODEL, "为 DashScope Qwen3 模型 ('${currentConfig.model}') 在 extra_body 中设置 'enable_thinking=false' (请确认此方式是否正确)")

                    // 目前，由于不确定 DashScope 的具体要求，这里暂时不主动为 DashScope Qwen3 设置。
                    // 如果需要控制，客户端应明确通过 customModelParameters 或 customExtraBody 发送。
                    Log.w(
                        TAG_APP_VIEW_MODEL,
                        "检测到 DashScope 上的 Qwen3 模型 ('${currentConfig.model}'). 如果需要关闭思考模式，请确保客户端通过 customModelParameters 或 customExtraBody 正确传递 'enable_thinking: false' (根据DashScope文档)。当前ViewModel不主动设置。"
                    )
                }
                // 如果还有其他平台支持 Qwen3 并有 'enable_thinking'，可以在这里添加更多的 else if 分支
            }
            // --- END: customModelParameters 和 customExtraBody 逻辑 ---

            val requestBody = ChatRequest(
                messages = apiHistoryMessages,
                provider = if (currentConfig.provider.equals(
                        "google",
                        ignoreCase = true
                    )
                ) "google" else "openai",
                apiAddress = currentConfig.address,
                apiKey = currentConfig.key,
                model = currentConfig.model,
                useWebSearch = if (currentWebSearchEnabled) true else null,
                forceGoogleReasoningPrompt = null, // 保持为 null，让后端决定
                // 传递其他可选的生成参数 (如果您的 ApiConfig 或 stateHolder 中有这些值)
                // 如果 ApiConfig 中没有这些字段，或者您希望使用API的默认值，可以将它们设为 null
                temperature = null, // 假设 ApiConfig 有 temperature 字段
                topP = null,                 // 假设 ApiConfig 有 topP 字段
                maxTokens = null,       // 假设 ApiConfig 有 maxTokens 字段
                tools = null, // 如果您的 ChatRequest 支持工具且有值，从相应位置获取
                toolChoice = null, // 同上
                // 传入我们刚刚决定的自定义参数
                customModelParameters = mapOf("enable_thinking" to false),
                customExtraBody = mapOf("auto_generate" to true)
            )

            apiHandler.streamChatResponse(
                requestBody = requestBody,
                userMessageTextForContext = textToActuallySend,
                afterUserMessageId = newUserMessage.id,
                onMessagesProcessed = {
                    viewModelScope.launch { historyManager.saveCurrentChatToHistoryIfNeeded() }
                }
            )
        }
    }

    /**
     * 当编辑消息对话框中的文本变化时的回调。
     */
    fun onEditDialogTextChanged(newText: String) {
        stateHolder._editDialogInputText.value = newText
    }

    /**
     * 请求编辑指定消息。
     */
    fun requestEditMessage(message: Message) {
        if (message.sender == Sender.User) {
            _editingMessageId.value = message.id
            stateHolder._editDialogInputText.value = message.text
            _showEditDialog.value = true
            Log.d(TAG_APP_VIEW_MODEL, "请求编辑用户消息: ${message.id.take(8)}")
        } else {
            showSnackbar("只能编辑您发送的消息")
        }
    }

    /**
     * 确认消息编辑。
     */
    fun confirmMessageEdit() {
        val messageIdToEdit = _editingMessageId.value ?: return
        val updatedText = stateHolder._editDialogInputText.value.trim()
        if (updatedText.isBlank()) {
            showSnackbar("消息内容不能为空"); return
        }
        viewModelScope.launch {
            val messageIndex = stateHolder.messages.indexOfFirst { it.id == messageIdToEdit }
            if (messageIndex != -1) {
                val originalMessage = stateHolder.messages[messageIndex]
                if (originalMessage.text != updatedText) {
                    stateHolder.messages[messageIndex] = originalMessage.copy(
                        text = updatedText,
                        timestamp = System.currentTimeMillis()
                    )
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
                    showSnackbar("消息已更新")
                    Log.i(TAG_APP_VIEW_MODEL, "用户消息 ${messageIdToEdit.take(8)} 已编辑并更新。")
                }
            }
            withContext(Dispatchers.Main.immediate) { dismissEditDialog() }
        }
    }

    /**
     * 关闭编辑消息对话框。
     */
    fun dismissEditDialog() {
        _showEditDialog.value = false
        _editingMessageId.value = null
        stateHolder._editDialogInputText.value = ""
    }

    /**
     * 为指定的用户消息重新生成AI回答。
     */
    fun regenerateAiResponse(originalUserMessage: Message) {
        if (originalUserMessage.sender != Sender.User) {
            showSnackbar("只能为您的消息重新生成回答")
            return
        }
        if (stateHolder._selectedApiConfig.value == null) {
            showSnackbar("请先选择 API 配置")
            return
        }

        val originalUserMessageText = originalUserMessage.text
        val originalUserMessageId = originalUserMessage.id
        Log.d(
            TAG_APP_VIEW_MODEL,
            "Regenerate: 开始为用户消息 ID ${originalUserMessageId.take(8)} 重新生成回答。"
        )

        viewModelScope.launch(Dispatchers.Main.immediate) {
            val userMessageIndex =
                stateHolder.messages.indexOfFirst { it.id == originalUserMessageId }
            if (userMessageIndex == -1) {
                showSnackbar("无法重新生成：原始用户消息在当前列表中未找到。")
                Log.w(
                    TAG_APP_VIEW_MODEL,
                    "Regenerate: 未找到ID为 ${originalUserMessageId.take(8)} 的原始用户消息。"
                )
                return@launch
            }

            val nextMessageIndex = userMessageIndex + 1
            if (nextMessageIndex < stateHolder.messages.size && stateHolder.messages[nextMessageIndex].sender == Sender.AI) {
                val aiMessageToRemove = stateHolder.messages[nextMessageIndex]
                if (stateHolder._currentStreamingAiMessageId.value == aiMessageToRemove.id) {
                    apiHandler.cancelCurrentApiJob(
                        "为消息重新生成回答，取消旧AI流",
                        isNewMessageSend = true
                    )
                }
                stateHolder.messages.removeAt(nextMessageIndex)
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "Regenerate: 已移除旧AI回答 (ID: ${aiMessageToRemove.id.take(8)})。"
                )
            }

            stateHolder.messages.removeAt(userMessageIndex)
            Log.d(
                TAG_APP_VIEW_MODEL,
                "Regenerate: 已移除原始用户消息 (ID: ${originalUserMessageId.take(8)})。"
            )

            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
            Log.d(TAG_APP_VIEW_MODEL, "Regenerate: 已强制保存移除旧消息后的聊天状态到历史。")

            onSendMessage(messageText = originalUserMessageText, isFromRegeneration = true)
            Log.i(
                TAG_APP_VIEW_MODEL,
                "Regenerate: 已为文本 '${originalUserMessageText.take(20)}...' 重新调用 onSendMessage。"
            )
        }
    }

    /**
     * 触发聊天列表滚动到底部。
     */
    fun triggerScrollToBottom() {
        stateHolder._scrollToBottomEvent.tryEmit(Unit)
    }

    /**
     * 取消当前正在进行的API调用。
     */
    fun onCancelAPICall() {
        apiHandler.cancelCurrentApiJob("用户取消操作")
        showSnackbar("已停止回答")
    }

    /**
     * 开始一个新的聊天。
     */
    fun startNewChat() {
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("开始新聊天")
        viewModelScope.launch {
            historyManager.saveCurrentChatToHistoryIfNeeded()
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat()
                triggerScrollToBottom()
                if (_isSearchActiveInDrawer.value) setSearchActiveInDrawer(false)
                stateHolder._loadedHistoryIndex.value = null
            }
            Log.i(TAG_APP_VIEW_MODEL, "已开始新聊天。")
        }
    }

    /**
     * 从历史记录中加载指定的对话。
     */
    fun loadConversationFromHistory(index: Int) {
        val conversationList = stateHolder._historicalConversations.value
        if (index < 0 || index >= conversationList.size) {
            showSnackbar("无法加载对话：无效的索引"); return
        }
        val conversationToLoad = conversationList[index]
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("加载历史索引 $index")

        viewModelScope.launch {
            historyManager.saveCurrentChatToHistoryIfNeeded()
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat()
                stateHolder.messages.addAll(conversationToLoad.map { msg ->
                    msg.copy(contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError)
                })
                stateHolder.messages.forEach { msg ->
                    stateHolder.messageAnimationStates[msg.id] = true
                }
                stateHolder._loadedHistoryIndex.value = index
                triggerScrollToBottom()
            }
            if (_isSearchActiveInDrawer.value) {
                withContext(Dispatchers.Main.immediate) { setSearchActiveInDrawer(false) }
            }
            Log.i(TAG_APP_VIEW_MODEL, "已从历史加载对话，索引: $index")
        }
    }

    /**
     * 删除指定索引的历史对话。
     */
    fun deleteConversation(indexToDelete: Int) {
        val currentLoadedIndex = stateHolder._loadedHistoryIndex.value
        if (indexToDelete < 0 || indexToDelete >= stateHolder._historicalConversations.value.size) {
            showSnackbar("无法删除：无效的索引"); return
        }
        viewModelScope.launch {
            val wasCurrentChatDeleted = (currentLoadedIndex == indexToDelete)
            historyManager.deleteConversation(indexToDelete)
            if (wasCurrentChatDeleted) {
                withContext(Dispatchers.Main.immediate) {
                    dismissEditDialog()
                    dismissSourcesDialog()
                    stateHolder.clearForNewChat()
                    triggerScrollToBottom()
                }
                apiHandler.cancelCurrentApiJob("当前聊天(#$indexToDelete)被删除，开始新聊天")
            }
            withContext(Dispatchers.Main) { showSnackbar("对话已删除") }
            Log.i(TAG_APP_VIEW_MODEL, "已删除历史对话，索引: $indexToDelete")
        }
    }

    /**
     * 清除所有历史对话。
     */
    fun clearAllConversations() {
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("清除所有历史记录")
        viewModelScope.launch {
            historyManager.clearAllHistory()
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat()
                triggerScrollToBottom()
            }
            withContext(Dispatchers.Main) { showSnackbar("所有对话已清除") }
            Log.i(TAG_APP_VIEW_MODEL, "已清除所有历史对话。")
        }
    }

    /**
     * 显示包含Web搜索结果来源的对话框。
     */
    fun showSourcesDialog(sources: List<WebSearchResult>) {
        viewModelScope.launch {
            stateHolder._sourcesForDialog.value = sources
            stateHolder._showSourcesDialog.value = true
            Log.d(TAG_APP_VIEW_MODEL, "请求显示来源对话框。来源数量: ${sources.size}")
        }
    }

    /**
     * 关闭Web搜索结果来源对话框。
     */
    fun dismissSourcesDialog() {
        viewModelScope.launch {
            if (stateHolder._showSourcesDialog.value) {
                stateHolder._showSourcesDialog.value = false
                Log.d(TAG_APP_VIEW_MODEL, "请求关闭来源对话框。")
            }
        }
    }

    // --- API 配置管理方法 (通过ConfigManager实现) ---
    fun addConfig(config: ApiConfig) = configManager.addConfig(config)
    fun updateConfig(config: ApiConfig) = configManager.updateConfig(config)
    fun deleteConfig(config: ApiConfig) = configManager.deleteConfig(config)
    fun clearAllConfigs() = configManager.clearAllConfigs()
    fun selectConfig(config: ApiConfig) = configManager.selectConfig(config)

    /**
     * 当消息动画播放完成时的回调。
     */
    fun onAnimationComplete(messageId: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            stateHolder.messageAnimationStates[messageId] = true
        }
    }

    /**
     * 检查指定消息的动画是否已经播放过。
     */
    fun hasAnimationBeenPlayed(messageId: String): Boolean =
        stateHolder.messageAnimationStates[messageId] ?: false

    /**
     * 获取历史对话的预览文本（用于在抽屉中显示）。
     */
    fun getConversationPreviewText(index: Int): String {
        val conversation = stateHolder._historicalConversations.value.getOrNull(index)
            ?: return "对话 ${index + 1}"
        val placeholderTitleMsg =
            conversation.firstOrNull { it.sender == Sender.System && it.isPlaceholderName && it.text.isNotBlank() }?.text?.trim()
        if (!placeholderTitleMsg.isNullOrBlank()) return placeholderTitleMsg
        val firstUserMsg =
            conversation.firstOrNull { it.sender == Sender.User && it.text.isNotBlank() }?.text?.trim()
        if (!firstUserMsg.isNullOrBlank()) return firstUserMsg
        val firstAiMsg =
            conversation.firstOrNull { it.sender == Sender.AI && it.text.isNotBlank() }?.text?.trim()
        if (!firstAiMsg.isNullOrBlank()) return firstAiMsg
        return "对话 ${index + 1}"
    }

    /**
     * 当重命名对话框输入文本变化时的回调。
     */
    fun onRenameInputTextChange(newName: String) {
        stateHolder._renameInputText.value = newName
    }

    /**
     * 显示重命名对话框。
     */
    fun showRenameDialog(index: Int) {
        if (index >= 0 && index < stateHolder._historicalConversations.value.size) {
            _renamingIndexState.value = index
            val currentPreview = getConversationPreviewText(index)
            val isDefaultPreview = currentPreview.startsWith("对话 ") && runCatching {
                currentPreview.substringAfter("对话 ").toIntOrNull() == index + 1
            }.getOrElse { false }
            stateHolder._renameInputText.value = if (isDefaultPreview) "" else currentPreview
            _showRenameDialogState.value = true
            Log.d(TAG_APP_VIEW_MODEL, "显示重命名对话框，索引: $index, 当前预览: '$currentPreview'")
        } else {
            showSnackbar("无法重命名：无效的对话索引")
        }
    }

    /**
     * 关闭重命名对话框。
     */
    fun dismissRenameDialog() {
        _showRenameDialogState.value = false
        _renamingIndexState.value = null
        stateHolder._renameInputText.value = ""
    }

    /**
     * 重命名指定索引的历史对话。
     */
    fun renameConversation(index: Int, newName: String) {
        val trimmedNewName = newName.trim()
        if (trimmedNewName.isBlank()) {
            showSnackbar("新名称不能为空"); return
        }
        viewModelScope.launch {
            val currentHistoricalConvos = stateHolder._historicalConversations.value
            if (index < 0 || index >= currentHistoricalConvos.size) {
                withContext(Dispatchers.Main) { showSnackbar("无法重命名：对话索引错误") }; return@launch
            }

            val originalConversationAtIndex = currentHistoricalConvos[index]
            val newMessagesForThisConversation = mutableListOf<Message>()

            var titleMessageUpdatedOrAdded = false
            if (originalConversationAtIndex.isNotEmpty() &&
                originalConversationAtIndex.first().sender == Sender.System &&
                originalConversationAtIndex.first().isPlaceholderName
            ) {
                newMessagesForThisConversation.add(
                    originalConversationAtIndex.first()
                        .copy(text = trimmedNewName, timestamp = System.currentTimeMillis())
                )
                newMessagesForThisConversation.addAll(originalConversationAtIndex.drop(1))
                titleMessageUpdatedOrAdded = true
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "更新了现有标题消息为 '$trimmedNewName' 对于历史索引 $index"
                )
            }

            if (!titleMessageUpdatedOrAdded) {
                val titleMessage = Message(
                    id = "title_${UUID.randomUUID()}",
                    text = trimmedNewName,
                    sender = Sender.System,
                    timestamp = System.currentTimeMillis() - 1,
                    contentStarted = true,
                    isPlaceholderName = true
                )
                newMessagesForThisConversation.add(titleMessage)
                newMessagesForThisConversation.addAll(originalConversationAtIndex)
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "添加了新的标题消息 '$trimmedNewName' 对于历史索引 $index"
                )
            }

            val updatedHistoricalConversationsList = currentHistoricalConvos.toMutableList().apply {
                this[index] = newMessagesForThisConversation.toList()
            }
            stateHolder._historicalConversations.value = updatedHistoricalConversationsList.toList()

            withContext(Dispatchers.IO) {
                persistenceManager.saveChatHistory()
            }

            if (stateHolder._loadedHistoryIndex.value == index) {
                withContext(Dispatchers.Main.immediate) {
                    stateHolder.messages.clear()
                    stateHolder.messages.addAll(
                        newMessagesForThisConversation.toList().map { msg ->
                            msg.copy(contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError)
                        }
                    )
                    stateHolder.messages.forEach { msg ->
                        stateHolder.messageAnimationStates[msg.id] = true
                    }
                }
            }

            withContext(Dispatchers.Main) {
                showSnackbar("对话已重命名为 '$trimmedNewName'")
                dismissRenameDialog()
            }
            Log.i(TAG_APP_VIEW_MODEL, "历史对话索引 $index 已重命名为 '$trimmedNewName'")
        }
    }

    /**
     * ViewModel 清理时的回调。
     */
    override fun onCleared() {
        Log.d(TAG_APP_VIEW_MODEL, "[ID:$instanceId] onCleared 开始")
        dismissEditDialog()
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("ViewModel cleared")

        if (viewModelScope.isActive) {
            val saveJob = viewModelScope.launch(Dispatchers.IO) {
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "[ID:$instanceId] onCleared: 调用 historyManager.saveCurrentChatToHistoryIfNeeded"
                )
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = false)
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "[ID:$instanceId] onCleared: historyManager.saveCurrentChatToHistoryIfNeeded 调用完成"
                )
            }
        } else {
            Log.w(
                TAG_APP_VIEW_MODEL,
                "[ID:$instanceId] onCleared: viewModelScope 已不活动，跳过最后的历史保存。"
            )
        }

        super.onCleared()
        Log.d(TAG_APP_VIEW_MODEL, "[ID:$instanceId] ViewModel onCleared 结束.")
    }

    // ViewModel 内的日志标签常量
    private companion object {
        private const val TAG_APP_VIEW_MODEL = "AppViewModel"
        private const val TAG_APP_VIEW_MODEL_INIT = "AppViewModelInit" // 初始化过程专用标签
    }
}