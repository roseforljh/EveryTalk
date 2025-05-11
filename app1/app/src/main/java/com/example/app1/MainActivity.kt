package com.example.app1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.app1.data.local.SharedPreferencesDataSource
import com.example.app1.navigation.Screen // 导航路由定义
import com.example.app1.ui.screens.viewmodel.AppDrawerContent // 抽屉内容 Composable
import com.example.app1.ui.screens.ChatScreen // 聊天屏幕 Composable
import com.example.app1.ui.screens.SettingsScreen // 设置屏幕 Composable
import com.example.app1.ui.theme.App1Theme // 应用主题
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction

// import kotlin.math.abs // abs 导入已存在，这里确认下

// ViewModel 工厂，用于创建 AppViewModel 实例并传入依赖 (SharedPreferencesDataSource)
class AppViewModelFactory(private val dataSource: SharedPreferencesDataSource) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) { // 如果请求的是 AppViewModel 类型
            @Suppress("UNCHECKED_CAST")
            return AppViewModel(dataSource) as T // 创建并返回实例
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}") // 未知类型则抛出异常
    }
}

private val defaultDrawerWidth = 280.dp // 抽屉的默认宽度

@OptIn(ExperimentalMaterial3Api::class) // 使用 Material3 实验性 API
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // 启用全屏沉浸式体验，让内容可以绘制到系统栏区域
        setContent {
            App1Theme { // 应用自定义主题
                val snackbarHostState = remember { SnackbarHostState() } // Snackbar 的状态管理器
                val navController = rememberNavController() // 导航控制器
                val coroutineScope = rememberCoroutineScope() // 获取协程作用域，用于启动协程

                // 获取 AppViewModel 实例，使用自定义工厂提供依赖
                val appViewModel: AppViewModel = viewModel(
                    factory = AppViewModelFactory(SharedPreferencesDataSource(applicationContext))
                )

                // 监听 ViewModel 中的 snackbarMessage SharedFlow，并在消息到来时显示 Snackbar
                LaunchedEffect(appViewModel.snackbarMessage, snackbarHostState) {
                    appViewModel.snackbarMessage.collectLatest { message -> // collectLatest确保只处理最新的消息
                        if (message.isNotBlank() && snackbarHostState.currentSnackbarData?.visuals?.message != message) {
                            // 如果消息非空且与当前显示的Snackbar消息不同，则显示新的Snackbar
                            snackbarHostState.showSnackbar(message)
                        }
                    }
                }

                Scaffold( // Material3 的脚手架布局
                    modifier = Modifier.fillMaxSize(), // 填充整个屏幕
                    snackbarHost = { // 定义 Snackbar 的宿主位置和样式
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.padding(bottom = 16.dp) // Snackbar 距离底部的边距
                        ) { snackbarData ->
                            Snackbar(snackbarData = snackbarData) // 使用默认的 Snackbar Composable
                        }
                    }
                ) { _ -> // Scaffold 的 content lambda，参数是 paddingValues (此处未使用，因为 ModalNavigationDrawer 会处理)

                    val density = LocalDensity.current // 获取当前屏幕密度
                    val drawerWidthPx =
                        with(density) { defaultDrawerWidth.toPx() } // 将抽屉宽度从 dp 转换为 px

                    // 计算主内容区域的水平偏移量，当抽屉打开时，主内容向右移动
                    val contentOffsetX by remember(
                        appViewModel.drawerState.currentValue,
                        appViewModel.drawerState.offset
                    ) {
                        derivedStateOf {
                            // drawerState.offset.value 表示抽屉从完全隐藏 (-drawerWidthPx) 到完全显示 (0) 的当前偏移量
                            // 我们需要的是抽屉可见部分的宽度
                            val visibleDrawerWidthPx =
                                drawerWidthPx + appViewModel.drawerState.offset.value
                            with(density) { visibleDrawerWidthPx.toDp() } // 转换为 dp
                        }
                    }

                    // --- 手动计算遮罩(Scrim)进度和颜色 ---
                    // ModalNavigationDrawer 的默认 scrimColor 是固定的，这里我们根据抽屉的打开程度动态计算 scrim 的透明度
                    val calculatedScrimProgress by remember(
                        appViewModel.drawerState.offset.value, // 依赖抽屉的当前偏移量
                        drawerWidthPx // 依赖抽屉的总宽度 (px)
                    ) {
                        derivedStateOf { // 使用 derivedStateOf 优化重计算
                            if (drawerWidthPx <= 0f) { // 防止除以零
                                0f // 抽屉宽度无效，则进度为0
                            } else {
                                // drawerState.offset.value 从 -drawerWidthPx (关闭) 到 0 (打开)
                                // 我们需要将其映射到 0.0f (关闭) 到 1.0f (打开) 的进度值
                                val currentOffset = appViewModel.drawerState.offset.value
                                // (当前偏移量 + 抽屉宽度) / 抽屉宽度
                                // 当关闭时: (-drawerWidthPx + drawerWidthPx) / drawerWidthPx = 0 / drawerWidthPx = 0
                                // 当打开时: (0 + drawerWidthPx) / drawerWidthPx = drawerWidthPx / drawerWidthPx = 1
                                val progress = (currentOffset + drawerWidthPx) / drawerWidthPx
                                progress.coerceIn(0f, 1f) // 确保值在 0 和 1 之间
                            }
                        }
                    }
                    val maxScrimAlpha = 0.32f // 定义遮罩的最大不透明度
                    val dynamicScrimColor by remember(calculatedScrimProgress) { // 根据计算出的进度动态生成颜色
                        derivedStateOf {
                            Color.Black.copy(alpha = calculatedScrimProgress * maxScrimAlpha) // 黑色背景，透明度随进度变化
                        }
                    }
                    // --- 手动计算遮罩进度结束 ---

                    ModalNavigationDrawer( // 模态导航抽屉
                        drawerState = appViewModel.drawerState, // 抽屉状态 (来自 ViewModel)
                        gesturesEnabled = true, // 允许通过手势打开/关闭抽屉
                        modifier = Modifier.fillMaxSize(),
                        scrimColor = dynamicScrimColor, // 使用计算出的动态遮罩颜色
                        drawerContent = { // 定义抽屉内部的内容
                            AppDrawerContent(
                                historicalConversations = appViewModel.historicalConversations.collectAsState().value, // 历史对话列表
                                loadedHistoryIndex = appViewModel.loadedHistoryIndex.collectAsState().value, // 当前加载的历史对话索引
                                onConversationClick = { index -> // 点击历史对话项的回调
                                    appViewModel.loadConversationFromHistory(index) // 加载对话
                                    coroutineScope.launch { appViewModel.drawerState.close() } // 关闭抽屉
                                },
                                onNewChatClick = { // 点击新建聊天的回调
                                    appViewModel.startNewChat() // 开始新聊天
                                    coroutineScope.launch { appViewModel.drawerState.close() } // 关闭抽屉
                                },
                                onRenameRequest = { index -> // 请求重命名对话的回调
                                    appViewModel.showRenameDialog(index) // 显示重命名对话框 (抽屉此时可能保持打开)
                                },
                                onDeleteRequest = { index -> // 请求删除对话的回调
                                    appViewModel.deleteConversation(index) // 删除对话 (抽屉此时可能保持打开)
                                },
                                onClearAllConversationsRequest = { // 请求清除所有对话的回调
                                    appViewModel.clearAllConversations() // 清除所有对话
                                },
                            )
                        }
                    ) { // ModalNavigationDrawer 的主内容区域 lambda
                        NavHost( // 导航容器
                            navController = navController, // 导航控制器
                            startDestination = Screen.CHAT_SCREEN, // 起始页面路由
                            modifier = Modifier
                                .fillMaxSize()
                                .offset(x = contentOffsetX) // 根据抽屉状态偏移主内容
                        ) {
                            composable(Screen.CHAT_SCREEN) { // 定义聊天屏幕的路由和内容
                                ChatScreen(
                                    viewModel = appViewModel,
                                    navController = navController
                                )
                            }
                            composable(Screen.SETTINGS_SCREEN) { // 定义设置屏幕的路由和内容
                                SettingsScreen(
                                    viewModel = appViewModel,
                                    navController = navController
                                )
                            }
                            // 其他 composable 路由可在此处添加
                        }
                    }
                    // 将重命名对话框放在 ModalNavigationDrawer 外部，使其可以覆盖整个屏幕 (包括抽屉)
                    RenameDialogInternal(viewModel = appViewModel)
                }
            }
        }
    }
}

