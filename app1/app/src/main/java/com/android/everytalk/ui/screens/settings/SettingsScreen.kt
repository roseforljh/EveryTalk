package com.android.everytalk.ui.screens.settings

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.SimpleModeManager
import com.android.everytalk.ui.screens.settings.dialogs.AutoFetchModelsConfirmDialog
import com.android.everytalk.ui.screens.settings.dialogs.ModelSelectionDialog
import java.util.UUID

// 平台默认地址映射
object SettingsDefaults {
    // 图像模式默认地址
    val imageDefaultApiAddresses: Map<String, String> = mapOf(
        "SiliconFlow" to "https://api.siliconflow.cn/v1/images/generations",
        "OpenAI Compatible" to "",
        "Gemini" to "",
        "SeeDream" to "https://ark.cn-beijing.volces.com/api/v3/images/generations"
    )
    // 文本模式默认地址
    val textDefaultApiAddresses: Map<String, String> = mapOf(
        "硅基流动" to "https://api.siliconflow.cn",
        "siliconflow" to "https://api.siliconflow.cn",
        "google" to "https://generativelanguage.googleapis.com",
        "谷歌" to "https://generativelanguage.googleapis.com",
        "阿里云百炼" to "https://dashscope.aliyuncs.com/compatible-mode",
        "火山引擎" to "https://ark.cn-beijing.volces.com/api/v3/chat/completions#",
        "深度求索" to "https://api.deepseek.com",
        "openrouter" to "https://openrouter.ai/api",
        "openrouter.ai" to "https://openrouter.ai/api",
        "默认" to "",
        "default" to ""
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
    val isFetchingModels by viewModel.isFetchingModels.collectAsState()
    val fetchedModels by viewModel.fetchedModels.collectAsState()
    val isRefreshingModels by viewModel.isRefreshingModels.collectAsState()
    val showAutoFetchConfirm by viewModel.showAutoFetchConfirmDialog.collectAsState()
    val showModelSelection by viewModel.showModelSelectionDialog.collectAsState()

    val apiConfigsByApiKeyAndModality = remember(textConfigs, imageConfigs, isInImageMode) {
        val configsToShow = if (isInImageMode) {
            // 图像模式显示图像生成配置 - 现在使用响应式状态
            imageConfigs.filter { it.modalityType == ModalityType.IMAGE }
        } else {
            // 文本模式显示文本配置
            textConfigs.filter { it.modalityType == ModalityType.TEXT }
        }
        
        configsToShow
            .groupBy { "${it.provider}|${it.address}|${it.channel}|${it.key}" }
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
    var addModelToKeyTargetApiKey by remember { mutableStateOf("") }
    var addModelToKeyTargetProvider by remember { mutableStateOf("") }
    var addModelToKeyTargetAddress by remember { mutableStateOf("") }
    var addModelToKeyTargetChannel by remember { mutableStateOf("") }
    var addModelToKeyTargetModality by remember { mutableStateOf(ModalityType.TEXT) }
    var addModelToKeyNewModelName by remember { mutableStateOf("") }
    
    // 新增：手动输入模型对话框状态
    var showManualModelInputDialog by remember { mutableStateOf(false) }
    var manualModelInputProvider by remember { mutableStateOf("") }
    var manualModelInputAddress by remember { mutableStateOf("") }
    var manualModelInputKey by remember { mutableStateOf("") }
    var manualModelInputChannel by remember { mutableStateOf("") }
    var manualModelInputIsImageGen by remember { mutableStateOf(false) }
    var manualModelInputName by remember { mutableStateOf("") }

    var showAddCustomProviderDialog by remember { mutableStateOf(false) }
    var newCustomProviderNameInput by remember { mutableStateOf("") }

    var backButtonEnabled by remember { mutableStateOf(true) }

    var showEditConfigDialog by remember { mutableStateOf(false) }
    var configToEdit by remember { mutableStateOf<ApiConfig?>(null) }
    var showConfirmDeleteProviderDialog by remember { mutableStateOf(false) }
    var providerToDelete by remember { mutableStateOf<String?>(null) }
    var showImportExportDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var exportData by remember { mutableStateOf<Pair<String, String>?>(null) }

    val exportSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri ->
            uri?.let { fileUri ->
                exportData?.second?.let { jsonContent ->
                    try {
                        context.contentResolver.openFileDescriptor(fileUri, "w")?.use { pfd ->
                            java.io.FileOutputStream(pfd.fileDescriptor).use { outputStream ->
                                outputStream.channel.truncate(0) // 强制清空文件
                                outputStream.write(jsonContent.toByteArray())
                            }
                        }
                        viewModel.showToast("配置已导出")
                    } catch (e: Exception) {
                        Log.e("SettingsScreen", "导出失败", e)
                        viewModel.showToast("导出失败: ${e.message}")
                    } finally {
                        exportData = null
                    }
                }
            }
        }
    )

