package com.example.everytalk.StateControler // 请替换为您的实际包名

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

            // (根据您的业务逻辑，以下关于 LastOpenChat 的日志和处理可能需要调整)
            // 这部分逻辑似乎是用于恢复上次打开的聊天，但注释表明可能与“总是新会话”的预期不符
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
                    // 如果有配置但没有选中的，则自动选择一个有效配置或第一个配置
                    val configToSelect =
                        stateHolder._apiConfigs.value.firstOrNull { it.isValid } // 优先选择标记为有效的
                            ?: stateHolder._apiConfigs.value.firstOrNull() // 否则选择第一个
                    configToSelect?.let { selectConfig(it) }
                }
                Log.d(TAG_APP_VIEW_MODEL_INIT, "[ID:$instanceId] 初始化UI相关逻辑完成。")
            }
        }
        Log.d(TAG_APP_VIEW_MODEL, "[ID:$instanceId] ViewModel 初始化逻辑结束.")
    }

    /**
     * 比较两个消息列表是否在内容上有效相等（用于历史记录匹配等）。
     * @param list1 第一个消息列表。
     * @param list2 第二个消息列表。
     * @return 如果两个列表有效相等则返回 true，否则 false。
     */
    private fun areMessageListsEffectivelyEqual(
        list1: List<Message>?,
        list2: List<Message>?
    ): Boolean {
        if (list1 == null && list2 == null) return true // 两者都为空，相等
        if (list1 == null || list2 == null) return false // 其中一个为空，不等
        val filteredList1 = filterMessagesForComparison(list1) // 过滤后比较
        val filteredList2 = filterMessagesForComparison(list2)
        if (filteredList1.size != filteredList2.size) return false // 大小不同，不等
        for (i in filteredList1.indices) {
            val msg1 = filteredList1[i]
            val msg2 = filteredList2[i]
            // 比较关键字段
            if (msg1.id != msg2.id || // ID不同（如果ID是持久化的，则应比较；如果是临时的，则不应比较）
                msg1.sender != msg2.sender ||
                msg1.text.trim() != msg2.text.trim() ||
                msg1.reasoning?.trim() != msg2.reasoning?.trim() ||
                msg1.isError != msg2.isError
            ) return false
        }
        return true // 所有比较项都相等
    }

    /**
     * 过滤消息列表，仅保留用于比较的有效消息。
     * @param messagesToFilter 待过滤的消息列表。
     * @return 过滤后的消息列表。
     */
    private fun filterMessagesForComparison(messagesToFilter: List<Message>): List<Message> {
        return messagesToFilter.filter { msg ->
            // 排除非占位符类型的系统消息
            (msg.sender != Sender.System || msg.isPlaceholderName) &&
                    // 用户消息，或有实际内容的AI消息（文本或思考过程），或作为占位符的系统消息，或错误消息
                    (msg.sender == Sender.User ||
                            (msg.sender == Sender.AI && (msg.contentStarted || msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())) ||
                            (msg.sender == Sender.System && msg.isPlaceholderName) ||
                            msg.isError)
        }.toList()
    }

    /**
     * 切换联网搜索模式。
     * @param enabled 是否启用联网搜索。
     */
    fun toggleWebSearchMode(enabled: Boolean) {
        stateHolder._isWebSearchEnabled.value = enabled
        showSnackbar("联网搜索已 ${if (enabled) "开启" else "关闭"}")
        Log.d(TAG_APP_VIEW_MODEL, "联网搜索模式切换为: $enabled")
    }

    /**
     * 添加用户自定义的模型提供商名称。
     * @param providerName 要添加的提供商名称。
     */
    fun addProvider(providerName: String) {
        val trimmedName = providerName.trim()
        if (trimmedName.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) { // IO操作
                val currentCustomProviders = _customProviders.value.toMutableSet()
                val predefinedForCheck = listOf( // 预设平台名称，用于检查重复（不区分大小写）
                    "openai compatible", "google", "硅基流动", "阿里云百炼", "火山引擎", "深度求索"
                ).map { it.lowercase() }

                if (predefinedForCheck.contains(trimmedName.lowercase())) { // 不能添加预设名称
                    withContext(Dispatchers.Main) { showSnackbar("平台名称 '$trimmedName' 是预设名称，无法添加。") }
                    return@launch
                }
                if (currentCustomProviders.any {
                        it.equals(
                            trimmedName,
                            ignoreCase = true
                        )
                    }) { // 不能添加已存在的自定义名称
                    withContext(Dispatchers.Main) { showSnackbar("模型平台 '$trimmedName' 已存在") }
                    return@launch
                }

                currentCustomProviders.add(trimmedName) // 添加到集合
                _customProviders.value = currentCustomProviders.toSet() // 更新StateFlow
                dataSource.saveCustomProviders(currentCustomProviders.toSet()) // 持久化保存
                withContext(Dispatchers.Main) { showSnackbar("模型平台 '$trimmedName' 已添加") }
                Log.i(TAG_APP_VIEW_MODEL, "添加了新的自定义提供商: $trimmedName")
            }
        } else {
            showSnackbar("平台名称不能为空")
        }
    }

    /**
     * 显示 Snackbar 提示消息。
     * @param message 要显示的消息文本。
     */
    fun showSnackbar(message: String) {
        stateHolder._snackbarMessage.tryEmit(message) // 尝试发送消息到SharedFlow
    }

    /**
     * 设置抽屉内搜索功能的激活状态。
     * @param isActive 是否激活搜索。
     */
    fun setSearchActiveInDrawer(isActive: Boolean) {
        _isSearchActiveInDrawer.value = isActive
        if (!isActive) _searchQueryInDrawer.value = "" // 如果关闭搜索，清空搜索查询
        Log.d(TAG_APP_VIEW_MODEL, "抽屉内搜索状态设置为: $isActive")
    }

    /**
     * 当抽屉内搜索查询文本变化时的回调。
     * @param query 新的搜索查询文本。
     */
    fun onDrawerSearchQueryChange(query: String) {
        _searchQueryInDrawer.value = query
    }

    /**
     * 当用户输入框文本变化时的回调。
     * @param newText 新的输入文本。
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

        viewModelScope.launch(Dispatchers.Main.immediate) { // 立即在主线程更新UI相关的部分
            // 添加用户消息到UI列表
            val newUserMessage =
                Message(text = textToActuallySend, sender = Sender.User, contentStarted = true)
            stateHolder.messages.add(newUserMessage)
            Log.d(
                TAG_APP_VIEW_MODEL,
                "onSendMessage: 用户消息已添加 (ID: ${newUserMessage.id.take(8)}), isFromRegeneration: $isFromRegeneration"
            )

            if (!isFromRegeneration) { // 只有非重新生成操作才清空输入框
                stateHolder._text.value = ""
            }
            triggerScrollToBottom() // 确保新消息可见

            // 构建发送给API的历史消息列表
            val apiHistoryMessages = mutableListOf<DataClassApiMessage>()
            val messagesSnapshotForHistory = stateHolder.messages.toList() // 使用当前最新的消息列表快照
            var historyMessageCount = 0
            val maxHistoryMessages = 20 // 例如，最多包含20条历史消息

            for (msg in messagesSnapshotForHistory.asReversed()) { // 从后往前遍历
                if (historyMessageCount >= maxHistoryMessages) break
                val apiMsgToAdd: DataClassApiMessage? = when {
                    msg.sender == Sender.User && msg.text.isNotBlank() ->
                        DataClassApiMessage(role = "user", content = msg.text.trim())
                    // 对于AI消息，只有当它不是错误，并且有实际内容（文本或思考过程已开始）时，才加入历史
                    msg.sender == Sender.AI && !msg.isError && (msg.contentStarted || msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank()) -> {
                        // 优先使用最终文本 `msg.text`，如果它不为空。
                        // 如果 `msg.text` 为空但 `msg.reasoning` 非空（例如，只有思考过程后中断），则可以使用 `msg.reasoning`。
                        // 后端期望的是一个连贯的对话历史。
                        val contentForApi =
                            if (msg.text.isNotBlank()) msg.text.trim() else msg.reasoning?.trim()
                                ?: ""
                        if (contentForApi.isNotBlank()) DataClassApiMessage(
                            role = "assistant",
                            content = contentForApi
                        ) else null
                    }
                    // 其他类型的消息（如未开始的AI消息、非占位符的系统消息）不加入API历史
                    else -> null
                }
                apiMsgToAdd?.let {
                    apiHistoryMessages.add(0, it) // 加到头部，保持顺序
                    historyMessageCount++
                }
            }
            // (防御性检查 apiHistoryMessages 的逻辑保持不变)
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


            val requestBody = ChatRequest(
                messages = apiHistoryMessages,
                provider = if (currentConfig.provider.equals(
                        "google",
                        ignoreCase = true
                    )
                ) "google" else "openai", // 简单映射
                apiAddress = currentConfig.address,
                apiKey = currentConfig.key,
                model = currentConfig.model,
                useWebSearch = if (currentWebSearchEnabled) true else null,
                // forceGoogleReasoningPrompt 设为 null，让后端根据模型名称等因素自行决定
            )

            apiHandler.streamChatResponse(
                requestBody = requestBody,
                userMessageTextForContext = textToActuallySend, // 用于日志
                afterUserMessageId = newUserMessage.id, // AI消息应在此用户消息之后插入
                onMessagesProcessed = {
                    viewModelScope.launch { historyManager.saveCurrentChatToHistoryIfNeeded() }
                }
            )
        }
    }

    /**
     * 当编辑消息对话框中的文本变化时的回调。
     * @param newText 新的编辑后文本。
     */
    fun onEditDialogTextChanged(newText: String) {
        stateHolder._editDialogInputText.value = newText
    }

    /**
     * 请求编辑指定消息。
     * @param message 要编辑的消息对象。
     */
    fun requestEditMessage(message: Message) {
        if (message.sender == Sender.User) { // 只能编辑用户发送的消息
            _editingMessageId.value = message.id
            stateHolder._editDialogInputText.value = message.text // 初始化编辑框文本
            _showEditDialog.value = true // 显示编辑对话框
            Log.d(TAG_APP_VIEW_MODEL, "请求编辑用户消息: ${message.id.take(8)}")
        } else {
            showSnackbar("只能编辑您发送的消息")
        }
    }

    /**
     * 确认消息编辑。
     */
    fun confirmMessageEdit() {
        val messageIdToEdit = _editingMessageId.value ?: return // 获取要编辑的消息ID
        val updatedText = stateHolder._editDialogInputText.value.trim() // 获取更新后的文本并去除空白
        if (updatedText.isBlank()) {
            showSnackbar("消息内容不能为空"); return
        }
        viewModelScope.launch { // 在协程中处理
            val messageIndex = stateHolder.messages.indexOfFirst { it.id == messageIdToEdit }
            if (messageIndex != -1) {
                val originalMessage = stateHolder.messages[messageIndex]
                if (originalMessage.text != updatedText) { // 只有文本实际改变时才更新
                    stateHolder.messages[messageIndex] = originalMessage.copy(
                        text = updatedText,
                        timestamp = System.currentTimeMillis() // 更新时间戳
                    )
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true) // 强制保存历史
                    showSnackbar("消息已更新")
                    Log.i(TAG_APP_VIEW_MODEL, "用户消息 ${messageIdToEdit.take(8)} 已编辑并更新。")
                }
            }
            withContext(Dispatchers.Main.immediate) { dismissEditDialog() } // 关闭对话框
        }
    }

    /**
     * 关闭编辑消息对话框。
     */
    fun dismissEditDialog() {
        _showEditDialog.value = false
        _editingMessageId.value = null
        stateHolder._editDialogInputText.value = "" // 清空编辑框文本
    }

    /**
     * 为指定的用户消息重新生成AI回答。
     * @param originalUserMessage 需要重新生成回答的用户消息对象。
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

            // 移除此用户消息之后紧邻的AI回答（如果存在）
            val nextMessageIndex = userMessageIndex + 1
            if (nextMessageIndex < stateHolder.messages.size && stateHolder.messages[nextMessageIndex].sender == Sender.AI) {
                val aiMessageToRemove = stateHolder.messages[nextMessageIndex]
                if (stateHolder._currentStreamingAiMessageId.value == aiMessageToRemove.id) { // 如果正在流式输出，则取消
                    apiHandler.cancelCurrentApiJob(
                        "为消息重新生成回答，取消旧AI流",
                        isNewMessageSend = true
                    )
                }
                stateHolder.messages.removeAt(nextMessageIndex) // 从列表中删除该AI消息
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "Regenerate: 已移除旧AI回答 (ID: ${aiMessageToRemove.id.take(8)})。"
                )
            }

            // 移除原始的用户消息本身
            stateHolder.messages.removeAt(userMessageIndex)
            Log.d(
                TAG_APP_VIEW_MODEL,
                "Regenerate: 已移除原始用户消息 (ID: ${originalUserMessageId.take(8)})。"
            )

            // 保存移除旧消息后的聊天状态到历史
            historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
            Log.d(TAG_APP_VIEW_MODEL, "Regenerate: 已强制保存移除旧消息后的聊天状态到历史。")

            // 使用原始用户消息的文本重新发送消息
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
        dismissEditDialog() // 关闭可能打开的对话框
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("开始新聊天") // 取消当前API调用
        viewModelScope.launch {
            historyManager.saveCurrentChatToHistoryIfNeeded() // 保存当前聊天（如果需要）
            withContext(Dispatchers.Main.immediate) { // 立即在主线程更新UI状态
                stateHolder.clearForNewChat() // 清理状态以准备新聊天
                triggerScrollToBottom() // 滚动到底部
                if (_isSearchActiveInDrawer.value) setSearchActiveInDrawer(false) // 关闭抽屉搜索
                stateHolder._loadedHistoryIndex.value = null // 清除加载的历史索引
            }
            Log.i(TAG_APP_VIEW_MODEL, "已开始新聊天。")
        }
    }

    /**
     * 从历史记录中加载指定的对话。
     * @param index 要加载的对话在历史列表中的索引。
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
            historyManager.saveCurrentChatToHistoryIfNeeded() // 保存当前聊天（如果需要）
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat() // 清理当前聊天状态
                // 确保加载的消息 contentStarted 状态正确
                stateHolder.messages.addAll(conversationToLoad.map { msg ->
                    msg.copy(contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank() || msg.isError)
                })
                stateHolder.messages.forEach { msg -> // 标记所有加载的消息动画已播放
                    stateHolder.messageAnimationStates[msg.id] = true
                }
                stateHolder._loadedHistoryIndex.value = index // 更新当前加载的历史索引
                triggerScrollToBottom() // 滚动到底部
            }
            if (_isSearchActiveInDrawer.value) { // 如果抽屉搜索是激活的，关闭它
                withContext(Dispatchers.Main.immediate) { setSearchActiveInDrawer(false) }
            }
            Log.i(TAG_APP_VIEW_MODEL, "已从历史加载对话，索引: $index")
        }
    }

    /**
     * 删除指定索引的历史对话。
     * @param indexToDelete 要删除的对话索引。
     */
    fun deleteConversation(indexToDelete: Int) {
        val currentLoadedIndex = stateHolder._loadedHistoryIndex.value
        if (indexToDelete < 0 || indexToDelete >= stateHolder._historicalConversations.value.size) {
            showSnackbar("无法删除：无效的索引"); return
        }
        viewModelScope.launch {
            val wasCurrentChatDeleted = (currentLoadedIndex == indexToDelete) // 检查删除的是否是当前打开的对话
            historyManager.deleteConversation(indexToDelete) // 调用HistoryManager删除
            if (wasCurrentChatDeleted) { // 如果删除的是当前聊天，则开始一个新聊天
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
            historyManager.clearAllHistory() // 调用HistoryManager清除所有历史
            withContext(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat() // 开始一个新聊天
                triggerScrollToBottom()
            }
            withContext(Dispatchers.Main) { showSnackbar("所有对话已清除") }
            Log.i(TAG_APP_VIEW_MODEL, "已清除所有历史对话。")
        }
    }

    /**
     * 显示包含Web搜索结果来源的对话框。
     * @param sources Web搜索结果列表。
     */
    fun showSourcesDialog(sources: List<WebSearchResult>) {
        viewModelScope.launch { // 使用协程以防将来有异步操作
            stateHolder._sourcesForDialog.value = sources // 设置要显示的结果
            stateHolder._showSourcesDialog.value = true // 显示对话框
            Log.d(TAG_APP_VIEW_MODEL, "请求显示来源对话框。来源数量: ${sources.size}")
        }
    }

    /**
     * 关闭Web搜索结果来源对话框。
     */
    fun dismissSourcesDialog() {
        viewModelScope.launch {
            if (stateHolder._showSourcesDialog.value) { // 仅当对话框已显示时才操作
                stateHolder._showSourcesDialog.value = false // 关闭对话框
                // stateHolder._sourcesForDialog.value = emptyList() // 可选：关闭时清空来源数据
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
     * @param messageId 完成动画的消息ID。
     */
    fun onAnimationComplete(messageId: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) { // 立即在主线程更新
            stateHolder.messageAnimationStates[messageId] = true // 标记动画已播放
        }
    }

    /**
     * 检查指定消息的动画是否已经播放过。
     * @param messageId 要检查的消息ID。
     * @return 如果动画已播放则返回 true，否则 false。
     */
    fun hasAnimationBeenPlayed(messageId: String): Boolean =
        stateHolder.messageAnimationStates[messageId] ?: false

    /**
     * 获取历史对话的预览文本（用于在抽屉中显示）。
     * @param index 对话在历史列表中的索引。
     * @return 对话的预览文本。
     */
    fun getConversationPreviewText(index: Int): String {
        val conversation = stateHolder._historicalConversations.value.getOrNull(index)
            ?: return "对话 ${index + 1}"
        // 优先使用占位符性质的系统消息作为标题
        val placeholderTitleMsg =
            conversation.firstOrNull { it.sender == Sender.System && it.isPlaceholderName && it.text.isNotBlank() }?.text?.trim()
        if (!placeholderTitleMsg.isNullOrBlank()) return placeholderTitleMsg
        // 其次使用第一条用户消息
        val firstUserMsg =
            conversation.firstOrNull { it.sender == Sender.User && it.text.isNotBlank() }?.text?.trim()
        if (!firstUserMsg.isNullOrBlank()) return firstUserMsg
        // 再次使用第一条AI消息
        val firstAiMsg =
            conversation.firstOrNull { it.sender == Sender.AI && it.text.isNotBlank() }?.text?.trim()
        if (!firstAiMsg.isNullOrBlank()) return firstAiMsg
        // 默认标题
        return "对话 ${index + 1}"
    }

    /**
     * 当重命名对话框输入文本变化时的回调。
     * @param newName 新的名称文本。
     */
    fun onRenameInputTextChange(newName: String) {
        stateHolder._renameInputText.value = newName
    }

    /**
     * 显示重命名对话框。
     * @param index 要重命名的对话在历史列表中的索引。
     */
    fun showRenameDialog(index: Int) {
        if (index >= 0 && index < stateHolder._historicalConversations.value.size) {
            _renamingIndexState.value = index
            val currentPreview = getConversationPreviewText(index)
            // 判断当前预览是否是默认生成的 "对话 X" 格式
            val isDefaultPreview = currentPreview.startsWith("对话 ") && runCatching {
                currentPreview.substringAfter("对话 ").toIntOrNull() == index + 1
            }.getOrElse { false }
            // 如果是默认预览，则输入框初始为空；否则为当前预览文本
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
        stateHolder._renameInputText.value = "" // 清空输入文本
    }

    /**
     * 重命名指定索引的历史对话。
     * @param index 要重命名的对话索引。
     * @param newName 新的对话名称。
     */
    fun renameConversation(index: Int, newName: String) {
        val trimmedNewName = newName.trim()
        if (trimmedNewName.isBlank()) {
            showSnackbar("新名称不能为空"); return
        }
        viewModelScope.launch { // 在协程中处理
            val currentHistoricalConvos = stateHolder._historicalConversations.value
            if (index < 0 || index >= currentHistoricalConvos.size) {
                withContext(Dispatchers.Main) { showSnackbar("无法重命名：对话索引错误") }; return@launch
            }

            val originalConversationAtIndex = currentHistoricalConvos[index]
            val newMessagesForThisConversation = mutableListOf<Message>()

            var titleMessageUpdatedOrAdded = false
            // 检查第一条消息是否是占位符系统消息
            if (originalConversationAtIndex.isNotEmpty() &&
                originalConversationAtIndex.first().sender == Sender.System &&
                originalConversationAtIndex.first().isPlaceholderName
            ) {
                // 更新现有的占位符系统消息
                newMessagesForThisConversation.add(
                    originalConversationAtIndex.first()
                        .copy(text = trimmedNewName, timestamp = System.currentTimeMillis())
                )
                newMessagesForThisConversation.addAll(originalConversationAtIndex.drop(1)) // 添加剩余消息
                titleMessageUpdatedOrAdded = true
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "更新了现有标题消息为 '$trimmedNewName' 对于历史索引 $index"
                )
            }

            if (!titleMessageUpdatedOrAdded) { // 如果没有现有的占位符，则添加一个新的
                val titleMessage = Message(
                    id = "title_${UUID.randomUUID()}", // 唯一ID
                    text = trimmedNewName,
                    sender = Sender.System,
                    timestamp = System.currentTimeMillis() - 1, // 确保时间戳比对话中其他消息早一点或一致
                    contentStarted = true, // 标记为内容已开始
                    isPlaceholderName = true // 标记为占位符名称
                )
                newMessagesForThisConversation.add(titleMessage) // 添加到列表头部
                newMessagesForThisConversation.addAll(originalConversationAtIndex) // 添加原始对话消息
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "添加了新的标题消息 '$trimmedNewName' 对于历史索引 $index"
                )
            }

            // 更新历史对话列表
            val updatedHistoricalConversationsList = currentHistoricalConvos.toMutableList().apply {
                this[index] = newMessagesForThisConversation.toList()
            }
            stateHolder._historicalConversations.value = updatedHistoricalConversationsList.toList()

            withContext(Dispatchers.IO) { // 在IO线程保存历史记录
                persistenceManager.saveChatHistory()
            }

            // 如果当前加载的对话就是被重命名的这个，则更新UI上的当前消息列表
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
                    } // 标记动画已播放
                }
            }

            withContext(Dispatchers.Main) { // 在主线程显示提示并关闭对话框
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
        dismissEditDialog() // 关闭可能打开的对话框
        dismissSourcesDialog()
        apiHandler.cancelCurrentApiJob("ViewModel cleared") // 取消任何正在进行的API调用

        if (viewModelScope.isActive) { // 仅当作用域仍活动时尝试
            val saveJob = viewModelScope.launch(Dispatchers.IO) { // 使用IO调度器进行保存
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "[ID:$instanceId] onCleared: 调用 historyManager.saveCurrentChatToHistoryIfNeeded"
                )
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = false) // 非强制保存
                Log.d(
                    TAG_APP_VIEW_MODEL,
                    "[ID:$instanceId] onCleared: historyManager.saveCurrentChatToHistoryIfNeeded 调用完成"
                )
            }
            // (可选) 如果需要确保保存完成，可以尝试 join，但要注意 onCleared 的时间限制
            // runBlocking { saveJob.join() } // 不推荐在 onCleared 中使用 runBlocking
        } else {
            Log.w(
                TAG_APP_VIEW_MODEL,
                "[ID:$instanceId] onCleared: viewModelScope 已不活动，跳过最后的历史保存。"
            )
        }

        super.onCleared() // 调用父类的 onCleared
        Log.d(TAG_APP_VIEW_MODEL, "[ID:$instanceId] ViewModel onCleared 结束.")
    }

    // ViewModel 内的日志标签常量
    private companion object {
        private const val TAG_APP_VIEW_MODEL = "AppViewModel"
        private const val TAG_APP_VIEW_MODEL_INIT = "AppViewModelInit" // 初始化过程专用标签
    }
}