// 内部 Composable 函数，用于显示重命名对话框
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameDialogInternal(viewModel: AppViewModel) {
    val showRenameDialog by viewModel.showRenameDialogState.collectAsState() // 是否显示对话框
    val renameIndex by viewModel.renamingIndexState.collectAsState() // 正在重命名的对话索引
    val renameText by viewModel.renameInputText.collectAsState() // 对话框输入框的文本
    val focusRequester = remember { FocusRequester() } // 用于请求焦点到输入框

    if (showRenameDialog && renameIndex != null) { // 当需要显示且索引有效时
        AlertDialog(
            onDismissRequest = { viewModel.dismissRenameDialog() }, // 点击外部或返回键时关闭
            title = { Text("重命名对话", color = Color.Black) }, // 标题
            text = { // 内容区域：一个文本输入框
                OutlinedTextField(
                    value = renameText,
                    onValueChange = viewModel::onRenameInputTextChange, // 文本变化时更新 ViewModel
                    label = { Text("新名称") }, // 标签
                    singleLine = true, // 单行输入
                    modifier = Modifier.focusRequester(focusRequester), // 关联焦点请求器
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), // 键盘回车键设为"完成"
                    keyboardActions = KeyboardActions(onDone = { // 点击"完成"时的操作
                        renameIndex?.let { idx ->
                            viewModel.renameConversation(
                                idx,
                                renameText
                            )
                        } // 执行重命名
                    }),
                    colors = OutlinedTextFieldDefaults.colors( // 输入框颜色配置
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = Color.Gray,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray
                    )
                )
            },
            confirmButton = { // 确认按钮
                TextButton(
                    onClick = {
                        renameIndex?.let { idx ->
                            viewModel.renameConversation(idx, renameText) // 执行重命名
                        }
                    },
                    enabled = renameText.isNotBlank(), // 当输入框非空时才可用
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) { Text("确定") }
            },
            dismissButton = { // 取消按钮
                TextButton(
                    onClick = { viewModel.dismissRenameDialog() }, // 关闭对话框
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) { Text("取消") }
            },
            containerColor = Color.White, // 对话框背景色
            titleContentColor = Color.Black, // 标题文本颜色
            textContentColor = Color.Black // 内容区域文本颜色 (OutlinedTextField 内部已设置)
        )
        // 当对话框显示时，请求焦点到输入框
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }
}