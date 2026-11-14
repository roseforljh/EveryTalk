package com.android.everytalk.statecontroller

import android.os.Looper
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.MessageQuoteReference
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.data.DataClass.VoiceBackendConfig
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.util.AppLogger
import com.android.everytalk.util.ScrollController
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
 
 class ViewModelStateHolder {
    // 🎯 Streaming message state manager for efficient UI updates
    // Provides StateFlow-based observation of streaming content
    // Requirements: 1.4, 3.4
    val streamingMessageStateManager = StreamingMessageStateManager()

    // 🎯 StreamingBuffer mapping (one buffer per message ID)
    private val streamingBuffers = mutableMapOf<String, StreamingBuffer>()

    // 新增：记录每条流式消息已提交到 UI 的长度，用于只追加增量，避免重复全量赋值造成卡顿
    private val streamingReasoningStates = mutableMapOf<String, MutableStateFlow<String>>()

    // 🎯 记录已完成同步的消息ID，避免重复同步导致UI抖动
    private val syncedMessageIds = mutableSetOf<String>()
    
    // CoroutineScope for StreamingBuffer operations (will be initialized from AppViewModel)
    private var bufferCoroutineScope: CoroutineScope? = null
    
    // 🎯 Task 11: Performance monitoring - Memory usage tracking
    // Track memory usage during long streaming sessions
    // Requirements: 1.4, 3.4
    private var lastMemoryCheckTime = 0L
    private val memoryCheckInterval = 10000L // Check every 10 seconds during streaming
    
    // Dirty flags to track conversation changes
    val isTextConversationDirty = MutableStateFlow(false)
    val isImageConversationDirty = MutableStateFlow(false)
    lateinit var scrollController: ScrollController
     val drawerState: DrawerState = DrawerState(initialValue = DrawerValue.Closed)
    
    // 置顶集合状态：文本与图像各自独立
    val pinnedTextConversationIds = MutableStateFlow<Set<String>>(emptySet())
    val pinnedImageConversationIds = MutableStateFlow<Set<String>>(emptySet())
    
    // 分组状态
    val conversationGroups = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /** 分组创建新会话时默认使用的服务器，键为分组名，值为 Computer ID。 */
    val conversationGroupComputerIds = MutableStateFlow<Map<String, String>>(emptyMap())

    // 分组总区域展开/折叠状态（默认折叠）
    val isGroupSectionExpanded = MutableStateFlow(false)

    // 分组展开/折叠状态（默认全部折叠）
    val expandedGroups = MutableStateFlow<Set<String>>(emptySet())
    
    @Volatile
    private var isTextHistoryReadyForStateCleanup = false
    @Volatile
    private var isImageHistoryReadyForStateCleanup = false
    
    fun markTextHistoryReadyForStateCleanup() {
        isTextHistoryReadyForStateCleanup = true
    }

    fun markImageHistoryReadyForStateCleanup() {
        isImageHistoryReadyForStateCleanup = true
    }

    fun isConversationStateCleanupReady(): Boolean =
        isTextHistoryReadyForStateCleanup && isImageHistoryReadyForStateCleanup

    fun getStreamingReasoning(messageId: String): StateFlow<String> {
        return streamingReasoningStates.getOrPut(messageId) { MutableStateFlow("") }.asStateFlow()
    }

    /**
     * Initialize coroutine scope for StreamingBuffer operations
     * Must be called from AppViewModel during initialization
     */
    fun initializeBufferScope(scope: CoroutineScope) {
        bufferCoroutineScope = scope
    }

    val _text = MutableStateFlow("")
    val messageEditSession = MutableStateFlow<com.android.everytalk.statecontroller.controller.conversation.MessageEditSession?>(null)
    // 页面只是选择当前会话的列表；发送准备持有旧列表时，切页不能清空或替换它的内容。
    val messages: SnapshotStateList<Message>
        get() = textMessagesForConversation(_currentConversationId.value)
    val imageGenerationMessages: SnapshotStateList<Message> = mutableStateListOf()

    // 分离的API状态
    val _isTextApiCalling = MutableStateFlow(false)
    /** 停止按钮发出远端取消后，等待 VPS 确认期间保持固定尺寸加载状态。 */
    val _isRemoteCancellationPending = MutableStateFlow(false)
    val _isImageApiCalling = MutableStateFlow(false)
    val _isMcpEnabledForNextRequest = MutableStateFlow(false)
    val _isAgentEnabled = MutableStateFlow(false)
    val _isAgentPreparing = MutableStateFlow(false)
    val agentActionGeneration = AtomicLong(0)

    val _lastSentUserMessageId = MutableStateFlow<String?>(null)
    val _lastSentImageUserMessageId = MutableStateFlow<String?>(null)

    // 分离的流式消息ID
    val _currentTextStreamingAiMessageId = MutableStateFlow<String?>(null)
    val _currentImageStreamingAiMessageId = MutableStateFlow<String?>(null)
    /** 当前会话在同账号另一台设备上运行中的回复（消息 ID 已换成本机 ID），本机没在运行时才显示。 */
    val _remoteLiveRun = MutableStateFlow<RemoteLiveRun?>(null)

// 仅用于冻结渲染的镜像；AgentRunPauseController 才是 Safe Pause 的运行事实。
val _isStreamingPaused = MutableStateFlow(false)
    // 分离的API Job
    var textApiJob: Job? = null
    var imageApiJob: Job? = null
    /** 任务归属固定到会话；上面的单值只供当前页面显示，不能用于判断后台任务是否失效。 */
    val textApiJobs: MutableMap<String, Job> = ConcurrentHashMap()
    /** textApiMessageIds 每次增删都加一，后台会话（如 Fork）的运行状态靠它刷新界面。 */
    val textApiRunVersion = MutableStateFlow(0L)
    val textApiMessageIds: MutableMap<String, String> = object : ConcurrentHashMap<String, String>() {
        private fun bump() = textApiRunVersion.update { it + 1 }
        override fun put(key: String, value: String): String? = super.put(key, value).also { bump() }
        override fun putIfAbsent(key: String, value: String): String? = super.putIfAbsent(key, value).also { if (it == null) bump() }
        override fun remove(key: String): String? = super.remove(key).also { if (it != null) bump() }
        override fun remove(key: String, value: String): Boolean = super.remove(key, value).also { if (it) bump() }
        override fun clear() { super.clear(); bump() }
    }
    val textMessageConversationIds: MutableMap<String, String> = ConcurrentHashMap()
    val preparingTextConversationIds = MutableStateFlow<Set<String>>(emptySet())
    private val textConversationMessages: MutableMap<String, SnapshotStateList<Message>> = ConcurrentHashMap()

    /** 离开页面前保存同一份最新消息；不结束缓冲，也不取消实际运行。只能在主线程调用。 */
    fun cacheTextConversation() {
        val sessionId = _currentConversationId.value
        if (sessionId.isBlank()) return
        messages.forEach { textMessageConversationIds[it.id] = sessionId }
    }

    fun textMessagesForConversation(sessionId: String): SnapshotStateList<Message> =
        textConversationMessages.getOrPut(sessionId) { mutableStateListOf() }

    /**
     * 页面实际持有过的文本消息。整页保存按“缺席即删除”清理时只能删这些消息；
     * 同步在页面打开后才写入、页面从未见过的消息不在其中，旧快照不能把它们删掉并同步删除到所有设备。
     */
    val seenTextMessageIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun rememberSeenTextMessages(messages: List<Message>) {
        messages.forEach { seenTextMessageIds += it.id }
    }

    /** 活跃任务的列表就是最新快照；历史加载只能补缺失消息，不能覆盖已收到的增量。 */
    fun loadTextConversationMessages(sessionId: String, loaded: List<Message>) {
        val target = textMessagesForConversation(sessionId)
        if (target.isNotEmpty() && textApiMessageIds.containsKey(sessionId)) {
            val current = target.associateBy { it.id }
            val loadedIds = loaded.mapTo(mutableSetOf()) { it.id }
            val merged = loaded.map { current[it.id] ?: it } + target.filterNot { it.id in loadedIds }
            target.clear()
            target.addAll(merged)
        } else {
            target.clear()
            target.addAll(loaded)
        }
        target.forEach { textMessageConversationIds[it.id] = sessionId }
        rememberSeenTextMessages(target)
    }

    /** 删除和换账号后释放缓存；普通切页保留原列表供后台任务使用。 */
    fun clearTextConversationCache(sessionId: String? = null) {
        if (sessionId == null) {
            textConversationMessages.clear()
            textMessageConversationIds.clear()
            migratedConversationIds.clear()
            seenTextMessageIds.clear()
        } else {
            textConversationMessages.remove(sessionId)
            textMessageConversationIds.entries.removeAll { it.value == sessionId }
        }
    }

    /** 只按消息实际归属查找；删除/切页后的迟到事件不能回落到当前会话。 */
    fun textMessagesForMessage(messageId: String): SnapshotStateList<Message> {
        textMessageConversationIds[messageId]?.let { return textMessagesForConversation(it) }
        val owner = textConversationMessages.entries.firstOrNull { (_, list) -> list.any { it.id == messageId } }
            ?: return mutableStateListOf()
        textMessageConversationIds[messageId] = owner.key
        return owner.value
    }

    /** 历史装载后优先合并活跃任务的内存快照，不以较旧的 Room 草稿覆盖实时增量。 */
    fun restoreTextConversationUi() {
        val sessionId = _currentConversationId.value
        messages.forEach { textMessageConversationIds[it.id] = sessionId }
        textApiJob = textApiJobs[sessionId]
        _currentTextStreamingAiMessageId.value = textApiMessageIds[sessionId]
        _isTextApiCalling.value = _currentTextStreamingAiMessageId.value != null
    }

    /** 临时会话 ID → 首次落库后的稳定 ID。发送入口固定的旧 ID 经此解析到当前归属。 */
    val migratedConversationIds: MutableMap<String, String> = ConcurrentHashMap()

    fun resolveConversationId(sessionId: String): String {
        var resolved = sessionId
        repeat(8) { resolved = migratedConversationIds[resolved] ?: return resolved }
        return resolved
    }

    /** 列表、消息归属和运行登记随会话 ID 迁移，进行中的发送和后台任务不丢失。 */
    fun migrateTextConversation(fromId: String, toId: String) {
        if (fromId.isBlank() || fromId == toId) return
        textConversationMessages.remove(fromId)?.let { textConversationMessages[toId] = it }
        textMessageConversationIds.replaceAll { _, sessionId -> if (sessionId == fromId) toId else sessionId }
        textApiJobs.remove(fromId)?.let { textApiJobs.putIfAbsent(toId, it) }
        textApiMessageIds.remove(fromId)?.let { textApiMessageIds.putIfAbsent(toId, it) }
        preparingTextConversationIds.update { if (fromId in it) it - fromId + toId else it }
        if (_currentConversationId.value == fromId) _currentConversationId.value = toId
        _forkConversationIds.update { links ->
            if (links.none { it.key == fromId || it.value == fromId }) links
            else links.entries.associate { (parent, fork) ->
                (if (parent == fromId) toId else parent) to (if (fork == fromId) toId else fork)
            }
        }
    }

    fun registerTextJob(sessionId: String, messageId: String, job: Job) {
        @Suppress("NAME_SHADOWING") val sessionId = resolveConversationId(sessionId)
        textMessageConversationIds[messageId] = sessionId
        textApiJobs[sessionId] = job
        textApiMessageIds[sessionId] = messageId
        if (_currentConversationId.value == sessionId) {
            textApiJob = job
            _currentTextStreamingAiMessageId.value = messageId
            _isTextApiCalling.value = true
        }
    }

    /** 比较任务身份后释放，旧请求的 finally 不能清理同会话后来开始的请求。 */
    fun completeTextJob(sessionId: String, messageId: String, job: Job, keepWaiting: Boolean = false) {
        @Suppress("NAME_SHADOWING") val sessionId = resolveConversationId(sessionId)
        if (!textApiJobs.remove(sessionId, job)) return
        preparingTextConversationIds.update { it - sessionId }
        if (!keepWaiting) textApiMessageIds.remove(sessionId, messageId)
        // 可见槽属于这个 Job，或可见消息就是它且没有别的任务占用时，才归位当前页面。
        if (textApiJob === job || (textApiJob == null && _currentTextStreamingAiMessageId.value == messageId)) {
            textApiJob = null
            _isTextApiCalling.value = keepWaiting
            _currentTextStreamingAiMessageId.value = messageId.takeIf { keepWaiting }
        }
    }
    /**
     * 图像生成任务按会话保存。页面切换只会更换可见会话，不应取消其他会话的任务。
     * key 是稳定会话 ID，value 是该会话当前正在运行的生成 Job。
     */
    val imageApiJobs: MutableMap<String, Job> = ConcurrentHashMap()
    /** 每个图像会话当前生成中的 AI 消息，用于切换回来时恢复停止按钮和加载状态。 */
    val imageApiMessageIds: MutableMap<String, String> = ConcurrentHashMap()

    // 分离的推理完成状态
    val textReasoningCompleteMap: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val imageReasoningCompleteMap: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    
    // 会话ID -> 使用的API配置ID的映射
    val conversationApiConfigIds: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
    val conversationFunctionToggleStates: MutableStateFlow<Map<String, ConversationFunctionToggleState>> =
        MutableStateFlow(emptyMap())

    fun getCurrentConversationFunctionToggleState(): ConversationFunctionToggleState {
        return conversationFunctionToggleStates.value[_currentConversationId.value] ?: ConversationFunctionToggleState()
    }

    fun getCurrentImageConversationFunctionToggleState(): ConversationFunctionToggleState {
        return conversationFunctionToggleStates.value[_currentImageGenerationConversationId.value]
            ?: ConversationFunctionToggleState()
    }

    fun updateCurrentConversationFunctionToggleState(
        update: (ConversationFunctionToggleState) -> ConversationFunctionToggleState
    ) {
        updateConversationFunctionToggleState(_currentConversationId.value, update)
    }

    /**
     * 更新指定会话的功能开关状态。
     * 后台选服、预热等异步任务必须使用启动任务时捕获的会话 ID，
     * 不能因为用户在任务完成前切换会话而把结果写到新会话。
     */
    fun updateConversationFunctionToggleState(
        conversationId: String,
        update: (ConversationFunctionToggleState) -> ConversationFunctionToggleState
    ) {
        conversationFunctionToggleStates.update { currentMap ->
            val currentState = currentMap[conversationId] ?: ConversationFunctionToggleState()
            currentMap + (conversationId to update(currentState))
        }
    }
    
    // 为历史会话设置稳定的ID
    fun setConversationIdForHistory(historyIndex: Int) {
        // 使用历史索引生成稳定的ID
        clearQuoteDrafts()
        _currentConversationId.value = "history_chat_$historyIndex"
        applyCurrentConversationFunctionToggleState()
    }

    fun setCurrentConversationId(conversationId: String) {
        val previous = _currentConversationId.value
        // 引用和 Fork 草稿只属于当前会话；临时 ID 迁到稳定 ID 是同一会话，不清。
        if (resolveConversationId(previous) != conversationId) clearQuoteDrafts()
        _currentConversationId.value = conversationId
        applyCurrentConversationFunctionToggleState()
    }

    fun clearQuoteDrafts() {
        composerQuoteReferences.clear()
        forkQuoteReferences.clear()
        forkSelectedMediaItems.clear()
        _forkComposerText.value = ""
    }

    fun applyCurrentConversationFunctionToggleState() {
        val toggleState = getCurrentConversationFunctionToggleState()
        _isWebSearchEnabled.value = toggleState.webSearchEnabled
        _isCodeExecutionEnabled.value = toggleState.codeExecutionEnabled
        _isMcpEnabledForNextRequest.value = toggleState.mcpEnabled
        _isAgentEnabled.value = toggleState.agentEnabled
    }

    fun applyCurrentImageConversationFunctionToggleState() {
        val toggleState = getCurrentImageConversationFunctionToggleState()
        _isWebSearchEnabled.value = toggleState.webSearchEnabled
        _isCodeExecutionEnabled.value = toggleState.codeExecutionEnabled
        _isMcpEnabledForNextRequest.value = toggleState.mcpEnabled
        _isAgentEnabled.value = false
    }

    // 分离的展开推理状态
    val textExpandedReasoningStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val imageExpandedReasoningStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    
    // 分离的消息动画状态
    val textMessageAnimationStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val imageMessageAnimationStates: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    // 分离的历史加载状态
    val _isLoadingTextHistory = MutableStateFlow(false)
    val _isLoadingImageHistory = MutableStateFlow(false)
    
    val conversationScrollStates = mutableStateMapOf<String, ConversationScrollState>()
    val systemPromptExpandedState = mutableStateMapOf<String, Boolean>()
    val systemPrompts = mutableStateMapOf<String, String>()
    // 是否将当前系统提示接入到该会话（开始/暂停）
    val systemPromptEngagedState = mutableStateMapOf<String, Boolean>()

    // 清理文本模式状态的方法 - 增强版本，确保完全隔离
    fun clearForNewTextChat() {
        cacheTextConversation()
        _text.value = ""
        selectedMediaItems.clear()
        clearQuoteDrafts()
        _isTextApiCalling.value = false
        _isRemoteCancellationPending.value = false
        // 页面切换不取消按会话登记的运行任务。
        textApiJob = null
        _currentTextStreamingAiMessageId.value = null
        textReasoningCompleteMap.clear()
        textExpandedReasoningStates.clear()
        textMessageAnimationStates.clear()
        _showSourcesDialog.value = false
        _sourcesForDialog.value = emptyList()
        _loadedHistoryIndex.value = null
        
        // 只释放已经没有任务所有者的缓冲，后台会话仍需接收模型增量。
        val activeIds = (textApiMessageIds.values + imageApiMessageIds.values).toSet()
        streamingBuffers.keys.filterNot { it in activeIds }.forEach(::clearStreamingBuffer)
        
        android.util.Log.d("ViewModelStateHolder", "Cleared all StreamingBuffers and streaming states for text chat")
        
        // 分配全新会话ID
        _currentConversationId.value = "new_chat_${System.currentTimeMillis()}"
        applyCurrentConversationFunctionToggleState()
        
        // 🎯 关键修复：确保ApiHandler中的会话状态完全清理
        _apiHandler?.clearTextChatResources()
        isTextConversationDirty.value = false
    }

    // 清理图像模式状态的方法 - 增强版本，确保完全隔离
    fun clearForNewImageChat() {
        val oldConversationId = _currentImageGenerationConversationId.value
        imageGenerationMessages.clear()
        _isImageApiCalling.value = false
        imageApiJob?.cancel()
        imageApiJobs.remove(oldConversationId)
        imageApiMessageIds.remove(oldConversationId)
        imageApiJob = null
        _currentImageStreamingAiMessageId.value = null
        imageReasoningCompleteMap.clear()
        imageExpandedReasoningStates.clear()
        imageMessageAnimationStates.clear()
        _loadedImageGenerationHistoryIndex.value = null
        _currentImageGenerationConversationId.value = "new_image_generation_${System.currentTimeMillis()}"
        
        // 只释放已经没有任务所有者的缓冲，后台会话仍需接收模型增量。
        val activeIds = (textApiMessageIds.values + imageApiMessageIds.values).toSet()
        streamingBuffers.keys.filterNot { it in activeIds }.forEach(::clearStreamingBuffer)
        
        android.util.Log.d("ViewModelStateHolder", "Cleared all StreamingBuffers and streaming states for image chat")
        
        // 🎯 关键修复：确保ApiHandler中的会话状态完全清理
        _apiHandler?.clearImageChatResources()
        isImageConversationDirty.value = false
        applyCurrentImageConversationFunctionToggleState()
    }

    val selectedMediaItems: SnapshotStateList<SelectedMediaItem> =
        mutableStateListOf()
    /** 主会话输入框中的引用文本标签列表。 */
    val composerQuoteReferences: SnapshotStateList<MessageQuoteReference> =
        mutableStateListOf()
    /** Fork 分支输入框的草稿：引用、正文和附件，和主输入框互不影响。 */
    val forkQuoteReferences: SnapshotStateList<MessageQuoteReference> =
        mutableStateListOf()
    val _forkComposerText = MutableStateFlow("")
    val forkSelectedMediaItems: SnapshotStateList<SelectedMediaItem> = mutableStateListOf()
    val _forkDrawerOpenRequestVersion = MutableStateFlow(0)
    /** 父会话 ID → Fork 分支会话 ID。分支是一条真实会话，走主会话的完整发送链路。 */
    val _forkConversationIds = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 分支里正由云端执行的回复：分支 ID → 回复消息 ID。分支没有本机流式 Job，运行态和停止按它判断。 */
    val _forkCloudRuns = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 正在从数据库装回内存的 Fork 分支；发送前要等它完成，否则分支旧上下文进不了请求。 */
    internal val forkLoadJobs: MutableMap<String, Job> = ConcurrentHashMap()
    /** 点击引用标签时要跳到的消息和引用原文，3 秒后清空，用于定位到具体段落并高亮。 */
    data class QuoteJump(val messageId: String, val quotedText: String, val token: Long, val occurrence: Int = -1)
    val _quoteJump = MutableStateFlow<QuoteJump?>(null)
    internal var highlightClearJob: Job? = null

    val _historicalConversations = MutableStateFlow<List<List<Message>>>(emptyList())
    val _imageGenerationHistoricalConversations = MutableStateFlow<List<List<Message>>>(emptyList())
    val _loadedHistoryIndex = MutableStateFlow<Int?>(null)
    val _loadedImageGenerationHistoryIndex = MutableStateFlow<Int?>(null)
    val _isLoadingHistory = MutableStateFlow(false)
    val _historyLoadGeneration = MutableStateFlow(0L)
    
    // 图像生成错误处理状态
    val _imageGenerationRetryCount = MutableStateFlow(0)
    val _imageGenerationError = MutableStateFlow<String?>(null)
    val _shouldShowImageGenerationError = MutableStateFlow(false)
    val _isLoadingHistoryData = MutableStateFlow(false)
    val _currentConversationId = MutableStateFlow<String>("new_chat_${System.currentTimeMillis()}")
    val _currentImageGenerationConversationId = MutableStateFlow<String>("new_image_generation_${System.currentTimeMillis()}")
    // 待加载的图像历史索引（用于跨页面导航时抑制"新建图像会话"）
    val _pendingImageHistoryIndex = MutableStateFlow<Int?>(null)

     val _apiConfigs = MutableStateFlow<List<ApiConfig>>(emptyList())
     val _selectedApiConfig = MutableStateFlow<ApiConfig?>(null)
    val _imageGenApiConfigs = MutableStateFlow<List<ApiConfig>>(emptyList())
    val _selectedImageGenApiConfig = MutableStateFlow<ApiConfig?>(null)
    // 图像输出宽高比（默认 AUTO）
    val _selectedImageRatio = MutableStateFlow(com.android.everytalk.data.DataClass.ImageRatio.DEFAULT_SELECTED)
    val _gptImageQuality = MutableStateFlow(com.android.everytalk.ui.components.ImageGenCapabilities.GptImageQuality.AUTO)
    
    // ========= 语音配置状态 =========
    /** 语音后端配置列表 */
    val _voiceBackendConfigs = MutableStateFlow<List<VoiceBackendConfig>>(emptyList())
    /** 当前选中的语音配置 */
    val _selectedVoiceConfig = MutableStateFlow<VoiceBackendConfig?>(null)
 
 
     val _snackbarMessage =
         MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val _scrollToBottomEvent =
        MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    // 滚动到指定消息ID的事件（用于重新回答时定位）
    val _scrollToItemEvent =
        MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)

    /** 手动「立即压缩」进行中；要发一次模型请求，按钮据此转圈并禁用。 */
    val _isCompactingContext = MutableStateFlow(false)


    val _showSettingsDialog = MutableStateFlow(false)
    
    // 🎯 新增：添加配置流程相关的对话框状态
    val _showAutoFetchConfirmDialog = MutableStateFlow(false)
    val _showModelSelectionDialog = MutableStateFlow(false)
    val _pendingConfigParams = MutableStateFlow<PendingConfigParams?>(null)

    val _isWebSearchEnabled = MutableStateFlow(false)
    val _isCodeExecutionEnabled = MutableStateFlow(false)

    val _showSourcesDialog = MutableStateFlow(false)
    val _sourcesForDialog = MutableStateFlow<List<WebSearchResult>>(emptyList())

    internal val _requestScrollForReasoningBoxEvent = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

 
     fun clearSelectedMedia() {
        selectedMediaItems.clear()
    }
