package com.example.app1.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.example.app1.data.local.SharedPreferencesDataSource
import com.example.app1.data.models.*
import com.example.app1.ui.components.SettingsDialog // 确认 SettingsDialog 导入正确
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

// --- 枚举和常量 ---
enum class AppView { CurrentChat, HistoryList }

const val ERROR_VISUAL_PREFIX = "⚠️ [错误] "
const val USER_CANCEL_MESSAGE = "用户手动停止"


// --- 主 Composable 函数 ---
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun AppContent(
    innerPadding: PaddingValues, // 从 Scaffold 传入的内边距
    snackbarHostState: SnackbarHostState // 用于显示提示信息
) {
    // --- 核心状态变量 ---
    var text by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<Message>() }
    var currentView by remember { mutableStateOf(AppView.CurrentChat) }
    val historicalConversations = remember { mutableStateListOf<List<Message>>() }
    var loadedHistoryIndex by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState() // LazyColumn 的状态
    val context = LocalContext.current
    val dataSource = remember { SharedPreferencesDataSource(context) }
    val apiConfigs = remember { mutableStateListOf<ApiConfig>() }
    var selectedApiConfig by remember { mutableStateOf<ApiConfig?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var isApiCalling by remember { mutableStateOf(false) } // 整体 API 调用状态
    val keyboardController = LocalSoftwareKeyboardController.current
    val expandedReasoningStates = remember { mutableStateMapOf<String, Boolean>() }
    var apiJob by remember { mutableStateOf<Job?>(null) }
    var currentStreamingAiMessageId by remember { mutableStateOf<String?>(null) } // 仍然需要记录当前流式 ID

    // --- 用于暂存切换到历史记录前的状态 ---
    var tempMessagesBeforeHistory by remember { mutableStateOf<List<Message>?>(null) }
    var tempLoadedIndexBeforeHistory by remember { mutableStateOf<Int?>(null) }

    // --- 新增：跟踪用户是否在列表底部 ---
    var userScrolledAway by remember { mutableStateOf(false) }
    // 使用 derivedStateOf 高效计算是否在底部
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            val totalItemsCount = layoutInfo.totalItemsCount

            if (totalItemsCount == 0) { // 列表为空时认为在底部
                true
            } else {
                // 获取最后一个可见项的信息
                val lastVisibleItem = visibleItemsInfo.lastOrNull()
                if (lastVisibleItem == null) {
                    // 如果没有可见项，通常也认为在底部（例如初始状态或列表很小完全可见）
                    true
                } else {
                    // 判断最后一个可见项的索引是否是列表的最后一项
                    val isLastItemVisible = lastVisibleItem.index == totalItemsCount - 1
                    // 判断最后一个可见项的底部是否接触到了列表的底部边缘（加上一些容差）
                    // 注意：这里的判断可能在项目高度变化时略有延迟，但对于用户滚动判断足够了
                    val isBottomEdgeVisible =
                        (lastVisibleItem.offset + lastVisibleItem.size) <= layoutInfo.viewportEndOffset + 5 // 加一点容差

                    isLastItemVisible && isBottomEdgeVisible
                }
            }
        }
    }

    // 监听滚动状态，更新 userScrolledAway 标志
    LaunchedEffect(listState.isScrollInProgress, isAtBottom) {
        // 这个 LaunchedEffect 会在 listState.isScrollInProgress 或 isAtBottom 变化时触发
        // 我们只关心滚动 *停止* 时的状态
        if (!listState.isScrollInProgress) {
            // 滚动停止时检查是否在底部
            if (!isAtBottom) {
                if (!userScrolledAway) { // 避免重复设置和打印
                    println("User scroll stopped away from bottom. Setting userScrolledAway = true.")
                    userScrolledAway = true
                }
            } else {
                // 滚动停止时如果在底部，重置标志
                if (userScrolledAway) { // 避免重复设置和打印
                    println("User scroll stopped at bottom, resetting userScrolledAway = false.")
                    userScrolledAway = false
                }
            }
        }
    }
    // --- 滚动状态跟踪结束 ---


    // --- 数据加载与保存 Effect ---
    LaunchedEffect(Unit) { // 初始加载
        val loadedConfigs = dataSource.loadApiConfigs(); apiConfigs.clear(); apiConfigs.addAll(loadedConfigs)
        val selectedConfigId = dataSource.loadSelectedConfigId(); selectedApiConfig = loadedConfigs.find { it.id == selectedConfigId } ?: loadedConfigs.firstOrNull()
        println("AppContent: Initial API configs loaded (${loadedConfigs.size}). Selected ID: $selectedConfigId")
        val loadedHistory = dataSource.loadChatHistory(); historicalConversations.clear(); historicalConversations.addAll(loadedHistory)
        println("AppContent: Initial history loaded with ${loadedHistory.size} conversations.")
        if (selectedApiConfig == null && apiConfigs.isNotEmpty()) {
            coroutineScope.launch { snackbarHostState.showSnackbar("请选择API配置", duration = SnackbarDuration.Long) }
        } else if (apiConfigs.isEmpty()) {
            coroutineScope.launch { snackbarHostState.showSnackbar("请添加API配置", duration = SnackbarDuration.Long) }
        }
    }
    LaunchedEffect(apiConfigs.toList(), selectedApiConfig) { // 保存 API 配置
        println("AppContent: API config state changed, saving..."); dataSource.saveApiConfigs(apiConfigs.toList()); dataSource.saveSelectedConfigId(selectedApiConfig?.id)
    }
    LaunchedEffect(historicalConversations.toList()) { // 保存聊天历史记录
        println("AppContent: Change detected in historicalConversations (${historicalConversations.size} items), triggering save."); dataSource.saveChatHistory(historicalConversations.toList())
    }

    // --- 列表滚动逻辑 ---
    // 处理非流式更新时的自动滚动（例如发送用户消息后）
    // 现在只在用户没有手动滚动离开时才自动滚动
    LaunchedEffect(messages.size) {
        // 仅当在当前聊天视图，且列表非空，且当前没有API调用，且用户没有滚动离开时才自动滚动
        if (currentView == AppView.CurrentChat && messages.isNotEmpty() && !isApiCalling) {
            if (!userScrolledAway) { // *** 仅在用户未滚动离开时自动滚动 ***
                coroutineScope.launch {
                    delay(60) // 短延迟等待 recomposition/layout
                    try {
                        val lastItemIndex = listState.layoutInfo.totalItemsCount - 1
                        if (lastItemIndex >= 0) {
                            println("Scrolling to bottom based on message size change (API not calling) - userScrolledAway = false.")
                            listState.animateScrollToItem(index = lastItemIndex, scrollOffset = Int.MAX_VALUE)
                        }
                    } catch (e: Exception) {
                        println("主列表滚动 (on size change launch) 错误: ${e.message}")
                    }
                }
            } else {
                println("Skipping auto-scroll on message size change (API not calling) - userScrolledAway = true.")
            }
        }
    }

    // 视图切换或加载历史后强制滚动到底部
    LaunchedEffect(currentView, loadedHistoryIndex) {
        // 仅当切换到当前聊天视图且列表非空时强制滚动
        if (currentView == AppView.CurrentChat && messages.isNotEmpty()) {
            delay(150) // 稍长延迟确保视图切换和数据加载完成
            try {
                val lastItemIndex = listState.layoutInfo.totalItemsCount - 1
                if (lastItemIndex >= 0) {
                    println("Scrolling to bottom after view change or history load (forced).")
                    // *** 这里是强制滚动，不检查 userScrolledAway ***
                    listState.animateScrollToItem(index = lastItemIndex, scrollOffset = Int.MAX_VALUE)
                    // 强制滚动到底部后，重置 userScrolledAway
                    userScrolledAway = false
                    println("userScrolledAway reset to false after forced scroll.")
                }
            } catch (e: Exception) {
                println("主列表滚动 (on view change/history load) 错误: ${e.message}")
            }
        }
    }


    // --- API 调用取消逻辑 ---
    fun cancelCurrentApiJob(reason: String) {
        val jobToCancel = apiJob
        val messageIdToCancel = currentStreamingAiMessageId
        println("--- [cancelCurrentApiJob] 请求取消。原因: $reason. MsgID: $messageIdToCancel")

        // *** 修改：立即重置 API 调用相关的状态，提供快速 UI 反馈 ***
        isApiCalling = false // 立即设置整体 API 调用状态为 false
        apiJob = null // 立即清空 Job 引用
        currentStreamingAiMessageId = null // 立即清空当前流式消息 ID

        var messageRemoved = false // 标记是否移除了消息

        if (messageIdToCancel != null) {
            val messageIndex = messages.indexOfFirst { it.id == messageIdToCancel }
            if (messageIndex != -1) {
                val messageToHandle = messages[messageIndex]
                // 如果是空的占位符且是本次取消的目标，并且不是错误状态，立即移除
                if (messageToHandle.text == "..." && !messageToHandle.contentStarted && messageToHandle.reasoning.isNullOrBlank() && !messageToHandle.isError) {
                    println("--- [cancelCurrentApiJob] 消息 (ID: $messageIdToCancel) 是空占位符，执行移除。")
                    messages.removeAt(messageIndex)
                    expandedReasoningStates.remove(messageIdToCancel) // 移除对应的 reasoning 状态
                    messageRemoved = true
                } else {
                    println("--- [cancelCurrentApiJob] 消息 (ID: $messageIdToCancel) 已有内容或 Reasoning 或已是错误状态，不移除。由 onCompletion 处理中断标记。")
                    // 等待 onCompletion 处理中断标记，UI 已经通过 isApiCalling 状态改变了
                }
            } else {
                println("--- [cancelCurrentApiJob] WARN: 尝试处理消息 (ID: $messageIdToCancel)，但在列表中未找到。")
            }
        } else {
            println("--- [cancelCurrentApiJob] WARN: currentStreamingAiMessageId 为 null，可能没有活跃的流式任务。")
        }

        // 发送取消信号给 Job
        if (jobToCancel != null && jobToCancel.isActive) {
            println("--- [cancelCurrentApiJob] 发送取消信号给 Job: $jobToCancel")
            jobToCancel.cancel(CancellationException(reason))
        } else {
            println("--- [cancelCurrentApiJob] Job 无效或不活动 ($jobToCancel)，无需取消后台任务。")
        }

        // 如果移除了消息，尝试滚动到新的底部 (强制滚动)
        if (messageRemoved && messages.isNotEmpty()) {
            coroutineScope.launch {
                delay(50) // 短暂延迟等待 UI 更新
                try {
                    val lastIndex = messages.lastIndex
                    if (lastIndex >= 0) {
                        println("--- [cancelCurrentApiJob] 移除消息后滚动到新的底部 (forced).")
                        listState.animateScrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE)
                        // 强制滚动后重置 userScrolledAway
                        userScrolledAway = false
                        println("userScrolledAway reset to false after cancel scroll.")
                    }
                } catch (e: Exception) {
                    println("取消后滚动错误: ${e.message}")
                }
            }
        } else if (messageRemoved && messages.isEmpty()) {
            // 如果移除了唯一的消息，列表变空，isAtBottom 会自动变 true，userScrolledAway 会被 LaunchedEffect 重置
            println("--- [cancelCurrentApiJob] 移除消息后列表变空.")
        }
    }

    // --- 导航回聊天视图的通用函数 ---
    fun navigateBackToChat() {
        println("Navigating back to chat view...")
        // navigateBackToChat 函数主要用于从 HistoryList 返回 CurrentChat
        // 它会尝试恢复 temp 状态，然后清空 temp 状态

        messages.clear()
        if (tempMessagesBeforeHistory != null) {
            messages.addAll(tempMessagesBeforeHistory!!)
            println("Restored ${tempMessagesBeforeHistory!!.size} messages from temp state.")
        } else {
            println("No temporary message state found to restore.")
        }
        loadedHistoryIndex = tempLoadedIndexBeforeHistory
        println("Restored loadedHistoryIndex to: $loadedHistoryIndex")

        // 清空用于“返回新对话”的临时状态，因为它已经被使用了
        tempMessagesBeforeHistory = null
        tempLoadedIndexBeforeHistory = null
        println("Cleared temporary state variables after restoring.")

        userScrolledAway = false // 返回时重置滚动状态标志，默认认为用户在底部
        expandedReasoningStates.clear() // 返回时折叠所有 reasoning
        currentView = AppView.CurrentChat
    }

    // --- 主 UI 布局 ---
    Box(
        modifier = Modifier
            .consumeWindowInsets(innerPadding)
            .padding(innerPadding)
            .fillMaxSize()
    ) {
        // --- 视图切换 ---
        when (currentView) {
            // --- 当前聊天视图 ---
            AppView.CurrentChat -> {
                ChatScreenContent( // 调用 ChatScreen Composable
                    messages = messages,
                    text = text,
                    onTextChange = { text = it },
                    selectedApiConfig = selectedApiConfig,
                    isApiCalling = isApiCalling, // 传递整体 API 调用状态给输入区域控制按钮 (ChatInputArea handled inside ChatScreenContent)
                    listState = listState, // 传递 listState
                    expandedReasoningStates = expandedReasoningStates,
                    // 按钮显示条件：正在调用API 且 用户已手动滚动离开 且 消息数量大于某个阈值 (避免在空列表或极短列表显示)
                    showScrollToBottomButton = isApiCalling && userScrolledAway && messages.size > 5,
                    onScrollToBottomClick = { // 处理滚动到底部按钮点击
                        coroutineScope.launch {
                            try {
                                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                                if (lastIndex >= 0) {
                                    println("Scroll to bottom button clicked, scrolling (forced)...")
                                    // *** 强制滚动到底部边缘 ***
                                    listState.animateScrollToItem(
                                        index = lastIndex,
                                        scrollOffset = Int.MAX_VALUE
                                    )
                                    delay(300) // 延迟一小会后重置标志，确保滚动完成
                                    userScrolledAway = false // *** 点击按钮后重置标志 ***
                                    println("User scrolled away flag reset after button click.")
                                }
                            } catch (e: Exception) {
                                println("Scroll to bottom button click error: ${e.message}")
                            }
                        }
                    },
                    onHistoryClick = { // 点击历史
                        println("History button clicked.")
                        // 在切换到历史记录前，取消API调用并保存当前对话（如果非空）
                        cancelCurrentApiJob("视图切换到历史记录")

                        println("Saving current chat state before navigating to history.");
                        tempMessagesBeforeHistory = messages.toList()
                        tempLoadedIndexBeforeHistory = loadedHistoryIndex
                        println("Saved state: ${tempMessagesBeforeHistory?.size ?: 0} messages, index $tempLoadedIndexBeforeHistory")

                        // 检查当前对话是否值得保存到历史记录（非空且非只有占位符）
                        val messagesToSave = messages.filter { it.text.isNotBlank() && it.text != "..." && it.sender != Sender.System }.toList()
                        if (messagesToSave.isNotEmpty()) {
                            if (loadedHistoryIndex != null && loadedHistoryIndex!! < historicalConversations.size) {
                                // 如果当前是加载的历史对话，则更新它
                                historicalConversations[loadedHistoryIndex!!] = messagesToSave
                                println("AppContent: Updated history at index $loadedHistoryIndex with ${messagesToSave.size} messages.")
                            } else {
                                // 如果是新对话或加载历史后有修改，保存为新历史记录 (添加到列表顶部)
                                historicalConversations.add(0, messagesToSave)
                                println("AppContent: Saved new conversation to history (${messagesToSave.size} messages).")
                            }
                        } else {
                            println("AppContent: Current chat is empty or only placeholder, not saving to history list.")
                            // 如果当前是加载的历史记录，并且现在清空了，可能需要考虑删除它
                            // 但这里为了简单，只在非空时保存或更新
                        }

                        messages.clear()
                        text = ""
                        loadedHistoryIndex = null // 进入历史列表时，当前加载的历史索引清空
                        expandedReasoningStates.clear() // 清空 reasoning 状态
                        userScrolledAway = false // 进入历史前重置，理论上没用但安全
                        println("Cleared current chat UI state.")
                        currentView = AppView.HistoryList
                    },
                    onSettingsClick = { // 点击设置
                        println("Settings button clicked."); cancelCurrentApiJob("打开设置"); showSettingsDialog = true
                    },
                    onSendMessage = { userMessageText -> // 发送消息
                        if (selectedApiConfig == null) {
                            coroutineScope.launch { snackbarHostState.showSnackbar("请先选择或添加API配置", duration = SnackbarDuration.Long) }
                            return@ChatScreenContent
                        }
                        if (isApiCalling) {
                            // 避免重复发送，或者提供取消当前任务的选项
                            println("API call is already in progress, ignoring send.")
                            // 可以考虑在这里提示用户或显示取消按钮
                            return@ChatScreenContent
                        }

                        keyboardController?.hide()

                        // 在添加新用户消息前，如果当前是加载的历史对话，先保存当前状态
                        // 确保后续对 messages 的修改是针对新状态，不直接修改历史记录副本
                        if (loadedHistoryIndex != null) {
                            val messagesToSave = messages.filter { it.text.isNotBlank() && it.text != "..." && it.sender != Sender.System }.toList()
                            if (messagesToSave.isNotEmpty()) {
                                if (loadedHistoryIndex!! < historicalConversations.size) {
                                    historicalConversations[loadedHistoryIndex!!] = messagesToSave
                                    println("AppContent: Saved current state of history $loadedHistoryIndex before adding new message.")
                                }
                            }
                            // 添加新消息后，这个对话就不再是“加载自历史记录X”的状态了，变成一个新的分支
                            loadedHistoryIndex = null
                            println("AppContent: Adding new message, setting loadedHistoryIndex to null.")
                        }


                        messages.add(Message(text = userMessageText, sender = Sender.User, reasoning = null))
                        text = "" // 发送后清空

                        // 确保之前的 API Job 被取消，尽 M 管理论上 isApiCalling 应该阻止到这里
                        cancelCurrentApiJob("发送新消息前清理")

                        val loadingMessage = Message(
                            id = UUID.randomUUID().toString(),
                            text = "...",
                            sender = Sender.AI,
                            reasoning = null, // 思考过程在这里还是 null
                            contentStarted = false,
                            isError = false
                        )
                        messages.add(loadingMessage) // 添加占位符消息

                        val aiMessageId = loadingMessage.id // 获取占位符的消息 ID
                        currentStreamingAiMessageId = aiMessageId // 设置当前正在流式处理的消息 ID

                        // 找到占位符的索引（尽管上面刚加，但保险起见）
                        val aiMessageIndex = messages.indexOfFirst { it.id == aiMessageId }
                        if (aiMessageIndex == -1) {
                            println("严重错误：添加占位符后无法立即找到！取消发送。")
                            // 移除刚才添加的占位符（如果能找到的话）
                            messages.remove(loadingMessage)
                            // 重置 API 调用状态 (理论上 cancelCurrentApiJob 会做，这里是双重保险)
                            isApiCalling = false
                            apiJob = null
                            currentStreamingAiMessageId = null
                            return@ChatScreenContent
                        }

                        expandedReasoningStates.remove(aiMessageId) // 确保新的AI消息 reasoning 默认是折叠的

                        userScrolledAway = false // 发送新消息时，重置滚动状态，准备接收回复，允许自动滚动
                        println("userScrolledAway reset to false on sendMessage.")

                        // 构建 historyApiMessages: 只包含用户和 AI 已完成的消息
                        // AI 占位符消息 (text "...") 不应该包含在历史消息中发送给 API
                        val historyApiMessages = messages
                            .filter { msg ->
                                // 包含所有用户消息，以及AI已完成（text != "..."）且非系统消息
                                (msg.sender == Sender.User || (msg.sender == Sender.AI && msg.text.isNotBlank() && msg.text != "..."))
                                        && msg.text.isNotBlank() // 再次确保文本非空
                            }
                            .mapNotNull { message ->
                                if (message.text.isNotBlank() && message.text != "...") { // 避免发送空的或占位符消息给API
                                    ApiMessage(
                                        role = if (message.sender == Sender.User) "user" else "assistant",
                                        content = message.text.trim() // 清理首尾空白
                                    )
                                } else null
                            }

                        // 确定 providerToSend
                        val providerToSend = when (selectedApiConfig!!.provider.lowercase()) {
                            "google" -> "google"
                            "openai" -> "openai"
                            else -> "openai".also { println("警告：未知提供商 '${selectedApiConfig!!.provider}', 使用 'openai'") }
                        }
                        // 创建 requestBody
                        val requestBody = ChatRequest(
                            messages = historyApiMessages, // 使用构建好的历史消息列表
                            provider = providerToSend,
                            apiAddress = selectedApiConfig!!.address,
                            apiKey = selectedApiConfig!!.key,
                            model = selectedApiConfig!!.model
                        )
                        println("发送请求: Provider='${requestBody.provider}', Model='${requestBody.model}', History Msgs=${requestBody.messages.size}")

                        // --- 启动 API 调用协程 ---
                        apiJob = coroutineScope.launch {
                            val thisJob = this.coroutineContext[Job]
                            println("--- [协程启动] Job: $thisJob, MsgID: $aiMessageId")

                            com.example.app1.data.network.ApiClient.streamChatResponse(requestBody)
                                .onStart {
                                    withContext(Dispatchers.Main) {
                                        println("--- [onStart] Job: $thisJob (当前: $apiJob)");
                                        // 再次检查 Job 是否仍然是当前的活跃 Job
                                        if (apiJob == thisJob) {
                                            isApiCalling = true // 设置整体 API 调用状态为 true
                                            try {
                                                // 初始滚动到底部 (强制，不检查 userScrolledAway)
                                                delay(50) // 短暂延迟等待布局
                                                val li = listState.layoutInfo.totalItemsCount - 1
                                                if (li >= 0) {
                                                    listState.animateScrollToItem(li, scrollOffset = Int.MAX_VALUE)
                                                    userScrolledAway = false // 强制滚动到底部，重置标志
                                                    println("userScrolledAway reset to false after onStart scroll.")
                                                }
                                            } catch (e: Exception) {
                                                println("onStart 滚动错误: ${e.message}")
                                            }
                                        } else {
                                            println("--- [onStart] WARNING: Job $thisJob 已过时，跳过状态更新和滚动。")
                                            throw CancellationException("Job outdated onStart") // 强制取消过时 Job 的 flow
                                        }
                                    }
                                }
                                .catch { e ->
                                    // 检查异常是否来自当前活跃的 Job
                                    if (apiJob == thisJob || e is CancellationException && e.message == USER_CANCEL_MESSAGE) {
                                        println("--- [catch] Job: $thisJob (当前: $apiJob), Error: ${e::class.simpleName} - ${e.message}");
                                        val isUserCancel = e is CancellationException && e.message == USER_CANCEL_MESSAGE;
                                        if (!isUserCancel) { // 非用户手动取消的错误才显示和处理
                                            val errorMsg = ERROR_VISUAL_PREFIX + (e.message ?: "未知流错误");
                                            withContext(Dispatchers.Main) {
                                                try {
                                                    // 找到对应的 AI 消息并更新为错误状态
                                                    val idx = messages.indexOfFirst { it.id == aiMessageId };
                                                    // 确保目标消息存在且仍然是当前流式 ID
                                                    if (idx != -1 && currentStreamingAiMessageId == aiMessageId) {
                                                        val msg = messages[idx]
                                                        messages[idx] = msg.copy(
                                                            text = if (msg.text == "...") errorMsg else msg.text + "\n\n" + errorMsg,
                                                            contentStarted = true, // 即使是错误，也算内容开始了
                                                            isError = true,
                                                            reasoning = null // 错误时清空 reasoning
                                                        );
                                                        expandedReasoningStates.remove(aiMessageId); // 错误时折叠 Reasoning
                                                        println("--- [catch] 更新错误消息 UI 成功 (ID: $aiMessageId)");
                                                        try {
                                                            // 滚动到底部显示错误信息 (强制)
                                                            if (listState.layoutInfo.totalItemsCount > 0) {
                                                                listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1, scrollOffset = Int.MAX_VALUE)
                                                                userScrolledAway = false // 强制滚动到底部，重置标志
                                                                println("userScrolledAway reset to false after catch scroll.")
                                                            }
                                                        } catch (scrE: Exception) {
                                                            println("catch 后滚动错误: ${scrE.message}")
                                                        }
                                                    } else {
                                                        println("--- [catch] 错误发生时找不到目标消息 (ID: $aiMessageId) 或 ID 不匹配。")
                                                    }
                                                } catch (listE: Exception) {
                                                    println("--- [catch] 更新错误消息时访问列表出错: ${listE.message}")
                                                }
                                                // 显示 Snackbar 提示用户
                                                snackbarHostState.showSnackbar(
                                                    "API 请求失败: ${e.message ?: "未知错误"}",
                                                    duration = SnackbarDuration.Long
                                                )
                                            }
                                        } else {
                                            println("--- [catch] 用户取消异常，由 onCompletion 处理。")
                                        }
                                    } else {
                                        println("--- [catch] WARN: Job $thisJob 已过时，忽略错误事件 ${e::class.simpleName}.")
                                    }
                                }
                                .onCompletion { t ->
                                    val completingJob = thisJob // 捕获当前 Job
                                    withContext(Dispatchers.Main) {
                                        val reason = when {
                                            t is CancellationException && t.message == USER_CANCEL_MESSAGE -> "用户取消";
                                            t is CancellationException -> "内部取消 (${t.message})";
                                            t != null -> "错误 (${t::class.simpleName})";
                                            else -> "正常完成"
                                        }
                                        println("--- [onCompletion] Job: $completingJob (当前: $apiJob), 原因: $reason, 操作目标MsgID: $aiMessageId")

                                        try {
                                            val finalIndex = messages.indexOfFirst { it.id == aiMessageId };
                                            var messageRemoved = false;

                                            if (finalIndex != -1) {
                                                var msg = messages[finalIndex];
                                                val wasCancelled = t is CancellationException;

                                                // 如果是空的占位符且是取消，并且不是错误，移除它
                                                if (msg.text == "..." && !msg.contentStarted && msg.reasoning.isNullOrBlank() && !msg.isError && wasCancelled) {
                                                    println("--- [onCompletion] 取消了空占位符 (ID: $aiMessageId)，移除消息。");
                                                    messages.removeAt(finalIndex);
                                                    expandedReasoningStates.remove(aiMessageId);
                                                    messageRemoved = true;
                                                } else {
                                                    // 如果有内容或 Reasoning，或者非空占位符被取消，或者正常完成/带错误完成
                                                    when {
                                                        wasCancelled && !msg.isError && !msg.text.contains("(已中断)") -> {
                                                            // 被取消，且之前没有错误标记，添加中断标记
                                                            msg = msg.copy(text = msg.text + " (已中断)");
                                                            println("--- [onCompletion] 更新消息 (ID: $aiMessageId) 状态为 '已中断'.")
                                                        }
                                                        (t != null && !msg.isError) -> {
                                                            // 完成时有错误，且消息本身不是错误状态（避免重复错误信息）
                                                            val err = ERROR_VISUAL_PREFIX + (t.message ?: "Completion Error");
                                                            msg = msg.copy(
                                                                text = if (msg.text == "...") err else msg.text + "\n\n" + err,
                                                                contentStarted = true,
                                                                isError = true,
                                                                reasoning = null // 错误时清空 reasoning
                                                            );
                                                            expandedReasoningStates.remove(aiMessageId); // 错误时折叠 Reasoning
                                                            println("--- [onCompletion] 更新消息 (ID: $aiMessageId) 状态为错误.");
                                                        }
                                                        t == null -> {
                                                            // 正常完成，确保 reasoning 是折叠状态
                                                            if (expandedReasoningStates[aiMessageId] == true) {
                                                                expandedReasoningStates[aiMessageId] = false
                                                            }
                                                            println("--- [onCompletion] 消息 (ID: $aiMessageId) 正常完成，确保思考过程折叠.")
                                                        }
                                                        else -> {
                                                            // 其他情况，如本身已经是错误状态，或取消的非空消息，不修改文本
                                                            println("--- [onCompletion] 消息 (ID: $aiMessageId) 完成处理，无需修改文本或已是错误状态.")
                                                        }
                                                    };
                                                    // 如果消息没有被移除，就更新它
                                                    val updateIdx = messages.indexOfFirst { it.id == aiMessageId };
                                                    if (updateIdx != -1) messages[updateIdx] = msg
                                                    else println("--- [onCompletion] Error: 更新最终状态时找不到消息 (可能已被移除)！")
                                                }
                                            } else {
                                                println("--- [onCompletion] 消息 (ID: $aiMessageId) 完成时不存在 (可能已被取消移除)。");
                                                expandedReasoningStates.remove(aiMessageId); // 移除对应的 reasoning 状态
                                            }

                                            // 完成后滚动到底部，无论消息是否被移除（如果列表非空）(强制)
                                            if (messages.isNotEmpty()) {
                                                coroutineScope.launch {
                                                    delay(50) // 短暂延迟等待 UI 更新
                                                    try {
                                                        val lastIndex = messages.lastIndex
                                                        if (lastIndex >= 0) {
                                                            println("--- [onCompletion] 滚动到底部 (forced).")
                                                            listState.animateScrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE)
                                                            userScrolledAway = false // 强制滚动到底部，重置标志
                                                            println("userScrolledAway reset to false after onCompletion scroll.")
                                                        }
                                                    } catch (e: Exception) {
                                                        println("onCompletion 滚动错误: ${e.message}")
                                                    }
                                                }
                                            }

                                        } catch (e: Exception) {
                                            println("!!! [onCompletion] 内部处理错误: ${e.message}")
                                        } finally {
                                            // ***** 确保状态重置 (这里的重置作为最终保障) *****
                                            println("--- [onCompletion] finally: 准备重置状态 (Job: $completingJob, 当前: $apiJob)");
                                            // 只有当前正在运行的 Job 完成时，才重置全局 API 调用状态
                                            // 如果 Job 在 cancelCurrentApiJob 中已经设为 null，这里的判断会是 false，不会重复重置
                                            if (apiJob == completingJob) {
                                                println("--- [onCompletion] finally: 执行最终重置 isApiCalling=false, apiJob=null, currentStreamingAiMessageId=null");
                                                isApiCalling = false; // 重置整体 API 调用状态
                                                apiJob = null;
                                                currentStreamingAiMessageId = null; // 清理流式 ID
                                            } else {
                                                println("--- [onCompletion] finally: Job $completingJob 已过时，不重置全局状态。")
                                                // 确保即使 Job 过时，如果 currentStreamingAiMessageId 匹配，也清理掉
                                                if (currentStreamingAiMessageId == aiMessageId) {
                                                    println("--- [onCompletion] finally: Job过时但ID匹配，清理 currentStreamingAiMessageId.");
                                                    currentStreamingAiMessageId = null;
                                                }
                                            }
                                        }
                                    }
                                } // onCompletion End
                                .collect { chunk -> // ***** 处理流式块 *****
                                    // 只有当 Job 和流式消息 ID 都匹配时，才处理这个块
                                    if (apiJob == thisJob && currentStreamingAiMessageId == aiMessageId) {
                                        withContext(Dispatchers.Main) {
                                            val idx = messages.indexOfFirst { it.id == aiMessageId }
                                            if (idx != -1) {
                                                // 1. 更新消息内容
                                                val msg = messages[idx];
                                                var uText = msg.text;
                                                var uRea = msg.reasoning;
                                                var uStart = msg.contentStarted;
                                                var uErr = msg.isError;
                                                var reasoningStarted = expandedReasoningStates[aiMessageId] ?: false

                                                when (chunk.type) {
                                                    "reasoning" -> {
                                                        if (!reasoningStarted) {
                                                            reasoningStarted = true; expandedReasoningStates[aiMessageId] = true
                                                        }; uRea = (uRea ?: "") + chunk.text
                                                        // println("--- [collect] Received reasoning chunk for $aiMessageId") // 太频繁，减少日志
                                                    };
                                                    "content" -> {
                                                        if (uText == "...") uText = chunk.text.trimStart() else uText += chunk.text;
                                                        uStart = true;
                                                        // 收到内容时，默认折叠 reasoning
                                                        if (expandedReasoningStates[aiMessageId] == true) expandedReasoningStates[aiMessageId] = false
                                                        // println("--- [collect] Received content chunk for $aiMessageId") // 太频繁，减少日志
                                                    };
                                                    "error" -> {
                                                        // 收到错误块，立即标记错误并取消流
                                                        expandedReasoningStates.remove(aiMessageId); // 错误时折叠 reasoning
                                                        val errTxt = ERROR_VISUAL_PREFIX + chunk.text;
                                                        uText = if (msg.text == "...") errTxt else msg.text + "\n\n" + errTxt;
                                                        uStart = true;
                                                        uErr = true;
                                                        println("--- [collect] 收到错误块 (ID: $aiMessageId): ${chunk.text}");
                                                        // Snackbar 在 catch块中处理更统一，这里不重复
                                                        // 通过抛出异常进入 catch/onCompletion 处理流中断
                                                        throw RuntimeException("Received error chunk: ${chunk.text}")
                                                    };
                                                    else -> println("--- [collect] 未知块类型: ${chunk.type} for $aiMessageId")
                                                }

                                                // 更新列表中的消息对象 (Compose ListState 需要对象引用不变或 copy)
                                                val updateIdx = messages.indexOfFirst { it.id == aiMessageId };
                                                if (updateIdx != -1) {
                                                    messages[updateIdx] = msg.copy(
                                                        text = uText,
                                                        reasoning = uRea,
                                                        contentStarted = uStart,
                                                        isError = uErr
                                                    )
                                                } else {
                                                    println("--- [collect] Error: 更新块时找不到消息 (ID: $aiMessageId)！");
                                                    // 如果找不到消息，可能是已经被取消移除了，此时应该停止处理这个流
                                                    throw CancellationException("Target message lost during collect")
                                                }

                                                // --- ***** 优化后的实时滚动（仅当用户未手动滚动时） ***** ---
                                                if (!userScrolledAway) { // 检查用户是否已滚动离开底部
                                                    coroutineScope.launch { // 启动新协程处理滚动，避免阻塞 UI
                                                        delay(20) // 短暂延迟，等待布局计算和 recomposition
                                                        try {
                                                            val lastIndex = listState.layoutInfo.totalItemsCount - 1
                                                            // 确保列表不为空，并且最后一项是我们要更新的 AI 消息
                                                            if (lastIndex >= 0 && messages.getOrNull(lastIndex)?.id == aiMessageId) {
                                                                // 直接、平滑地滚动到最后一项的底部
                                                                listState.animateScrollToItem(
                                                                    index = lastIndex,
                                                                    scrollOffset = Int.MAX_VALUE
                                                                )
                                                                // println("--- [collect] Auto-scrolling smoothly to index $lastIndex with offset ---") // 太频繁，减少日志
                                                            } else if (lastIndex >= 0 && messages.isNotEmpty() && messages.getOrNull(lastIndex)?.id != aiMessageId) {
                                                                // 理论上这里不会发生，因为上面已经做了 Job 和 ID 匹配过滤
                                                                println("--- [collect] WARNING: Last message ID ${messages.getOrNull(lastIndex)?.id} != current AI ID $aiMessageId. Skipping auto-scroll.")
                                                            } else {
                                                                // println("--- [collect] No items to scroll to. ---") // 太频繁，减少日志
                                                            }
                                                        } catch (e: Exception) {
                                                            println("优化后的实时滚动错误: ${e.message}")
                                                        }
                                                    }
                                                } else {
                                                    // println("--- [collect] User scrolled away, skipping auto-scroll ---") // Optional print
                                                    // 此时 showScrollToBottomButton 会变为 true (在 ChatScreenContent 中计算)
                                                }
                                                // --- ***** 滚动修改结束 ***** ---

                                            } else {
                                                println("--- [collect] 消息 (ID: $aiMessageId) 处理块时丢失。");
                                                // 如果目标消息在处理流时丢失，说明状态不同步，应该立即取消 Job
                                                throw CancellationException("Target message lost during collect")
                                            }
                                        } // withContext(Dispatchers.Main) End
                                    } else {
                                        println("--- [collect] WARN: Job $thisJob 或 MsgID $aiMessageId 已过时或不匹配当前活跃任务，忽略块。");
                                        // 如果 Job 或 ID 不匹配，说明这是旧的流，应该停止处理
                                        throw CancellationException("Job outdated on collect")
                                    }
                                } // collect End
                        } // launch End
                    },
                    onCancelAPICall = { // 取消 API 调用
                        println("Cancel button clicked in Input Area.")
                        cancelCurrentApiJob(USER_CANCEL_MESSAGE) // 直接调用修改后的取消函数
                    },
                    snackbarHostState = snackbarHostState,
                    onToggleReasoningExpand = { messageId -> // 展开/折叠 Reasoning
                        val current = expandedReasoningStates[messageId] ?: false
                        expandedReasoningStates[messageId] = !current
                        println("Toggled reasoning expand for $messageId to ${!current}")
                        // 如果是展开，尝试滚动到该消息使其顶部可见
                        if (!current) {
                            coroutineScope.launch {
                                delay(50); // 短暂延迟等待状态更新和 recomposition
                                val index = messages.indexOfFirst { it.id == messageId };
                                if (index != -1) {
                                    try {
                                        // 滚动到该消息，使其顶部可见
                                        listState.animateScrollToItem(index) // 这里不需要滚到底部，只需滚到该项
                                    } catch (e: Exception) {
                                        println("Reasoning scroll error: ${e.message}")
                                    }
                                }
                            }
                        }
                    },
                    // *** 传递 API 状态变量 ***
                    currentStreamingAiMessageId = currentStreamingAiMessageId,
                    isApiCallingOverall = isApiCalling
                ) // ChatScreenContent 调用结束
            } // AppView.CurrentChat 结束

            // --- 历史记录视图 ---
            AppView.HistoryList -> {
                // BackHandler 在 HistoryList 启用，返回 ChatView
                BackHandler(enabled = true) {
                    println("Back gesture/button pressed on History screen");
                    // 从历史记录返回时，取消可能的API调用，并返回聊天视图
                    cancelCurrentApiJob("返回聊天视图 (Back Gesture)");
                    navigateBackToChat() // 调用通用返回函数
                }
                // 进入历史视图时，取消当前可能的API调用
                LaunchedEffect(Unit) { cancelCurrentApiJob("进入历史视图") }

                HistoryScreenContent(
                    historicalConversations = historicalConversations,
                    onNavigateBack = {
                        println("History top bar back button clicked.");
                        // 从历史记录返回时，取消可能的API调用，并返回聊天视图
                        cancelCurrentApiJob("返回聊天视图 (Top Bar)");
                        navigateBackToChat() // 调用通用返回函数
                    },
                    onConversationClick = { conversation, index ->
                        println("Clicked history item at index $index.");
                        // 加载历史记录时，取消当前可能的API调用
                        cancelCurrentApiJob("加载历史记录");
                        // 清空当前聊天状态，加载历史对话
                        messages.clear();
                        messages.addAll(conversation);
                        expandedReasoningStates.clear(); // 清空 reasoning 状态
                        text = "";
                        loadedHistoryIndex = index; // 记录加载的历史记录索引
                        // 清空用于“返回新对话”的临时状态，因为我们现在加载了一个具体的历史对话
                        tempMessagesBeforeHistory = null;
                        tempLoadedIndexBeforeHistory = null;
                        userScrolledAway = false // 加载历史并切换后，默认认为用户在底部
                        println("userScrolledAway reset to false on history load.")
                        currentView = AppView.CurrentChat; // 切换回聊天视图
                    },
                    onDeleteConversation = { indexToDelete ->
                        println("Delete requested for history item at index $indexToDelete.");
                        if (indexToDelete >= 0 && indexToDelete < historicalConversations.size) {
                            // 删除历史记录项
                            val deletedConversation = historicalConversations.removeAt(indexToDelete);
                            println("AppContent: Deleted history item at index $indexToDelete (${deletedConversation.size} messages).")
                            // 如果删除的是当前加载的历史记录，需要重置相关状态
                            if (loadedHistoryIndex == indexToDelete) {
                                loadedHistoryIndex = null // 当前加载的历史记录被删除了
                                println("AppContent: Deleted currently loaded history $indexToDelete, resetting loadedHistoryIndex.")
                                // 理论上在 HistoryList 视图时 messages 是空的，loadedHistoryIndex 只是一个标记，
                                // 但这里还是安全地处理一下。如果未来允许在聊天中删除当前加载的历史，这里逻辑要复杂些。
                            } else if (loadedHistoryIndex != null && loadedHistoryIndex!! > indexToDelete) {
                                // 如果当前加载的历史记录在被删除项的下方，其索引需要减一
                                loadedHistoryIndex = loadedHistoryIndex!! - 1
                                println("AppContent: Decremented loadedHistoryIndex to $loadedHistoryIndex.")
                            } else if (loadedHistoryIndex != null && loadedHistoryIndex!! < indexToDelete) {
                                // 如果当前加载的历史记录在被删除项的上方，索引不变
                                println("AppContent: Loaded history index $loadedHistoryIndex is above deleted index $indexToDelete, no change.")
                            }

                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("已删除一条历史记录", duration = SnackbarDuration.Short)
                            }
                        } else {
                            println("Delete request invalid: Index $indexToDelete out of bounds.")
                        }
                    },
                    onNewChatClick = {
                        println("FAB clicked: Starting new chat.");
                        // 开始新对话时，取消当前可能的API调用
                        cancelCurrentApiJob("新建对话");
                        // 清空当前聊天状态
                        messages.clear();
                        text = "";
                        loadedHistoryIndex = null; // 新对话没有加载历史记录索引
                        expandedReasoningStates.clear(); // 清空 reasoning 状态
                        // 清空用于“返回新对话”的临时状态
                        tempMessagesBeforeHistory = null;
                        tempLoadedIndexBeforeHistory = null;
                        userScrolledAway = false // 开始新对话，默认认为用户在底部
                        println("userScrolledAway reset to false on new chat.")
                        currentView = AppView.CurrentChat; // 切换回聊天视图
                    }
                ) // HistoryScreenContent 调用结束
            } // AppView.HistoryList 结束
        } // when 结束
    } // Box 结束

    // --- 设置对话框 ---
    if (showSettingsDialog) {
        LaunchedEffect(showSettingsDialog) {
            if (showSettingsDialog) {
                println("Settings dialog shown, cancelling API job."); cancelCurrentApiJob("打开设置对话框")
            }
        }
        SettingsDialog( // --- SettingsDialog 调用参数 ---
            savedConfigs = apiConfigs.toList(),
            selectedConfig = selectedApiConfig,
            onDismissRequest = { showSettingsDialog = false },
            onAddConfig = { configToAdd ->
                // 检查是否存在完全相同的配置（地址、key、model、provider）
                val contentExists = apiConfigs.any { existingConfig ->
                    existingConfig.address.trim().equals(configToAdd.address.trim(), ignoreCase = true) &&
                            existingConfig.key.trim() == configToAdd.key.trim() && // Key 通常是区分大小写的
                            existingConfig.model.trim().equals(configToAdd.model.trim(), ignoreCase = true) &&
                            existingConfig.provider.trim().equals(configToAdd.provider.trim(), ignoreCase = true)
                };

                if (!contentExists) {
                    // 如果要添加的配置 ID 已经存在（这通常不会发生，除非从外部加载），生成新 ID
                    val finalConfig = if (apiConfigs.any { it.id == configToAdd.id }) configToAdd.copy(id = UUID.randomUUID().toString()) else configToAdd;
                    apiConfigs.add(finalConfig);
                    // 如果没有选中配置，或者这是第一个配置，则选中它
                    if (selectedApiConfig == null || apiConfigs.size == 1) {
                        selectedApiConfig = finalConfig;
                        println("AppContent: Added and selected new config ${finalConfig.model}")
                    } else {
                        println("AppContent: Added new config ${finalConfig.model}, but kept existing selection.")
                    }
                    coroutineScope.launch { snackbarHostState.showSnackbar("配置 '${finalConfig.model}' 已保存", duration = SnackbarDuration.Short) }
                } else {
                    coroutineScope.launch { snackbarHostState.showSnackbar("配置已存在", duration = SnackbarDuration.Short) }
                }
            },
            onUpdateConfig = { configToUpdate ->
                val index = apiConfigs.indexOfFirst { it.id == configToUpdate.id };
                if (index != -1) {
                    // 只有当配置内容实际发生变化时才更新并提示
                    if (apiConfigs[index] != configToUpdate) {
                        apiConfigs[index] = configToUpdate;
                        // 如果更新的是当前选中的配置，也要更新 selectedApiConfig
                        if (selectedApiConfig?.id == configToUpdate.id) selectedApiConfig = configToUpdate;
                        println("AppContent: Updated config ${configToUpdate.model}")
                        coroutineScope.launch { snackbarHostState.showSnackbar("配置 '${configToUpdate.model}' 已更新", duration = SnackbarDuration.Short) }
                    } else {
                        println("AppContent: Config ${configToUpdate.model} content unchanged.")
                    }
                } else {
                    println("AppContent: Update failed, config with ID ${configToUpdate.id} not found.")
                    coroutineScope.launch { snackbarHostState.showSnackbar("更新失败：未找到配置", duration = SnackbarDuration.Short) }
                }
            },
            onDeleteConfig = { configToDelete ->
                val index = apiConfigs.indexOfFirst { it.id == configToDelete.id };
                if (index != -1) {
                    val wasSelected = selectedApiConfig?.id == configToDelete.id;
                    val name = apiConfigs[index].model;

                    if (wasSelected) {
                        // 如果删除的是选中配置，先取消 API 调用，然后重新选择
                        cancelCurrentApiJob("删除选中的配置")
                        selectedApiConfig = null // 先清空选中
                        // 删除配置
                        apiConfigs.removeAt(index);
                        // 尝试选择下一个配置（如果有）
                        selectedApiConfig = apiConfigs.firstOrNull()
                        println("AppContent: Deleted selected config '$name'. New selection: ${selectedApiConfig?.model}")

                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("已删除选中配置 '$name'", duration = SnackbarDuration.Short);
                            delay(250); // 短暂延迟
                            if (selectedApiConfig == null) {
                                snackbarHostState.showSnackbar("请添加API配置", duration = SnackbarDuration.Long)
                            } else {
                                snackbarHostState.showSnackbar("已自动选择: ${selectedApiConfig?.model} (${selectedApiConfig?.provider})", duration = SnackbarDuration.Short)
                            }
                        }
                    } else {
                        // 删除非选中配置
                        apiConfigs.removeAt(index);
                        println("AppContent: Deleted config '$name'")
                        coroutineScope.launch { snackbarHostState.showSnackbar("配置 '$name' 已删除", duration = SnackbarDuration.Short) }
                    }
                } else {
                    println("AppContent: Delete failed, config with ID ${configToDelete.id} not found.")
                    coroutineScope.launch { snackbarHostState.showSnackbar("删除失败：未找到配置", duration = SnackbarDuration.Short) }
                }
            },
            onClearAll = {
                if (apiConfigs.isNotEmpty()) {
                    cancelCurrentApiJob("清除所有配置");
                    apiConfigs.clear();
                    selectedApiConfig = null;
                    println("AppContent: Cleared all configs.")
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("所有配置已清除", duration = SnackbarDuration.Short);
                        delay(250);
                        snackbarHostState.showSnackbar("请添加API配置", duration = SnackbarDuration.Long)
                    }
                } else {
                    println("AppContent: No configs to clear.")
                    coroutineScope.launch { snackbarHostState.showSnackbar("没有配置可清除", duration = SnackbarDuration.Short) }
                }
            },
            onSelectConfig = { config ->
                if (selectedApiConfig?.id != config.id) {
                    cancelCurrentApiJob("切换选中配置"); // 切换配置时取消当前调用
                    selectedApiConfig = config;
                    println("AppContent: Selected config ${config.model}")
                    showSettingsDialog = false; // 选中后关闭对话框
                    coroutineScope.launch { snackbarHostState.showSnackbar("已选择: ${config.model} (${config.provider})", duration = SnackbarDuration.Short) }
                } else {
                    // 点击当前选中配置，也关闭对话框
                    println("AppContent: Config ${config.model} already selected, closing dialog.")
                    showSettingsDialog = false
                }
            }
        ) // SettingsDialog 调用结束
    } // if (showSettingsDialog) 结束
} // AppContent Composable 结束