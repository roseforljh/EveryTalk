package com.example.everytalk.ui.screens.settings

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
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.DataClass.ModalityType
import com.example.everytalk.statecontroller.AppViewModel
import com.example.everytalk.statecontroller.SimpleModeManager
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    Log.i("ScreenComposition", "SettingsScreen Composing/Recomposing.")
    val savedConfigs by viewModel.apiConfigs.collectAsState()
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

    val apiConfigsByApiKeyAndModality = remember(savedConfigs, isInImageMode) {
        val configsToShow = if (isInImageMode) {
            // 图像模式显示图像生成配置
            viewModel.imageGenApiConfigs.value.filter { it.modalityType == ModalityType.IMAGE }
        } else {
            // 文本模式显示文本配置
            savedConfigs.filter { it.modalityType == ModalityType.TEXT }
        }
        
        configsToShow
            .groupBy { it.key }
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
    var addModelToKeyTargetModality by remember { mutableStateOf(ModalityType.TEXT) }
    var addModelToKeyNewModelName by remember { mutableStateOf("") }

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
                        viewModel.showSnackbar("配置已导出")
                    } catch (e: Exception) {
                        Log.e("SettingsScreen", "导出失败", e)
                        viewModel.showSnackbar("导出失败: ${e.message}")
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
                        viewModel.importSettings(jsonContent, isImageGen = false)
                    }
                } catch (e: Exception) {
                    viewModel.showSnackbar("导入失败: ${e.message}")
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

    LaunchedEffect(savedConfigs, selectedConfigForApp, isInImageMode) {
        val currentSelected = selectedConfigForApp
        val configsToCheck = if (isInImageMode) {
            viewModel.imageGenApiConfigs.value
        } else {
            savedConfigs
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
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        SettingsScreenContent(
            paddingValues = paddingValues,
            apiConfigsByApiKeyAndModality = apiConfigsByApiKeyAndModality,
            onAddFullConfigClick = {
                val initialProvider = allProviders.firstOrNull() ?: "openai compatible"
                newFullConfigProvider = initialProvider
                newFullConfigKey = ""
                val providerKey = initialProvider.lowercase().trim()
                newFullConfigAddress = defaultApiAddresses[providerKey] ?: ""
                showAddFullConfigDialog = true
            },
            onSelectConfig = { configToSelect ->
                viewModel.selectConfig(configToSelect, isInImageMode)
            },
            selectedConfigIdInApp = selectedConfigForApp?.id,
            onAddModelForApiKeyClick = { apiKey, existingProvider, existingAddress, existingModality ->
                addModelToKeyTargetApiKey = apiKey
                addModelToKeyTargetProvider = existingProvider
                addModelToKeyTargetAddress = existingAddress
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
            onDeleteConfigGroup = { apiKey, modalityType ->
                viewModel.deleteConfigGroup(apiKey, modalityType, isInImageMode)
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
                newFullConfigAddress = defaultApiAddresses[providerKey] ?: ""
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
            onConfirm = { provider, address, key, channel, _, _, _ ->
                if (key.isNotBlank() && provider.isNotBlank() && address.isNotBlank()) {
                    viewModel.createConfigAndFetchModels(provider, address, key, channel, isInImageMode)
                    showAddFullConfigDialog = false
                    viewModel.clearFetchedModels()
                }
            }
        )
    }


    if (showAddModelToKeyDialog) {
        // This dialog is no longer used in the new workflow, but we keep the logic here
        // in case it's needed in the future.
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
                        isImageGen = isInImageMode
                    )
                    showAddModelToKeyDialog = false
                }
            }
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
                        newFullConfigAddress = defaultApiAddresses[providerKey] ?: (defaultApiAddresses[providerKey.replace(" ", "")] ?: "")
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
            onConfirm = { newAddress, newKey, newChannel ->
                viewModel.updateConfigGroup(configToEdit!!, newAddress, newKey, configToEdit!!.provider, newChannel, isInImageMode)
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
                    newFullConfigAddress = defaultApiAddresses[providerKey] ?: ""
                }
                showConfirmDeleteProviderDialog = false
                providerToDelete = null
            },
            title = "删除平台",
            text = "您确定要删除模型平台 “$providerToDelete” 吗？\n\n这将同时删除所有使用此平台的配置。此操作不可撤销。"
        )
    }
    if (showImportExportDialog) {
        ImportExportDialog(
            onDismissRequest = { showImportExportDialog = false },
            onExport = {
                viewModel.exportSettings(isImageGen = false)
                showImportExportDialog = false
            },
            onImport = {
                importSettingsLauncher.launch("application/json")
                showImportExportDialog = false
            },
            isExportEnabled = savedConfigs.isNotEmpty()
        )
    }
}
