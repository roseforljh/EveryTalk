package com.example.everytalk.ui.screens.ImageGeneration

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
import com.example.everytalk.ui.screens.settings.AddNewFullConfigDialog
import com.example.everytalk.ui.screens.settings.AddProviderDialog
import com.example.everytalk.ui.screens.settings.ConfirmDeleteDialog
import com.example.everytalk.ui.screens.settings.EditConfigDialog
import com.example.everytalk.ui.screens.settings.ImportExportDialog
import com.example.everytalk.ui.screens.settings.SettingsScreenContent
import com.example.everytalk.ui.screens.settings.defaultApiAddresses
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenerationSettingsScreen(
    viewModel: AppViewModel,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    Log.i("ScreenComposition", "ImageGenerationSettingsScreen Composing/Recomposing.")
    
    // 图像设置界面固定为图像模式，不依赖应用状态，因为这是专用的图像配置界面
    Log.d("ImageGenerationSettings", "Fixed to IMAGE mode - this is the dedicated image configuration screen")
    
    val savedConfigs by viewModel.imageGenApiConfigs.collectAsState()
    val selectedConfigForApp by viewModel.selectedImageGenApiConfig.collectAsState()
    val allProviders by viewModel.allProviders.collectAsState()
    val isFetchingModels by viewModel.isFetchingModels.collectAsState()
    val fetchedModels by viewModel.fetchedModels.collectAsState()
    val isRefreshingModels by viewModel.isRefreshingModels.collectAsState()

    // 固定为图像模式的配置分组
    val apiConfigsByApiKeyAndModality = remember(savedConfigs) {
        savedConfigs.filter { it.modalityType == ModalityType.IMAGE }
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
    var showAddModelNameDialog by remember { mutableStateOf(false) }
    var pendingFullConfig by remember { mutableStateOf<ApiConfig?>(null) }
    var addModelToKeyTargetApiKey by remember { mutableStateOf("") }
    var addModelToKeyTargetProvider by remember { mutableStateOf("") }
    var addModelToKeyTargetAddress by remember { mutableStateOf("") }
    var addModelToKeyTargetModality by remember { mutableStateOf(ModalityType.IMAGE) }
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
                        viewModel.importSettings(jsonContent, isImageGen = true)
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

    LaunchedEffect(savedConfigs, selectedConfigForApp) {
        // 固定使用图像配置选择逻辑
        val currentSelected = selectedConfigForApp
        val imageConfigs = savedConfigs.filter { it.modalityType == ModalityType.IMAGE }
        if (currentSelected != null && imageConfigs.none { it.id == currentSelected.id }) {
            imageConfigs.firstOrNull()?.let {
                viewModel.selectConfig(it, isImageGen = true)  // 固定传入 isImageGen = true
            } ?: viewModel.clearSelectedConfig(true)  // 固定传入 isImageGen = true
        } else if (currentSelected == null && imageConfigs.isNotEmpty()) {
            viewModel.selectConfig(imageConfigs.first(), isImageGen = true)  // 固定传入 isImageGen = true
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { 
                    // 图像设置界面固定显示图像配置标题
                    Text("图像生成配置", color = MaterialTheme.colorScheme.onSurface) 
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
                viewModel.selectConfig(configToSelect, isImageGen = true)
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
                viewModel.deleteConfig(configToDelete, isImageGen = true)
            },
            onEditConfigClick = { config ->
                configToEdit = config
                showEditConfigDialog = true
            },
            onDeleteConfigGroup = { apiKey, modalityType ->
                viewModel.deleteImageGenConfigGroup(apiKey, modalityType)
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
                viewModel.clearFetchedModels()
            },
           onConfirm = { provider, address, key, channel, imageSize, numInferenceSteps, guidanceScale ->
               if (key.isNotBlank() && provider.isNotBlank() && address.isNotBlank()) {
                   pendingFullConfig = ApiConfig(
                       address = address,
                       key = key,
                       model = "",
                       provider = provider,
                       name = "",
                       channel = channel,
                       modalityType = ModalityType.IMAGE,
                       imageSize = imageSize,
                       numInferenceSteps = numInferenceSteps,
                       guidanceScale = guidanceScale
                   )
                   showAddFullConfigDialog = false
                   showAddModelNameDialog = true
                   viewModel.clearFetchedModels()
               }
           }
        )
    }

    if (showAddModelNameDialog) {
        AddImageModelToKeyDialog(
            onDismissRequest = {
                showAddModelNameDialog = false
                pendingFullConfig = null
            },
            onConfirm = { modelName ->
                pendingFullConfig?.let {
                    val finalConfig = it.copy(
                        id = UUID.randomUUID().toString(),
                        model = modelName,
                        name = modelName
                    )
                    viewModel.addConfig(finalConfig, isImageGen = true)
                }
                showAddModelNameDialog = false
                pendingFullConfig = null
            }
        )
    }

    if (showAddModelToKeyDialog) {
        AddImageModelToKeyDialog(
            onDismissRequest = { showAddModelToKeyDialog = false },
            onConfirm = { modelName ->
                val config = ApiConfig(
                    id = UUID.randomUUID().toString(),
                    address = addModelToKeyTargetAddress,
                    key = addModelToKeyTargetApiKey,
                    model = modelName,
                    provider = addModelToKeyTargetProvider,
                    name = modelName,
                    modalityType = ModalityType.IMAGE
                )
                viewModel.addConfig(config, isImageGen = true)
                showAddModelToKeyDialog = false
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
                // 固定传入 isImageGen = true，确保更新图像配置
                viewModel.updateConfigGroup(configToEdit!!, newAddress, newKey, configToEdit!!.provider, newChannel, true)
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
                viewModel.exportSettings(isImageGen = true)
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

@Composable
private fun AddImageModelToKeyDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var modelName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("添加图像模型") },
        text = {
            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                label = { Text("模型名称") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (modelName.isNotBlank()) {
                        onConfirm(modelName)
                    }
                }
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("取消")
            }
        }
    )
}