fun addMessage(message: Message, isImageGeneration: Boolean = false) {
    check(Looper.myLooper() == Looper.getMainLooper()) {
        "addMessage 必须在主线程调用，以保持消息写入与后续保存的顺序"
    }
    addMessageInternal(message, isImageGeneration)
}

private fun addMessageInternal(message: Message, isImageGeneration: Boolean) {
    if (isImageGeneration) {
        imageGenerationMessages.add(message)
        isImageConversationDirty.value = true
    } else {
        messages.add(message)
        isTextConversationDirty.value = true
    }
}

    fun shouldAutoScroll(): Boolean {
        return ::scrollController.isInitialized && !scrollController.userManuallyScrolledAwayFromBottom
    }

    fun showSnackbar(message: String) {
        _snackbarMessage.tryEmit(message)
    }

    fun triggerScrollToBottom() {
        _scrollToBottomEvent.tryEmit(Unit)
    }
    
    /**
     * 为消息创建流式缓冲区。
     *
     * 首个内容块立即显示，后续按时间和字符阈值合并，并同步初始化独立渲染状态。
     */
    fun createStreamingBuffer(messageId: String, isImageGeneration: Boolean = false): StreamingBuffer {
        val scope = bufferCoroutineScope ?: throw IllegalStateException(
            "Buffer coroutine scope not initialized. Call initializeBufferScope() first."
        )
        
        // Clean up any existing buffer for this message
        streamingBuffers[messageId]?.let { existingBuffer ->
            existingBuffer.flush()
            existingBuffer.clear()
        }
        
        // 🎯 Initialize StreamingMessageStateManager for this message
        // This allows UI components to observe streaming content efficiently
        // Requirements: 1.4, 3.4
        streamingMessageStateManager.startStreaming(messageId)

        dropPendingReasoning(messageId)
        streamingReasoningStates[messageId] = MutableStateFlow("")

        val buffer = StreamingBuffer(
            messageId = messageId,
            onUpdate = { _, delta ->
                if (delta.isNotEmpty()) {
                    streamingMessageStateManager.appendText(messageId, delta)
                    // 有序正文跟随同一缓冲批次落入消息，避免每个模型 token 都触发整表重算。
                    val messageList = if (isImageGeneration) imageGenerationMessages else textMessagesForMessage(messageId)
                    val index = messageList.indexOfFirst { it.id == messageId }
                    if (index >= 0) {
                        val current = messageList[index]
                        messageList[index] = current.copy(
                            contentStarted = true,
                            executionTrace = appendExecutionTraceContent(current.executionTrace, delta),
                        )
                    }
                }
                if (isImageGeneration) {
                    isImageConversationDirty.value = true
                } else {
                    isTextConversationDirty.value = true
                }
            },
            coroutineScope = scope
        )
        
        streamingBuffers[messageId] = buffer
        android.util.Log.d("ViewModelStateHolder", "Created StreamingBuffer and initialized streaming state for message: $messageId")
        
        return buffer
    }
    
    /**
     * Direct message content update (called by StreamingBuffer callback)
     * 
     * This is an internal method used by the buffer's onUpdate callback.
     * It updates the message content without going through the buffer again.
     * 
     * @param messageId Message to update
     * @param content New content to set
     * @param isImageGeneration Whether this is for image generation chat
     */
    private fun updateMessageContentDirect(messageId: String, content: String, isImageGeneration: Boolean) {
        val messageList = if (isImageGeneration) imageGenerationMessages else textMessagesForMessage(messageId)
        val index = messageList.indexOfFirst { it.id == messageId }
        
        if (index != -1) {
            val currentMessage = messageList[index]
            val updatedMessage = currentMessage.copy(
                text = content,
                contentStarted = true,
                timestamp = System.currentTimeMillis()
            )
            messageList[index] = updatedMessage
            
            if (isImageGeneration) {
                isImageConversationDirty.value = true
            } else {
                isTextConversationDirty.value = true
            }
        }
    }
    
    /**
     * Flush a StreamingBuffer immediately
     * 
     * This method forces the buffer to commit all pending content to the UI.
     * Also finalizes the streaming state in StreamingMessageStateManager.
     * Should be called when:
     * - Stream completes successfully
     * - Stream encounters an error
     * - Need to ensure all content is visible
     * 
     * Requirements: 1.4, 3.3, 3.4, 7.1, 7.2
     * 
     * @param messageId Message whose buffer should be flushed
     */
    fun flushStreamingBuffer(messageId: String) {
        streamingBuffers[messageId]?.let { buffer ->
            buffer.flush()

            // 这里只负责把缓冲区内容推进到 StreamingMessageStateManager，最终收尾统一由 syncStreamingMessageToList 负责
        }
    }
    
    /**
     * Clear a StreamingBuffer and remove it
     * 
     * This method clears the buffer content and removes it from the map.
     * Also clears the streaming state in StreamingMessageStateManager.
     * Should be called when:
     * - Stream is cancelled
     * - Error occurs and need to clean up
     * - Message is being removed
     * 
     * Requirements: 1.4, 3.4, 6.1, 6.2
     * 
     * @param messageId Message whose buffer should be cleared
     */
    fun clearStreamingBuffer(messageId: String) {
        streamingBuffers.remove(messageId)?.let { buffer ->
            buffer.clear()
        }

        // 🎯 清理同步标记，允许下次同步
        syncedMessageIds.remove(messageId)

        // 🎯 Clear streaming state in StreamingMessageStateManager
        streamingMessageStateManager.clearStreamingState(messageId)
        dropPendingReasoning(messageId)
        streamingReasoningStates.remove(messageId)

        android.util.Log.d("ViewModelStateHolder", "Cleared StreamingBuffer and streaming state for message: $messageId")
    }
    
    /**
     * Get a StreamingBuffer for a message (if exists)
     * 
     * @param messageId Message ID
     * @return StreamingBuffer instance or null if not found
     */
    fun getStreamingBuffer(messageId: String): StreamingBuffer? {
        return streamingBuffers[messageId]
    }
    
    /**
     * Get the count of active streaming buffers
     * Used for resource monitoring and memory pressure detection
     * 
     * @return Number of active streaming buffers
     */
    fun getStreamingBufferCount(): Int {
        return streamingBuffers.size
    }
    
    /**
     * 🎯 Task 11: Monitor memory usage during long streaming sessions
     * 
     * This method checks memory usage and logs warnings if memory pressure is detected.
     * Should be called periodically during streaming to detect potential memory issues.
     * 
     * Requirements: 1.4, 3.4
     */
    fun checkMemoryUsage() {
        val currentTime = System.currentTimeMillis()
        
        // Only check memory every 10 seconds to avoid performance impact
        if (currentTime - lastMemoryCheckTime < memoryCheckInterval) {
            return
        }
        
        lastMemoryCheckTime = currentTime
        
        try {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val availableMemory = maxMemory - usedMemory
            val memoryUsagePercent = (usedMemory.toFloat() / maxMemory.toFloat() * 100).toInt()
            
            // Log memory stats
            android.util.Log.d("ViewModelStateHolder", 
                "Memory usage: ${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB " +
                "($memoryUsagePercent%), " +
                "available: ${availableMemory / 1024 / 1024}MB, " +
                "activeBuffers: ${streamingBuffers.size}, " +
                "textMessages: ${messages.size}, " +
                "imageMessages: ${imageGenerationMessages.size}")
            
            // Warn if memory usage is high (>80%)
            if (memoryUsagePercent > 80) {
                android.util.Log.w("ViewModelStateHolder", 
                    "High memory usage detected: $memoryUsagePercent%. " +
                    "Consider cleaning up resources.")
            }
            
            // Critical warning if memory usage is very high (>90%)
            if (memoryUsagePercent > 90) {
                android.util.Log.e("ViewModelStateHolder", 
                    "Critical memory usage: $memoryUsagePercent%. " +
                    "Memory pressure detected. Forcing cleanup of inactive buffers.")
                
                // Force cleanup of inactive buffers
                val activeStreamingIds = (textApiMessageIds.values + imageApiMessageIds.values + listOfNotNull(
                    _currentTextStreamingAiMessageId.value,
                    _currentImageStreamingAiMessageId.value,
                )).toSet()
                
                val buffersToRemove = streamingBuffers.keys.filter { it !in activeStreamingIds }
                buffersToRemove.forEach { messageId ->
                    clearStreamingBuffer(messageId)
                }
                
                if (buffersToRemove.isNotEmpty()) {
                    android.util.Log.d("ViewModelStateHolder", 
                        "Cleaned up ${buffersToRemove.size} inactive buffers due to memory pressure")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ViewModelStateHolder", "Error checking memory usage", e)
        }
    }
    
    /**
     * 🎯 Task 11: Get performance metrics for all active streaming buffers
     * 
     * Returns a summary of performance metrics across all buffers.
     * Useful for debugging and monitoring streaming performance.
     * 
     * Requirements: 1.4, 3.4
     * 
     * @return Map of performance metrics
     */
    fun getStreamingPerformanceMetrics(): Map<String, Any> {
        val metrics = mutableMapOf<String, Any>()
        
        metrics["activeBufferCount"] = streamingBuffers.size
        metrics["textMessageCount"] = messages.size
        metrics["imageMessageCount"] = imageGenerationMessages.size
        
        // Aggregate buffer statistics
        var totalFlushes = 0
        var totalCharsProcessed = 0
        
        streamingBuffers.values.forEach { buffer ->
            val stats = buffer.getStats()
            totalFlushes += (stats["flushCount"] as? Int) ?: 0
            totalCharsProcessed += (stats["totalCharsProcessed"] as? Int) ?: 0
        }
        
        metrics["totalFlushes"] = totalFlushes
        metrics["totalCharsProcessed"] = totalCharsProcessed
        metrics["avgCharsPerFlush"] = if (totalFlushes > 0) totalCharsProcessed / totalFlushes else 0
        
        // Memory metrics
        try {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            metrics["usedMemoryMB"] = usedMemory / 1024 / 1024
            metrics["maxMemoryMB"] = maxMemory / 1024 / 1024
            metrics["memoryUsagePercent"] = (usedMemory.toFloat() / maxMemory.toFloat() * 100).toInt()
        } catch (e: Exception) {
            android.util.Log.e("ViewModelStateHolder", "Error getting memory metrics", e)
        }
        
        return metrics
    }
    // 思考增量按批提交：逐 token 改消息列表会让整表投影、思考区 Markdown 解析和全文 StateFlow 每个 token 重算一次，
    // 长思考时分配量随长度平方增长，是流式期间内存暴涨、卡顿的主要来源。
    private class PendingReasoning(val isImageGeneration: Boolean) {
        val text = StringBuilder()
        var lastCommitAt = 0L
        var job: Job? = null
    }
    private val pendingReasoning = mutableMapOf<String, PendingReasoning>()

    /** 主线程调用。首个分片立即提交，之后最多每 [REASONING_COMMIT_INTERVAL_MS] 提交一次。 */
    fun queueReasoningChunk(messageId: String, chunk: String, isImageGeneration: Boolean = false) {
        if (chunk.isEmpty()) return
        val pending = pendingReasoning.getOrPut(messageId) { PendingReasoning(isImageGeneration) }
        pending.text.append(chunk)
        val wait = pending.lastCommitAt + REASONING_COMMIT_INTERVAL_MS - System.currentTimeMillis()
        val scope = bufferCoroutineScope
        if (wait <= 0L || scope == null) {
            flushPendingReasoning(messageId)
        } else if (pending.job == null) {
            pending.job = scope.launch {
                delay(wait)
                pending.job = null
                flushPendingReasoning(messageId)
            }
        }
    }

    /** 其它事件处理前必须先调用，保证思考、正文、工具在执行链里的先后顺序不变。 */
    fun flushPendingReasoning(messageId: String) {
        val pending = pendingReasoning[messageId] ?: return
        pending.job?.cancel()
        pending.job = null
        if (pending.text.isEmpty()) return
        val chunk = pending.text.toString()
        pending.text.setLength(0)
        pending.lastCommitAt = System.currentTimeMillis()
        val messageList = if (pending.isImageGeneration) imageGenerationMessages else textMessagesForMessage(messageId)
        val index = messageList.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            val current = messageList[index]
            val seeded = applyActiveReasoningChunk(current, chunk)
            if (seeded != current) messageList[index] = seeded
        }
        appendReasoningToMessage(messageId, chunk, pending.isImageGeneration)
    }

    private fun dropPendingReasoning(messageId: String) {
        pendingReasoning.remove(messageId)?.job?.cancel()
    }

    fun appendReasoningToMessage(messageId: String, text: String, isImageGeneration: Boolean = false) {
        if (text.isEmpty()) return
        val state = streamingReasoningStates.getOrPut(messageId) { MutableStateFlow("") }
        state.value += text
    }

    /**
     * 追加内容到消息
     * 🎯 优化：通过 StreamingBuffer 实现节流，避免高频修改 messages 列表
     * 
     * 旧逻辑问题：
     * - 每次调用都修改 messages (SnapshotStateList)
     * - 触发 snapshotFlow 发射 → combine 重新计算所有消息
     * - 导致 LazyColumn 频繁重组（100次/10秒）
     * 
     * 新逻辑：
     * - 流式期间：通过 StreamingBuffer 路由，按 80 至 180ms 和 64 字符阈值合并
     * - 同时更新 StreamingMessageStateManager 以支持高效的 UI 观察
     * - 非流式：正常更新 messages
     * - 正文由独立 StateFlow 增量显示；有序输出轨迹按同一缓冲节奏更新消息列表
     * 
     * Requirements: 1.4, 3.1, 3.2, 3.3, 3.4
     */
    fun appendContentToMessage(messageId: String, text: String, isImageGeneration: Boolean = false) {
        if (text.isEmpty()) return

        // 🔧 修复竞态条件：使用 synchronized 保护对 streamingBuffers 的检查和操作
        // 避免多线程环境下 check-then-act 的竞态问题
        val buffer = synchronized(streamingBuffers) {
            streamingBuffers[messageId]
        }
        
        if (buffer != null) {
            buffer.append(text)
            return
        }

        // 仅在没有缓冲的极端回退路径，才直接更新一次（如历史重放/异常场景）
        val messageList = if (isImageGeneration) imageGenerationMessages else textMessagesForMessage(messageId)
        val index = messageList.indexOfFirst { it.id == messageId }
        if (index != -1) {
            val currentMessage = messageList[index]
            val updatedMessage = currentMessage.copy(
                text = currentMessage.text + text,
                contentStarted = true,
                executionTrace = appendExecutionTraceContent(currentMessage.executionTrace, text),
            )
            messageList[index] = updatedMessage
            streamingMessageStateManager.updateContent(messageId, updatedMessage.text)
            if (isImageGeneration) {
                isImageConversationDirty.value = true
            } else {
                isTextConversationDirty.value = true
            }
        }
    }

    /**
     * 更新消息状态（用于 ToolCall 等非内容追加的更新）
     */
    fun updateMessageStatus(messageId: String, currentWebSearchStage: String, isImageGeneration: Boolean = false) {
        val messageList = if (isImageGeneration) imageGenerationMessages else textMessagesForMessage(messageId)
        val index = messageList.indexOfFirst { it.id == messageId }
        if (index != -1) {
            val currentMessage = messageList[index]
            val updatedMessage = currentMessage.copy(
                currentWebSearchStage = currentWebSearchStage
            )
            messageList[index] = updatedMessage
        }
    }

    fun clearMessageStatus(messageId: String, isImageGeneration: Boolean = false) {
        val messageList = if (isImageGeneration) imageGenerationMessages else textMessagesForMessage(messageId)
        val index = messageList.indexOfFirst { it.id == messageId }
        if (index != -1) {
            val currentMessage = messageList[index]
            messageList[index] = currentMessage.copy(
                currentWebSearchStage = null,
                executionStatus = currentMessage.executionStatus.takeIf { currentMessage.isError }
            )
        }
    }

    /**
     * 把应用级恢复的 AgentRun 重新挂到当前文本会话 UI。
     * 只接管当前消息列表里真实存在的消息，也不会覆盖另一条正在运行的回复。
     */
    fun attachTextAgentUi(messageId: String): Boolean {
        // 当前列表就是当前会话；未登记的消息在其中即属于当前会话，登记到别处的才拒绝。
        val currentSessionId = _currentConversationId.value
        if (messages.none { it.id == messageId }) return false
        if (textMessageConversationIds.getOrPut(messageId) { currentSessionId } != currentSessionId) return false
        if (_isTextApiCalling.value && _currentTextStreamingAiMessageId.value != messageId) return false
        _currentTextStreamingAiMessageId.value = messageId
        _isTextApiCalling.value = true
        return true
    }

    /** 只有终态属于当前消息时才归位 UI，防止后台旧 Run 清掉另一条新回复的状态。 */
    fun detachTextAgentUi(messageId: String) {
        if (_currentTextStreamingAiMessageId.value != messageId) return
        _isTextApiCalling.value = false
        _currentTextStreamingAiMessageId.value = null
        _isRemoteCancellationPending.value = false
    }

    fun startLocalSlashLoading(messageId: String, isImageGeneration: Boolean = false) {
        if (isImageGeneration) {
            _currentImageStreamingAiMessageId.value = messageId
            _isImageApiCalling.value = true
        } else {
            _currentTextStreamingAiMessageId.value = messageId
            _isTextApiCalling.value = true
        }
    }

    fun finishLocalSlashLoading(
        messageId: String,
        finalText: String,
        isImageGeneration: Boolean = false
    ) {
        val messageList = if (isImageGeneration) imageGenerationMessages else textMessagesForMessage(messageId)
        val index = messageList.indexOfFirst { it.id == messageId }
        AppLogger.debug(
            "SlashCommand",
            "finishLocalSlashLoading messageId=$messageId index=$index finalTextChars=${finalText.length}"
        )
        if (index != -1) {
            val currentMessage = messageList[index]
            messageList[index] = currentMessage.copy(
                text = finalText,
                contentStarted = finalText.isNotBlank(),
                timestamp = System.currentTimeMillis()
            )
            AppLogger.debug(
                "SlashCommand",
                "finishLocalSlashLoading updated contentStarted=${finalText.isNotBlank()} textLen=${finalText.length}"
            )
            if (isImageGeneration) {
                isImageConversationDirty.value = true
            } else {
                isTextConversationDirty.value = true
            }
        }

        if (isImageGeneration) {
            if (_currentImageStreamingAiMessageId.value == messageId) {
                _currentImageStreamingAiMessageId.value = null
            }
            _isImageApiCalling.value = false
        } else {
            if (_currentTextStreamingAiMessageId.value == messageId) {
                _currentTextStreamingAiMessageId.value = null
            }
            _isTextApiCalling.value = false
            _isRemoteCancellationPending.value = false
        }
    }

    /**
     * 同步流式消息到 messages 列表
     * 🎯 在流式结束时调用，将缓冲区的文本同步到持久化存储
     * 该方法是幂等的，重复调用不会产生副作用
     */
    fun syncStreamingMessageToList(messageId: String, isImageGeneration: Boolean = false) {
        // 🎯 幂等检查：如果已经同步过，直接返回
        if (syncedMessageIds.contains(messageId)) {
            android.util.Log.d("ViewModelStateHolder", "syncStreamingMessageToList: already synced $messageId, skipping")
            return
        }

        streamingBuffers[messageId]?.flush()
        flushPendingReasoning(messageId)
        val finalText = streamingMessageStateManager.finishStreaming(messageId)
        val finalReasoning = streamingReasoningStates[messageId]?.value.orEmpty()

        android.util.Log.d("ViewModelStateHolder", "🎯 Syncing streaming message $messageId: finalText.length=${finalText.length}")

        if (finalText.isEmpty() && finalReasoning.isEmpty()) {
            android.util.Log.w("ViewModelStateHolder", "syncStreamingMessageToList: empty content for $messageId")
            return
        }

        val messageList = if (isImageGeneration) imageGenerationMessages else textMessagesForMessage(messageId)
        val index = messageList.indexOfFirst { it.id == messageId }

        if (index != -1) {
            val currentMessage = messageList[index]
            val updatedMessage = currentMessage.copy(
                text = finalText.ifEmpty { currentMessage.text },
                reasoning = finalReasoning.ifEmpty { currentMessage.reasoning },
                contentStarted = finalText.isNotEmpty() || currentMessage.contentStarted,
            )
            messageList[index] = updatedMessage
            android.util.Log.d("ViewModelStateHolder", "🎯 Synced message.text chars=${finalText.length}")

            if (isImageGeneration) {
                isImageConversationDirty.value = true
            } else {
                isTextConversationDirty.value = true
            }

            // 🎯 标记为已同步，防止重复同步
            syncedMessageIds.add(messageId)

            android.util.Log.d("ViewModelStateHolder", "Synced streaming message $messageId, final length: ${finalText.length}")
        }
    }

    /**
     * 将当前流式快照写入消息列表，但不结束流，也不设置终态幂等标记。
     * 暂停后恢复显示时使用，后续增量仍可继续进入真正的终态同步。
     */
    fun syncStreamingSnapshotToList(messageId: String, isImageGeneration: Boolean = false) {
        streamingBuffers[messageId]?.flush()
        flushPendingReasoning(messageId)
        val currentText = streamingMessageStateManager.getCurrentContent(messageId)
        val currentReasoning = streamingReasoningStates[messageId]?.value.orEmpty()
        if (currentText.isEmpty() && currentReasoning.isEmpty()) return

        val messageList = if (isImageGeneration) imageGenerationMessages else textMessagesForMessage(messageId)
        val index = messageList.indexOfFirst { it.id == messageId }
        if (index < 0) return

        val currentMessage = messageList[index]
        messageList[index] = currentMessage.copy(
            text = currentText.ifEmpty { currentMessage.text },
            reasoning = currentReasoning.ifEmpty { currentMessage.reasoning },
            contentStarted = currentText.isNotEmpty() || currentMessage.contentStarted,
        )
    }
    
    private var _apiHandler: ApiHandler? = null
    fun setApiHandler(handler: ApiHandler) {
        _apiHandler = handler
    }

    fun getApiHandler(): ApiHandler {
        return _apiHandler ?: throw IllegalStateException("ApiHandler not initialized")
    }
}

/** 与正文缓冲同一量级：人眼看不出差别，但思考区每秒最多重算约 8 次。 */
private const val REASONING_COMMIT_INTERVAL_MS = 120L
