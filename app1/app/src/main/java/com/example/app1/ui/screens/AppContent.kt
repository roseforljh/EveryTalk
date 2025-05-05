package com.example.app1.ui.screens // 确保这个包名和你的文件路径完全匹配

import androidx.compose.runtime.*
import androidx.compose.runtime.getValue // 确保导入 getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.app1.data.models.* // 导入数据模型 (如果 HistoryScreen 等需要)
import com.example.app1.ui.components.SettingsDialog // 导入设置对话框
import kotlinx.coroutines.flow.collectLatest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import com.example.app1.ui.screens.AppContent


@Composable
fun AppContent(
    snackbarHostState: SnackbarHostState,
    innerPadding: PaddingValues // 来自外部 Scaffold 的内边距
) {
    val context = LocalContext.current
    // 创建 ViewModelFactory 和 ViewModel 实例
    // 确保 AppViewModelFactory 在正确的包中或者已经导入
    val factory = remember { AppViewModelFactory(context.applicationContext) }
    val viewModel: AppViewModel = viewModel(factory = factory)

    // 收集顶层需要的状态
    val currentView by viewModel.currentView.collectAsState()
    val showSettingsDialog by viewModel.showSettingsDialog.collectAsState()
    val apiConfigs by viewModel.apiConfigs.collectAsState()
    val selectedApiConfig by viewModel.selectedApiConfig.collectAsState()
    val historicalConversations by viewModel.historicalConversations.collectAsState()

    // 处理 Snackbar
    LaunchedEffect(snackbarHostState, viewModel) {
        viewModel.snackbarMessage.collectLatest { message ->
            // snackbarHostState.currentSnackbarData?.dismiss() // 可选：如果需要打断之前的
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }

    // 处理返回按钮：当在历史列表视图时，返回到聊天视图
    // 确保 AppView 被正确引用 (来自 viewModel 或直接导入)
    BackHandler(enabled = currentView == AppView.HistoryList) {
        viewModel.navigateToChat(fromHistory = true)
    }

    // 主 UI 布局
    Box(modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)) { // 应用来自外部 Scaffold 的 padding

        // 确保 AppView 被正确引用
        when (currentView) {
            AppView.CurrentChat -> {
                // 调用 ChatScreen
                ChatScreen(
                    viewModel = viewModel,
                    modifier = Modifier // 可以传递 modifier
                )
            }

            AppView.HistoryList -> {
                // 调用 HistoryScreen
                HistoryScreen(
                    historicalConversations = historicalConversations,
                    onNavigateBack = { viewModel.navigateToChat(fromHistory = true) },
                    onConversationClick = viewModel::loadConversationFromHistory,
                    onDeleteConversation = viewModel::deleteConversation,
                    onNewChatClick = viewModel::startNewChat,
                    onClearAll = viewModel::clearAllHistory // 传递清除所有历史的回调
                )
            }
        }
    } // Box End

    // 设置对话框 (如果 showSettingsDialog 为 true 则显示)
    if (showSettingsDialog) {
        // 确保 SettingsDialog 被正确导入
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

}