package com.example.app1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController // 确保导入
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.app1.data.local.SharedPreferencesDataSource
import com.example.app1.navigation.Screen // 确保导入
import com.example.app1.ui.screens.AppDrawerContent // <--- 确保导入你的抽屉 Composable
import com.example.app1.ui.screens.ChatScreen
import com.example.app1.ui.screens.SettingsScreen // 确保导入
import com.example.app1.ui.theme.App1Theme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction


// ViewModel Factory (保持不变)
class AppViewModelFactory(private val dataSource: SharedPreferencesDataSource) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppViewModel(dataSource) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            App1Theme {
                val snackbarHostState = remember { SnackbarHostState() }
                val navController = rememberNavController() // <--- NavController 在这里创建
                val coroutineScope = rememberCoroutineScope()

                val appViewModel: AppViewModel = viewModel(
                    factory = AppViewModelFactory(SharedPreferencesDataSource(applicationContext))
                )

                LaunchedEffect(appViewModel.snackbarMessage, snackbarHostState) {
                    appViewModel.snackbarMessage.collectLatest { message ->
                        if (message.isNotBlank() && snackbarHostState.currentSnackbarData?.visuals?.message != message) {
                            snackbarHostState.showSnackbar(message)
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = {
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) { snackbarData ->
                            Snackbar(snackbarData = snackbarData)
                        }
                    }
                ) { _ -> // 忽略 innerPadding

                    ModalNavigationDrawer(
                        drawerState = appViewModel.drawerState,
                        gesturesEnabled = true,
                        modifier = Modifier.fillMaxSize(),
                        drawerContent = {
                            // 调用你的 AppDrawerContent
                            AppDrawerContent(
                                historicalConversations = appViewModel.historicalConversations.collectAsState().value,
                                loadedHistoryIndex = appViewModel.loadedHistoryIndex.collectAsState().value,
                                onConversationClick = { index ->
                                    appViewModel.loadConversationFromHistory(index)
                                    coroutineScope.launch { appViewModel.drawerState.close() }
                                },
                                onNewChatClick = {
                                    appViewModel.startNewChat()
                                    coroutineScope.launch { appViewModel.drawerState.close() }
                                },
                                onRenameRequest = { index ->
                                    appViewModel.showRenameDialog(index)
                                },
                                onDeleteRequest = { index ->
                                    appViewModel.deleteConversation(index)
                                },
                                onClearAllConversationsRequest = {
                                    appViewModel.clearAllConversations()
                                },
                            )
                        }
                    ) {
                        // NavHost 作为抽屉的主内容区域
                        NavHost(
                            navController = navController, // <--- NavHost 使用 NavController
                            startDestination = Screen.CHAT_SCREEN,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // ChatScreen 在 NavHost 中定义，并接收 NavController
                            composable(Screen.CHAT_SCREEN) {
                                ChatScreen(
                                    viewModel = appViewModel,
                                    navController = navController // <--- 正确传递 NavController
                                )
                            }
                            // SettingsScreen 也在 NavHost 中定义
                            composable(Screen.SETTINGS_SCREEN) {
                                SettingsScreen(
                                    viewModel = appViewModel,
                                    navController = navController // SettingsScreen 也需要它来返回
                                )
                            }
                        }
                    }
                    // 重命名对话框放在 Scaffold 的顶层
                    RenameDialogInternal(viewModel = appViewModel)
                }
            }
        }
    }
}

// RenameDialogInternal (保持不变)
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
                        renameIndex?.let { idx -> viewModel.renameConversation(idx, renameText) }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
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
            confirmButton = {
                TextButton(
                    onClick = {
                        renameIndex?.let { idx ->
                            viewModel.renameConversation(
                                idx,
                                renameText
                            )
                        }
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
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}