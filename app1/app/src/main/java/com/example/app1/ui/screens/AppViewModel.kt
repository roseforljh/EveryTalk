package com.example.app1.ui.screens // 替换为你的实际包名

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.app1.data.local.SharedPreferencesDataSource // 导入你的 DataSource
import com.example.app1.data.models.* // 导入你的 Models
import com.example.app1.data.network.ApiClient // 导入你的 ApiClient
import com.example.app1.data.network.ApiConfig
import com.example.app1.data.network.ApiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException // <-- Import IOException
import java.util.UUID

// --- 枚举和常量 ---
enum class AppView { CurrentChat, HistoryList }
const val ERROR_VISUAL_PREFIX = "⚠️ [错误] "
const val USER_CANCEL_MESSAGE = "用户手动停止" // 用于 cancelCurrentApiJob 的原因


class AppViewModel(private val dataSource: SharedPreferencesDataSource) : ViewModel() {

    // --- 核心状态变量 ---
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow() // <-- CORRECTED TYPE

    private val _currentView = MutableStateFlow(AppView.CurrentChat)
    val currentView: StateFlow<AppView> = _currentView.asStateFlow()

    private val _historicalConversations = MutableStateFlow<List<List<Message>>>(emptyList())
    val historicalConversations: StateFlow<List<List<Message>>> = _historicalConversations.asStateFlow() // This one is correct

    private val _loadedHistoryIndex = MutableStateFlow<Int?>(null)
    val loadedHistoryIndex: StateFlow<Int?> = _loadedHistoryIndex.asStateFlow()

    private val _apiConfigs = MutableStateFlow<List<ApiConfig>>(emptyList())
    val apiConfigs: StateFlow<List<ApiConfig>> = _apiConfigs.asStateFlow()

    private val _selectedApiConfig = MutableStateFlow<ApiConfig?>(null)
    val selectedApiConfig: StateFlow<ApiConfig?> = _selectedApiConfig.asStateFlow()

    private val _showSettingsDialog = MutableStateFlow(false)
    val showSettingsDialog: StateFlow<Boolean> = _showSettingsDialog.asStateFlow()

    private val _isApiCalling = MutableStateFlow(false)
    val isApiCalling: StateFlow<Boolean> = _isApiCalling.asStateFlow() // <-- ChatScreen 监听这个状态

    // 将 currentStreamingAiMessageId 声明为 MutableStateFlow 以使其响应式
    private val _currentStreamingAiMessageId = MutableStateFlow<String?>(null)
    val currentStreamingAiMessageId: StateFlow<String?> = _currentStreamingAiMessageId.asStateFlow() // 暴露给 UI 作为 StateFlow

    // 使用 SnapshotStateMap 以便 Compose 能直接观察其变化
    val expandedReasoningStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    private var apiJob: Job? = null // 持有当前 API 请求的 Job


    // --- 临时状态 ---
    private var tempMessagesBeforeHistory: List<Message>? = null
    private var tempLoadedIndexBeforeHistory: Int? = null

    // --- UI 事件 ---
    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    private val _userScrolledAway = MutableStateFlow(false)
    // val userScrolledAway: StateFlow<Boolean> = _userScrolledAway.asStateFlow() // ChatScreen 直接回调更新

    // 滚动到底部按钮状态
    val showScrollToBottomButton: StateFlow<Boolean> = combine(
        _userScrolledAway, messages
    ) { scrolledAway, currentMessages ->
        scrolledAway && currentMessages.size > 1 // 至少需要超过1条消息才可能需要滚动按钮
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)


    init {
        loadInitialData()
    }

    // --- 数据加载与保存 ---
    private fun loadInitialData() {
        viewModelScope.launch(Dispatchers.IO) {
            val loadedConfigs = dataSource.loadApiConfigs()
            _apiConfigs.value = loadedConfigs

            val selectedConfigId = dataSource.loadSelectedConfigId()
            val selectedConfig = loadedConfigs.find { it.id == selectedConfigId } ?: loadedConfigs.firstOrNull()
            _selectedApiConfig.value = selectedConfig
            println("ViewModel: Initial API configs loaded (${loadedConfigs.size}). Selected ID: $selectedConfigId")

            val loadedHistory = dataSource.loadChatHistory()
            _historicalConversations.value = loadedHistory
            println("ViewModel: Initial history loaded with ${loadedHistory.size} conversations.")

            if (selectedConfig == null && loadedConfigs.isNotEmpty()) {
                launch(Dispatchers.Main) { _snackbarMessage.emit("请选择API配置") }
            } else if (loadedConfigs.isEmpty()) {
                launch(Dispatchers.Main) { _snackbarMessage.emit("请添加API配置") }
            }
        }
    }

