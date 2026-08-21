package com.android.everytalk.ui.screens.settings
import com.android.everytalk.statecontroller.*

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.navigation.NavController
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.SimpleModeManager
import com.android.everytalk.statecontroller.controller.config.modelsForPendingConfigGroup
import com.android.everytalk.ui.screens.mcp.McpServerListContent
import com.android.everytalk.ui.screens.settings.EditExternalWebSearchProviderDialog
import com.android.everytalk.data.network.ExternalWebSearchProvider
import com.android.everytalk.ui.screens.settings.dialogs.AutoFetchModelsConfirmDialog
import com.android.everytalk.ui.screens.settings.dialogs.ModelSelectionDialog
import com.android.everytalk.util.storage.readAtMost
import com.android.everytalk.ui.components.floatingEdgeGradient
import com.android.everytalk.ui.components.popup.AppFloatingCardPopup
import com.android.everytalk.navigation.Screen
import java.util.UUID

private const val MAX_SETTINGS_IMPORT_BYTES = 50L * 1024L * 1024L

private fun readSettingsImportText(
    inputStream: java.io.InputStream,
    tooLargeMessage: String,
): String {
    return try {
        readAtMost(inputStream, MAX_SETTINGS_IMPORT_BYTES).toString(Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException(tooLargeMessage, e)
    }
}

// 平台默认地址映射
object SettingsDefaults {
    // 图像模式默认地址
    val imageDefaultApiAddresses: Map<String, String> = mapOf(
        "SiliconFlow" to "https://api.siliconflow.cn/v1/images/generations",
        "OpenAI Compatible" to "",
        "Google" to "",
        "SeeDream" to "https://ark.cn-beijing.volces.com/api/v3/images/generations"
    )
    fun imageDefaultApiAddressFor(provider: String): String {
        val normalized = provider.trim().lowercase().replace(" ", "")
        val lookupKey = if (normalized == "gemini") "google" else normalized
        return imageDefaultApiAddresses.entries.firstOrNull {
            it.key.trim().lowercase().replace(" ", "") == lookupKey
        }?.value ?: ""
    }
    // 文本模式默认地址
    val textDefaultApiAddresses: Map<String, String> = mapOf(
        "硅基流动" to "https://api.siliconflow.cn",
        "siliconflow" to "https://api.siliconflow.cn",
        "google" to "https://generativelanguage.googleapis.com",
        "谷歌" to "https://generativelanguage.googleapis.com",
        "anthropic" to "https://api.anthropic.com",
        "阿里云百炼" to "https://dashscope.aliyuncs.com/compatible-mode",
        "火山引擎" to "https://ark.cn-beijing.volces.com/api/v3/chat/completions#",
        "深度求索" to "https://api.deepseek.com",
        "openrouter" to "https://openrouter.ai/api",
        "openrouter.ai" to "https://openrouter.ai/api"
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    Log.i("ScreenComposition", "SettingsScreen Composing/Recomposing.")
    val textConfigs by viewModel.apiConfigs.collectAsState()
    val imageConfigs by viewModel.imageGenApiConfigs.collectAsState()
    // 使用UI意图模式，避免基于内容态推断造成的短暂不一致
    val intendedMode by viewModel.uiModeFlow.collectAsState()
    val isInImageMode = intendedMode == SimpleModeManager.ModeType.IMAGE
    
    val selectedConfigForApp by if (isInImageMode) {
        viewModel.selectedImageGenApiConfig.collectAsState()
    } else {
        viewModel.selectedApiConfig.collectAsState()
    }
    val allProviders by viewModel.allProviders.collectAsState()
    val fetchedModels by viewModel.fetchedModels.collectAsState()
    val pendingConfigParams by viewModel.pendingConfigParams.collectAsState()
    val isRefreshingModels by viewModel.isRefreshingModels.collectAsState()
    val showAutoFetchConfirm by viewModel.showAutoFetchConfirmDialog.collectAsState()
    val showModelSelection by viewModel.showModelSelectionDialog.collectAsState()
    
    val mcpServerStates by viewModel.mcpServerStates.collectAsState()
    val allMcpConfigs by viewModel.allMcpConfigs.collectAsState()
    val externalWebSearchConfigs by viewModel.externalWebSearchConfigs.collectAsState()
    val selectedExternalWebSearchProviderId by viewModel.selectedExternalWebSearchProviderId.collectAsState()
    var editingExternalProvider by remember { mutableStateOf<ExternalWebSearchProvider?>(null) }
    val scope = rememberCoroutineScope()

    val apiConfigsByApiKeyAndModality = remember(textConfigs, imageConfigs, isInImageMode) {
        val configsToShow = if (isInImageMode) {
            // 图像模式显示图像生成配置 - 现在使用响应式状态
            imageConfigs.filter { it.modalityType == ModalityType.IMAGE }
        } else {
            // 文本模式显示文本配置
            textConfigs.filter { it.modalityType == ModalityType.TEXT }
        }
        
        configsToShow
            .groupBy { config ->
                "${config.provider}|${config.address}|${config.key}"
            }
            .mapValues { entry ->
                entry.value.groupBy { it.modalityType }
            }
            .filterValues { it.isNotEmpty() }
    }

    var showAddFullConfigDialog by remember { mutableStateOf(false) }
    var newFullConfigProvider by remember { mutableStateOf("") }
    var newFullConfigAddress by remember { mutableStateOf("") }
    var newFullConfigKey by remember { mutableStateOf("") }

    var showAddModelToKeyDialog by remember { mutableStateOf(false) }
    var addModelToKeyTarget by remember { mutableStateOf<ApiConfig?>(null) }
    
    var showManualModelInputDialog by remember { mutableStateOf(false) }

    var showAddCustomProviderDialog by remember { mutableStateOf(false) }
    var newCustomProviderNameInput by remember { mutableStateOf("") }

    var backButtonEnabled by remember { mutableStateOf(true) }

    var showEditConfigDialog by remember { mutableStateOf(false) }
    var configToEdit by remember { mutableStateOf<ApiConfig?>(null) }
    var modelParametersTarget by remember { mutableStateOf<ApiConfig?>(null) }
    var showConfirmDeleteProviderDialog by remember { mutableStateOf(false) }
    var providerToDelete by remember { mutableStateOf<String?>(null) }
    var showImportExportDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val importTooLargeMessage = stringResource(R.string.settings_import_too_large)
    val exportContentExpiredMessage = stringResource(R.string.settings_export_content_expired)
    val exportSuccessMessage = stringResource(R.string.settings_export_success)
    val importUnreadableMessage = stringResource(R.string.settings_import_unreadable)

    val exportSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri ->
            val exportData = viewModel.consumeSettingsExport()
            if (uri == null) {
                exportData?.file?.delete()
                return@rememberLauncherForActivityResult
            } else {
                val sourceFile = exportData?.file?.takeIf { it.isFile }
                if (sourceFile == null) {
                    viewModel.showToast(exportContentExpiredMessage)
                } else {
                    scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openFileDescriptor(uri, "w")?.use { pfd ->
                                java.io.FileOutputStream(pfd.fileDescriptor).use { outputStream ->
                                    outputStream.channel.truncate(0)
                                    sourceFile.inputStream().buffered().use { input ->
                                        input.copyTo(outputStream)
                                    }
                                }
                            }
                        }
                        viewModel.showToast(exportSuccessMessage)
                    } catch (e: Exception) {
                        Log.e("SettingsScreen", "导出失败", e)
                        viewModel.showToast(
                            context.getString(
                                R.string.settings_export_failed_detail,
                                e.message ?: context.getString(R.string.unknown_error),
                            )
                        )
                    } finally {
                        sourceFile.delete()
                    }
                }
            }
            }
        }
    )

    val importSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let {
                scope.launch {
                try {
                    val jsonContent = withContext(Dispatchers.IO) {
                        context.contentResolver.openAssetFileDescriptor(it, "r")?.use { descriptor ->
                            val declaredLength = descriptor.length
                            if (declaredLength > MAX_SETTINGS_IMPORT_BYTES) {
                                throw IllegalStateException(importTooLargeMessage)
                            }
                        }
                        context.contentResolver.openInputStream(it)?.use { inputStream ->
                            readSettingsImportText(inputStream, importTooLargeMessage)
                        }
                    }
                    if (jsonContent == null) {
                        viewModel.showToast(importUnreadableMessage)
                    } else {
                        viewModel.importSettings(jsonContent)
                    }
                } catch (e: Exception) {
                    viewModel.showToast(
                        context.getString(
                            R.string.settings_import_failed_detail,
                            e.message ?: context.getString(R.string.unknown_error),
                        )
                    )
                }
                }
            }
        }
    )

    LaunchedEffect(Unit) {
        viewModel.settingsExportRequest.collect { data ->
            viewModel.stageSettingsExport(data)
            exportSettingsLauncher.launch(data.fileName)
        }
    }
    
    LaunchedEffect(isInImageMode) {
        viewModel.showManualModelInputRequest.collect { request ->
            if (request.isImageGen == isInImageMode) showManualModelInputDialog = true
        }
    }

    LaunchedEffect(textConfigs, imageConfigs, selectedConfigForApp, isInImageMode) {
        val currentSelected = selectedConfigForApp
        val configsToCheck = if (isInImageMode) {
            imageConfigs
        } else {
            textConfigs
        }
        
        if (currentSelected != null && configsToCheck.none { it.id == currentSelected.id }) {
            configsToCheck.firstOrNull()?.let {
                viewModel.selectConfig(it, isInImageMode)
            } ?: viewModel.clearSelectedConfig(isInImageMode)
        } else if (currentSelected == null && configsToCheck.isNotEmpty()) {
            viewModel.selectConfig(configsToCheck.first(), isInImageMode)
        }
    }


    val isDark = isSystemInDarkTheme()
    val buttonBg = if (isDark) Color(0xFF303030) else Color.White
    val contentColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val iconButtonSize = 44.dp
    val topButtonSize = iconButtonSize + 2.dp

    // Tab 状态：0=平台配置, 1=联网搜索, 2=MCP
    val tabs = listOf(
        stringResource(R.string.settings_tab_platforms),
        stringResource(R.string.settings_tab_web_search),
        stringResource(R.string.settings_tab_mcp),
    )
    var currentTabIndex by remember { mutableIntStateOf(0) }
    var showTabMenu by remember { mutableStateOf(false) }
    var showMcpAddDialog by remember { mutableStateOf(false) }
    val settingsBackStackEntry = remember(navController) {
        navController.getBackStackEntry(Screen.SETTINGS_SCREEN)
    }
    val existingModelsForSelection = remember(pendingConfigParams, textConfigs, imageConfigs) {
        modelsForPendingConfigGroup(
            configs = if (pendingConfigParams?.isImageGen == true) imageConfigs else textConfigs,
            params = pendingConfigParams,
        )
    }
    val requestedTabIndex by settingsBackStackEntry.savedStateHandle
        .getStateFlow(Screen.SETTINGS_TAB_REQUEST_KEY, -1)
        .collectAsState()
    val importExportRequested by settingsBackStackEntry.savedStateHandle
        .getStateFlow(Screen.SETTINGS_IMPORT_EXPORT_REQUEST_KEY, false)
        .collectAsState()

    LaunchedEffect(requestedTabIndex) {
        if (requestedTabIndex in tabs.indices) {
            currentTabIndex = requestedTabIndex
            settingsBackStackEntry.savedStateHandle[Screen.SETTINGS_TAB_REQUEST_KEY] = -1
        }
    }
    LaunchedEffect(importExportRequested) {
        if (importExportRequested) {
            showImportExportDialog = true
            settingsBackStackEntry.savedStateHandle[Screen.SETTINGS_IMPORT_EXPORT_REQUEST_KEY] = false
        }
    }

    val topContentPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + topButtonSize + 24.dp
    val bottomContentPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 48.dp

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp)
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            // 内容层
            Column(modifier = Modifier.fillMaxSize()) {
                if (isInImageMode) {
                    SettingsScreenContent(
                        paddingValues = PaddingValues(top = topContentPadding, bottom = bottomContentPadding),
                        apiConfigsByApiKeyAndModality = apiConfigsByApiKeyAndModality,
                        isImageMode = isInImageMode,
                        onAddFullConfigClick = {
                            newFullConfigProvider = ""
                            newFullConfigKey = ""
                            newFullConfigAddress = ""
                            showAddFullConfigDialog = true
                        },
                        onSelectConfig = { configToSelect ->
                            viewModel.selectConfig(configToSelect, isInImageMode)
                        },
                        selectedConfigIdInApp = selectedConfigForApp?.id,
                        onAddModelForApiKeyClick = { representativeConfig ->
                            addModelToKeyTarget = representativeConfig
                            showAddModelToKeyDialog = true
                        },
                        onDeleteModelForApiKey = { configToDelete ->
                            viewModel.deleteConfig(configToDelete, isInImageMode)
                        },
                        onEditConfigClick = { config ->
                            configToEdit = config
                            showEditConfigDialog = true
                        },
                        onDeleteConfigGroup = { representativeConfig ->
                            viewModel.deleteConfigGroup(representativeConfig, isInImageMode)
                        },
                        onRefreshModelsClick = { config ->
                            viewModel.refreshModelsForConfig(config)
                        },
                        onConfigureModelParameters = if (isInImageMode) null else { config ->
                            modelParametersTarget = config
                        },
                        isRefreshingModels = isRefreshingModels
                    )
                } else {
                    AnimatedContent(
                        targetState = currentTabIndex,
                        transitionSpec = {
                            val direction = if (targetState > initialState) {
                                slideInHorizontally { it / 3 } + fadeIn(
                                    animationSpec = tween(200)
                                ) togetherWith
                                slideOutHorizontally { -it / 3 } + fadeOut(
                                    animationSpec = tween(200)
                                )
                            } else {
                                slideInHorizontally { -it / 3 } + fadeIn(
                                    animationSpec = tween(200)
                                ) togetherWith
                                slideOutHorizontally { it / 3 } + fadeOut(
                                    animationSpec = tween(200)
                                )
                            }
                            direction.using(SizeTransform(clip = false))
                        },
                        label = "settings_tab_transition"
                    ) { tabIndex ->
                        when (tabIndex) {
                            0 -> {
                                SettingsScreenContent(
                                    paddingValues = PaddingValues(top = topContentPadding, bottom = bottomContentPadding),
                                    apiConfigsByApiKeyAndModality = apiConfigsByApiKeyAndModality,
                                    isImageMode = isInImageMode,
                                    onAddFullConfigClick = {
                                        newFullConfigProvider = ""
                                        newFullConfigKey = ""
                                        newFullConfigAddress = ""
                                        showAddFullConfigDialog = true
                                    },
                                    onSelectConfig = { configToSelect ->
                                        viewModel.selectConfig(configToSelect, isInImageMode)
                                    },
                                    selectedConfigIdInApp = selectedConfigForApp?.id,
                                    onAddModelForApiKeyClick = { representativeConfig ->
                                        addModelToKeyTarget = representativeConfig
                                        showAddModelToKeyDialog = true
                                    },
                                    onDeleteModelForApiKey = { configToDelete ->
                                        viewModel.deleteConfig(configToDelete, isInImageMode)
                                    },
                                    onEditConfigClick = { config ->
                                        configToEdit = config
                                        showEditConfigDialog = true
                                    },
                                    onDeleteConfigGroup = { representativeConfig ->
                                        viewModel.deleteConfigGroup(representativeConfig, isInImageMode)
                                    },
                                    onRefreshModelsClick = { config ->
                                        viewModel.refreshModelsForConfig(config)
                                    },
                                    onConfigureModelParameters = if (isInImageMode) null else { config ->
                                        modelParametersTarget = config
                                    },
                                    isRefreshingModels = isRefreshingModels
                                )
                            }
                            1 -> {
                                ExternalWebSearchSettingsContent(
                                    selectedProviderId = selectedExternalWebSearchProviderId,
                                    configs = externalWebSearchConfigs,
                                    onSelectProvider = { viewModel.selectExternalWebSearchProvider(it) },
                                    onEditProvider = { editingExternalProvider = it },
                                    topContentPadding = topContentPadding,
                                    bottomContentPadding = bottomContentPadding,
                                )
                            }
                            2 -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 20.dp)
                                        .padding(bottom = bottomContentPadding)
                                ) {
                                    Spacer(Modifier.height(topContentPadding))
                                    McpServerListContent(
                                        serverStates = allMcpConfigs.mapValues { (id, persistedState) ->
                                            val runtimeState = mcpServerStates[id]
                                            if (persistedState.config.enabled) {
                                                runtimeState ?: persistedState
                                            } else {
                                                // 关闭操作已经写入数据库时立即显示关闭，避免旧连接态覆盖开关。
                                                persistedState.copy(tools = runtimeState?.tools.orEmpty())
                                            }
                                        },
                                        onAddServer = { config -> 
                                            viewModel.addMcpServer(config) 
                                        },
                                        onUpdateServer = { config ->
                                            viewModel.updateMcpServer(config)
                                        },
                                        onRemoveServer = { id -> 
                                            viewModel.removeMcpServer(id) 
                                        },
                                        onToggleServer = { id, enabled -> 
                                            viewModel.toggleMcpServer(id, enabled) 
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(bottomContentPadding)
                    .floatingEdgeGradient(MaterialTheme.colorScheme.background, fromTop = false),
            )

            // 浮动顶栏：顶部阴影渐隐，内容仍可滚动到其后方
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .floatingEdgeGradient(MaterialTheme.colorScheme.background, fromTop = true)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧返回按钮
                    Box(
                        modifier = Modifier
                            .size(topButtonSize)
                            .shadow(3.dp, CircleShape, clip = false)
                            .clip(CircleShape)
                            .background(buttonBg)
                            .clickable(enabled = backButtonEnabled) {
                                backButtonEnabled = false
                                navController.popBackStack()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.navigation_back),
                            tint = contentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // 右侧按钮
                    if (!isInImageMode) {
                        val showAddButton = currentTabIndex != 1
                        val rightButtonWidth by animateDpAsState(
                            targetValue = if (showAddButton) topButtonSize * 2 else topButtonSize,
                            animationSpec = tween(durationMillis = 180),
                            label = "settingsRightButtonWidth"
                        )
                        Box {
                            Row(
                                modifier = Modifier
                                    .width(rightButtonWidth)
                                    .height(topButtonSize)
                                    .shadow(3.dp, RoundedCornerShape(percent = 50), clip = false)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(buttonBg),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (showAddButton) {
                                    Box(
                                        modifier = Modifier
                                            .size(topButtonSize)
                                            .clip(CircleShape)
                                            .clickable {
                                                if (currentTabIndex == 0) {
                                                    newFullConfigProvider = ""
                                                    newFullConfigKey = ""
                                                    newFullConfigAddress = ""
                                                    showAddFullConfigDialog = true
                                                } else if (currentTabIndex == 2) {
                                                    showMcpAddDialog = true
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_plus),
                                            contentDescription = stringResource(R.string.action_add),
                                            tint = contentColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .size(topButtonSize)
                                        .clip(CircleShape)
                                        .clickable { showTabMenu = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_dots_horizontal),
                                        contentDescription = stringResource(R.string.action_more),
                                        tint = contentColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            SettingsTabMenu(
                                expanded = showTabMenu,
                                tabs = tabs,
                                currentTabIndex = currentTabIndex,
                                onTabSelected = { index ->
                                    currentTabIndex = index
                                    showTabMenu = false
                                },
                                onImportExport = { showImportExportDialog = true },
                                onOpenComputers = {
                                    showTabMenu = false
                                    navController.navigate(Screen.COMPUTER_SCREEN) { launchSingleTop = true }
                                },
                                onOpenSkills = {
                                    showTabMenu = false
                                    navController.navigate(Screen.SKILL_SCREEN) { launchSingleTop = true }
                                },
                                onDismiss = { showTabMenu = false }
                            )
                        }
                    } else {
                        // 图像模式：胶囊形状（左边添加 + 右边更多）
                        Box {
                            Row(
                                modifier = Modifier
                                    .width(topButtonSize * 2)
                                    .height(topButtonSize)
                                    .shadow(3.dp, RoundedCornerShape(percent = 50), clip = false)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(buttonBg),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(topButtonSize)
                                        .clip(CircleShape)
                                        .clickable {
                                            newFullConfigProvider = ""
                                            newFullConfigKey = ""
                                            newFullConfigAddress = ""
                                            showAddFullConfigDialog = true
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_plus),
                                        contentDescription = stringResource(R.string.action_add),
                                        tint = contentColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(topButtonSize)
                                        .clip(CircleShape)
                                        .clickable { showTabMenu = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_dots_horizontal),
                                        contentDescription = stringResource(R.string.action_more),
                                        tint = contentColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            SettingsTabMenu(
                                expanded = showTabMenu,
                                tabs = emptyList(),
                                currentTabIndex = -1,
                                onTabSelected = { showTabMenu = false },
                                onImportExport = { showImportExportDialog = true },
                                onOpenComputers = {
                                    showTabMenu = false
                                    navController.navigate(Screen.COMPUTER_SCREEN) { launchSingleTop = true }
                                },
                                onOpenSkills = {
                                    showTabMenu = false
                                    navController.navigate(Screen.SKILL_SCREEN) { launchSingleTop = true }
                                },
                                onDismiss = { showTabMenu = false }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddFullConfigDialog) {
        AddNewFullConfigDialog(
            provider = newFullConfigProvider,
            onProviderChange = { selectedProvider ->
                newFullConfigProvider = selectedProvider
                val providerKey = selectedProvider.lowercase().trim()
                newFullConfigAddress = if (isInImageMode)
                    SettingsDefaults.imageDefaultApiAddressFor(providerKey)
                else
                    SettingsDefaults.textDefaultApiAddresses[providerKey] ?: ""
            },
            allProviders = allProviders,
            onShowAddCustomProviderDialog = { showAddCustomProviderDialog = true },
            onDeleteProvider = { providerNameToDelete ->
                providerToDelete = providerNameToDelete
                showConfirmDeleteProviderDialog = true
            },
            apiAddress = newFullConfigAddress,
            onApiAddressChange = { newFullConfigAddress = it },
            apiKey = newFullConfigKey,
            onApiKeyChange = { newFullConfigKey = it },
            onDismissRequest = {
                showAddFullConfigDialog = false
                // 重置获取的模型列表
                viewModel.clearFetchedModels()
            },
            onConfirm = { provider, address, key, channel, imageSize, numInferenceSteps, guidanceScale, enableCodeExecution, toolsJson ->
                val providerTrim = provider.trim()
                val pLower = providerTrim.lowercase()
                val isDefaultProvider = pLower in listOf("默认", "default")
                if (isDefaultProvider && isInImageMode) {
                    val config = ApiConfig(
                        id = UUID.randomUUID().toString(),
                        name = "Kwai-Kolors/Kolors",
                        provider = providerTrim,
                        address = "",
                        key = "",
                        model = "Kwai-Kolors/Kolors",
                        modalityType = ModalityType.IMAGE,
                        channel = channel,
                        isValid = true,
                        imageSize = imageSize,
                        numInferenceSteps = numInferenceSteps,
                        guidanceScale = guidanceScale,
                    )
                    viewModel.addConfig(config, isImageGen = true)
                    showAddFullConfigDialog = false
                    viewModel.clearFetchedModels()
                } else if (key.isNotBlank() && providerTrim.isNotBlank() && address.isNotBlank()) {
                    viewModel.startAddConfigFlow(
                        provider = providerTrim,
                        address = address,
                        key = key,
                        channel = channel,
                        isImageGen = isInImageMode,
                        enableCodeExecution = enableCodeExecution,
                        toolsJson = toolsJson,
                        imageSize = imageSize,
                        numInferenceSteps = numInferenceSteps,
                        guidanceScale = guidanceScale,
                    )
                    showAddFullConfigDialog = false
                }
            },
            isImageMode = isInImageMode
        )
    }

    editingExternalProvider?.let { provider ->
        EditExternalWebSearchProviderDialog(
            provider = provider,
            currentApiKey = externalWebSearchConfigs[provider.providerId]?.apiKey.orEmpty(),
            onApiKeyChange = { apiKey ->
                viewModel.updateExternalWebSearchProviderApiKey(provider, apiKey)
            },
            onDismiss = { editingExternalProvider = null }
        )
    }


    if (showAddModelToKeyDialog) {
        AddModelDialog(
            onDismissRequest = {
                showAddModelToKeyDialog = false
                addModelToKeyTarget = null
            },
            onConfirm = { newModelName ->
                addModelToKeyTarget?.let { representativeConfig ->
                    viewModel.addModelToConfigGroup(representativeConfig, newModelName)
                    showAddModelToKeyDialog = false
                    addModelToKeyTarget = null
                }
            }
        )
    }

    modelParametersTarget?.let { target ->
        ModelParametersDialog(
            config = target,
            onDismissRequest = { modelParametersTarget = null },
            onConfirm = { updatedConfig ->
                viewModel.updateConfig(updatedConfig, isImageGen = false)
                modelParametersTarget = null
            },
            onAutoLoad = { config -> viewModel.loadModelParameters(config) },
        )
    }
    
    if (showManualModelInputDialog) {
        AddModelDialog(
            onDismissRequest = {
                showManualModelInputDialog = false
                viewModel.dismissManualModelInput()
            },
            onConfirm = { modelName ->
                if (modelName.isNotBlank()) {
                    viewModel.submitManualModel(modelName)
                    showManualModelInputDialog = false
                }
            }
        )
    }

    // 自动获取模型：确认对话框
    if (showAutoFetchConfirm) {
        AutoFetchModelsConfirmDialog(
            showDialog = true,
            onDismiss = { viewModel.dismissAutoFetchConfirmDialog() },
            onConfirmAutoFetch = { viewModel.onConfirmAutoFetch() },
            onManualInput = { viewModel.onManualInput() }
        )
    }

    // 模型选择对话框（支持"全部添加/手动选择/改为手动输入"）
    if (showModelSelection) {
        ModelSelectionDialog(
            showDialog = true,
            models = fetchedModels,
            existingModels = existingModelsForSelection,
            onDismiss = { viewModel.dismissModelSelectionDialog() },
            onSelectModels = { selected -> viewModel.onSelectModels(selected) },
            onManualInput = { viewModel.onManualInput() }
        )
    }

    if (showAddCustomProviderDialog) {
        AddProviderDialog(
            newProviderName = newCustomProviderNameInput,
            onNewProviderNameChange = { newCustomProviderNameInput = it },
            onDismissRequest = {
                showAddCustomProviderDialog = false
                newCustomProviderNameInput = ""
            },
            onConfirm = {
                val trimmedName = newCustomProviderNameInput.trim()
                if (trimmedName.isNotBlank() && !allProviders.any {
                        it.equals(trimmedName, ignoreCase = true)
                    }) {
                    viewModel.addProvider(trimmedName)
                    if (showAddFullConfigDialog) {
                        newFullConfigProvider = trimmedName
                        newFullConfigAddress = SettingsDefaults.imageDefaultApiAddressFor(trimmedName)
                    }
                    showAddCustomProviderDialog = false
                    newCustomProviderNameInput = ""
                }
            }
        )
    }

    if (showEditConfigDialog && configToEdit != null) {
        EditConfigDialog(
            representativeConfig = configToEdit!!,
            allProviders = allProviders,
            onDismissRequest = {
                showEditConfigDialog = false
                configToEdit = null
            },
            onConfirm = { newProvider, newAddress, newKey, newChannel, newEnableCodeExecution, newToolsJson ->
                viewModel.updateConfigGroup(
                    representativeConfig = configToEdit!!,
                    newProvider = newProvider,
                    newAddress = newAddress,
                    newKey = newKey,
                    newChannel = newChannel,
                    isImageGen = isInImageMode,
                    newEnableCodeExecution = newEnableCodeExecution,
                    newToolsJson = newToolsJson
                )
                showEditConfigDialog = false
                configToEdit = null
            },
            isImageMode = isInImageMode
        )
    }

    if (showConfirmDeleteProviderDialog && providerToDelete != null) {
        ConfirmDeleteDialog(
            onDismissRequest = {
                showConfirmDeleteProviderDialog = false
                providerToDelete = null
            },
            onConfirm = {
                val providerNameToDelete = providerToDelete!!
                viewModel.deleteProvider(providerNameToDelete)
                if (newFullConfigProvider == providerNameToDelete) {
                    val nextDefaultProvider = viewModel.allProviders.value.firstOrNull() ?: "openai compatible"
                    newFullConfigProvider = nextDefaultProvider
                    newFullConfigAddress = SettingsDefaults.imageDefaultApiAddressFor(nextDefaultProvider)
                }
                showConfirmDeleteProviderDialog = false
                providerToDelete = null
            },
            title = stringResource(R.string.settings_delete_platform_title),
            text = stringResource(
                R.string.settings_delete_platform_description,
                localizedProviderLabel(providerToDelete.orEmpty()),
            ),
        )
    }
    if (showImportExportDialog) {
        // 获取聊天历史数量
        val chatHistory by viewModel.historicalConversations.collectAsState()
        val imageHistory by viewModel.imageGenerationHistoricalConversations.collectAsState()
        
        ImportExportDialog(
            onDismissRequest = { showImportExportDialog = false },
            onExport = { includeHistory ->
                viewModel.exportSettings(includeHistory)
                showImportExportDialog = false
            },
            onImport = {
                importSettingsLauncher.launch("application/json")
                showImportExportDialog = false
            },
            isExportEnabled = (textConfigs + imageConfigs).isNotEmpty() || chatHistory.isNotEmpty() || imageHistory.isNotEmpty(),
            chatHistoryCount = chatHistory.size,
            imageHistoryCount = imageHistory.size
        )
    }

    if (showMcpAddDialog) {
        com.android.everytalk.ui.screens.mcp.AddMcpServerDialog(
            onConfirm = { config ->
                viewModel.addMcpServer(config)
                showMcpAddDialog = false
            },
            onDismiss = { showMcpAddDialog = false }
        )
    }
}

@Composable
internal fun SettingsTabMenu(
    expanded: Boolean,
    tabs: List<String>,
    currentTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    onImportExport: () -> Unit,
    onOpenComputers: () -> Unit,
    onOpenSkills: () -> Unit,
    isComputerSelected: Boolean = false,
    isSkillSelected: Boolean = false,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val selectedColor = if (isDark) Color(0xFF6EB5FF) else Color(0xFF3B82F6)
    val subtextColor = if (isDark) Color.White.copy(alpha = 0.5f) else Color(0xFF0D0D0D).copy(alpha = 0.5f)

    AppFloatingCardPopup(
        visible = expanded,
        alignment = Alignment.TopEnd,
        offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx().toInt() }),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
        modifier = Modifier.widthIn(min = 112.dp, max = 176.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp)
        ) {
            // 菜单顺序与设置页页签一致，服务器入口固定紧跟第三项 MCP。
            tabs.forEachIndexed { index, title ->
                val isSelected = index == currentTabIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clickable { onTabSelected(index) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isSelected) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .size(12.dp)
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clickable {
                        onOpenComputers()
                        onDismiss()
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.settings_servers),
                    fontSize = 16.sp,
                    fontWeight = if (isComputerSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isComputerSelected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(12.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clickable {
                        onOpenSkills()
                        onDismiss()
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "技能",
                    fontSize = 16.sp,
                    fontWeight = if (isSkillSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isSkillSelected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.align(Alignment.CenterStart).size(12.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clickable {
                        onImportExport()
                        onDismiss()
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.settings_import_export),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
