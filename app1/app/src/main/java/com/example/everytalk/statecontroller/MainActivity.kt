package com.example.everytalk.statecontroller

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.everytalk.data.local.SharedPreferencesDataSource
import com.example.everytalk.data.network.ApiClient
import com.example.everytalk.navigation.Screen
import com.example.everytalk.ui.screens.MainScreen.AppDrawerContent
import com.example.everytalk.ui.screens.MainScreen.ChatScreen
import com.example.everytalk.ui.screens.settings.SettingsScreen
import com.example.everytalk.ui.theme.App1Theme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AppViewModelFactory(
    private val application: Application,
    private val dataSource: SharedPreferencesDataSource
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppViewModel(application, dataSource) as T
        }
        throw IllegalArgumentException("未知的 ViewModel 类: ${modelClass.name}")
    }
}

private val defaultDrawerWidth = 320.dp

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
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ApiClient.initialize(this)
        enableEdgeToEdge()
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContent {
            App1Theme(dynamicColor = false) {
                var showSplash by remember { mutableStateOf(true) }

                if (showSplash) {
                    SplashScreen(onAnimationEnd = { showSplash = false })
                } else {
                    val snackbarHostState = remember { SnackbarHostState() }
                    val navController = rememberNavController()
                    val coroutineScope = rememberCoroutineScope()

                    val appViewModel: AppViewModel = viewModel(
                        factory = AppViewModelFactory(
                            application,
                            SharedPreferencesDataSource(applicationContext)
                        )
                    )

                    val isSearchActiveInDrawer by appViewModel.isSearchActiveInDrawer.collectAsState()
                    val searchQueryInDrawer by appViewModel.searchQueryInDrawer.collectAsState()
                    val isLoadingHistoryData by appViewModel.isLoadingHistoryData.collectAsState()

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
                        containerColor = MaterialTheme.colorScheme.background,
                        snackbarHost = {
                            SnackbarHost(
                                hostState = snackbarHostState,
                                modifier = Modifier.padding(bottom = 16.dp)
                            ) { snackbarData ->
                                Snackbar(snackbarData = snackbarData)
                            }
                        }
                    ) { contentPadding ->
                        val density = LocalDensity.current
                        val configuration = LocalConfiguration.current
                        val screenWidthDp = configuration.screenWidthDp.dp

                        LaunchedEffect(appViewModel.drawerState.isClosed, isSearchActiveInDrawer) {
                            if (appViewModel.drawerState.isClosed && isSearchActiveInDrawer) {
                                appViewModel.setSearchActiveInDrawer(false)
                            }
                        }

                        DismissibleNavigationDrawer(
                            drawerState = appViewModel.drawerState,
                            gesturesEnabled = true,
                            modifier = Modifier.fillMaxSize(),
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
                                    onRenameRequest = { index, newName -> appViewModel.renameConversation(index, newName) },
                                    onDeleteRequest = { index -> appViewModel.deleteConversation(index) },
                                    onClearAllConversationsRequest = { appViewModel.clearAllConversations() },
                                    getPreviewForIndex = { index ->
                                        appViewModel.getConversationPreviewText(
                                            index
                                        )
                                    },
                                    onAboutClick = { appViewModel.showAboutDialog() },
                                    isLoadingHistoryData = isLoadingHistoryData
                                )
                            }
                        ) {
                            NavHost(
                                navController = navController,
                                startDestination = Screen.CHAT_SCREEN,
                                modifier = Modifier
                                    .fillMaxSize()
                            ) {
                                composable(Screen.CHAT_SCREEN) {
                                    ChatScreen(viewModel = appViewModel, navController = navController)
                                }
                                composable(
                                    route = Screen.SETTINGS_SCREEN,
                                    enterTransition = { androidx.compose.animation.EnterTransition.None },
                                    exitTransition = { ExitTransition.None },
                                    popEnterTransition = { androidx.compose.animation.EnterTransition.None },
                                    popExitTransition = { ExitTransition.None }
                                ) {
                                    SettingsScreen(
                                        viewModel = appViewModel,
                                        navController = navController
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun SplashScreen(onAnimationEnd: () -> Unit) {
        var startAnimation by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(
            targetValue = if (startAnimation) 1f else 0f,
            animationSpec = tween(durationMillis = 800),
            label = "SplashScale"
        )

        LaunchedEffect(Unit) {
            startAnimation = true
            kotlinx.coroutines.delay(1200) // 800ms for anim, 400ms pause
            onAnimationEnd()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Image(
                painter = androidx.compose.ui.res.painterResource(
                    id = if (isSystemInDarkTheme()) com.example.everytalk.R.drawable.logo_dark
                         else com.example.everytalk.R.drawable.ic_foreground_logo
                ),
                contentDescription = "Logo",
                modifier = Modifier.scale(scale)
            )
        }
    }
}