    private fun saveApiConfigs() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentConfigs = _apiConfigs.value
            val currentSelectedId = _selectedApiConfig.value?.id
            println("ViewModel: Saving API configs (${currentConfigs.size}) and selected ID ($currentSelectedId)...")
            dataSource.saveApiConfigs(currentConfigs)
            dataSource.saveSelectedConfigId(currentSelectedId)
        }
    }

    private fun saveChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            // 使用 toList() 创建一个不可变副本以防并发修改问题
            val currentHistory = _historicalConversations.value.toList()
            println("ViewModel: Saving chat history (${currentHistory.size} items)...")
            dataSource.saveChatHistory(currentHistory)
        }
    }

    // --- 事件处理函数 ---

    fun onTextChange(newText: String) {
        _text.value = newText
    }

    fun onUserScrolledAwayChange(scrolledAway: Boolean) {
        if (_userScrolledAway.value != scrolledAway) {
            _userScrolledAway.value = scrolledAway
            println("ViewModel: userScrolledAway updated to $scrolledAway")
        }
    }

    // --- 取消 API Job (重构 - 立即重置 UI 状态 + 立即移除空占位符) ---
    private fun cancelCurrentApiJob(reason: String) {
        val jobToCancel = apiJob // 捕获当前的 Job 引用
        val messageIdToCancel = _currentStreamingAiMessageId.value // 捕获当前的 Message ID
        println("--- [ViewModel.cancelCurrentApiJob] 请求取消。原因: $reason. MsgID: $messageIdToCancel, 当前 Job: $jobToCancel")

        var wasPlaceholderRemoved = false // 标记是否移除了占位符

        // 1. **立即** 尝试移除空占位符消息
        if (messageIdToCancel != null) {
            _messages.update { currentMsgs ->
                val index = currentMsgs.indexOfFirst { it.id == messageIdToCancel }
                if (index != -1) {
                    val msg = currentMsgs[index]
                    // 条件：空文本，未开始内容，无推理，非错误
                    if ((msg.text ?: "").isEmpty() && !msg.contentStarted && msg.reasoning.isNullOrBlank() && !msg.isError) {
                        println("--- [ViewModel.cancelCurrentApiJob] 立即移除空占位符消息 (ID: $messageIdToCancel).")
                        expandedReasoningStates.remove(messageIdToCancel)
                        wasPlaceholderRemoved = true
                        currentMsgs.toMutableList().apply { removeAt(index) } // 返回移除后的新列表
                    } else {
                        // 如果不是纯占位符，则不在此处移除，等待 onCompletion 添加 "(已中断)"
                        println("--- [ViewModel.cancelCurrentApiJob] 消息 (ID: $messageIdToCancel) 不是纯空占位符，不在此处移除。")
                        currentMsgs // 返回原始列表
                    }
                } else {
                    println("--- [ViewModel.cancelCurrentApiJob] 尝试移除消息 (ID: $messageIdToCancel) 时未找到。")
                    currentMsgs // 返回原始列表
                }
            }
        }

        // 2. **立即** 重置 UI 相关的 StateFlows
        if (_isApiCalling.value) {
            println("--- [ViewModel.cancelCurrentApiJob] 立即重置 UI 状态：isApiCalling=false")
            _isApiCalling.value = false // <-- 立即更新 StateFlow
        }
        if (_currentStreamingAiMessageId.value != null) {
            println("--- [ViewModel.cancelCurrentApiJob] 立即清理 _currentStreamingAiMessageId.value: ${_currentStreamingAiMessageId.value}")
            _currentStreamingAiMessageId.value = null // <-- 立即更新 StateFlow
        }

        // 3. 发送取消信号给 Job (如果 Job 仍然是活动状态)
        // **不**在这里将 apiJob 设为 null，交给 onCompletion 处理
        if (jobToCancel != null && jobToCancel.isActive) { // 检查捕获的 Job 是否活跃
            println("--- [ViewModel.cancelCurrentApiJob] 发送取消信号给 Job: $jobToCancel")
            jobToCancel.cancel(CancellationException(reason))
        } else {
            println("--- [ViewModel.cancelCurrentApiJob] Job ($jobToCancel) 不活跃，无需发送取消信号。")
            // 如果 Job 已经无效，但 apiJob 引用还在，在这里清理它，因为 onCompletion 可能不会被调用
            if(apiJob == jobToCancel && jobToCancel != null){
                println("--- [ViewModel.cancelCurrentApiJob] Job 不活跃，清理 apiJob 引用。")
                apiJob = null
            }
        }

        println("--- [ViewModel.cancelCurrentApiJob] 完成。空占位符已尝试移除(${wasPlaceholderRemoved})。最终 apiJob 清理将在 onCompletion 处理。")
    }


    fun navigateToHistory() {
        println("ViewModel: Navigating to history view.")
        // 确保在切换视图前取消任何正在进行的 API 调用。状态清理在 cancelCurrentApiJob + onCompletion
        cancelCurrentApiJob("视图切换到历史记录")

        println("ViewModel: Saving current chat state before navigating to history.")
        tempMessagesBeforeHistory = _messages.value.toList() // 保存副本
        tempLoadedIndexBeforeHistory = _loadedHistoryIndex.value
        println("ViewModel: Saved state: ${tempMessagesBeforeHistory?.size ?: 0} messages, index $tempLoadedIndexBeforeHistory")

        val messagesToSave = _messages.value
            .filter { it.text.isNotBlank() && it.sender != Sender.System } // Allow empty AI messages
            .toList()
        var historyUpdated = false
        if (messagesToSave.isNotEmpty()) {
            _historicalConversations.update { currentHistory ->
                val loadedIndex = _loadedHistoryIndex.value
                val mutableHistory = currentHistory.toMutableList() // 操作副本
                if (loadedIndex != null && loadedIndex >= 0 && loadedIndex < mutableHistory.size) {
                    // 更新现有历史记录项
                    mutableHistory[loadedIndex] = messagesToSave
                    println("ViewModel: Updated history at index $loadedIndex with ${messagesToSave.size} messages.")
                    historyUpdated = true
                } else {
                    // 添加为新的历史记录项（添加到开头）
                    mutableHistory.add(0, messagesToSave)
                    println("ViewModel: Saved new conversation to history (${messagesToSave.size} messages).")
                    historyUpdated = true
                    _loadedHistoryIndex.value = 0 // 新增的项索引为0
                }
                mutableHistory // 返回更新后的列表
            }
            if (historyUpdated) saveChatHistory()
        } else {
            println("ViewModel: Current chat is empty or only placeholder, not saving to history list.")
            // 如果当前聊天为空且是从某个历史加载的，不清空该历史
            if (_loadedHistoryIndex.value != null) {
                println("ViewModel: Current chat is empty, preserving loaded history index ${_loadedHistoryIndex.value}.")
            }
        }

        // 清理当前聊天状态以显示历史列表
        _messages.value = emptyList()
        _text.value = ""
        // _loadedHistoryIndex 在上面处理过了，这里不用动
        expandedReasoningStates.clear()
        _userScrolledAway.value = false
        println("ViewModel: Cleared current chat UI state for history view.")
        _currentView.value = AppView.HistoryList
    }


    fun navigateToChat(fromHistory: Boolean = true) {
        println("ViewModel: Navigating back to chat view...")
        // 确保在切换视图前取消任何正在进行的 API 调用。状态清理在 cancelCurrentApiJob + onCompletion
        cancelCurrentApiJob(if(fromHistory) "返回聊天视图" else "新建对话")

        if (fromHistory) {
            // 尝试恢复之前的状态
            val restoredMessages = tempMessagesBeforeHistory ?: emptyList()
            _messages.value = restoredMessages
            _loadedHistoryIndex.value = tempLoadedIndexBeforeHistory
            println("ViewModel: Restored state: ${restoredMessages.size} messages, index $tempLoadedIndexBeforeHistory")
            // 清理临时变量
            tempMessagesBeforeHistory = null
            tempLoadedIndexBeforeHistory = null
            println("ViewModel: Cleared temporary state variables after restoring.")
        } else { // 开始新聊天
            // 保存可能存在的当前聊天到历史
            val messagesToSave = _messages.value
                .filter { it.text.isNotBlank() && it.sender != Sender.System } // Allow empty AI messages
                .toList()
            var historyUpdated = false
            if (messagesToSave.isNotEmpty()) {
                _historicalConversations.update { currentHistory ->
                    val loadedIndex = _loadedHistoryIndex.value // 获取加载前的索引
                    val mutableHistory = currentHistory.toMutableList()
                    if (loadedIndex != null && loadedIndex >= 0 && loadedIndex < mutableHistory.size) {
                        mutableHistory[loadedIndex] = messagesToSave
                        historyUpdated = true; println("ViewModel: Saved current chat to history index $loadedIndex before starting new.")
                    } else {
                        mutableHistory.add(0, messagesToSave)
                        historyUpdated = true; println("ViewModel: Saved current chat as new history item before starting new.")
                    }
                    mutableHistory
                }
                if(historyUpdated) saveChatHistory()
            }

            // 清理状态以开始新聊天
            _messages.value = emptyList()
            _loadedHistoryIndex.value = null
            _text.value = ""
            println("ViewModel: Starting new chat, cleared messages and index.")
        }

        _userScrolledAway.value = false
        expandedReasoningStates.clear()
        _currentView.value = AppView.CurrentChat
    }


    fun loadConversationFromHistory(index: Int) {
        println("ViewModel: Loading history item at index $index.")
        val conversation = _historicalConversations.value.getOrNull(index)
        if (conversation != null) {
            // 确保在加载历史前取消任何正在进行的 API 调用。状态清理在 cancelCurrentApiJob + onCompletion
            cancelCurrentApiJob("加载历史记录")

            // 保存可能存在的当前聊天
            val messagesToSave = _messages.value
                .filter { it.text.isNotBlank() && it.sender != Sender.System } // Allow empty AI messages
                .toList()
            var historyUpdated = false
            if (messagesToSave.isNotEmpty()) {
                _historicalConversations.update { currentHistory ->
                    val loadedIndex = _loadedHistoryIndex.value // 获取加载前的索引
                    val mutableHistory = currentHistory.toMutableList()
                    if (loadedIndex != null && loadedIndex >= 0 && loadedIndex < mutableHistory.size) {
                        mutableHistory[loadedIndex] = messagesToSave
                        historyUpdated = true; println("ViewModel: Saved previous chat to history index $loadedIndex before loading $index.")
                    } else {
                        mutableHistory.add(0, messagesToSave)
                        historyUpdated = true; println("ViewModel: Saved previous chat as new history item before loading $index.")
                    }
                    mutableHistory
                }
                if(historyUpdated) saveChatHistory()
            }

            // 加载选中的历史记录
            _messages.value = conversation.toList() // 加载副本
            expandedReasoningStates.clear()
            _text.value = ""
            _loadedHistoryIndex.value = index // 设置新加载的索引
            tempMessagesBeforeHistory = null // 清理临时变量
            tempLoadedIndexBeforeHistory = null
            _userScrolledAway.value = false
            println("ViewModel: userScrolledAway reset to false on history load.")
            _currentView.value = AppView.CurrentChat
        } else {
            println("ViewModel: Error loading history at index $index.")
            viewModelScope.launch { _snackbarMessage.emit("无法加载该历史记录") }
        }
    }


    fun deleteConversation(indexToDelete: Int) {
        println("ViewModel: Delete requested for history item at index $indexToDelete.")
        var deleted = false
        _historicalConversations.update { currentHistory ->
            if (indexToDelete >= 0 && indexToDelete < currentHistory.size) {
                val mutableHistory = currentHistory.toMutableList()
                val deletedConversation = mutableHistory.removeAt(indexToDelete)
                println("ViewModel: Deleted history item at index $indexToDelete (${deletedConversation.size} messages).")

                val currentLoadedIndex = _loadedHistoryIndex.value
                if (currentLoadedIndex == indexToDelete) {
                    // 如果删除的是当前加载的项，需要决定下一步行为
                    // 选项1：清空当前聊天，但不自动加载其他项
                    // 选项2：自动加载前一项或后一项（如果存在）
                    // 这里选择选项1：清空，但不自动加载
                    _loadedHistoryIndex.value = null
                    // _messages.value = emptyList() // 可以选择是否清空聊天区
                    println("ViewModel: Deleted currently loaded history $indexToDelete, resetting loadedHistoryIndex.")
                    // 清理临时状态，以防万一
                    tempMessagesBeforeHistory = null
                    tempLoadedIndexBeforeHistory = null
                } else if (currentLoadedIndex != null && currentLoadedIndex > indexToDelete) {
                    // 如果加载的项在被删除项之后，更新索引
                    _loadedHistoryIndex.value = currentLoadedIndex - 1
                    println("ViewModel: Decremented loadedHistoryIndex to ${_loadedHistoryIndex.value}.")
                    // 同步更新临时索引（如果存在）
                    if (tempLoadedIndexBeforeHistory != null && tempLoadedIndexBeforeHistory!! > indexToDelete) {
                        tempLoadedIndexBeforeHistory = tempLoadedIndexBeforeHistory!! - 1
                    }
                }

                deleted = true
                mutableHistory // <-- Explicitly return the list
            } else {
                println("ViewModel: Delete request invalid: Index $indexToDelete out of bounds.")
                currentHistory // <-- Explicitly return the list
            }
        }
        if (deleted) {
            saveChatHistory()
            viewModelScope.launch { _snackbarMessage.emit("已删除一条历史记录") }
        }
    }


    fun startNewChat() {
        navigateToChat(fromHistory = false)
    }


    // --- 发送消息 (修正后) ---
    fun onSendMessage() {
        val userMessageText = _text.value.trim()
        if (userMessageText.isEmpty()) return

        val currentConfig = _selectedApiConfig.value
        if (currentConfig == null) {
            viewModelScope.launch { _snackbarMessage.emit("请先选择或添加API配置") }
            return
        }
        // 在发送新消息时，如果API正在调用，直接忽略发送（由取消按钮处理取消）
        if (_isApiCalling.value) { // 读 StateFlow
            println("ViewModel: API call is already in progress, ignoring send.")
            return
        }

        // 如果当前是从历史记录加载的，先将其视为独立对话，不再更新原历史项
        val currentIndex = _loadedHistoryIndex.value
        if (currentIndex != null) {
            println("ViewModel: Sending message in a loaded history context. Setting loadedHistoryIndex to null.")
            _loadedHistoryIndex.value = null // 脱离原历史记录项
        }

        // 添加用户消息并清空输入
        val userMessage = Message(text = userMessageText, sender = Sender.User)
        _messages.update { it + userMessage }
        _text.value = ""
        _userScrolledAway.value = false // 发送消息后认为用户在底部

        // 在启动新 Job 前，确保取消之前的 Job。
        // cancelCurrentApiJob 只会清理 ID 和发送取消信号，不会立即重置 isApiCalling
        cancelCurrentApiJob("发送新消息前清理")

        // 添加 AI 加载占位符
        val loadingMessage = Message(id = UUID.randomUUID().toString(), text = "", sender = Sender.AI, contentStarted = false) // <-- text is ""
        _messages.update { it + loadingMessage }

        val aiMessageId = loadingMessage.id
        // 设置当前流式 ID StateFlow 并标记 API 正在调用 StateFlow
        _currentStreamingAiMessageId.value = aiMessageId // <-- 设置当前流式 ID StateFlow
        _isApiCalling.value = true // <-- 设置 API 正在调用 StateFlow
        apiJob = null // Safety: Ensure apiJob is null before launching the new one

        expandedReasoningStates.remove(aiMessageId) // Ensure reasoning is collapsed for the new message


        // Prepare the API request messages
        val historyApiMessages = _messages.value
            // Filter out placeholder, system messages, and empty AI messages (unless it's the one we just added)
            .filter { msg ->
                (msg.sender == Sender.User // Always include user messages
                        || (msg.sender == Sender.AI // Include AI messages if...
                        && !msg.isError // ...not an error...
                        && msg.text.isNotBlank() // ...and text is NOT blank (i.e., not the initial empty placeholder)...
                        && msg.id != aiMessageId // ...and it's NOT the new message we just added (which is empty)
                        )
                        ) && msg.sender != Sender.System // Exclude system messages
            }
            // Additional safety filter to exclude the message we just added if the above logic is tricky
            .filterNot { it.id == aiMessageId }
            .map { message -> // Map to ApiMessage format
                ApiMessage(
                    role = if (message.sender == Sender.User) "user" else "assistant",
                    content = message.text.trim() // Ensure content is trimmed
                )
            }


        val providerToSend = when (currentConfig.provider.lowercase()) {
            "google" -> "google"
            "openai" -> "openai"
            else -> "openai".also { println("警告：未知提供商 '${currentConfig.provider}', 使用 'openai'") }
        }
        val requestBody = ChatRequest(
            messages = historyApiMessages,
            provider = providerToSend,
            apiAddress = currentConfig.address,
            apiKey = currentConfig.key,
            model = currentConfig.model
        )
        println("ViewModel Sending Request: Provider='${requestBody.provider}', Model='${requestBody.model}', History Msgs=${historyApiMessages.size}")

        // --- Launch API Call Coroutine ---
        apiJob = viewModelScope.launch { // Save the reference to the new Job
            val thisJob = this.coroutineContext[Job] // Get the Job instance of the current coroutine
            println("--- [ViewModel Coroutine Started] Job: $thisJob, MsgID: $aiMessageId")

            ApiClient.streamChatResponse(requestBody)
                // onStart now only logs, state set before launch
                .onStart {
                    println("--- [ViewModel.onStart] Job: $thisJob (Current VM Job: $apiJob) - Stream started.")
                    // State already set before launching the Job
                }
                // Catch non-Cancellation exceptions for error handling
                .catch { e ->
                    if (e !is CancellationException) {
                        val handlingJob = thisJob
                        val targetMessageId = aiMessageId
                        val currentJobRef = apiJob
                        val shouldHandle = (currentJobRef == handlingJob) // Handle only if it's the current job

                        if (shouldHandle) {
                            println("--- [ViewModel.catch] Non-cancellation error in active job: $handlingJob, Error: ${e::class.simpleName} - ${e.message}")
                            val errorTextForMessage = ERROR_VISUAL_PREFIX + when (e) {
                                is IOException -> "网络连接错误: ${e.message}"
                                else -> e.message ?: "未知流处理错误"
                            }
                            launch(Dispatchers.Main) {
                                _messages.update { currentMsgs ->
                                    val idx = currentMsgs.indexOfFirst { it.id == targetMessageId }
                                    if (idx != -1) {
                                        val msg = currentMsgs[idx]
                                        if (!msg.isError) {
                                            val updatedMsg = msg.copy(
                                                text = (msg.text ?: "") + "\n\n" + errorTextForMessage,
                                                contentStarted = true, isError = true, reasoning = null
                                            )
                                            expandedReasoningStates.remove(targetMessageId)
                                            println("--- [ViewModel.catch] Updated error message UI successfully (ID: $targetMessageId)")
                                            currentMsgs.toMutableList().apply { set(idx, updatedMsg) }
                                        } else currentMsgs
                                    } else {
                                        println("--- [ViewModel.catch] Error occurred, target message (ID: $targetMessageId) not found in list.")
                                        currentMsgs
                                    }
                                }
                                _snackbarMessage.emit("API 请求失败: ${errorTextForMessage}")
                            }
                        } else {
                            println("--- [ViewModel.catch] WARN: Job $handlingJob is outdated or mismatched (Current VM Job: $currentJobRef). Ignoring error event ${e::class.simpleName}.")
                        }
                    } else {
                        println("--- [ViewModel.catch] CancellationException caught, letting onCompletion handle it. Reason: ${e.message}")
                        // Do nothing here, let onCompletion handle cancellation states
                    }
                }
                .onCompletion { t -> // Called when the flow completes (success, failure, or cancellation)
                    val completingJob = thisJob // Capture the Job instance of the current coroutine
                    val targetMessageId = aiMessageId // Capture the Message ID associated with this flow

                    launch(Dispatchers.Main) { // Switch to the main thread to update UI and state
                        val reason = when {
                            t is CancellationException && t.message == USER_CANCEL_MESSAGE -> "User Cancellation"
                            t is CancellationException -> "Internal Cancellation (${t.message})"
                            t != null -> "Error (${t::class.simpleName})"
                            else -> "Normal Completion"
                        }
                        println("--- [ViewModel.onCompletion] Job: $completingJob, Target MsgID: $targetMessageId, Reason: $reason")

                        // --- Global State Cleanup: ONLY reset if completingJob is the current apiJob ---
                        val currentJobRef = apiJob // Read the current reference safely
                        println("--- [ViewModel.onCompletion] Global State Cleanup Check: Completed Job: $completingJob. Current VM Job: $currentJobRef.")
                        if (currentJobRef == completingJob) { // <-- Crucial check
                            println("--- [ViewModel.onCompletion] Global State Cleanup: Job matched. Setting isApiCalling=false, apiJob=null.")
                            _isApiCalling.value = false // <-- Set calling state to false
                            apiJob = null // <-- Clear the job reference
                        } else {
                            println("--- [ViewModel.onCompletion] Global State Cleanup: Completed Job ($completingJob) does not match current active job ($currentJobRef). Skipping global state reset.")
                        }
                        // --- End Global State Cleanup ---

                        // --- Update Message List State: Remove empty placeholder on cancel, add markers ---
                        _messages.update { currentMsgs ->
                            val finalIndex = currentMsgs.indexOfFirst { it.id == targetMessageId }
                            if (finalIndex == -1) {
                                println("--- [ViewModel.onCompletion] Message (ID: $targetMessageId) not found upon completion. Returning original list.")
                                expandedReasoningStates.remove(targetMessageId) // Ensure cleanup
                                return@update currentMsgs // <-- Explicitly return original list
                            }

                            val msg = currentMsgs[finalIndex]
                            val wasCancelled = t is CancellationException

                            // Case 1: Remove empty placeholder if cancelled
                            if ((msg.text ?: "").isEmpty() && !msg.contentStarted && msg.reasoning.isNullOrBlank() && !msg.isError && wasCancelled) {
                                println("--- [ViewModel.onCompletion] Pure empty placeholder message (ID: $targetMessageId) was cancelled, removing.")
                                expandedReasoningStates.remove(targetMessageId)
                                return@update currentMsgs.toMutableList().apply { removeAt(finalIndex) } // <-- Return the new list
                            }

                            // Case 2: Update existing message (add interrupted/error marker)
                            var updatedMsg = msg
                            val currentText = msg.text ?: ""
                            var messageObjectWasModified = false

                            when {
                                wasCancelled && !msg.isError && !currentText.contains("(已中断)") -> {
                                    updatedMsg = msg.copy(text = currentText + " (已中断)")
                                    messageObjectWasModified = true
                                    println("--- [ViewModel.onCompletion] Updating message (ID: $targetMessageId) to 'Interrupted'.")
                                }
                                (t != null && t !is CancellationException && !msg.isError) -> {
                                    val completionErrText = ERROR_VISUAL_PREFIX + (t.message ?: "Completion Error")
                                    updatedMsg = msg.copy(
                                        text = (currentText.takeIf { it.isNotEmpty() } ?: "") + "\n\n" + completionErrText,
                                        contentStarted = true, isError = true, reasoning = null
                                    )
                                    expandedReasoningStates.remove(targetMessageId)
                                    messageObjectWasModified = true
                                    println("--- [ViewModel.onCompletion] Updating message (ID: $targetMessageId) to Error state from completion.")
                                }
                                t == null && !msg.isError -> { // Normal completion
                                    if (expandedReasoningStates[targetMessageId] == true) {
                                        expandedReasoningStates[targetMessageId] = false
                                    }
                                    println("--- [ViewModel.onCompletion] Message (ID: $targetMessageId) completed normally.")
                                }
                                else -> {
                                    println("--- [ViewModel.onCompletion] Message (ID: $targetMessageId) finished processing, no modification needed or already in final state.")
                                }
                            } // End when

                            if (messageObjectWasModified) {
                                return@update currentMsgs.toMutableList().apply { set(finalIndex, updatedMsg) } // <-- Return the new list
                            } else {
                                return@update currentMsgs // <-- Return original list
                            }

                        } // End _messages.update lambda in onCompletion
                    } // launch(Dispatchers.Main) End
                } // onCompletion End
                .collect { chunk -> // Process each chunk from the stream
                    // Process chunks only if the Job matches AND the Message ID is still the one currently streaming
                    val currentJobRef = apiJob // Read the current reference safely
                    if (thisJob == currentJobRef && _currentStreamingAiMessageId.value == aiMessageId) { // Check against the current job ref
                        launch(Dispatchers.Main) { // Update UI-related state on the main thread
                            _messages.update { currentMsgs ->
                                val idx = currentMsgs.indexOfFirst { it.id == aiMessageId }
                                if (idx == -1) {
                                    println("--- [ViewModel.collect] Message (ID: $aiMessageId) lost or ID mismatch during chunk processing. Throwing CancellationException.")
                                    throw CancellationException("Target message lost or ID mismatch during collect")
                                }

                                val msg = currentMsgs[idx]
                                // Create copies for modification
                                var uText = msg.text // Nullable String
                                var uRea = msg.reasoning // Nullable String
                                var uStart = msg.contentStarted
                                var uErr = msg.isError
                                var reasoningUpdated = false // Flag if reasoning changed in this chunk

                                val updatedMsg = when (chunk.type) { // <-- This when determines the updated message object
                                    "reasoning" -> {
                                        val wasReasoningEmpty = msg.reasoning.isNullOrBlank() // Check BEFORE appending
                                        uRea = (uRea ?: "") + chunk.text
                                        reasoningUpdated = true // Mark reasoning as updated

                                        // Only auto-expand on the *first* reasoning chunk if not explicitly collapsed
                                        if (wasReasoningEmpty && expandedReasoningStates[aiMessageId] != false) {
                                            println("--- [ViewModel.collect] First reasoning chunk for $aiMessageId, ensuring expanded.")
                                            expandedReasoningStates[aiMessageId] = true
                                        }
                                        msg.copy(text = uText ?: "", reasoning = uRea, contentStarted = uStart, isError = uErr) // Return updated copy
                                    }
                                    "content" -> {
                                        uText = (uText ?: "") + chunk.text
                                        uStart = true // Mark content as started
                                        // Auto-collapse reasoning only when the *first* content chunk arrives
                                        if (expandedReasoningStates[aiMessageId] == true && !reasoningUpdated) {
                                            println("--- [ViewModel.collect] Content started for $aiMessageId, auto-collapsing reasoning.")
                                            expandedReasoningStates[aiMessageId] = false
                                        }
                                        msg.copy(text = uText ?: "", reasoning = uRea, contentStarted = uStart, isError = uErr) // Return updated copy
                                    }
                                    // Removed error handling from collect, moved to catch/onCompletion
                                    else -> {
                                        println("--- [ViewModel.collect] Unknown chunk type: ${chunk.type} for $aiMessageId")
                                        msg // <-- Return original message if unknown type
                                    }
                                } // End when

                                // Apply the updatedMsg to the list and return the new list
                                return@update currentMsgs.toMutableList().apply { set(idx, updatedMsg) } // <-- Explicitly return the new list


                            } // End if (idx != -1)
                            // Note: The outer lambda MUST return a List<Message>.
                            // Since the `if (idx == -1)` branch above throws,
                            // the code doesn't reach here if idx is -1.
                            // If idx is not -1, the code inside the `if` branch returns a list.
                            // So, the outer lambda's return is handled by the branches within.
                        } // End _messages.update lambda in collect
                    } else { // Job or ID mismatch, indicates outdated or cancelled
                        println("--- [ViewModel.collect] WARN: Job $thisJob is outdated (Current VM Job: $currentJobRef) or MsgID $aiMessageId is no longer the current Streaming ID (${_currentStreamingAiMessageId.value}), ignoring chunk.")
                        throw CancellationException("Job outdated on collect") // Cancel the collect flow
                    }
                } // collect End
        } // apiJob = viewModelScope.launch End
    } // onSendMessage End


    // --- UI Call Cancel ---
    fun onCancelAPICall() {
        println("ViewModel: Cancel button clicked.")
        // Call cancelCurrentApiJob, which clears ID, attempts to remove empty placeholder, and cancels Job.
        // onCompletion handles the final state reset (_isApiCalling).
        cancelCurrentApiJob(USER_CANCEL_MESSAGE)
    }

    // --- 其他 UI 交互 ---
    fun onToggleReasoningExpand(messageId: String) {
        val current = expandedReasoningStates[messageId] ?: false
        expandedReasoningStates[messageId] = !current
        println("ViewModel: Toggled reasoning expand for $messageId to ${!current}")
    }

    fun showSettingsDialog() {
        _showSettingsDialog.value = true
    }

    fun dismissSettingsDialog() {
        _showSettingsDialog.value = false
    }

    // --- Config Management ---
    fun addConfig(configToAdd: ApiConfig) {
        // Check if a config with the exact same content already exists
        val contentExists = _apiConfigs.value.any { existingConfig ->
            existingConfig.address.trim().equals(configToAdd.address.trim(), ignoreCase = true) &&
                    existingConfig.key.trim() == configToAdd.key.trim() &&
                    existingConfig.model.trim().equals(configToAdd.model.trim(), ignoreCase = true) &&
                    existingConfig.provider.trim().equals(configToAdd.provider.trim(), ignoreCase = true)
        }

        if (!contentExists) {
            // Generate a new ID if there's an ID collision
            val finalConfig = if (_apiConfigs.value.any { it.id == configToAdd.id }) configToAdd.copy(id = UUID.randomUUID().toString()) else configToAdd
            _apiConfigs.update { it + finalConfig }
            // If it's the first config or none is currently selected, auto-select it
            if (_selectedApiConfig.value == null) {
                _selectedApiConfig.value = finalConfig
            }
            saveApiConfigs()
            viewModelScope.launch { _snackbarMessage.emit("Config '${finalConfig.model}' saved") }
        } else {
            viewModelScope.launch { _snackbarMessage.emit("Config already exists") }
        }
    }

    fun updateConfig(configToUpdate: ApiConfig) {
        var updated = false
        _apiConfigs.update { currentConfigs ->
            val index = currentConfigs.indexOfFirst { it.id == configToUpdate.id }
            if (index != -1) {
                // Only update if content has actually changed
                if (currentConfigs[index] != configToUpdate) {
                    val mutableConfigs = currentConfigs.toMutableList()
                    mutableConfigs[index] = configToUpdate
                    // If the updated config is the currently selected one, update _selectedApiConfig as well
                    if (_selectedApiConfig.value?.id == configToUpdate.id) {
                        _selectedApiConfig.value = configToUpdate
                    }
                    updated = true
                    mutableConfigs // <-- Explicitly return the list
                } else {
                    currentConfigs // Content not changed, return original list
                }
            } else {
                viewModelScope.launch { _snackbarMessage.emit("Update failed: Config not found") }
                currentConfigs // Not found, return original list
            }
        }
        if (updated) {
            saveApiConfigs()
            // viewModelScope.launch { _snackbarMessage.emit("Config '${configToUpdate.model}' updated") } // Update message might be annoying
        }
    }

    fun deleteConfig(configToDelete: ApiConfig) {
        val wasSelected = _selectedApiConfig.value?.id == configToDelete.id
        var deletedName: String? = null
        var newSelectedConfig: ApiConfig? = null

        _apiConfigs.update { currentConfigs ->
            val index = currentConfigs.indexOfFirst { it.id == configToDelete.id }
            if (index != -1) {
                deletedName = currentConfigs[index].model
                val mutableConfigs = currentConfigs.toMutableList()
                mutableConfigs.removeAt(index)

                if (wasSelected) {
                    // Ensure any ongoing API call is cancelled before deleting the selected config
                    cancelCurrentApiJob("Deleting selected config")
                    newSelectedConfig = mutableConfigs.firstOrNull() // Automatically select the first if exists
                    _selectedApiConfig.value = newSelectedConfig // Update _selectedApiConfig atomically within the update block
                }
                mutableConfigs // <-- Explicitly return the list
            } else {
                viewModelScope.launch { _snackbarMessage.emit("Delete failed: Config not found") }
                currentConfigs // Not found, return original list
            }
        }

        // If a config was successfully deleted
        if (deletedName != null) {
            saveApiConfigs() // Save changes
            viewModelScope.launch {
                if (wasSelected) {
                    _snackbarMessage.emit("Selected config '$deletedName' deleted")
                    kotlinx.coroutines.delay(250) // Short delay for first message to show
                    if (newSelectedConfig == null) {
                        _snackbarMessage.emit("Please add or select a new API config")
                    } else {
                        _snackbarMessage.emit("Automatically selected: ${newSelectedConfig.model} (${newSelectedConfig.provider})")
                    }
                } else {
                    _snackbarMessage.emit("Config '$deletedName' deleted")
                }
            }
        }
    }

    fun clearAllConfigs() {
        if (_apiConfigs.value.isNotEmpty()) {
            // Ensure any ongoing API call is cancelled before clearing all configs
            cancelCurrentApiJob("Clearing all configs")
            _apiConfigs.value = emptyList()
            _selectedApiConfig.value = null
            println("ViewModel: Cleared all configs.")
            saveApiConfigs()
            viewModelScope.launch {
                _snackbarMessage.emit("All configs cleared")
                kotlinx.coroutines.delay(250)
                _snackbarMessage.emit("Please add API config")
            }
        } else {
            println("ViewModel: No configs to clear.")
            viewModelScope.launch { _snackbarMessage.emit("No configs to clear") }
        }
    }

    fun selectConfig(config: ApiConfig) {
        if (_selectedApiConfig.value?.id != config.id) {
            // Ensure any ongoing API call is cancelled before switching config
            cancelCurrentApiJob("Switching selected config")
            _selectedApiConfig.value = config
            saveApiConfigs() // Save new selection
            println("ViewModel: Selected config ${config.model}")
            dismissSettingsDialog() // Close dialog
            viewModelScope.launch { _snackbarMessage.emit("Selected: ${config.model} (${config.provider})") }
        } else {
            println("ViewModel: Config ${config.model} already selected, closing dialog.")
            dismissSettingsDialog() // Just close dialog
        }
    }
}


// --- ViewModel Factory ---
class AppViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            val dataSource = SharedPreferencesDataSource(context.applicationContext)
            @Suppress("UNCHECKED_CAST")
            return AppViewModel(dataSource) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}") // Corrected reference
    }
}