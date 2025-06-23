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
import com.example.everytalk.statecontroler.AppViewModel
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
            uri?.let {
                exportData?.second?.let { jsonContent ->
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(jsonContent.toByteArray())
                    }
                    viewModel.showSnackbar("配置已导出")
                    exportData = null
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
                actions = {
                    IconButton(onClick = { showImportExportDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.ImportExport,
                            contentDescription = "导入/导出配置",
                            tint = Color.Black
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
                val initialProvider = allProviders.firstOrNull() ?: "openai compatible"
                newFullConfigProvider = initialProvider
                newFullConfigKey = ""
                val providerKey = initialProvider.lowercase().trim()
                newFullConfigAddress = defaultApiAddresses[providerKey] ?: ""
                showAddFullConfigDialog = true
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
            },
            onDeleteConfigGroup = { apiKey, modalityType ->
                viewModel.deleteConfigGroup(apiKey, modalityType)
            }
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
            },
            onConfirm = {
                if (newFullConfigKey.isNotBlank() && newFullConfigProvider.isNotBlank() && newFullConfigAddress.isNotBlank()) {
                    showAddFullConfigDialog = false
                    addModelToKeyTargetApiKey = newFullConfigKey.trim()
                    addModelToKeyTargetProvider = newFullConfigProvider.trim()
                    addModelToKeyTargetAddress = newFullConfigAddress.trim()
                    addModelToKeyTargetModality = ModalityType.TEXT
                    addModelToKeyNewModelName = ""
                    showAddModelToKeyDialog = true
                }
            }
        )
    }

    if (showAddModelToKeyDialog) {
        AddModelToExistingKeyDialog(
            targetProvider = addModelToKeyTargetProvider,
            targetAddress = addModelToKeyTargetAddress,
            newModelName = addModelToKeyNewModelName,
            onNewModelNameChange = { addModelToKeyNewModelName = it },
            onDismissRequest = {
                showAddModelToKeyDialog = false
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
                viewModel.exportSettings()
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
