package com.example.everytalk.ui.screens.settings

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.example.everytalk.StateControler.AppViewModel
import com.example.everytalk.data.local.SharedPreferencesDataSource
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
    val selectedConfig by viewModel.selectedApiConfig.collectAsState()
    val allProviders by viewModel.allProviders.collectAsState()

    var editApiAddress by remember { mutableStateOf<String>("") }
    var editApiKey by remember { mutableStateOf<String>("") }
    var editModel by remember { mutableStateOf<String>("") }
    var editProvider by remember(allProviders) {
        mutableStateOf<String>(allProviders.firstOrNull() ?: "openai compatible")
    }
    var currentEditingConfigId by remember { mutableStateOf<String?>(null) }

    var showAddProviderDialog by remember { mutableStateOf<Boolean>(false) }
    var newProviderNameInput by remember { mutableStateOf<String>("") }

    val context = LocalContext.current
    val dataSource = remember(context) { SharedPreferencesDataSource(context.applicationContext) }
    val savedModelNamesMapByAddress = remember { mutableStateMapOf<String, Set<String>>() }

    var backButtonEnabled by remember { mutableStateOf<Boolean>(true) }

    LaunchedEffect(Unit) { // 加载模型名称历史
        savedModelNamesMapByAddress.putAll(dataSource.loadSavedModelNamesByApiAddress())
    }

    LaunchedEffect(allProviders, Unit) { // 同步 editProvider
        val currentEditProvider = editProvider
        if (allProviders.isNotEmpty() && !allProviders.contains(currentEditProvider)) {
            editProvider = allProviders.first()
        } else if (allProviders.isEmpty() && currentEditProvider != "openai compatible") {
            editProvider = "openai compatible"
        }
    }

    LaunchedEffect(currentEditingConfigId, savedConfigs, editProvider) {
        Log.d(
            "SettingsScreenEffect",
            "Config/Provider changed. EditingId: $currentEditingConfigId, Provider: $editProvider"
        )
        val configToEdit = savedConfigs.find { it.id == currentEditingConfigId }
        if (configToEdit != null) {
            Log.d("SettingsScreenEffect", "Loading config to edit: ${configToEdit.id}")
            editApiAddress = configToEdit.address
            editApiKey = configToEdit.key
            editModel = configToEdit.model
            if (editProvider != configToEdit.provider) {
                Log.d(
                    "SettingsScreenEffect",
                    "Syncing editProvider from '${editProvider}' to '${configToEdit.provider}'"
                )
                editProvider = configToEdit.provider
            }
        } else {
            Log.d(
                "SettingsScreenEffect",
                "Not editing. Applying defaults for provider: $editProvider"
            )
            val providerKey = editProvider.lowercase().trim()
            // 只有当地址确实需要根据平台改变时才更新，避免覆盖用户可能刚输入的值
            val expectedAddress = defaultApiAddresses[providerKey] ?: ""
            if (editApiAddress != expectedAddress && defaultApiAddresses.containsKey(providerKey)) {
                editApiAddress = expectedAddress
            } else if (!defaultApiAddresses.containsKey(providerKey) && currentEditingConfigId == null) {
                // 如果不是已知平台的默认地址，并且不是在编辑，则清空地址 (例如自定义平台)
                // 但如果用户正在输入地址，则不应该清空
            }
            // 清空 Key 和 Model，因为是“新”状态或基于新平台的默认状态
            editApiKey = ""
            editModel = ""
            Log.d(
                "SettingsScreenEffect",
                "Applied defaults: Address='${editApiAddress}', Key='', Model=''"
            )
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.White,
        topBar = {
            TopAppBar(
                title = { Text("API 配置管理", color = Color.Black) },
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
            savedConfigs = savedConfigs,
            onAddConfig = { configToAdd ->
                viewModel.addConfig(configToAdd)
                val trimmedModel = configToAdd.model.trim()
                val trimmedAddress = configToAdd.address.trim()
                if (trimmedModel.isNotEmpty() && trimmedAddress.isNotEmpty()) {
                    dataSource.addSavedModelNameForApiAddress(trimmedAddress, trimmedModel)
                    savedModelNamesMapByAddress.compute(trimmedAddress) { _, set ->
                        (set ?: emptySet()) + trimmedModel
                    }
                }
                currentEditingConfigId = null
                val providerKey = editProvider.lowercase().trim()
                editApiAddress = defaultApiAddresses[providerKey] ?: ""
                editApiKey = ""
                editModel = ""
            },
            onUpdateConfig = { configToUpdate ->
                viewModel.updateConfig(configToUpdate)
                val trimmedModel = configToUpdate.model.trim()
                val trimmedAddress = configToUpdate.address.trim()
                if (trimmedModel.isNotEmpty() && trimmedAddress.isNotEmpty()) {
                    dataSource.addSavedModelNameForApiAddress(trimmedAddress, trimmedModel)
                    savedModelNamesMapByAddress.compute(trimmedAddress) { _, set ->
                        (set ?: emptySet()) + trimmedModel
                    }
                }
                currentEditingConfigId = null
            },
            onDeleteConfig = { configToDelete ->
                viewModel.deleteConfig(configToDelete)
                if (configToDelete.id == currentEditingConfigId) currentEditingConfigId = null
            },
            onClearAll = {
                viewModel.clearAllConfigs()
                currentEditingConfigId = null
            },
            onConfigSelectForEdit = { config -> currentEditingConfigId = config.id },
            editApiAddress = editApiAddress,
            onEditApiAddressChange = { newAddress ->
                // 允许用户自由修改地址，地址变化会自然影响模型名称列表的获取
                if (editApiAddress != newAddress) editApiAddress = newAddress
            },
            editApiKey = editApiKey, onEditApiKeyChange = { editApiKey = it },
            editModel = editModel, onEditModelChange = { editModel = it },
            editProvider = editProvider,
            onEditProviderChange = { newProvider ->
                if (editProvider != newProvider) {
                    Log.d(
                        "SettingsScreen",
                        "Provider changed by user from '$editProvider' to '$newProvider'"
                    )
                    editProvider = newProvider
                    val providerKey = newProvider.lowercase().trim()
                    val newDefaultAddress = defaultApiAddresses[providerKey] ?: ""

                    // 只有当地址确实因平台切换而需要改变时才更新
                    if (editApiAddress != newDefaultAddress && (defaultApiAddresses.containsKey(
                            providerKey
                        ) || newDefaultAddress.isEmpty())
                    ) {
                        editApiAddress = newDefaultAddress
                    }
                    // 清空模型和API密钥，因为平台变了
                    editModel = ""
                    editApiKey = ""
                    if (currentEditingConfigId != null) {
                        Log.d(
                            "SettingsScreen",
                            "Exiting edit mode (was $currentEditingConfigId) due to provider change."
                        )
                        currentEditingConfigId = null
                    }
                }
            },
            currentEditingConfigId = currentEditingConfigId,
            onClearEditFields = {
                currentEditingConfigId = null
                val providerKey = editProvider.lowercase().trim()
                editApiAddress = defaultApiAddresses[providerKey] ?: ""
                editApiKey = ""
                editModel = ""
            },
            onSelectConfig = { configToSelect -> viewModel.selectConfig(configToSelect) },
            selectedConfigIdInApp = selectedConfig?.id,
            allProviders = allProviders,
            onShowAddProviderDialogChange = { showAddProviderDialog = it },
            savedModelNamesForProvider = savedModelNamesMapByAddress[editApiAddress.trim()]
                ?: emptySet(),
            onDeleteSavedModelName = { modelNameToDelete -> // 回调简化，API地址从 editApiAddress 获取
                val currentTrimmedAddress = editApiAddress.trim()
                if (currentTrimmedAddress.isNotEmpty()) {
                    dataSource.removeSavedModelNameForApiAddress(
                        currentTrimmedAddress,
                        modelNameToDelete
                    )
                    savedModelNamesMapByAddress.computeIfPresent(currentTrimmedAddress) { _, set -> set - modelNameToDelete }
                }
            }
        )
    }

    if (showAddProviderDialog) {
        AddProviderDialog(
            newProviderName = newProviderNameInput,
            onNewProviderNameChange = { newProviderNameInput = it },
            onDismissRequest = { showAddProviderDialog = false; newProviderNameInput = "" },
            onConfirm = {
                val trimmedName = newProviderNameInput.trim()
                if (trimmedName.isNotBlank() && !allProviders.any {
                        it.equals(
                            trimmedName,
                            ignoreCase = true
                        )
                    }) {
                    viewModel.addProvider(trimmedName)
                    if (editProvider != trimmedName) {
                        editProvider = trimmedName
                        val providerKey = trimmedName.lowercase().trim()
                        editApiAddress = defaultApiAddresses[providerKey] ?: "" // 新平台默认地址
                        editModel = ""
                        editApiKey = ""
                        if (currentEditingConfigId != null) currentEditingConfigId = null
                    }
                    showAddProviderDialog = false; newProviderNameInput = ""
                } else {
                    Log.w("SettingsScreen", "平台名称无效或已存在: $trimmedName")
                }
            }
        )
    }
}