    val importSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let {
                try {
                    context.contentResolver.openInputStream(it)?.use { inputStream ->
                        val jsonContent = inputStream.bufferedReader().use { reader -> reader.readText() }
                        viewModel.importSettings(jsonContent)
                    }
                } catch (e: Exception) {
                    viewModel.showToast("导入失败: ${e.message}")
                }
            }
        }
    )

    LaunchedEffect(Unit) {
        viewModel.settingsExportRequest.collect { data ->
            exportData = data
            exportSettingsLauncher.launch(data.first)
        }
    }
    
    // 新增：监听手动输入模型请求
    LaunchedEffect(Unit) {
        viewModel.showManualModelInputRequest.collect { request ->
            manualModelInputProvider = request.provider
            manualModelInputAddress = request.address
            manualModelInputKey = request.key
            manualModelInputChannel = request.channel
            manualModelInputIsImageGen = request.isImageGen
            manualModelInputName = ""
            showManualModelInputDialog = true
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


    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { 
                    val titleText = if (isInImageMode) "图像生成配置" else "文本模型配置"
                    Text(titleText, color = MaterialTheme.colorScheme.onSurface) 
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (backButtonEnabled) {
                            backButtonEnabled = false; navController.popBackStack()
                        }
                    }, enabled = backButtonEnabled) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "返回",
                            tint = if (backButtonEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showImportExportDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.ImportExport,
                            contentDescription = "导入/导出配置",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        SettingsScreenContent(
            paddingValues = paddingValues,
            apiConfigsByApiKeyAndModality = apiConfigsByApiKeyAndModality,
            isImageMode = isInImageMode,
            onAddFullConfigClick = {
                // 文本和图像模式都默认选择"默认"
                val initialProvider = "默认"
                newFullConfigProvider = initialProvider
                newFullConfigKey = ""
                val providerKey = initialProvider.lowercase().trim()
                newFullConfigAddress = if (isInImageMode)
                    SettingsDefaults.imageDefaultApiAddresses[providerKey] ?: ""
                else
                    SettingsDefaults.textDefaultApiAddresses[providerKey] ?: ""
                showAddFullConfigDialog = true
            },
            onSelectConfig = { configToSelect ->
                viewModel.selectConfig(configToSelect, isInImageMode)
            },
            selectedConfigIdInApp = selectedConfigForApp?.id,
            onAddModelForApiKeyClick = { apiKey, existingProvider, existingAddress, existingChannel, existingModality ->
                addModelToKeyTargetApiKey = apiKey
                addModelToKeyTargetProvider = existingProvider
                addModelToKeyTargetAddress = existingAddress
                addModelToKeyTargetChannel = existingChannel
                addModelToKeyTargetModality = existingModality
                addModelToKeyNewModelName = ""
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
            isRefreshingModels = isRefreshingModels
        )
    }

    if (showAddFullConfigDialog) {
        AddNewFullConfigDialog(
            provider = newFullConfigProvider,
            onProviderChange = { selectedProvider ->
                newFullConfigProvider = selectedProvider
                val providerKey = selectedProvider.lowercase().trim()
                newFullConfigAddress = if (isInImageMode)
                    SettingsDefaults.imageDefaultApiAddresses[providerKey] ?: ""
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
            onConfirm = { provider, address, key, channel, _, _, _, enableCodeExecution, toolsJson ->
                val providerTrim = provider.trim()
                val pLower = providerTrim.lowercase()
                val isDefaultProvider = pLower in listOf("默认", "default")
                if (isDefaultProvider && isInImageMode) {
                    // 图像模式下的"默认"平台：直接创建 Kolors 配置（地址/Key 由后端隐藏注入）
                    val config = ApiConfig(
                        id = UUID.randomUUID().toString(),
                        name = "Kwai-Kolors/Kolors",
                        provider = providerTrim,
                        address = "",
                        key = "",
                        model = "Kwai-Kolors/Kolors",
                        modalityType = ModalityType.IMAGE,
                        channel = channel,
                        isValid = true
                    )
                    viewModel.addConfig(config, isImageGen = true)
                    showAddFullConfigDialog = false
                    viewModel.clearFetchedModels()
                } else if (isDefaultProvider && !isInImageMode) {
                    // 文本模式下的"默认"平台：创建多个默认模型配置
                    // 确保所有配置使用相同的 provider、address、key 和 channel，以便在UI上聚合为一个卡片
                    val defaultModels = listOf(
                        "gemini-2.5-pro-1M",
                        "gemini-2.5-flash",
                        "gemini-flash-lite-latest"
                    )
                    
                    defaultModels.forEach { modelName ->
                        val config = ApiConfig(
                            id = UUID.randomUUID().toString(),
                            name = modelName,
                            provider = providerTrim,  // "默认"
                            address = "",  // 空,由后端注入
                            key = "",      // 空,由后端注入
                            model = modelName,
                            modalityType = ModalityType.TEXT,
                            channel = "",  // 使用空字符串确保所有默认配置聚合在一起
                            isValid = true
                        )
                        viewModel.addConfig(config, isImageGen = false)
                    }
                    showAddFullConfigDialog = false
                    viewModel.clearFetchedModels()
                } else if (key.isNotBlank() && providerTrim.isNotBlank() && address.isNotBlank()) {
                    // 改为启动"是否自动获取模型列表"的流程
                    // 将 enableCodeExecution 和 toolsJson 暂时存储在 ViewModel 或通过 Flow 传递
                    // 这里简单处理：直接传递给 startAddConfigFlow (需要修改 ViewModel)
                    // 或者先简化：在确认自动获取后，手动更新 Config
                    viewModel.startAddConfigFlow(providerTrim, address, key, channel, isInImageMode, enableCodeExecution, toolsJson)
                    showAddFullConfigDialog = false
                }
            },
            isImageMode = isInImageMode
        )
    }


    if (showAddModelToKeyDialog) {
        AddModelDialog(
            onDismissRequest = { showAddModelToKeyDialog = false },
            onConfirm = { newModelName ->
                if (newModelName.isNotBlank()) {
                    viewModel.addModelToConfigGroup(
                        apiKey = addModelToKeyTargetApiKey,
                        provider = addModelToKeyTargetProvider,
                        address = addModelToKeyTargetAddress,
                        modelName = newModelName,
                        channel = addModelToKeyTargetChannel,
                        isImageGen = isInImageMode
                    )
                    showAddModelToKeyDialog = false
                }
            }
        )
    }
    
    // 新增：手动输入模型对话框
    if (showManualModelInputDialog) {
        AddModelDialog(
            onDismissRequest = {
                showManualModelInputDialog = false
                manualModelInputName = ""
            },
            onConfirm = { modelName ->
                if (modelName.isNotBlank()) {
                    val newConfig = ApiConfig(
                        id = UUID.randomUUID().toString(),
                        name = modelName,
                        provider = manualModelInputProvider,
                        address = manualModelInputAddress,
                        key = manualModelInputKey,
                        model = modelName,
                        modalityType = if (manualModelInputIsImageGen) ModalityType.IMAGE else ModalityType.TEXT,
                        channel = manualModelInputChannel,
                        isValid = true
                    )
                    viewModel.addConfig(newConfig, manualModelInputIsImageGen)
                    showManualModelInputDialog = false
                    manualModelInputName = ""
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

    // 模型选择对话框（支持“全部添加/手动选择/改为手动输入”）
    if (showModelSelection) {
        ModelSelectionDialog(
            showDialog = true,
            models = fetchedModels,
            onDismiss = { viewModel.dismissModelSelectionDialog() },
            onSelectAll = { viewModel.onSelectAllModels() },
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
                        val providerKey = trimmedName.lowercase().trim()
                        newFullConfigAddress = SettingsDefaults.imageDefaultApiAddresses[providerKey]
                            ?: SettingsDefaults.imageDefaultApiAddresses[providerKey.replace(" ", "")]
                            ?: ""
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
            }
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
                    val providerKey = nextDefaultProvider.lowercase().trim()
                    newFullConfigAddress = SettingsDefaults.imageDefaultApiAddresses[providerKey] ?: ""
                }
                showConfirmDeleteProviderDialog = false
                providerToDelete = null
            },
            title = "删除平台",
            text = "您确定要删除模型平台 “$providerToDelete” 吗？\n\n这将同时删除所有使用此平台的配置。此操作不可撤销。"
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
            isExportEnabled = (textConfigs + imageConfigs).any {
                it.provider.trim().lowercase() !in listOf("默认", "default")
            } || chatHistory.isNotEmpty() || imageHistory.isNotEmpty(),
            chatHistoryCount = chatHistory.size,
            imageHistoryCount = imageHistory.size
        )
    }
}
