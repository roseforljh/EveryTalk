package com.android.everytalk.statecontroller.facade

import androidx.compose.material3.DrawerState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.statecontroller.SimpleModeManager
import com.android.everytalk.statecontroller.ViewModelStateHolder
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UiStateFacade
 * 统一对外暴露只读 UI 状态入口，屏蔽 AppViewModel 对 stateHolder 的直接大量访问。
 *
 * 第一阶段：提供只读桥接，不改变原有字段命名/类型，保持 UI 兼容；
 * 后续可以逐步将 AppViewModel 的公开属性改为转发到此处，最终由 UI 直接注入 Facade。
 */
class UiStateFacade(
    private val stateHolder: ViewModelStateHolder,
    private val simpleModeManager: SimpleModeManager
) {
    // Drawer
    val drawerState: DrawerState get() = stateHolder.drawerState

    // 文本输入
    val text: StateFlow<String> get() = stateHolder._text.asStateFlow()

    // 当前会话 ID
    val currentConversationId: StateFlow<String> get() = stateHolder._currentConversationId.asStateFlow()
    val currentImageConversationId: StateFlow<String> get() = stateHolder._currentImageGenerationConversationId.asStateFlow()

    // 历史列表
    val historicalConversations: StateFlow<List<List<Message>>> get() = stateHolder._historicalConversations.asStateFlow()
    val imageHistoricalConversations: StateFlow<List<List<Message>>> get() = stateHolder._imageGenerationHistoricalConversations.asStateFlow()

    // 历史加载状态
    val loadedHistoryIndex: StateFlow<Int?> get() = stateHolder._loadedHistoryIndex.asStateFlow()
    val loadedImageHistoryIndex: StateFlow<Int?> get() = stateHolder._loadedImageGenerationHistoryIndex.asStateFlow()
    val isLoadingHistory: StateFlow<Boolean> get() = stateHolder._isLoadingHistory.asStateFlow()
    val isLoadingHistoryData: StateFlow<Boolean> get() = stateHolder._isLoadingHistoryData.asStateFlow()

    // 配置相关
    val apiConfigs: StateFlow<List<ApiConfig>> get() = stateHolder._apiConfigs.asStateFlow()
    val selectedApiConfig: StateFlow<ApiConfig?> get() = stateHolder._selectedApiConfig.asStateFlow()
    val imageGenApiConfigs: StateFlow<List<ApiConfig>> get() = stateHolder._imageGenApiConfigs.asStateFlow()
    val selectedImageGenApiConfig: StateFlow<ApiConfig?> get() = stateHolder._selectedImageGenApiConfig.asStateFlow()

    // API 调用状态
    val isTextApiCalling: StateFlow<Boolean> get() = stateHolder._isTextApiCalling.asStateFlow()
    val isImageApiCalling: StateFlow<Boolean> get() = stateHolder._isImageApiCalling.asStateFlow()
    val currentTextStreamingAiMessageId: StateFlow<String?> get() = stateHolder._currentTextStreamingAiMessageId.asStateFlow()
    val currentImageStreamingAiMessageId: StateFlow<String?> get() = stateHolder._currentImageStreamingAiMessageId.asStateFlow()

    // Web 搜索 / 来源对话框
    val isWebSearchEnabled: StateFlow<Boolean> get() = stateHolder._isWebSearchEnabled.asStateFlow()
    val showSourcesDialog: StateFlow<Boolean> get() = stateHolder._showSourcesDialog.asStateFlow()
    val sourcesForDialog: StateFlow<List<WebSearchResult>> get() = stateHolder._sourcesForDialog.asStateFlow()

    // Streaming 暂停
    val isStreamingPaused: StateFlow<Boolean> get() = stateHolder._isStreamingPaused.asStateFlow()

    // 消息与媒体（只读容器）
    val messages: SnapshotStateList<Message> get() = stateHolder.messages
    val imageMessages: SnapshotStateList<Message> get() = stateHolder.imageGenerationMessages
    val selectedMediaItems: SnapshotStateList<SelectedMediaItem> get() = stateHolder.selectedMediaItems

    // 推理展开/完成状态（只读 Map 视图）
    val textReasoningCompleteMap: SnapshotStateMap<String, Boolean> get() = stateHolder.textReasoningCompleteMap
    val imageReasoningCompleteMap: SnapshotStateMap<String, Boolean> get() = stateHolder.imageReasoningCompleteMap
    val textExpandedReasoningStates: SnapshotStateMap<String, Boolean> get() = stateHolder.textExpandedReasoningStates
    val imageExpandedReasoningStates: SnapshotStateMap<String, Boolean> get() = stateHolder.imageExpandedReasoningStates

    // 模式查询（保持与 SimpleModeManager 一致）
    fun getCurrentMode(): SimpleModeManager.ModeType = simpleModeManager.getCurrentMode()
    fun isInImageMode(): Boolean = simpleModeManager.isInImageMode()
    fun isInTextMode(): Boolean = simpleModeManager.isInTextMode()
}