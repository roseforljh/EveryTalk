package com.example.app1.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel // 导入 viewModel()
import com.example.app1.data.models.*
import com.example.app1.ui.components.SettingsDialog // 确认 SettingsDialog 导入正确
import kotlinx.coroutines.flow.collectLatest
import androidx.activity.compose.BackHandler // 导入 BackHandler
import androidx.compose.foundation.layout.Box // 导入 Box
import androidx.compose.foundation.layout.PaddingValues // 导入 PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding // 导入 padding 修饰符
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.flow.StateFlow // 导入 StateFlow (虽然不直接用在collectAsState这行，但为了理解类型)


@Composable
fun AppContent(
    snackbarHostState: SnackbarHostState, // Snackbar 仍然由外部传入和管理
    innerPadding: PaddingValues // <<< ADD innerPadding parameter back
) {
    val context = LocalContext.current
    // 获取 ViewModel 实例 (使用 Factory)
    val factory = remember { AppViewModelFactory(context.applicationContext) }
    val viewModel: AppViewModel = viewModel(factory = factory)

    // --- 收集 ViewModel 的状态 (使用 collectAsState) ---
    val currentView by viewModel.currentView.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val text by viewModel.text.collectAsState()
    val historicalConversations by viewModel.historicalConversations.collectAsState()
    val selectedApiConfig by viewModel.selectedApiConfig.collectAsState()
    val showSettingsDialog by viewModel.showSettingsDialog.collectAsState()
    val isApiCalling by viewModel.isApiCalling.collectAsState()
    // 正确收集 StateFlow<String?> currentStreamingAiMessageId
    val currentStreamingAiMessageId by viewModel.currentStreamingAiMessageId.collectAsState() // <-- 已修改为 collectAsState()

    val expandedReasoningStates = viewModel.expandedReasoningStates // 直接使用 StateMap
    val showScrollToBottomButton by viewModel.showScrollToBottomButton.collectAsState()
    val apiConfigs by viewModel.apiConfigs.collectAsState()


    // --- 处理 Snackbar 消息 ---
    LaunchedEffect(snackbarHostState, viewModel) {
        viewModel.snackbarMessage.collectLatest { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }

    // --- 处理返回按钮 ---
    BackHandler(enabled = currentView == AppView.HistoryList) {
        viewModel.navigateToChat(fromHistory = true)
    }

    // --- 主 UI 布局 ---
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding) // <<< APPLY innerPadding HERE
    ) {
        when (currentView) {
            AppView.CurrentChat -> {
                ChatScreen(
                    messages = messages,
                    text = text,
                    onTextChange = viewModel::onTextChange,
                    selectedApiConfig = selectedApiConfig,
                    isApiCalling = isApiCalling,
                    currentStreamingAiMessageId = currentStreamingAiMessageId, // <-- 现在传递的是 String?
                    showScrollToBottomButton = showScrollToBottomButton,
                    onScrollToBottomClick = { /* ChatScreen 内部处理滚动 */ },
                    onHistoryClick = viewModel::navigateToHistory,
                    onSettingsClick = viewModel::showSettingsDialog,
                    onSendMessage = viewModel::onSendMessage,
                    onCancelAPICall = viewModel::onCancelAPICall,
                    expandedReasoningStates = expandedReasoningStates,
                    onToggleReasoningExpand = viewModel::onToggleReasoningExpand,
                    onUserScrolledAwayChange = viewModel::onUserScrolledAwayChange
                )
            }
            AppView.HistoryList -> {
                HistoryScreen( // 假设 HistoryScreen 存在
                    historicalConversations = historicalConversations,
                    onNavigateBack = { viewModel.navigateToChat(fromHistory = true) },
                    onConversationClick = viewModel::loadConversationFromHistory,
                    onDeleteConversation = viewModel::deleteConversation,
                    onNewChatClick = viewModel::startNewChat
                )
            }
        }
    } // Box End

    // --- 设置对话框 ---
    if (showSettingsDialog) {
        SettingsDialog(
            savedConfigs = apiConfigs,
            selectedConfig = selectedApiConfig,
            onDismissRequest = viewModel::dismissSettingsDialog,
            onAddConfig = viewModel::addConfig,
            onUpdateConfig = viewModel::updateConfig,
            onDeleteConfig = viewModel::deleteConfig,
            onClearAll = viewModel::clearAllConfigs,
            onSelectConfig = viewModel::selectConfig
        )
    }
} // AppContent End