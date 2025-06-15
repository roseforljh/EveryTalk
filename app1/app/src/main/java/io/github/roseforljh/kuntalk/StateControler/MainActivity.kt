package io.github.roseforljh.kuntalk.StateControler

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.roseforljh.kuntalk.data.local.SharedPreferencesDataSource
import io.github.roseforljh.kuntalk.data.local.database.AppDatabase
import io.github.roseforljh.kuntalk.navigation.Screen
import io.github.roseforljh.kuntalk.ui.screens.MainScreen.AppDrawerContent
import io.github.roseforljh.kuntalk.ui.screens.MainScreen.ChatScreen
import io.github.roseforljh.kuntalk.ui.screens.settings.SettingsScreen
import io.github.roseforljh.kuntalk.ui.theme.App1Theme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AppViewModelFactory(
    private val application: Application,
    private val database: AppDatabase,
    private val dataSource: SharedPreferencesDataSource
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppViewModel(application, database, dataSource) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class ${modelClass.name}")
    }
}

private val defaultDrawerWidth = 280.dp

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private var fileContentToSave: String? = null

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let {
            fileContentToSave?.let { content ->
                try {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(content.toByteArray())
                    }
                    fileContentToSave = null
                } catch (e: Exception) {
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            App1Theme {
                val snackbarHostState = remember { SnackbarHostState() }
                val navController = rememberNavController()
                val coroutineScope = rememberCoroutineScope()

                val database = AppDatabase.getDatabase(applicationContext, lifecycleScope)

                val appViewModel: AppViewModel = viewModel(
                    factory = AppViewModelFactory(
                        application,
                        database,
                        SharedPreferencesDataSource(applicationContext)
                    )
                )

                val isSearchActiveInDrawer by appViewModel.isSearchActiveInDrawer.collectAsState()
                val searchQueryInDrawer by appViewModel.searchQueryInDrawer.collectAsState()

                LaunchedEffect(appViewModel.snackbarMessage, snackbarHostState) {
                    appViewModel.snackbarMessage.collectLatest { message ->
                        if (message.isNotBlank() && snackbarHostState.currentSnackbarData?.visuals?.message != message) {
                            snackbarHostState.showSnackbar(message)
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    appViewModel.exportRequest.collectLatest { (fileName, content) ->
                        fileContentToSave = content
                        createDocument.launch(fileName)
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
                ) { _ ->
                    val density = LocalDensity.current
                    val configuration = LocalConfiguration.current
                    val screenWidthDp = configuration.screenWidthDp.dp

                    LaunchedEffect(appViewModel.drawerState.isClosed, isSearchActiveInDrawer) {
                        if (appViewModel.drawerState.isClosed && isSearchActiveInDrawer) {
                            appViewModel.setSearchActiveInDrawer(false)
                        }
                    }

                    val contentOffsetX by remember(
                        appViewModel.drawerState.currentValue,
                        appViewModel.drawerState.offset.value,
                        isSearchActiveInDrawer
                    ) {
                        derivedStateOf {
                            val drawerOffsetPx = appViewModel.drawerState.offset.value
                            val actualDrawerVisibleWidthPx = if (isSearchActiveInDrawer) {
                                val screenWidthPx = with(density) { screenWidthDp.toPx() }
                                screenWidthPx + drawerOffsetPx
                            } else {
                                val defaultDrawerWidthPx =
                                    with(density) { defaultDrawerWidth.toPx() }
                                defaultDrawerWidthPx + drawerOffsetPx
                            }
                            with(density) { actualDrawerVisibleWidthPx.coerceAtLeast(0f).toDp() }
                        }
                    }

                    val calculatedScrimProgress by remember(
                        appViewModel.drawerState.offset.value,
                        isSearchActiveInDrawer
                    ) {
                        derivedStateOf {
                            val currentOffset = appViewModel.drawerState.offset.value
                            val actualDrawerWidthPx =
                                if (isSearchActiveInDrawer) with(density) { screenWidthDp.toPx() }
                                else with(density) { defaultDrawerWidth.toPx() }
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
                                    appViewModel.setSearchActiveInDrawer(
                                        isActive
                                    )
                                },
                                onSearchQueryChange = { query ->
                                    appViewModel.onDrawerSearchQueryChange(
                                        query
                                    )
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
                                    appViewModel.getConversationPreviewText(
                                        index
                                    )
                                }
                            )
                        }
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = Screen.CHAT_SCREEN,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = with(density) { contentOffsetX.toPx() }
                                }
                        ) {
                            composable(Screen.CHAT_SCREEN) {
                                ChatScreen(viewModel = appViewModel, navController = navController)
                            }
                            composable(
                                route = Screen.SETTINGS_SCREEN,
                                enterTransition = { EnterTransition.None },
                                exitTransition = { ExitTransition.None },
                                popEnterTransition = { EnterTransition.None },
                                popExitTransition = { ExitTransition.None }
                            ) {
                                SettingsScreen(
                                    viewModel = appViewModel,
                                    navController = navController
                                )
                            }
                        }
                    }
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
                            if (renameText.isNotBlank()) viewModel.renameConversation(
                                idx,
                                renameText
                            )
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
