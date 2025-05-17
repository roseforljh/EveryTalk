package com.example.everytalk.StateControler // 您提供的包名，如果MainActivity实际位置不同请调整

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition // 新增：导入无动画过渡
import androidx.compose.animation.ExitTransition  // 新增：导入无动画过渡
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.everytalk.data.local.SharedPreferencesDataSource
import com.example.everytalk.navigation.Screen
import com.example.everytalk.ui.screens.MainScreen.AppDrawerContent
import com.example.everytalk.ui.screens.MainScreen.ChatScreen
import com.example.everytalk.ui.screens.settings.SettingsScreen
import com.example.everytalk.ui.theme.App1Theme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction

// ViewModel 工厂 (保持不变)
class AppViewModelFactory(private val dataSource: SharedPreferencesDataSource) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppViewModel(dataSource) as T
        }
        throw IllegalArgumentException("未知的 ViewModel 类: ${modelClass.name}")
    }
}

private val defaultDrawerWidth = 280.dp // 抽屉默认宽度 (与AppDrawerContent中一致)

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // 启用边缘到边缘显示
        setContent {
            App1Theme { // 应用您的主题
                val snackbarHostState = remember { SnackbarHostState() } // Snackbar状态
                val navController = rememberNavController() // 导航控制器
                val coroutineScope = rememberCoroutineScope() // 协程作用域

                // 获取ViewModel实例
                val appViewModel: AppViewModel = viewModel(
                    factory = AppViewModelFactory(SharedPreferencesDataSource(applicationContext))
                )

                // 收集抽屉搜索相关的状态
                val isSearchActiveInDrawer by appViewModel.isSearchActiveInDrawer.collectAsState()
                val searchQueryInDrawer by appViewModel.searchQueryInDrawer.collectAsState()

                // 处理Snackbar消息
                LaunchedEffect(appViewModel.snackbarMessage, snackbarHostState) {
                    appViewModel.snackbarMessage.collectLatest { message ->
                        if (message.isNotBlank() && snackbarHostState.currentSnackbarData?.visuals?.message != message) {
                            snackbarHostState.showSnackbar(message)
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { // Snackbar容器
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.padding(bottom = 16.dp) // Snackbar距离底部的边距
                        ) { snackbarData ->
                            Snackbar(snackbarData = snackbarData) // Material 3 Snackbar
                        }
                    }
                ) { _ -> // Scaffold的content lambda，忽略其padding参数，因为我们自己处理
                    val density = LocalDensity.current
                    val configuration = LocalConfiguration.current
                    val screenWidthDp = configuration.screenWidthDp.dp

                    // 当抽屉通过手势关闭且之前搜索模式是激活的，则停用搜索模式
                    LaunchedEffect(appViewModel.drawerState.isClosed, isSearchActiveInDrawer) {
                        if (appViewModel.drawerState.isClosed && isSearchActiveInDrawer) {
                            Log.d(
                                "MainActivity",
                                "抽屉已关闭 (isClosed=true)，搜索模式之前已激活。正在停用搜索模式。"
                            )
                            appViewModel.setSearchActiveInDrawer(false)
                        }
                    }

                    // 计算主内容区域的X轴偏移量（用于抽屉拉出效果）
                    val contentOffsetX by remember(
                        appViewModel.drawerState.currentValue,
                        appViewModel.drawerState.offset.value,
                        isSearchActiveInDrawer
                    ) {
                        derivedStateOf {
                            val drawerOffsetPx =
                                appViewModel.drawerState.offset.value
                            val actualDrawerVisibleWidthPx =
                                if (isSearchActiveInDrawer) {
                                    val screenWidthPx = with(density) { screenWidthDp.toPx() }
                                    screenWidthPx + drawerOffsetPx
                                } else {
                                    val defaultDrawerWidthPx =
                                        with(density) { defaultDrawerWidth.toPx() }
                                    defaultDrawerWidthPx + drawerOffsetPx
                                }
                            with(density) {
                                actualDrawerVisibleWidthPx.coerceAtLeast(0f).toDp()
                            }
                        }
                    }

                    // 计算遮罩层的进度和颜色
                    val calculatedScrimProgress by remember(
                        appViewModel.drawerState.offset.value,
                        isSearchActiveInDrawer
                    ) {
                        derivedStateOf {
                            val currentOffset = appViewModel.drawerState.offset.value
                            val actualDrawerWidthPx = if (isSearchActiveInDrawer) {
                                with(density) { screenWidthDp.toPx() }
                            } else {
                                with(density) { defaultDrawerWidth.toPx() }
                            }
                            if (actualDrawerWidthPx <= 0f) 0f
                            else ((currentOffset + actualDrawerWidthPx) / actualDrawerWidthPx).coerceIn(
                                0f,
                                1f
                            )
                        }
                    }
                    val maxScrimAlpha = 0.32f
                    val dynamicScrimColor by remember(calculatedScrimProgress) {
                        derivedStateOf { Color.Black.copy(alpha = calculatedScrimProgress * maxScrimAlpha) }
                    }

                    ModalNavigationDrawer(
                        drawerState = appViewModel.drawerState,
                        gesturesEnabled = true,
                        modifier = Modifier.fillMaxSize(),
                        scrimColor = dynamicScrimColor,
                        drawerContent = {
                            AppDrawerContent(
                                historicalConversations = appViewModel.historicalConversations.collectAsState().value,
                                loadedHistoryIndex = appViewModel.loadedHistoryIndex.collectAsState().value,
                                isSearchActive = isSearchActiveInDrawer,
                                currentSearchQuery = searchQueryInDrawer,
                                onSearchActiveChange = { isActive ->
                                    appViewModel.setSearchActiveInDrawer(isActive)
                                },
                                onSearchQueryChange = { query ->
                                    appViewModel.onDrawerSearchQueryChange(query)
                                },
                                onConversationClick = { index ->
                                    appViewModel.loadConversationFromHistory(index)
                                    coroutineScope.launch { appViewModel.drawerState.close() }
                                },
                                onNewChatClick = {
                                    appViewModel.startNewChat()
                                    coroutineScope.launch { appViewModel.drawerState.close() }
                                },
                                onRenameRequest = { index -> appViewModel.showRenameDialog(index) },
                                onDeleteRequest = { index -> appViewModel.deleteConversation(index) },
                                onClearAllConversationsRequest = { appViewModel.clearAllConversations() },
                                getPreviewForIndex = { index ->
                                    appViewModel.getConversationPreviewText(index)
                                }
                            )
                        }
                    ) { // 主内容区域
                        NavHost(
                            navController = navController,
                            startDestination = Screen.CHAT_SCREEN,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = with(density) { contentOffsetX.toPx() }
                                }
                            // 注意：这里没有在NavHost级别设置全局的过渡动画，
                            // 动画移除仅针对 SettingsScreen 的 composable。
                        ) {
                            composable(Screen.CHAT_SCREEN) {
                                ChatScreen(viewModel = appViewModel, navController = navController)
                            }
                            composable(
                                route = Screen.SETTINGS_SCREEN,
                                // --- 唯一的修改在这里，移除了进入和退出动画 ---
                                enterTransition = { EnterTransition.None },
                                exitTransition = { ExitTransition.None },
                                popEnterTransition = { EnterTransition.None },
                                popExitTransition = { ExitTransition.None }
                                // ---------------------------------------
                            ) {
                                SettingsScreen(
                                    viewModel = appViewModel,
                                    navController = navController
                                )
                            }
                        }
                    }
                    // 重命名对话框的调用 (它会根据ViewModel中的状态自行显示)
                    RenameDialogInternal(viewModel = appViewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameDialogInternal(viewModel: AppViewModel) {
    val showRenameDialog by viewModel.showRenameDialogState.collectAsState()
    val renameIndex by viewModel.renamingIndexState.collectAsState()
    val renameText by viewModel.renameInputText.collectAsState()
    val focusRequester = remember { FocusRequester() }

    if (showRenameDialog && renameIndex != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRenameDialog() },
            title = { Text("重命名对话", color = Color.Black) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = viewModel::onRenameInputTextChange,
                    label = { Text("新名称") },
                    singleLine = true,
                    modifier = Modifier.focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        renameIndex?.let { idx ->
                            if (renameText.isNotBlank()) {
                                viewModel.renameConversation(idx, renameText)
                            }
                        }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = Color.Gray,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameIndex?.let { idx -> viewModel.renameConversation(idx, renameText) }
                    },
                    enabled = renameText.isNotBlank(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissRenameDialog() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) { Text("取消") }
            },
            containerColor = Color.White,
            titleContentColor = Color.Black,
            textContentColor = Color.Black
        )
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }
}