package com.example.everytalk.ui.screens.settings

import android.util.Log
import androidx.compose.foundation.layout.* // Keep this for PaddingValues if SettingsScreenContent uses it from here
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavController
import com.example.everytalk.StateControler.AppViewModel
import com.example.everytalk.data.DataClass.ApiConfig
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

    val apiConfigsByApiKey = remember(savedConfigs) {
        savedConfigs.groupBy { it.key }.filterValues { it.isNotEmpty() }
    }

    var showAddFullConfigDialog by remember { mutableStateOf(false) }
    var newFullConfigProvider by remember(allProviders) {
        mutableStateOf(
            allProviders.firstOrNull() ?: "openai compatible"
        )
    }
    var newFullConfigAddress by remember(newFullConfigProvider) {
        mutableStateOf(
            defaultApiAddresses[newFullConfigProvider.lowercase().trim()] ?: ""
        )
    }
    var newFullConfigKey by remember { mutableStateOf("") }

    var showAddModelToKeyDialog by remember { mutableStateOf(false) }
    var addModelToKeyTargetApiKey by remember { mutableStateOf("") }
    var addModelToKeyTargetProvider by remember { mutableStateOf("") }
    var addModelToKeyTargetAddress by remember { mutableStateOf("") }
    var addModelToKeyNewModelName by remember { mutableStateOf("") }

    var showAddCustomProviderDialog by remember { mutableStateOf(false) }
    var newCustomProviderNameInput by remember { mutableStateOf("") }

    var backButtonEnabled by remember { mutableStateOf(true) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.White,
        topBar = {
            TopAppBar(
                title = { Text("API 配置", color = Color.Black) }, // Title from your latest snippet
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
            apiConfigsByApiKey = apiConfigsByApiKey,
            onAddFullConfigClick = {
                newFullConfigProvider = allProviders.firstOrNull() ?: "openai compatible"
                newFullConfigAddress =
                    defaultApiAddresses[newFullConfigProvider.lowercase().trim()] ?: ""
                newFullConfigKey = ""
                showAddFullConfigDialog = true
            },
            onSelectConfig = { configToSelect ->
                viewModel.selectConfig(configToSelect)
            },
            selectedConfigIdInApp = selectedConfigForApp?.id,
            onAddModelForApiKeyClick = { apiKey, existingProvider, existingAddress ->
                addModelToKeyTargetApiKey = apiKey
                addModelToKeyTargetProvider = existingProvider
                addModelToKeyTargetAddress = existingAddress
                addModelToKeyNewModelName = ""
                showAddModelToKeyDialog = true
            },
            onDeleteModelForApiKey = { configToDelete ->
                viewModel.deleteConfig(configToDelete)
            }
        )
    }

    // Conditional Dialog displays - their definitions must be in another file
    if (showAddFullConfigDialog) {
        AddNewFullConfigDialog(
            provider = newFullConfigProvider,
            onProviderChange = { selectedProvider ->
                newFullConfigProvider = selectedProvider
                newFullConfigAddress =
                    defaultApiAddresses[selectedProvider.lowercase().trim()] ?: ""
            },
            allProviders = allProviders,
            onShowAddCustomProviderDialog = { showAddCustomProviderDialog = true },
            apiAddress = newFullConfigAddress,
            onApiAddressChange = { newFullConfigAddress = it },
            apiKey = newFullConfigKey,
            onApiKeyChange = { newFullConfigKey = it },
            onDismissRequest = { showAddFullConfigDialog = false },
            onConfirm = {
                if (newFullConfigKey.isNotBlank() && newFullConfigProvider.isNotBlank() && newFullConfigAddress.isNotBlank()) {
                    showAddFullConfigDialog = false
                    addModelToKeyTargetApiKey = newFullConfigKey.trim()
                    addModelToKeyTargetProvider = newFullConfigProvider.trim()
                    addModelToKeyTargetAddress = newFullConfigAddress.trim()
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
            onDismissRequest = { showAddModelToKeyDialog = false },
            onConfirm = {
                if (addModelToKeyNewModelName.isNotBlank()) {
                    val newConfig = ApiConfig(
                        id = UUID.randomUUID().toString(),
                        address = addModelToKeyTargetAddress.trim(),
                        key = addModelToKeyTargetApiKey.trim(),
                        model = addModelToKeyNewModelName.trim(),
                        provider = addModelToKeyTargetProvider.trim(),
                        name = addModelToKeyNewModelName.trim()
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
                showAddCustomProviderDialog = false; newCustomProviderNameInput = ""
            },
            onConfirm = {
                val trimmedName = newCustomProviderNameInput.trim()
                if (trimmedName.isNotBlank() && !allProviders.any {
                        it.equals(
                            trimmedName,
                            ignoreCase = true
                        )
                    }) {
                    viewModel.addProvider(trimmedName)
                    if (showAddFullConfigDialog && newFullConfigProvider != trimmedName) {
                        newFullConfigProvider = trimmedName
                        newFullConfigAddress =
                            defaultApiAddresses[trimmedName.lowercase().trim()] ?: ""
                    }
                    showAddCustomProviderDialog = false
                    newCustomProviderNameInput = ""
                }
            }
        )
    }
}