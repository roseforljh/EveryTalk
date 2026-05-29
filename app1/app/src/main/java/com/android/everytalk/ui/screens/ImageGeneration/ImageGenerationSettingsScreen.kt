package com.android.everytalk.ui.screens.ImageGeneration

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogCancelColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.screens.settings.AddNewFullConfigDialog
import com.android.everytalk.ui.screens.settings.AddProviderDialog
import com.android.everytalk.ui.screens.settings.ConfirmDeleteDialog
import com.android.everytalk.ui.screens.settings.EditConfigDialog
import com.android.everytalk.ui.screens.settings.SettingsScreenContent
import java.util.UUID
import com.android.everytalk.ui.screens.settings.DialogTextFieldColors
import com.android.everytalk.ui.screens.settings.DialogShape
import com.android.everytalk.ui.screens.settings.SettingsDefaults
import com.android.everytalk.ui.screens.settings.SettingsFieldLabel
import com.android.everytalk.ui.screens.settings.dialogs.AutoFetchModelsConfirmDialog
import com.android.everytalk.ui.screens.settings.dialogs.ModelSelectionDialog

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
    val showAutoFetchConfirm by viewModel.showAutoFetchConfirmDialog.collectAsState()
    val showModelSelection by viewModel.showModelSelectionDialog.collectAsState()

    // 固定为图像模式的配置分组
    val apiConfigsByApiKeyAndModality = remember(savedConfigs) {
        savedConfigs.filter { it.modalityType == ModalityType.IMAGE }
            .groupBy { config ->
                // 对于"默认"provider，只按provider分组（忽略address、key、channel），所有默认配置在同一个卡片
                val isDefaultProvider = config.provider.trim().lowercase() in listOf("默认", "default")
                if (isDefaultProvider) {
                    "default|||"
                } else {
                    "${config.provider}|${config.address}|${config.channel}|${config.key}"
                }
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
    var showAddModelNameDialog by remember { mutableStateOf(false) }
    var pendingFullConfig by remember { mutableStateOf<ApiConfig?>(null) }
    var addModelToKeyTargetApiKey by remember { mutableStateOf("") }
    var addModelToKeyTargetProvider by remember { mutableStateOf("") }
    var addModelToKeyTargetAddress by remember { mutableStateOf("") }
    var addModelToKeyTargetChannel by remember { mutableStateOf("") }
    var addModelToKeyTargetModality by remember { mutableStateOf(ModalityType.IMAGE) }
    var addModelToKeyNewModelName by remember { mutableStateOf("") }
    var showManualModelInputDialog by remember { mutableStateOf(false) }
    var manualModelInputProvider by remember { mutableStateOf("") }
    var manualModelInputAddress by remember { mutableStateOf("") }
    var manualModelInputKey by remember { mutableStateOf("") }
    var manualModelInputChannel by remember { mutableStateOf("") }

    var showAddCustomProviderDialog by remember { mutableStateOf(false) }
    var newCustomProviderNameInput by remember { mutableStateOf("") }

    var backButtonEnabled by remember { mutableStateOf(true) }

    var showEditConfigDialog by remember { mutableStateOf(false) }
    var configToEdit by remember { mutableStateOf<ApiConfig?>(null) }
    var showConfirmDeleteProviderDialog by remember { mutableStateOf(false) }
    var providerToDelete by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        viewModel.showManualModelInputRequest.collect { request ->
            if (request.isImageGen) {
                manualModelInputProvider = request.provider
                manualModelInputAddress = request.address
                manualModelInputKey = request.key
                manualModelInputChannel = request.channel
                showManualModelInputDialog = true
            }
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

    val isDark = isSystemInDarkTheme()
    val buttonBg = if (isDark) Color(0xFF303030) else Color(0xFFEDEDED)
    val borderColor = if (isDark) Color(0xFF414141) else Color(0xFFF3F3F3)
    val contentColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val iconButtonSize = 44.dp
    val topContentPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + iconButtonSize + 24.dp

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp)
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            SettingsScreenContent(
                paddingValues = PaddingValues(top = topContentPadding),
            apiConfigsByApiKeyAndModality = apiConfigsByApiKeyAndModality,
            onAddFullConfigClick = {
                // 图像模式新增时默认即为"默认"
                val initialProvider = "默认"
                newFullConfigProvider = initialProvider
                newFullConfigKey = ""
                newFullConfigAddress = SettingsDefaults.imageDefaultApiAddressFor(initialProvider)
                showAddFullConfigDialog = true
            },
            onSelectConfig = { configToSelect ->
                viewModel.selectConfig(configToSelect, isImageGen = true)
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
                viewModel.deleteConfig(configToDelete, isImageGen = true)
            },
            onEditConfigClick = { config ->
                configToEdit = config
                showEditConfigDialog = true
            },
            onDeleteConfigGroup = { representativeConfig ->
                viewModel.deleteImageGenConfigGroup(representativeConfig)
            },
            onRefreshModelsClick = { config ->
                viewModel.refreshModelsForConfig(config)
            },
                isRefreshingModels = isRefreshingModels,
                isImageMode = true
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to MaterialTheme.colorScheme.background,
                                0.65f to MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                                1.0f to Color.Transparent
                            )
                        )
                    )
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(iconButtonSize)
                            .shadow(6.dp, CircleShape, clip = false)
                            .clip(CircleShape)
                            .background(buttonBg)
                            .border(1.dp, borderColor, CircleShape)
                            .clickable(enabled = backButtonEnabled) {
                                backButtonEnabled = false
                                navController.popBackStack()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = contentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Box(
                        modifier = Modifier
                            .size(iconButtonSize)
                            .shadow(6.dp, CircleShape, clip = false)
                            .clip(CircleShape)
                            .background(buttonBg)
                            .border(1.dp, borderColor, CircleShape)
                            .clickable {
                                val initialProvider = "默认"
                                newFullConfigProvider = initialProvider
                                newFullConfigKey = ""
                                newFullConfigAddress = SettingsDefaults.imageDefaultApiAddressFor(initialProvider)
                                showAddFullConfigDialog = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "添加",
                            tint = contentColor,
                            modifier = Modifier.size(20.dp)
                        )
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
                newFullConfigAddress = SettingsDefaults.imageDefaultApiAddressFor(selectedProvider)
            },
            allProviders = SettingsDefaults.imageDefaultApiAddresses.keys.toList().filter { it !in listOf("默认", "default") },
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
           onConfirm = { provider, address, key, channel, imageSize, numInferenceSteps, guidanceScale, _, _ ->
               val providerTrim = provider.trim()
               val pLower = providerTrim.lowercase()
               val isDefaultProvider = pLower in listOf("默认","default")
               if (isDefaultProvider) {
                   // 选择"默认"时，直接添加 Kolors 配置并关闭弹窗
                   val config = ApiConfig(
                       id = java.util.UUID.randomUUID().toString(),
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
               } else if (pLower in listOf("硅基流动","siliconflow") && key.isBlank() && address.isBlank()) {
                   // 新增：硅基流动默认配置（使用预设地址，便于快速添加）
                   val defaultAddr = SettingsDefaults.imageDefaultApiAddressFor(providerTrim)
                       .ifBlank { "https://api.siliconflow.cn/v1/images/generations" }
                   val config = ApiConfig(
                       id = java.util.UUID.randomUUID().toString(),
                       name = "SiliconFlow (默认)",
                       provider = providerTrim,
                       address = defaultAddr,
                       key = "",
                       model = "",
                       modalityType = ModalityType.IMAGE,
                       channel = channel,
                       isValid = true
                   )
                   viewModel.addConfig(config, isImageGen = true)
                   showAddFullConfigDialog = false
                   viewModel.clearFetchedModels()
               } else if (key.isNotBlank() && providerTrim.isNotBlank() && address.isNotBlank()) {
                   viewModel.startAddConfigFlow(providerTrim, address, key, channel, isImageGen = true)
                   showAddFullConfigDialog = false
               }
           },
           isImageMode = true
        )
    }

    if (showManualModelInputDialog) {
        AddImageModelToKeyDialog(
            onDismissRequest = { showManualModelInputDialog = false },
            onConfirm = { modelName ->
                val config = ApiConfig(
                    id = UUID.randomUUID().toString(),
                    address = manualModelInputAddress,
                    key = manualModelInputKey,
                    model = modelName,
                    provider = manualModelInputProvider,
                    name = modelName,
                    modalityType = ModalityType.IMAGE,
                    channel = manualModelInputChannel
                )
                viewModel.addConfig(config, isImageGen = true)
                showManualModelInputDialog = false
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
                    modalityType = ModalityType.IMAGE,
                    channel = addModelToKeyTargetChannel
                )
                viewModel.addConfig(config, isImageGen = true)
                showAddModelToKeyDialog = false
            }
        )
    }

    if (showAutoFetchConfirm) {
        AutoFetchModelsConfirmDialog(
            showDialog = true,
            onDismiss = { viewModel.dismissAutoFetchConfirmDialog() },
            onConfirmAutoFetch = { viewModel.onConfirmAutoFetch() },
            onManualInput = { viewModel.onManualInput() }
        )
    }

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
            onConfirm = { newProvider, newAddress, newKey, newChannel, _, _ ->
                // 固定传入 isImageGen = true，确保更新图像配置
                // Also pass newProvider to update the provider name
                viewModel.updateConfigGroup(
                    representativeConfig = configToEdit!!,
                    newProvider = newProvider,
                    newAddress = newAddress,
                    newKey = newKey,
                    newChannel = newChannel,
                    isImageGen = true
                )
                showEditConfigDialog = false
                configToEdit = null
            },
            isImageMode = true
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
            title = "删除平台",
            text = "您确定要删除模型平台 \"${providerToDelete}\" 吗？\n\n这将同时删除所有使用此平台的配置。此操作不可撤销。"
        )
    }
}

@Composable
private fun AddImageModelToKeyDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var modelName by remember { mutableStateOf("") }

    val dialogBg = appDialogContainerColor()
    val contentColor = appDialogContentColor()
    val cancelButtonColor = appDialogCancelColor()
    val confirmButtonColor = contentColor
    val confirmButtonTextColor = dialogBg

    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = dialogBg,
        titleContentColor = contentColor,
        textContentColor = contentColor,
        title = {
            Text(
                text = "添加图像模型",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SettingsFieldLabel("模型名称")
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    placeholder = { Text("例如: Kwai-Kolors/Kolors") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = DialogShape,
                    colors = DialogTextFieldColors
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 取消按钮：与语音模式 TTS 样式统一（红色描边）
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = dialogBg,
                        contentColor = cancelButtonColor
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, cancelButtonColor)
                ) {
                    Text(
                        text = "取消",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                // 确认按钮：与语音模式 TTS 样式统一
                Button(
                    onClick = {
                        if (modelName.isNotBlank()) {
                            onConfirm(modelName)
                        }
                    },
                    enabled = modelName.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = confirmButtonColor,
                        contentColor = confirmButtonTextColor,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Text(
                        text = "确认",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        },
        dismissButton = {}
    )
}
