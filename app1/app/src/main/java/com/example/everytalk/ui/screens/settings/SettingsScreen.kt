package com.example.everytalk.ui.screens.settings

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavController
import com.example.everytalk.StateControler.AppViewModel
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.DataClass.ModalityType
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
    val selectedConfigForApp by viewModel.selectedApiConfig.collectAsState()
    val allProviders by viewModel.allProviders.collectAsState()

    val apiConfigsByApiKeyAndModality = remember(savedConfigs) {
        savedConfigs.groupBy { it.key }
            .mapValues { entry ->
                entry.value.groupBy { it.modalityType }
            }
            .filterValues { it.isNotEmpty() }
    }

    // --- 对话框状态 ---
    var showSelectModalityDialog by remember { mutableStateOf(false) }
    var selectedModalityForNewConfig by remember { mutableStateOf<ModalityType?>(null) }

    var showAddFullConfigDialog by remember { mutableStateOf(false) }
    var newFullConfigProvider by remember { mutableStateOf("") } // 初始化为空，将在适当时候设置
    var newFullConfigAddress by remember { mutableStateOf("") }
    var newFullConfigKey by remember { mutableStateOf("") }

    var showAddModelToKeyDialog by remember { mutableStateOf(false) }
    var addModelToKeyTargetApiKey by remember { mutableStateOf("") }
    var addModelToKeyTargetProvider by remember { mutableStateOf("") } // Corrected typo from mutableStateOF
    var addModelToKeyTargetAddress by remember { mutableStateOf("") }
    var addModelToKeyTargetModality by remember { mutableStateOf(ModalityType.TEXT) }
    var addModelToKeyNewModelName by remember { mutableStateOf("") }

    var showAddCustomProviderDialog by remember { mutableStateOf(false) }
    var newCustomProviderNameInput by remember { mutableStateOf("") }

    var backButtonEnabled by remember { mutableStateOf(true) }

    // --- 编辑和删除对话框状态 ---
    var showEditConfigDialog by remember { mutableStateOf(false) }
    var configToEdit by remember { mutableStateOf<ApiConfig?>(null) }
    var showConfirmDeleteProviderDialog by remember { mutableStateOf(false) }
    var providerToDelete by remember { mutableStateOf<String?>(null) }


    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.White,
        topBar = {
            TopAppBar(
                title = { Text("API 配置", color = Color.Black) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (backButtonEnabled) {
                            backButtonEnabled = false; navController.popBackStack()
                        }
                    }, enabled = backButtonEnabled) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "返回",
                            tint = if (backButtonEnabled) Color.Black else Color.Gray
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White,
                    titleContentColor = Color.Black,
                    navigationIconContentColor = Color.Black
                )
            )
        }
    ) { paddingValues ->
        SettingsScreenContent(
            paddingValues = paddingValues,
            apiConfigsByApiKeyAndModality = apiConfigsByApiKeyAndModality,
            onAddFullConfigClick = {
                selectedModalityForNewConfig = null
                showSelectModalityDialog = true
            },
            onSelectConfig = { configToSelect ->
                viewModel.selectConfig(configToSelect)
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
                viewModel.deleteConfig(configToDelete)
            },
            onEditConfigClick = { config ->
                configToEdit = config
                showEditConfigDialog = true
            }
        )
    }

    // --- 对话框链 ---

    // 1. 选择模态类型对话框
    if (showSelectModalityDialog) {
        SelectModalityDialog(
            onDismissRequest = { showSelectModalityDialog = false },
            onModalitySelected = { modality ->
                selectedModalityForNewConfig = modality
                showSelectModalityDialog = false

                // 关键: 在显示 AddNewFullConfigDialog 之前，设置 newFullConfigProvider 的初始值
                val initialProvider = allProviders.firstOrNull() ?: "openai compatible"
                newFullConfigProvider = initialProvider
                newFullConfigKey = "" // 重置key

                val providerKey = initialProvider.lowercase().trim()
                newFullConfigAddress = if (modality == ModalityType.TEXT) {
                    defaultApiAddresses[providerKey] ?: ""
                } else if (providerKey == "google" && modality == ModalityType.MULTIMODAL) { // Corrected enum constant
                    defaultApiAddresses["google"] ?: ""
                } else {
                    ""
                }
                showAddFullConfigDialog = true
            }
        )
    }


    // 2. 添加完整配置对话框 (Provider, Address, Key)
    if (showAddFullConfigDialog && selectedModalityForNewConfig != null) {
        AddNewFullConfigDialog(
            provider = newFullConfigProvider,
            onProviderChange = { selectedProvider ->
                newFullConfigProvider = selectedProvider
                val currentModality = selectedModalityForNewConfig!!
                val providerKey = selectedProvider.lowercase().trim()

                newFullConfigAddress = if (currentModality == ModalityType.TEXT) {
                    defaultApiAddresses[providerKey] ?: ""
                } else if (providerKey == "google" && currentModality == ModalityType.MULTIMODAL) { // Corrected enum constant
                    defaultApiAddresses["google"] ?: ""
                } else {
                    ""
                }
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
                selectedModalityForNewConfig = null
            },
            onConfirm = {
                if (newFullConfigKey.isNotBlank() && newFullConfigProvider.isNotBlank() && newFullConfigAddress.isNotBlank()) {
                    showAddFullConfigDialog = false
                    addModelToKeyTargetApiKey = newFullConfigKey.trim()
                    addModelToKeyTargetProvider = newFullConfigProvider.trim()
                    addModelToKeyTargetAddress = newFullConfigAddress.trim()
                    addModelToKeyTargetModality = selectedModalityForNewConfig!!
                    addModelToKeyNewModelName = ""
                    showAddModelToKeyDialog = true
                }
            }
        )
    }

    // 3. 为Key添加模型对话框
    if (showAddModelToKeyDialog) {
        AddModelToExistingKeyDialog(
            targetProvider = addModelToKeyTargetProvider,
            targetAddress = addModelToKeyTargetAddress,
            newModelName = addModelToKeyNewModelName,
            onNewModelNameChange = { addModelToKeyNewModelName = it },
            onDismissRequest = {
                showAddModelToKeyDialog = false
                selectedModalityForNewConfig = null
            },
            onConfirm = {
                if (addModelToKeyNewModelName.isNotBlank()) {
                    val newConfig = ApiConfig(
                        id = UUID.randomUUID().toString(),
                        address = addModelToKeyTargetAddress.trim(),
                        key = addModelToKeyTargetApiKey.trim(),
                        model = addModelToKeyNewModelName.trim(),
                        provider = addModelToKeyTargetProvider.trim(),
                        name = addModelToKeyNewModelName.trim(),
                        modalityType = addModelToKeyTargetModality
                    )
                    viewModel.addConfig(newConfig)
                    showAddModelToKeyDialog = false
                    selectedModalityForNewConfig = null
                }
            }
        )
    }

    // 添加自定义Provider的对话框
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
                    viewModel.addProvider(trimmedName) // ViewModel 会处理将其设为最新添加的

                    // 当添加新的自定义平台后，自动在 AddNewFullConfigDialog 中选中它
                    if (showAddFullConfigDialog) {
                        newFullConfigProvider = trimmedName // 选中新添加的平台
                        val currentModality = selectedModalityForNewConfig
                        val providerKey = trimmedName.lowercase().trim()

                        if (currentModality != null) {
                            newFullConfigAddress = if (currentModality == ModalityType.TEXT) {
                                defaultApiAddresses[providerKey] ?: (defaultApiAddresses[providerKey.replace(" ", "")] ?: "") // 尝试移除空格再查找
                            } else if (providerKey == "google" && currentModality == ModalityType.MULTIMODAL) {
                                defaultApiAddresses["google"] ?: ""
                            } else {
                                // 对于其他类型的模型或新的自定义平台，可能没有预设地址
                                // 特别是新添加的 OpenRouter，如果 modality 不是 TEXT，这里会是空
                                // 如果是 OpenRouter 且是 TEXT，则会使用 SettingsUtils 中定义的地址
                                if (providerKey == "openrouter" && currentModality == ModalityType.TEXT) {
                                    defaultApiAddresses["openrouter"] ?: ""
                                } else {
                                    "" // 用户需要手动输入
                                }
                            }
                        } else {
                            newFullConfigAddress = "" // 如果没有选择模态，则清空地址
                        }
                    }

                    showAddCustomProviderDialog = false
                    newCustomProviderNameInput = ""
                }
            }
        )
    }

    // --- 编辑和删除对话框 ---

    if (showEditConfigDialog && configToEdit != null) {
        EditConfigDialog(
            representativeConfig = configToEdit!!,
            onDismissRequest = {
                showEditConfigDialog = false
                configToEdit = null
            },
            onConfirm = { newAddress, newKey ->
                viewModel.updateConfigGroup(configToEdit!!, newAddress, newKey)
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
                // 如果当前选中的 provider 被删除了，需要重置
                if (newFullConfigProvider == providerNameToDelete) {
                    val nextDefaultProvider = viewModel.allProviders.value.firstOrNull() ?: "openai compatible"
                    newFullConfigProvider = nextDefaultProvider
                    val currentModality = selectedModalityForNewConfig
                    if (currentModality != null) {
                        val providerKey = nextDefaultProvider.lowercase().trim()
                        newFullConfigAddress = if (currentModality == ModalityType.TEXT) {
                            defaultApiAddresses[providerKey] ?: ""
                        } else if (providerKey == "google" && currentModality == ModalityType.MULTIMODAL) {
                            defaultApiAddresses["google"] ?: ""
                        } else {
                            ""
                        }
                    } else {
                        newFullConfigAddress = "" // 或者其他默认逻辑
                    }
                }
                showConfirmDeleteProviderDialog = false
                providerToDelete = null
            },
            title = "删除平台",
            text = "您确定要删除模型平台 “$providerToDelete” 吗？\n\n这将同时删除所有使用此平台的配置。此操作不可撤销。"
        )
    }
}