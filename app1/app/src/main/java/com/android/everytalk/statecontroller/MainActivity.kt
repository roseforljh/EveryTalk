package com.android.everytalk.statecontroller

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.profileinstaller.ProfileInstaller
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
// Removed SharedPreferencesDataSource import
import com.android.everytalk.data.network.ApiClient
import com.android.everytalk.navigation.Screen
import com.android.everytalk.ui.screens.MainScreen.AppDrawerContent
import com.android.everytalk.ui.screens.MainScreen.ChatScreen
import com.android.everytalk.ui.screens.ImageGeneration.ImageGenerationScreen
import com.android.everytalk.ui.screens.settings.SettingsScreen
import com.android.everytalk.ui.theme.App1Theme
import kotlinx.coroutines.flow.collectLatest

class AppViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppViewModel(application) as T
        }
        throw IllegalArgumentException("未知的 ViewModel 类: ${modelClass.name}")
    }
}

private val defaultDrawerWidth = 320.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberDrawerSessionKey(drawerState: DrawerState): Int {
    var drawerSessionKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(drawerState) {
        var wasDrawerActive = drawerState.currentValue != DrawerValue.Closed ||
            drawerState.targetValue != DrawerValue.Closed
        var wasDrawerTargetClosed = drawerState.targetValue == DrawerValue.Closed

        snapshotFlow { drawerState.currentValue to drawerState.targetValue }.collect { (currentValue, targetValue) ->
            val isDrawerActive = currentValue != DrawerValue.Closed || targetValue != DrawerValue.Closed
            val isDrawerTargetClosed = targetValue == DrawerValue.Closed
            if ((!wasDrawerActive && isDrawerActive) || (wasDrawerTargetClosed && !isDrawerTargetClosed)) {
                drawerSessionKey += 1
            }
            wasDrawerActive = isDrawerActive
            wasDrawerTargetClosed = isDrawerTargetClosed
        }
    }

    return drawerSessionKey
}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private var fileContentToSave: String? = null
    private lateinit var appViewModel: AppViewModel
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let {
            fileContentToSave?.let { content ->
                try {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(content.toByteArray())
                    }
                    fileContentToSave = null
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to save file content", e)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 异步初始化ProfileInstaller
        lifecycleScope.launch(Dispatchers.IO) {
            ProfileInstaller.writeProfile(this@MainActivity)
        }
        
        // 处理分享过来的内容（首次启动）
        handleIncomingShareIntent(intent)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ApiClient.initialize(this)
        enableEdgeToEdge()
        
        // 设置透明的状态栏和导航栏
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        
        // 强制设置导航栏完全透明
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        
        setContent {
            App1Theme(dynamicColor = false) {
                // 根据当前主题动态设置状态栏和导航栏图标颜色
                val isDarkTheme = isSystemInDarkTheme()
                val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
                windowInsetsController.isAppearanceLightNavigationBars = !isDarkTheme
                windowInsetsController.isAppearanceLightStatusBars = !isDarkTheme
                
                val snackbarHostState = remember { SnackbarHostState() }
                    val navController = rememberNavController()
                    val coroutineScope = rememberCoroutineScope()

                    appViewModel = viewModel(
                        factory = AppViewModelFactory(
                            application
                        )
                    )

                    // 应用启动：静默检查更新，UI 侧监听 latestReleaseInfo/updateInfo 后弹出统一更新对话卡
                    LaunchedEffect(Unit) {
                        appViewModel.checkForUpdatesSilently()
                    }

                    val isSearchActiveInDrawer by appViewModel.isSearchActiveInDrawer.collectAsState()
                    val searchQueryInDrawer by appViewModel.searchQueryInDrawer.collectAsState()
                    val expandedDrawerItemIndex by appViewModel.expandedDrawerItemIndex.collectAsState()
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
                        contentWindowInsets = WindowInsets(0, 0, 0, 0), // 允许内容延伸到系统栏区域
                        snackbarHost = {
                            SnackbarHost(
                                hostState = snackbarHostState,
                                modifier = Modifier
                                    .padding(bottom = 16.dp)
                                    .windowInsetsPadding(WindowInsets.navigationBars) // 确保Snackbar在导航栏上方
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

                        // 处理抽屉的返回键逻辑 - 最低优先级，只在抽屉打开时有效
                        BackHandler(enabled = !appViewModel.drawerState.isClosed) {
                            coroutineScope.launch {
                                appViewModel.drawerState.close()
                            }
                        }

                        // 🎯 根据代码块滚动状态动态控制抽屉手势
                        val isCodeBlockScrolling by appViewModel.gestureManager.isCodeBlockScrolling.collectAsState()
                        val drawerSessionKey = rememberDrawerSessionKey(appViewModel.drawerState)

                        DismissibleNavigationDrawer(
                            drawerState = appViewModel.drawerState,
                            gesturesEnabled = !isCodeBlockScrolling, // 代码块滚动时禁用抽屉手势
                            modifier = Modifier.fillMaxSize(),
                            drawerContent = {
                                val navBackStackEntry by navController.currentBackStackEntryAsState()
                                val currentRoute = navBackStackEntry?.destination?.route
                                val isImageGenerationMode = currentRoute == Screen.IMAGE_GENERATION_SCREEN

                                LaunchedEffect(drawerSessionKey, isImageGenerationMode) {
                                    if (drawerSessionKey > 0 && expandedDrawerItemIndex != null) {
                                        appViewModel.setExpandedDrawerItemIndex(null)
                                    }
                                }
                                
                                // 置顶集合状态
                                val pinnedIds = if (isImageGenerationMode) {
                                    appViewModel.stateHolder.pinnedImageConversationIds.collectAsState().value
                                } else {
                                    appViewModel.stateHolder.pinnedTextConversationIds.collectAsState().value
                                }

                                key(drawerSessionKey, isImageGenerationMode) {
                                    AppDrawerContent(
                                        historicalConversations = if (isImageGenerationMode) appViewModel.imageGenerationHistoricalConversations.collectAsState().value else appViewModel.historicalConversations.collectAsState().value,
                                        loadedHistoryIndex = if (isImageGenerationMode) appViewModel.loadedImageGenerationHistoryIndex.collectAsState().value else appViewModel.loadedHistoryIndex.collectAsState().value,
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
                                        onImageGenerationConversationClick = { index ->
                                        // 先声明意图模式，避免因内容/索引造成的误判
                                        // 跨模式点击时显示 Toast 提示
                                        appViewModel.simpleModeManager.setIntendedMode(SimpleModeManager.ModeType.IMAGE, showToast = !isImageGenerationMode)
                                        // 跨模式点击时，先跳转到图像生成页
                                        if (!isImageGenerationMode) {
                                            navController.navigate(Screen.IMAGE_GENERATION_SCREEN) {
                                                popUpTo(navController.graph.startDestinationRoute!!) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                            // 等待页面过渡完成后再加载历史会话
                                            coroutineScope.launch {
                                                // 等待导航和动画完成 - 400ms确保300ms过渡动画完全结束 + 额外缓冲时间
                                                kotlinx.coroutines.delay(400) // 稍微超过动画时间，确保过渡流畅
                                                // 🔥 修复：不清除文本模式索引，保持两个模式独立
                                                // appViewModel.stateHolder._loadedHistoryIndex.value = null
                                                appViewModel.loadImageGenerationConversationFromHistory(index)
                                                appViewModel.drawerState.close()
                                            }
                                        } else {
                                            // 同模式内点击，直接加载
                                            // 🔥 修复：不清除文本模式索引，保持两个模式独立
                                            // appViewModel.stateHolder._loadedHistoryIndex.value = null
                                            appViewModel.loadImageGenerationConversationFromHistory(index)
                                            coroutineScope.launch { appViewModel.drawerState.close() }
                                        }
                                    },
                                    onConversationClick = { index ->
                                        // 先声明意图模式，避免因内容/索引造成的误判
                                        // 跨模式点击时显示 Toast 提示
                                        appViewModel.simpleModeManager.setIntendedMode(SimpleModeManager.ModeType.TEXT, showToast = isImageGenerationMode)
                                        // 跨模式点击时，先跳转到文本聊天页
                                        if (isImageGenerationMode) {
                                            navController.navigate(Screen.CHAT_SCREEN) {
                                                popUpTo(navController.graph.startDestinationRoute!!) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                            // 等待页面过渡完成后再加载历史会话
                                            coroutineScope.launch {
                                                // 等待导航和动画完成 - 400ms确保300ms过渡动画完全结束 + 额外缓冲时间
                                                kotlinx.coroutines.delay(400) // 稍微超过动画时间，确保过渡流畅
                                                // 🔥 修复：不清除图像模式索引，保持两个模式独立
                                                // appViewModel.stateHolder._loadedImageGenerationHistoryIndex.value = null
                                                appViewModel.loadConversationFromHistory(index)
                                                appViewModel.drawerState.close()
                                            }
                                        } else {
                                            // 同模式内点击，直接加载
                                            // 🔥 修复：不清除图像模式索引，保持两个模式独立
                                            // appViewModel.stateHolder._loadedImageGenerationHistoryIndex.value = null
                                            appViewModel.loadConversationFromHistory(index)
                                            coroutineScope.launch { appViewModel.drawerState.close() }
                                        }
                                    },
                                    onNewChatClick = {
                                        if (isImageGenerationMode) {
                                            // 从图像模式切换到文本模式，显示 Toast
                                            appViewModel.simpleModeManager.setIntendedMode(SimpleModeManager.ModeType.TEXT, showToast = true)
                                            coroutineScope.launch { appViewModel.drawerState.close() }
                                            // 🔥 修复：不清除图像模式索引，保持两个模式独立
                                            // appViewModel.stateHolder._loadedImageGenerationHistoryIndex.value = null
                                            navController.navigate(Screen.CHAT_SCREEN) {
                                                popUpTo(navController.graph.startDestinationRoute!!) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                            appViewModel.startNewChat()
                                        } else {
                                            appViewModel.startNewChat()
                                        }
                                        coroutineScope.launch { appViewModel.drawerState.close() }
                                    },
                                    onRenameRequest = { index, newName ->
                                        appViewModel.renameConversation(
                                            index,
                                            newName,
                                            isImageGeneration = isImageGenerationMode
                                        )
                                    },
                                    onDeleteRequest = { index ->
                                        if (isImageGenerationMode) {
                                            appViewModel.deleteImageGenerationConversation(index)
                                        } else {
                                            appViewModel.deleteConversation(index)
                                        }
                                    },
                                    onClearAllConversationsRequest = appViewModel::clearAllConversations,
                                    onClearAllImageGenerationConversationsRequest = appViewModel::clearAllImageGenerationConversations,
                                    showClearImageHistoryDialog = appViewModel.showClearImageHistoryDialog.collectAsState().value,
                                    onShowClearImageHistoryDialog = appViewModel::showClearImageHistoryDialog,
                                    onDismissClearImageHistoryDialog = appViewModel::dismissClearImageHistoryDialog,
                                    getPreviewForIndex = { index ->
                                        appViewModel.getConversationPreviewText(
                                            index,
                                            isImageGenerationMode
                                        )
                                    },
                                    getFullTextForIndex = { index ->
                                        appViewModel.getConversationFullText(
                                            index,
                                            isImageGenerationMode
                                        )
                                    },
                                    onAboutClick = { appViewModel.showAboutDialog() },
                                    onImageGenerationClick = {
                                        // 从文本模式切换到图像模式，显示 Toast
                                        appViewModel.simpleModeManager.setIntendedMode(SimpleModeManager.ModeType.IMAGE, showToast = !isImageGenerationMode)
                                        coroutineScope.launch { appViewModel.drawerState.close() }
                                        // 🔥 修复：不清除文本模式索引，保持两个模式独立
                                        // appViewModel.stateHolder._loadedHistoryIndex.value = null
                                        navController.navigate(Screen.IMAGE_GENERATION_SCREEN) {
                                            popUpTo(navController.graph.startDestinationRoute!!) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                        appViewModel.startNewImageGeneration()
                                    },
                                    isLoadingHistoryData = isLoadingHistoryData,
                                    isImageGenerationMode = isImageGenerationMode,
                                    expandedItemIndex = expandedDrawerItemIndex,
                                    onExpandItem = { index -> appViewModel.setExpandedDrawerItemIndex(index) },
                                    pinnedIds = pinnedIds,
                                    onTogglePin = { index ->
                                        appViewModel.togglePinForConversation(index, isImageGenerationMode)
                                    },
                                    conversationGroups = appViewModel.stateHolder.conversationGroups.collectAsState().value,
                                    onCreateGroup = { groupName -> appViewModel.createGroup(groupName) },
                                    onRenameGroup = { oldName, newName -> appViewModel.renameGroup(oldName, newName) },
                                    onDeleteGroup = { groupName -> appViewModel.deleteGroup(groupName) },
                                    onMoveConversationToGroup = { index, groupName, isImageGen -> appViewModel.moveConversationToGroup(index, groupName, isImageGen) },
                                    expandedGroups = appViewModel.stateHolder.expandedGroups.collectAsState().value,
                                    onToggleGroup = { groupKey -> appViewModel.toggleGroupExpanded(groupKey) },
                                    onShareConversation = { index ->
                                        appViewModel.shareConversation(index, isImageGenerationMode)
                                    }
                                    )
                                }
                            }
                        ) {
                            NavHost(
                                navController = navController,
                                startDestination = Screen.CHAT_SCREEN,
                                modifier = Modifier
                                    .fillMaxSize()
                            ) {
                                composable(
                                    route = Screen.CHAT_SCREEN,
                                    enterTransition = {
                                        androidx.compose.animation.fadeIn(
                                            animationSpec = tween(
                                                durationMillis = 280,
                                                easing = FastOutSlowInEasing
                                            )
                                        )
                                    },
                                    exitTransition = {
                                        androidx.compose.animation.fadeOut(
                                            animationSpec = tween(
                                                durationMillis = 220,
                                                easing = FastOutSlowInEasing
                                            )
                                        )
                                    },
                                    popEnterTransition = {
                                        androidx.compose.animation.fadeIn(
                                            animationSpec = tween(
                                                durationMillis = 280,
                                                easing = FastOutSlowInEasing
                                            )
                                        )
                                    },
                                    popExitTransition = {
                                        androidx.compose.animation.fadeOut(
                                            animationSpec = tween(
                                                durationMillis = 220,
                                                easing = FastOutSlowInEasing
                                            )
                                        )
                                    }
                                ) {
                                    ChatScreen(viewModel = appViewModel, navController = navController)
                                }
                               composable(
                                   route = Screen.IMAGE_GENERATION_SCREEN,
                                   enterTransition = {
                                       androidx.compose.animation.fadeIn(
                                           animationSpec = tween(
                                               durationMillis = 280,
                                               easing = FastOutSlowInEasing
                                           )
                                       )
                                   },
                                   exitTransition = {
                                       androidx.compose.animation.fadeOut(
                                           animationSpec = tween(
                                               durationMillis = 220,
                                               easing = FastOutSlowInEasing
                                           )
                                       )
                                   },
                                   popEnterTransition = {
                                       androidx.compose.animation.fadeIn(
                                           animationSpec = tween(
                                               durationMillis = 280,
                                               easing = FastOutSlowInEasing
                                           )
                                       )
                                   },
                                   popExitTransition = {
                                       androidx.compose.animation.fadeOut(
                                           animationSpec = tween(
                                               durationMillis = 220,
                                               easing = FastOutSlowInEasing
                                           )
                                       )
                                   }
                               ) {
                                    ImageGenerationScreen(viewModel = appViewModel, navController = navController)
                               }
                                composable(
                                    route = Screen.SETTINGS_SCREEN,
                                    enterTransition = {
                                        androidx.compose.animation.slideInHorizontally(
                                            initialOffsetX = { fullWidth -> fullWidth },
                                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                                        )
                                    },
                                    exitTransition = {
                                        androidx.compose.animation.slideOutHorizontally(
                                            targetOffsetX = { fullWidth -> fullWidth },
                                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                                        )
                                    },
                                    popEnterTransition = {
                                        androidx.compose.animation.slideInHorizontally(
                                            initialOffsetX = { fullWidth -> fullWidth },
                                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                                        )
                                    },
                                    popExitTransition = {
                                        androidx.compose.animation.slideOutHorizontally(
                                            targetOffsetX = { fullWidth -> fullWidth },
                                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                                        )
                                    }
                                ) {
                                    SettingsScreen(
                                        viewModel = appViewModel,
                                        navController = navController
                                    )
                                }
                                composable(
                                    route = Screen.IMAGE_GENERATION_SETTINGS_SCREEN,
                                    enterTransition = {
                                        androidx.compose.animation.slideInHorizontally(
                                            initialOffsetX = { fullWidth -> fullWidth },
                                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                                        )
                                    },
                                    exitTransition = {
                                        androidx.compose.animation.slideOutHorizontally(
                                            targetOffsetX = { fullWidth -> fullWidth },
                                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                                        )
                                    },
                                    popEnterTransition = {
                                        androidx.compose.animation.slideInHorizontally(
                                            initialOffsetX = { fullWidth -> fullWidth },
                                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                                        )
                                    },
                                    popExitTransition = {
                                        androidx.compose.animation.slideOutHorizontally(
                                            targetOffsetX = { fullWidth -> fullWidth },
                                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                                        )
                                    }
                                ) {
                                    com.android.everytalk.ui.screens.ImageGeneration.ImageGenerationSettingsScreen(
                                        viewModel = appViewModel,
                                        navController = navController
                                    )
                                }
                               composable(
                                   route = Screen.VOICE_INPUT_SCREEN,
                                   enterTransition = {
                                       androidx.compose.animation.slideInHorizontally(
                                           initialOffsetX = { fullWidth -> fullWidth },
                                           animationSpec = tween(300, easing = FastOutSlowInEasing)
                                       )
                                   },
                                   exitTransition = {
                                       androidx.compose.animation.slideOutHorizontally(
                                           targetOffsetX = { fullWidth -> fullWidth },
                                           animationSpec = tween(300, easing = FastOutSlowInEasing)
                                       )
                                   },
                                   popEnterTransition = {
                                       androidx.compose.animation.slideInHorizontally(
                                           initialOffsetX = { fullWidth -> fullWidth },
                                           animationSpec = tween(300, easing = FastOutSlowInEasing)
                                       )
                                   },
                                   popExitTransition = {
                                       androidx.compose.animation.slideOutHorizontally(
                                           targetOffsetX = { fullWidth -> fullWidth },
                                           animationSpec = tween(300, easing = FastOutSlowInEasing)
                                       )
                                   }
                              ) {
                                  val selectedApiConfig by appViewModel.selectedApiConfig.collectAsState()
                                  com.android.everytalk.ui.screens.MainScreen.chat.voice.ui.VoiceInputScreen(
                                      onClose = { navController.popBackStack() },
                                      selectedApiConfig = selectedApiConfig,
                                      viewModel = appViewModel
                                  )
                              }
                           }
                       }
               }
           }
       }
   }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 处理分享过来的内容（应用已在运行时）
        handleIncomingShareIntent(intent)
    }
    
    /**
     * 处理系统分享过来的文本内容
     * 支持两种方式：
     * 1. 直接分享文本（EXTRA_TEXT）
     * 2. 分享文本文件（EXTRA_STREAM）- 读取文件内容
     */
    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        
        when {
            // 处理直接分享的文本
            intent.type == "text/plain" -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
                    if (sharedText.isNotBlank()) {
                        lifecycleScope.launch {
                            // 等待 ViewModel 初始化完成
                            while (!::appViewModel.isInitialized) {
                                kotlinx.coroutines.delay(50)
                            }
                            appViewModel.onTextChange(sharedText)
                            appViewModel.showSnackbar("已接收分享内容")
                        }
                    }
                }
            }
            // 处理分享的文本文件
            intent.type?.startsWith("text/") == true -> {
                val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                uri?.let { fileUri ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val content = contentResolver.openInputStream(fileUri)?.use { inputStream ->
                                inputStream.bufferedReader().readText()
                            }
                            if (!content.isNullOrBlank()) {
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    while (!::appViewModel.isInitialized) {
                                        kotlinx.coroutines.delay(50)
                                    }
                                    appViewModel.onTextChange(content)
                                    appViewModel.showSnackbar("已接收分享文件内容")
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "读取分享文件失败", e)
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                if (::appViewModel.isInitialized) {
                                    appViewModel.showSnackbar("读取文件失败: ${e.message}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
   
   override fun onPause() {
       super.onPause()
       // 在应用暂停时也保存数据作为额外保护
       if (this::appViewModel.isInitialized) {
           appViewModel.onAppStop()
       }
   }
   
   override fun onStop() {
       super.onStop()
       if (this::appViewModel.isInitialized) {
           appViewModel.onAppStop()
       }
   }
   
   /**
    *  低内存回调 - 清理缓存
    */
   override fun onTrimMemory(level: Int) {
       super.onTrimMemory(level)
       
       // 中等及以上内存压力时清理缓存
       if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
           if (this::appViewModel.isInitialized) {
               appViewModel.onLowMemory()
           }
       }
   }
   
}
