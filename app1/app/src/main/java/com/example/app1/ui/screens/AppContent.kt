package com.example.app1.ui.screens // 确保包名正确

import android.content.Context // 导入 Context
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.app1.data.local.SharedPreferencesDataSource // 确保路径正确
import com.example.app1.ui.components.SettingsDialog // 确保路径正确
import kotlinx.coroutines.flow.collectLatest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState



@Composable
fun AppContent(
    snackbarHostState: SnackbarHostState,
    innerPadding: PaddingValues
) {
    val context = LocalContext.current
    val factory = remember { AppViewModelFactory(context.applicationContext) }
    val viewModel: AppViewModel = viewModel(factory = factory)

    val currentView by viewModel.currentView.collectAsState()
    val showSettingsDialog by viewModel.showSettingsDialog.collectAsState()
    val apiConfigs by viewModel.apiConfigs.collectAsState()
    val selectedApiConfig by viewModel.selectedApiConfig.collectAsState()
    val historicalConversations by viewModel.historicalConversations.collectAsState()

    LaunchedEffect(snackbarHostState, viewModel) {
        viewModel.snackbarMessage.collectLatest { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }

    BackHandler(enabled = currentView == AppView.HistoryList) {
        viewModel.navigateToChat(fromHistory = true)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
    ) { // 应用来自外部 Scaffold 的 padding

        when (currentView) {
            AppView.CurrentChat -> {
                ChatScreen(
                    viewModel = viewModel // 传递 ViewModel
                )
            }

            AppView.HistoryList -> {
                HistoryScreen( // 确保 HistoryScreen 正确导入并接收参数
                    historicalConversations = historicalConversations,
                    onNavigateBack = { viewModel.navigateToChat(fromHistory = true) },
                    onConversationClick = viewModel::loadConversationFromHistory,
                    onDeleteConversation = viewModel::deleteConversation,
                    onNewChatClick = viewModel::startNewChat,
                )
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog( // 确保 SettingsDialog 正确导入并接收参数
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