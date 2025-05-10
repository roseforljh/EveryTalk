package com.example.app1 // 确保包名正确

import android.util.Log
import androidx.compose.material3.DrawerState // 假设这是正确的 DrawerState 导入
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app1.data.local.SharedPreferencesDataSource
import com.example.app1.data.models.ApiConfig
import com.example.app1.ui.screens.ApiHandler
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.example.app1.data.network.ApiClient // 确保导入 ApiClient
import com.example.app1.ui.screens.viewmodel.ConfigManager
import com.example.app1.ui.screens.viewmodel.HistoryManager
import com.example.app1.ui.screens.viewmodel.data.DataPersistenceManager
import com.example.app1.ui.screens.viewmodel.state.ViewModelStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AppViewModel(dataSource: SharedPreferencesDataSource) : ViewModel() {

    private val instanceId = UUID.randomUUID().toString() // ViewModel 实例的唯一ID
    val viewModelInstanceIdForLogging: String get() = instanceId // 公开一个只读属性用于日志记录

    private val stateHolder = ViewModelStateHolder()

    // DataPersistenceManager 依赖 dataSource 和 stateHolder
    private val persistenceManager = DataPersistenceManager(dataSource, stateHolder, viewModelScope)

    // HistoryManager 依赖 stateHolder 和 persistenceManager
    private val historyManager: HistoryManager =
        HistoryManager(stateHolder, persistenceManager, viewModelScope)

    // 将 ApiHandler 和 ConfigManager 声明为 lazy，以便在 init 中控制其预热
    // ApiHandler 依赖 stateHolder 和 historyManager。
    private val apiHandler: ApiHandler by lazy {
        Log.d("AppViewModelLazyInit", "[ID:$instanceId] 正在创建 ApiHandler 实例...")
        ApiHandler(
            stateHolder,
            viewModelScope,
            historyManager
        )
    }

    // ConfigManager 依赖 stateHolder, persistenceManager, apiHandler
    private val configManager: ConfigManager by lazy {
        Log.d("AppViewModelLazyInit", "[ID:$instanceId] 正在创建 ConfigManager 实例...")
        ConfigManager(stateHolder, persistenceManager, apiHandler, viewModelScope)
    }

    // --- UI 相关的 StateFlow 和 SharedFlow ---
    val drawerState: DrawerState = stateHolder.drawerState // 抽屉状态
    val text: StateFlow<String> = stateHolder._text.asStateFlow() // 输入框文本
    val messages = stateHolder.messages // 这是 MutableList, 直接暴露给 Compose (聊天消息列表)
    val historicalConversations: StateFlow<List<List<Message>>> =
        stateHolder._historicalConversations.asStateFlow() // 历史对话列表
    val loadedHistoryIndex: StateFlow<Int?> =
        stateHolder._loadedHistoryIndex.asStateFlow() // 当前加载的历史对话索引
    val apiConfigs: StateFlow<List<ApiConfig>> = stateHolder._apiConfigs.asStateFlow() // API 配置列表
    val selectedApiConfig: StateFlow<ApiConfig?> =
        stateHolder._selectedApiConfig.asStateFlow() // 当前选中的 API 配置
    val showSettingsDialog: StateFlow<Boolean> =
        stateHolder._showSettingsDialog.asStateFlow() // 是否显示设置对话框 (此状态似乎用于旧的对话框逻辑，AppTopBar现在导航到SettingsScreen)
    val isApiCalling: StateFlow<Boolean> = stateHolder._isApiCalling.asStateFlow() // API 是否正在调用
    val currentStreamingAiMessageId: StateFlow<String?> =
        stateHolder._currentStreamingAiMessageId.asStateFlow() // 当前正在流式传输的 AI 消息 ID
    val reasoningCompleteMap = stateHolder.reasoningCompleteMap // AI 推理是否完成的映射
    val expandedReasoningStates = stateHolder.expandedReasoningStates // AI 推理是否展开的映射
    val messageAnimationStates = stateHolder.messageAnimationStates // 消息动画状态的映射
    val snackbarMessage: SharedFlow<String> =
        stateHolder._snackbarMessage.asSharedFlow() // Snackbar 提示消息
    val scrollToBottomEvent: SharedFlow<Unit> =
        stateHolder._scrollToBottomEvent.asSharedFlow() // 滚动到底部事件

    // 编辑消息相关的状态
    private val _showEditDialog = MutableStateFlow(false) // 是否显示编辑对话框
    val showEditDialog: StateFlow<Boolean> = _showEditDialog.asStateFlow()
    private val _editingMessageId = MutableStateFlow<String?>(null) // 当前正在编辑的消息ID
    private val _editDialogInputText = MutableStateFlow("") // 编辑对话框中的输入文本
    val editDialogInputText: StateFlow<String> = _editDialogInputText.asStateFlow()

    // 用户是否向上滚动，使最新消息离开视野
    val userScrolledAway: StateFlow<Boolean> =
        stateHolder._userScrolledAway.asStateFlow()

    // 重命名历史对话相关的状态
    private val _showRenameDialogState = MutableStateFlow(false) // 是否显示重命名对话框
    val showRenameDialogState = _showRenameDialogState.asStateFlow()
    private val _renamingIndexState = MutableStateFlow<Int?>(null) // 正在重命名的历史对话索引
    val renamingIndexState = _renamingIndexState.asStateFlow()
    private val _renameInputText = MutableStateFlow("") // 重命名对话框的输入文本
    val renameInputText: StateFlow<String> = _renameInputText.asStateFlow()

    init {
        Log.d(
            "AppViewModel",
            "[ID:$instanceId] ViewModel 初始化开始, 线程: ${Thread.currentThread().name}"
        )

        // 启动一个后台协程执行预热任务
        viewModelScope.launch(Dispatchers.IO) { // 使用 Dispatchers.IO 进行非阻塞操作
            Log.d("AppViewModelInit", "[ID:$instanceId] 开始预热任务 (IO线程)...")
            try {
                // 1. 预热 ApiClient (Ktor HttpClient 和 Json 解析器)
                Log.d("AppViewModelInit", "[ID:$instanceId] 调用 ApiClient.preWarm()...")
                ApiClient.preWarm()
                Log.d("AppViewModelInit", "[ID:$instanceId] ApiClient 预热完成。")

                // 2. 主动预热 ApiHandler
                //    通过访问 lazy 属性来触发其初始化块。
                Log.d("AppViewModelInit", "[ID:$instanceId] 主动预热 ApiHandler...")
                val handlerInstance = apiHandler // 访问 apiHandler 触发其 lazy 初始化
                Log.d(
                    "AppViewModelInit",
                    "[ID:$instanceId] ApiHandler 实例已创建: $handlerInstance"
                )

                // 3. 主动预热 ConfigManager
                //    ConfigManager 依赖 apiHandler，所以在 apiHandler 之后预热
                Log.d("AppViewModelInit", "[ID:$instanceId] 主动预热 ConfigManager...")
                val configManagerInstance = configManager // 访问 configManager 触发其 lazy 初始化
                Log.d(
                    "AppViewModelInit",
                    "[ID:$instanceId] ConfigManager 实例已创建: $configManagerInstance"
                )

                Log.d("AppViewModelInit", "[ID:$instanceId] 所有主要组件预热完成。")
            } catch (e: Exception) {
                Log.e("AppViewModelInit", "[ID:$instanceId] 预热任务中发生错误", e)
            }
            Log.d("AppViewModelInit", "[ID:$instanceId] 预热任务 (IO线程) 结束。")
        }

        // 加载初始数据 (例如 API 配置和历史记录)
        // persistenceManager.loadInitialData 是异步的，它会在其自己的作用域中处理回调
        // persistenceManager 内部会更新 StateFlow，相关的日志应在 persistenceManager 中
        persistenceManager.loadInitialData { initialConfigPresent, historyPresent ->
            Log.d(
                "AppViewModelInit",
                "[ID:$instanceId] 初始数据加载回调: initialConfigPresent=$initialConfigPresent, historyPresent=$historyPresent"
            )
            // UI相关的更新需要在 Main 线程执行
            viewModelScope.launch(Dispatchers.Main) {
                if (!initialConfigPresent && stateHolder._apiConfigs.value.isEmpty()) {
                    // 如果没有初始配置且当前配置列表为空
                    stateHolder._snackbarMessage.tryEmit("请添加 API 配置")
                } else if (stateHolder._selectedApiConfig.value == null && stateHolder._apiConfigs.value.isNotEmpty()) {
                    // 如果没有选中的配置但配置列表不为空，尝试选择第一个
                    stateHolder._apiConfigs.value.firstOrNull()?.let {
                        selectConfig(it) // selectConfig 内部会处理 API 调用取消等
                    } ?: stateHolder._snackbarMessage.tryEmit("请选择一个 API 配置")
                }
            }
        }
        Log.d("AppViewModel", "[ID:$instanceId] ViewModel 初始化逻辑结束。")
    }

    /** 当输入框文本变化时调用 */
    fun onTextChange(newText: String) {
        stateHolder._text.value = newText
    }

    /** 当用户滚动状态变化时调用 */
    fun onUserScrolledAwayChange(scrolledAway: Boolean) {
        if (stateHolder._userScrolledAway.value != scrolledAway) {
            stateHolder._userScrolledAway.value = scrolledAway
            Log.d("AppViewModel", "[ID:$instanceId] 用户滚动状态已更改为: $scrolledAway")
        }
    }

    /**
     * 发送用户消息或重新生成AI回复。
     * @param messageText 要发送的文本内容。对于新消息，这是用户在输入框中输入的文本。
     *                    对于重新生成，这是原始用户消息的文本。
     * @param isFromRegeneration 标记此调用是否来自“重新生成回复”操作。
     */
    fun onSendMessage(messageText: String, isFromRegeneration: Boolean = false) {
        val textToActuallySend = messageText.trim() // 去除首尾空格

        // 检查消息是否为空
        if (textToActuallySend.isEmpty()) {
            if (!isFromRegeneration) {
                viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("请输入消息内容") }
            } else {
                // 如果是重新生成，但原始用户消息文本为空（理论上不应发生），则记录警告
                Log.w("AppViewModel", "[ID:$instanceId] 重新生成的消息文本为空，不发送。")
            }
            return
        }
        // 检查是否已选择API配置
        if (stateHolder._selectedApiConfig.value == null) {
            viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("请先选择 API 配置") }
            return
        }

        // 在Main(immediate)协程中执行UI更新和API调用发起
        viewModelScope.launch(Dispatchers.Main.immediate) {
            // 创建新的用户消息对象
            val newUserMessage = Message(
                id = UUID.randomUUID().toString(),
                text = textToActuallySend,
                sender = Sender.User,
                timestamp = System.currentTimeMillis(),
                contentStarted = true // 用户消息内容总是“已开始”
            )
            // 将新用户消息添加到消息列表的开头 (在UI上显示在最底部)
            stateHolder.messages.add(0, newUserMessage)
            Log.d(
                "AppViewModel",
                "[ID:$instanceId] 用户消息 (ID: ${newUserMessage.id}, Text: '${
                    textToActuallySend.take(
                        30
                    )
                }', FromRegen: $isFromRegeneration) 已添加到索引 0。"
            )

            // 如果不是从重新生成调用的，并且ViewModel中的_text状态不为空，则清空它。
            // ChatScreen的逻辑是：如果键盘可见，点击发送时会先调用onTextChange("")清空UI输入框，
            // 然后在键盘隐藏后调用此onSendMessage。
            // 这里的清空是为了确保_text状态与UI最终一致。
            if (!isFromRegeneration && stateHolder._text.value.isNotEmpty()) {
                Log.d(
                    "AppViewModel",
                    "[ID:$instanceId] 清空 ViewModel 中的 _text。之前的值: '${stateHolder._text.value}'"
                )
                stateHolder._text.value = "" // 清空 ViewModel 中的文本状态
            }


            // 触发滚动到底部
            stateHolder._userScrolledAway.value = false // 重置用户滚动状态
            triggerScrollToBottom()

            // 调用ApiHandler处理流式聊天响应
            // apiHandler 属性在这里被访问，如果之前没有在 init 中预热，会在这里触发 lazy 初始化
            apiHandler.streamChatResponse(
                userMessageTextForContext = textToActuallySend, // 将实际发送的文本作为上下文
                onMessagesProcessed = { // AI消息占位符已添加到UI后的回调
                    if (!stateHolder._userScrolledAway.value) { // 如果用户没有向上滚动
                        triggerScrollToBottom() // 再次确保滚动到底部
                    }
                }
            )
        }
    }

    // --- 编辑消息相关方法 ---
    fun onEditDialogTextChanged(newText: String) {
        _editDialogInputText.value = newText
    }

    fun requestEditMessage(message: Message) {
        if (message.sender == Sender.User) {
            _editingMessageId.value = message.id
            _editDialogInputText.value = message.text
            _showEditDialog.value = true
        } else {
            viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("只能编辑您发送的消息") }
        }
    }

    fun confirmMessageEdit() {
        val messageIdToEdit = _editingMessageId.value ?: return
        val updatedText = _editDialogInputText.value.trim()
        if (updatedText.isBlank()) {
            viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("消息内容不能为空") }
            return
        }
        val messageIndex = stateHolder.messages.indexOfFirst { it.id == messageIdToEdit }
        if (messageIndex != -1) {
            val originalMessage = stateHolder.messages[messageIndex]
            if (originalMessage.text != updatedText) { // 仅当文本实际更改时才更新
                stateHolder.messages[messageIndex] = originalMessage.copy(
                    text = updatedText,
                    timestamp = System.currentTimeMillis() // 更新时间戳以反映编辑时间
                )
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true) // 强制保存历史记录
            }
        }
        dismissEditDialog()
    }

    fun dismissEditDialog() {
        _showEditDialog.value = false
        _editingMessageId.value = null
        _editDialogInputText.value = ""
    }

    // --- 重新生成AI回复 ---
    fun regenerateAiResponse(originalUserMessage: Message) {
        if (originalUserMessage.sender != Sender.User) {
            viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("只能为您的消息重新生成回答") }
            return
        }
        if (stateHolder._selectedApiConfig.value == null) {
            viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("请先选择 API 配置") }
            return
        }

        val originalUserMessageText = originalUserMessage.text
        Log.d(
            "AppViewModel",
            "[ID:$instanceId] 为用户消息重新生成回复: '${originalUserMessageText.take(30)}' (ID: ${originalUserMessage.id})"
        )

        viewModelScope.launch(Dispatchers.Main.immediate) {
            // 找到原始用户消息在当前列表中的索引
            val userMessageInitialIndex =
                stateHolder.messages.indexOfFirst { it.id == originalUserMessage.id }

            if (userMessageInitialIndex == -1) {
                Log.e(
                    "AppViewModel",
                    "[ID:$instanceId] 重新生成失败: 原始用户消息 (ID: ${originalUserMessage.id}) 未找到。"
                )
                stateHolder._snackbarMessage.tryEmit("无法重新生成：原始消息未找到。")
                return@launch
            }
            Log.d(
                "AppViewModel",
                "[ID:$instanceId] 原始用户消息 (ID: ${originalUserMessage.id}) 位于索引: $userMessageInitialIndex。"
            )

            var messagesRemovedCount = 0
            // 1. 移除原始用户消息本身
            stateHolder.messages.removeAt(userMessageInitialIndex)
            messagesRemovedCount++
            Log.d(
                "AppViewModel",
                "[ID:$instanceId] 已移除原始用户消息 (ID: ${originalUserMessage.id})，位于原索引 $userMessageInitialIndex。"
            )

            // 2. 移除该用户消息之后（即索引更小，在UI上更靠上）的所有AI消息
            var indexToCheckForAi = userMessageInitialIndex - 1
            while (indexToCheckForAi >= 0) {
                if (indexToCheckForAi < stateHolder.messages.size) {
                    val messageAtCurrentIndex = stateHolder.messages[indexToCheckForAi]
                    if (messageAtCurrentIndex.sender == Sender.AI) {
                        Log.d(
                            "AppViewModel",
                            "[ID:$instanceId] 移除后续的AI消息 (ID: ${messageAtCurrentIndex.id})，位于当前数据索引 $indexToCheckForAi。"
                        )
                        stateHolder.messages.removeAt(indexToCheckForAi)
                        messagesRemovedCount++
                        indexToCheckForAi-- // 继续检查前一个索引（因为移除了一个，所以索引不变，但内容是新的前一个）
                    } else {
                        Log.d(
                            "AppViewModel",
                            "[ID:$instanceId] 在数据索引 $indexToCheckForAi 停止AI消息移除，遇到 ${messageAtCurrentIndex.sender}。"
                        )
                        break // 遇到非AI消息，停止
                    }
                } else {
                    // 索引超出列表范围，通常不应该发生，但作为安全检查
                    Log.d(
                        "AppViewModel",
                        "[ID:$instanceId] 停止AI消息移除，因为索引 $indexToCheckForAi 超出当前列表大小 ${stateHolder.messages.size}。"
                    )
                    break
                }
            }

            Log.d(
                "AppViewModel",
                "[ID:$instanceId] 总共移除了 $messagesRemovedCount 条消息 (原始用户消息 + 后续AI消息)。"
            )

            // 如果有消息被移除，则保存历史记录
            if (messagesRemovedCount > 0) {
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
            }

            // 使用原始文本重新发送消息以进行回复生成
            Log.d(
                "AppViewModel",
                "[ID:$instanceId] 使用原始文本重新发送消息以进行回复生成: '${
                    originalUserMessageText.take(30)
                }'"
            )
            onSendMessage(messageText = originalUserMessageText, isFromRegeneration = true)
        }
    }

    // --- UI 控制方法 ---
    fun triggerScrollToBottom() {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            stateHolder._scrollToBottomEvent.tryEmit(Unit)
            Log.d("AppViewModel", "[ID:$instanceId] 已触发滚动到底部事件 (索引 0)。")
        }
    }

    fun onCancelAPICall() {
        apiHandler.cancelCurrentApiJob("用户取消操作")
    }

    // --- 聊天管理方法 ---
    fun startNewChat() {
        Log.d("AppViewModel", "[ID:$instanceId] 开始新聊天...")
        dismissEditDialog() // 关闭可能打开的编辑对话框
        apiHandler.cancelCurrentApiJob("开始新聊天") // 取消任何正在进行的API调用
        historyManager.saveCurrentChatToHistoryIfNeeded() // 保存当前聊天（如果需要）
        stateHolder.clearForNewChat() // 清理 ViewModelStateHolder 中的聊天状态
        triggerScrollToBottom() // 滚动到新聊天的底部
    }

    fun loadConversationFromHistory(index: Int) {
        Log.d("AppViewModel", "[ID:$instanceId] 从历史记录加载对话，索引: $index")
        dismissEditDialog() // 关闭可能打开的编辑对话框
        stateHolder._historicalConversations.value.getOrNull(index)?.let { conversationToLoad ->
            apiHandler.cancelCurrentApiJob("加载历史索引 $index") // 取消当前API调用
            historyManager.saveCurrentChatToHistoryIfNeeded() // 保存当前聊天
            viewModelScope.launch(Dispatchers.Main.immediate) {
                stateHolder.clearForNewChat() // 清理当前聊天状态
                // 加载历史消息，并确保 contentStarted 状态正确
                stateHolder.messages.addAll(
                    conversationToLoad.map { msg ->
                        msg.copy(contentStarted = msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())
                    }
                )
                // 为已加载的消息设置动画状态为已播放
                stateHolder.messages.forEach { msg ->
                    if ((msg.sender == Sender.AI || msg.sender == Sender.User) &&
                        (msg.text.isNotBlank() || !msg.reasoning.isNullOrBlank())
                    ) {
                        stateHolder.messageAnimationStates[msg.id] = true
                    }
                }
                stateHolder._loadedHistoryIndex.value = index // 更新当前加载的历史索引
                triggerScrollToBottom() // 滚动到加载的聊天的底部
                Log.d(
                    "AppViewModel",
                    "[ID:$instanceId] 对话 $index 已加载。消息数量: ${stateHolder.messages.size}"
                )
            }
        } ?: viewModelScope.launch { stateHolder._snackbarMessage.tryEmit("无法加载对话") }
    }

    fun deleteConversation(indexToDelete: Int) {
        Log.d("AppViewModel", "[ID:$instanceId] 删除历史对话，索引: $indexToDelete")
        val currentLoadedIndex = stateHolder._loadedHistoryIndex.value
        historyManager.deleteConversation(indexToDelete) // 调用 HistoryManager 删除
        // 如果删除的是当前正在查看的对话，则清空当前聊天界面
        if (currentLoadedIndex == indexToDelete) {
            dismissEditDialog()
            stateHolder._loadedHistoryIndex.value = null // 重置加载索引
            stateHolder.clearForNewChat() // 清理聊天状态
            triggerScrollToBottom() // 滚动到底部
        }
    }

    fun clearAllConversations() {
        Log.d("AppViewModel", "[ID:$instanceId] 清除所有历史对话...")
        viewModelScope.launch {
            dismissEditDialog()
            historyManager.clearAllHistory() // 调用 HistoryManager 清除所有历史
            // 如果当前正在查看某个历史对话，则清空当前聊天界面
            if (stateHolder._loadedHistoryIndex.value != null) {
                withContext(Dispatchers.Main.immediate) {
                    stateHolder.clearForNewChat()
                    stateHolder._loadedHistoryIndex.value = null
                    triggerScrollToBottom()
                }
            }
            Log.d("AppViewModel", "[ID:$instanceId] 所有历史对话已清除。")
        }
    }

    // --- 设置界面和API配置管理 ---
    // 注意: 此处的 showSettingsDialog/dismissSettingsScreenIntent 似乎是旧的对话框逻辑。
    // ChatScreen 的 AppTopBar 现在通过 navController.navigate(Screen.SETTINGS_SCREEN) 导航到设置屏幕。
    // 如果 SettingsScreen 是一个独立的 Composable 屏幕，这些方法可能不再需要。
    fun showSettingsScreen() { // 这个方法可能不再被直接使用，如果导航到的是一个完整的 SettingsScreen Composable
        Log.d("AppViewModel", "[ID:$instanceId] 请求显示设置屏幕 (旧逻辑?)")
        stateHolder._showSettingsDialog.value = true
    }

    fun dismissSettingsScreenIntent() { // 这个方法可能不再被直接使用
        Log.d("AppViewModel", "[ID:$instanceId] 请求关闭设置屏幕 (旧逻辑?)")
        stateHolder._showSettingsDialog.value = false
    }

    fun addConfig(configToAdd: ApiConfig) {
        configManager.addConfig(configToAdd)
    }

    fun updateConfig(configToUpdate: ApiConfig) {
        configManager.updateConfig(configToUpdate)
    }

    fun deleteConfig(configToDelete: ApiConfig) {
        configManager.deleteConfig(configToDelete)
    }

    fun clearAllConfigs() {
        configManager.clearAllConfigs()
    }

    fun selectConfig(config: ApiConfig) {
        configManager.selectConfig(config)
    }

    // --- AI消息的推理部分UI控制 ---
    fun collapseReasoning(messageId: String) {
        if (stateHolder.expandedReasoningStates.containsKey(messageId)) {
            stateHolder.expandedReasoningStates[messageId] = false
        }
    }

    fun onToggleReasoningExpand(messageId: String) {
        stateHolder.expandedReasoningStates[messageId] =
            !(stateHolder.expandedReasoningStates[messageId] ?: false)
    }

    // --- 消息动画控制 ---
    fun onAnimationComplete(messageId: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (stateHolder.messageAnimationStates[messageId] != true) {
                stateHolder.messageAnimationStates[messageId] = true
            }
        }
    }

    fun hasAnimationBeenPlayed(messageId: String): Boolean =
        stateHolder.messageAnimationStates[messageId] ?: false


    // --- 历史对话重命名相关 ---
    fun getConversationPreviewText(index: Int): String {
        val conversation = stateHolder._historicalConversations.value.getOrNull(index)
        val firstMsgText = conversation?.firstOrNull()?.text?.trim()
        return if (!firstMsgText.isNullOrBlank()) {
            firstMsgText
        } else {
            "对话 ${index + 1}" // 默认预览文本
        }
    }

    fun onRenameInputTextChange(newName: String) {
        _renameInputText.value = newName
    }

    fun showRenameDialog(index: Int) {
        if (index >= 0 && index < stateHolder._historicalConversations.value.size) {
            _renamingIndexState.value = index
            // 设置输入框的初始值为当前预览文本，除非它是默认的 "对话 X"
            _renameInputText.value =
                getConversationPreviewText(index).takeIf { it != "对话 ${index + 1}" } ?: ""
            _showRenameDialogState.value = true
        } else {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("无法重命名：无效的对话索引") }
        }
    }

    fun dismissRenameDialog() {
        _showRenameDialogState.value = false
        _renamingIndexState.value = null
        _renameInputText.value = ""
    }

    fun renameConversation(index: Int, newName: String) {
        val trimmedNewName = newName.trim()
        if (trimmedNewName.isBlank()) {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("新名称不能为空") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) { // IO操作放到IO线程
            val currentHistory = stateHolder._historicalConversations.value.toMutableList()
            if (index >= 0 && index < currentHistory.size) {
                var conversationToModify = currentHistory[index].toMutableList()

                // 如果对话为空，则添加一条包含新名称的用户消息作为第一条消息
                if (conversationToModify.isEmpty()) {
                    conversationToModify.add(
                        Message(
                            id = UUID.randomUUID().toString(),
                            text = trimmedNewName,
                            sender = Sender.User, // 将重命名视为用户操作，或定义一个SystemSender
                            timestamp = System.currentTimeMillis(),
                            contentStarted = true
                        )
                    )
                } else {
                    // 修改第一条消息的文本为新名称
                    // 如果第一条消息不是用户消息，或者文本已经是新名称，则考虑是否替换或创建新的"标题"消息
                    val originalFirstMessage = conversationToModify[0]
                    if (originalFirstMessage.text != trimmedNewName || originalFirstMessage.sender != Sender.User) {
                        // 如果第一条消息不是用户发的，或者文本不同，则将其改为用户发送的新名称消息
                        // 这确保了重命名的对话有一个明确的、用户定义的“标题”
                        // 如果希望保留原始发送者但只改文本，则调整此逻辑
                        conversationToModify[0] = originalFirstMessage.copy(
                            text = trimmedNewName,
                            sender = Sender.User, // 强制设为User，或者你可以选择其他逻辑
                            timestamp = System.currentTimeMillis() // 更新时间戳
                        )
                    } else if (originalFirstMessage.text != trimmedNewName) {
                        // 如果第一条是用户消息但文本不同，则只更新文本和时间戳
                        conversationToModify[0] = originalFirstMessage.copy(
                            text = trimmedNewName,
                            timestamp = System.currentTimeMillis()
                        )
                    }
                    // 如果文本和发送者都相同，则不作更改（除非需要更新时间戳）
                }
                currentHistory[index] = conversationToModify.toList()

                withContext(Dispatchers.Main.immediate) { // 更新UI相关的StateFlow
                    stateHolder._historicalConversations.value = currentHistory.toList()
                    stateHolder._snackbarMessage.tryEmit("对话已重命名为 '$trimmedNewName'")
                }
                persistenceManager.saveChatHistory() // 保存到持久化存储
            } else {
                withContext(Dispatchers.Main) {
                    stateHolder._snackbarMessage.tryEmit("无法重命名：对话索引错误")
                }
            }
            withContext(Dispatchers.Main) { // 确保在主线程关闭对话框
                dismissRenameDialog()
            }
        }
    }

    // --- ViewModel 清理 ---
    override fun onCleared() {
        Log.d("AppViewModel", "[ID:$instanceId] ViewModel onCleared 已调用。")
        dismissEditDialog() // 关闭可能打开的编辑对话框
        apiHandler.cancelCurrentApiJob("ViewModel cleared") // 取消API调用
        historyManager.saveCurrentChatToHistoryIfNeeded() // 保存当前聊天记录
        super.onCleared()
    }
}