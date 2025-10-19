package com.example.everytalk.statecontroller

import android.app.Application
import android.os.Bundle
import androidx.profileinstaller.ProfileInstaller
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.everytalk.data.local.SharedPreferencesDataSource
import com.example.everytalk.data.network.ApiClient
import com.example.everytalk.navigation.Screen
import com.example.everytalk.ui.screens.MainScreen.AppDrawerContent
import com.example.everytalk.ui.screens.MainScreen.ChatScreen
import com.example.everytalk.ui.screens.ImageGeneration.ImageGenerationScreen
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
   private lateinit var appViewModel: AppViewModel
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
        
        // 异步初始化ProfileInstaller
        lifecycleScope.launch(Dispatchers.IO) {
            ProfileInstaller.writeProfile(this@MainActivity)
        }
        
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
                            application,
                            SharedPreferencesDataSource(applicationContext)
                        )
                    )

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
                        
                        DismissibleNavigationDrawer(
                            drawerState = appViewModel.drawerState,
                            gesturesEnabled = !isCodeBlockScrolling, // 代码块滚动时禁用抽屉手势
                            modifier = Modifier.fillMaxSize(),
                            drawerContent = {
                                val navBackStackEntry by navController.currentBackStackEntryAsState()
                                val currentRoute = navBackStackEntry?.destination?.route
                                val isImageGenerationMode = currentRoute == Screen.IMAGE_GENERATION_SCREEN

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
                                                appViewModel.stateHolder._loadedHistoryIndex.value = null
                                                appViewModel.loadImageGenerationConversationFromHistory(index)
                                                appViewModel.drawerState.close()
                                            }
                                        } else {
                                            // 同模式内点击，直接加载
                                            appViewModel.stateHolder._loadedHistoryIndex.value = null
                                            appViewModel.loadImageGenerationConversationFromHistory(index)
                                            coroutineScope.launch { appViewModel.drawerState.close() }
                                        }
                                    },
                                    onConversationClick = { index ->
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
                                                // 文本模式历史点击：重置图像模式索引
                                                appViewModel.stateHolder._loadedImageGenerationHistoryIndex.value = null
                                                appViewModel.loadConversationFromHistory(index)
                                                appViewModel.drawerState.close()
                                            }
                                        } else {
                                            // 同模式内点击，直接加载
                                            appViewModel.stateHolder._loadedImageGenerationHistoryIndex.value = null
                                            appViewModel.loadConversationFromHistory(index)
                                            coroutineScope.launch { appViewModel.drawerState.close() }
                                        }
                                    },
                                    onNewChatClick = {
                                        if (isImageGenerationMode) {
                                            coroutineScope.launch { appViewModel.drawerState.close() }
                                            // 关键修复：切换到文本模式时强制重置图像模式索引
                                            appViewModel.stateHolder._loadedImageGenerationHistoryIndex.value = null
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
                                        coroutineScope.launch { appViewModel.drawerState.close() }
                                        // 关键修复：切换模式时强制重置文本模式索引
                                        appViewModel.stateHolder._loadedHistoryIndex.value = null
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
                                    onExpandItem = { index -> appViewModel.setExpandedDrawerItemIndex(index) }
                                )
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
                               composable(
                                   route = Screen.IMAGE_GENERATION_SETTINGS_SCREEN,
                                   enterTransition = { androidx.compose.animation.EnterTransition.None },
                                   exitTransition = { ExitTransition.None },
                                   popEnterTransition = { androidx.compose.animation.EnterTransition.None },
                                   popExitTransition = { ExitTransition.None }
                               ) {
                                   com.example.everytalk.ui.screens.ImageGeneration.ImageGenerationSettingsScreen(
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
    * 🎯 低内存回调 - 清理缓存
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