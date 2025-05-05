package com.example.app1.ui.components

import android.annotation.SuppressLint
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.app1.data.local.SharedPreferencesDataSource
import com.example.app1.data.models.ApiConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.compose.material3.BasicAlertDialog
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    savedConfigs: List<ApiConfig>,
    selectedConfig: ApiConfig?,
    onDismissRequest: () -> Unit,
    onAddConfig: (config: ApiConfig) -> Unit,
    onUpdateConfig: (config: ApiConfig) -> Unit,
    onDeleteConfig: (config: ApiConfig) -> Unit,
    onClearAll: () -> Unit,
    onSelectConfig: (config: ApiConfig) -> Unit
) {
    // --- 状态变量 ---
    var editApiAddress by remember { mutableStateOf("") }
    var editApiKey by remember { mutableStateOf("") }
    var editModel by remember { mutableStateOf("") }
    var editProvider by remember { mutableStateOf("openai") }
    var currentEditingConfigId by remember { mutableStateOf<String?>(null) }

    // --- 管理已保存的模型名称 ---
    val context = LocalContext.current
    val dataSource = remember { SharedPreferencesDataSource(context) }
    val savedModelNamesMap = remember { mutableStateMapOf<String, Set<String>>() }

    // --- 加载逻辑 ---
    LaunchedEffect(Unit) {
        savedModelNamesMap.putAll(dataSource.loadSavedModelNamesByProvider())
    }
    LaunchedEffect(currentEditingConfigId) {
        val configToEdit = savedConfigs.find { it.id == currentEditingConfigId }
        if (configToEdit != null) {
            editApiAddress = configToEdit.address; editApiKey = configToEdit.key; editModel =
                configToEdit.model; editProvider = configToEdit.provider
        } else {
            editApiAddress = ""; editApiKey = ""; editModel = ""; editProvider = "openai"
        }
    }

    // --- UI 委托给 SettingsDialogContent ---
    SettingsDialogContent(
        savedConfigs = savedConfigs,
        onDismissRequest = onDismissRequest,
        onAddConfig = { configToAdd ->
            onAddConfig(configToAdd)
            dataSource.addSavedModelName(configToAdd.provider, configToAdd.model)
            savedModelNamesMap.compute(configToAdd.provider) { _, set ->
                (set ?: emptySet()) + configToAdd.model.trim()
            }
            editApiAddress = ""; editApiKey = ""; editModel = ""; editProvider = "openai"
            currentEditingConfigId = null
        },
        onUpdateConfig = { configToUpdate ->
            onUpdateConfig(configToUpdate)
            dataSource.addSavedModelName(configToUpdate.provider, configToUpdate.model)
            savedModelNamesMap.compute(configToUpdate.provider) { _, set ->
                (set ?: emptySet()) + configToUpdate.model.trim()
            }
        },
        onDeleteConfig = onDeleteConfig,
        onClearAll = onClearAll,
        onConfigSelectForEdit = { config -> currentEditingConfigId = config.id },
        editApiAddress = editApiAddress, onEditApiAddressChange = { editApiAddress = it },
        editApiKey = editApiKey, onEditApiKeyChange = { editApiKey = it },
        editModel = editModel, onEditModelChange = { editModel = it },
        editProvider = editProvider, onEditProviderChange = { editProvider = it },
        currentEditingConfigId = currentEditingConfigId,
        onClearEditFields = {
            editApiAddress = ""; editApiKey = ""; editModel = ""; editProvider = "openai"
            currentEditingConfigId = null
        },
        onSelectConfig = onSelectConfig,
        selectedConfigIdInApp = selectedConfig?.id,
        savedModelNamesForProvider = savedModelNamesMap[editProvider] ?: emptySet(),
        onDeleteSavedModelName = { modelNameToDelete ->
            dataSource.removeSavedModelName(editProvider, modelNameToDelete)
            savedModelNamesMap.computeIfPresent(editProvider) { _, set -> set - modelNameToDelete }
        }
    )
}


@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialogContent(
    savedConfigs: List<ApiConfig>,
    onDismissRequest: () -> Unit,
    onAddConfig: (config: ApiConfig) -> Unit,
    onUpdateConfig: (config: ApiConfig) -> Unit,
    onDeleteConfig: (config: ApiConfig) -> Unit,
    onClearAll: () -> Unit,
    onConfigSelectForEdit: (config: ApiConfig) -> Unit,
    editApiAddress: String, onEditApiAddressChange: (String) -> Unit,
    editApiKey: String, onEditApiKeyChange: (String) -> Unit,
    editModel: String, onEditModelChange: (String) -> Unit,
    editProvider: String, onEditProviderChange: (String) -> Unit,
    currentEditingConfigId: String?,
    onClearEditFields: () -> Unit,
    onSelectConfig: (config: ApiConfig) -> Unit,
    selectedConfigIdInApp: String?,
    savedModelNamesForProvider: Set<String>,
    onDeleteSavedModelName: (modelName: String) -> Unit
) {
    val isEditingExisting = currentEditingConfigId != null
    val canSaveOrUpdate =
        editApiAddress.isNotBlank() && editApiKey.isNotBlank() && editModel.isNotBlank() && editProvider.isNotBlank()
    val providers = remember { listOf("openai", "google") }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    val settingsDialogSnackbarHostState = remember { SnackbarHostState() }
    val settingsDialogCoroutineScope = rememberCoroutineScope()
    var currentSnackbarJob: Job? by remember { mutableStateOf(null) }

    // 定义按钮和相关间距的大小
    val buttonSize = 40.dp
    val buttonBottomPadding = 16.dp // 按钮距离底部边缘的距离 (调整这个值)
    val buttonHorizontalPadding = 24.dp // 按钮距离左右边缘的距离
    val contentBottomPadding = buttonSize + buttonBottomPadding + 8.dp // 内容区域底部需要留出的空间

    BasicAlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .widthIn(max = 600.dp),
        onDismissRequest = onDismissRequest,
        content = {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize()) { // 根 Box
                    // --- 内容 Column (包含标题和滚动内容) ---
                    Column(
                        modifier = Modifier
                            .fillMaxSize() // 填充整个 Box
                            .verticalScroll(rememberScrollState()) // 使整个内容区可滚动
                            // 添加左右和顶部内边距
                            .padding(start = 24.dp, top = 24.dp, end = 24.dp)
                    ) {
                        // 1. 标题
                        Text(
                            "API 配置管理",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        // 2. 主要内容区域 (表单和列表)
                        // ... (内部元素保持不变，依赖父 Column 的 padding) ...
                        Text(
                            text = if (isEditingExisting) "编辑配置" else "添加新配置:",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editApiAddress, onValueChange = onEditApiAddressChange,
                            label = { Text("API 地址") }, modifier = Modifier.fillMaxWidth(),
                            singleLine = true, shape = RoundedCornerShape(8.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editApiKey, onValueChange = onEditApiKeyChange,
                            label = { Text("API 密钥") }, modifier = Modifier.fillMaxWidth(),
                            singleLine = true, shape = RoundedCornerShape(8.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        ExposedDropdownMenuBox(
                            expanded = providerMenuExpanded,
                            onExpandedChange = { providerMenuExpanded = !providerMenuExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = editProvider, onValueChange = {}, readOnly = true,
                                label = { Text("API 提供商") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = providerMenuExpanded,
                                onDismissRequest = { providerMenuExpanded = false }) {
                                providers.forEach { provider ->
                                    DropdownMenuItem(
                                        text = { Text(provider) },
                                        onClick = {
                                            onEditProviderChange(provider); onEditModelChange(""); providerMenuExpanded =
                                            false
                                        })
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        ExposedDropdownMenuBox(
                            expanded = modelMenuExpanded,
                            onExpandedChange = { modelMenuExpanded = !modelMenuExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = editModel, onValueChange = onEditModelChange,
                                label = { Text("模型名称") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp), singleLine = true
                            )
                            ExposedDropdownMenu(
                                expanded = modelMenuExpanded && savedModelNamesForProvider.isNotEmpty(),
                                onDismissRequest = { modelMenuExpanded = false }) {
                                savedModelNamesForProvider.sorted().forEach { modelName ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(modelName)
                                                IconButton(
                                                    onClick = {
                                                        onDeleteSavedModelName(
                                                            modelName
                                                        )
                                                    },
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .padding(start = 8.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Outlined.DeleteOutline,
                                                        contentDescription = "删除模型建议 $modelName",
                                                        modifier = Modifier.size(18.dp),
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            onEditModelChange(modelName); modelMenuExpanded = false
                                        },
                                        contentPadding = PaddingValues(
                                            horizontal = 8.dp,
                                            vertical = 0.dp
                                        )
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = onClearEditFields,
                                enabled = editApiAddress.isNotEmpty() || editApiKey.isNotEmpty() || editModel.isNotEmpty() || isEditingExisting
                            ) { Text(if (isEditingExisting) "取消编辑" else "清除表单") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                if (isEditingExisting) {
                                    val configToUpdate = ApiConfig(
                                        id = currentEditingConfigId!!,
                                        address = editApiAddress.trim(),
                                        key = editApiKey.trim(),
                                        model = editModel.trim(),
                                        provider = editProvider
                                    )
                                    onUpdateConfig(configToUpdate)
                                    currentSnackbarJob?.cancel()
                                    currentSnackbarJob = settingsDialogCoroutineScope.launch {
                                        try {
                                            settingsDialogSnackbarHostState.showSnackbar(
                                                "配置 '${configToUpdate.model}' 已更新",
                                                duration = SnackbarDuration.Short
                                            )
                                        } catch (e: CancellationException) { /* Job cancelled */
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                } else {
                                    val newConfig = ApiConfig(
                                        id = UUID.randomUUID().toString(),
                                        address = editApiAddress.trim(),
                                        key = editApiKey.trim(),
                                        model = editModel.trim(),
                                        provider = editProvider
                                    )
                                    onAddConfig(newConfig)
                                    currentSnackbarJob?.cancel()
                                    currentSnackbarJob = settingsDialogCoroutineScope.launch {
                                        try {
                                            settingsDialogSnackbarHostState.showSnackbar(
                                                "配置 '${newConfig.model}' 已保存",
                                                duration = SnackbarDuration.Short
                                            )
                                        } catch (e: CancellationException) { /* Job cancelled */
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                    // onClearEditFields is handled in onAddConfig callback
                                }
                            }, enabled = canSaveOrUpdate) {
                                Icon(Icons.Default.Save, null, Modifier.size(18.dp)); Spacer(
                                Modifier.width(4.dp)
                            ); Text(if (isEditingExisting) "更新" else "保存")
                            }
                        }
                        Divider(modifier = Modifier.padding(vertical = 20.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "已存配置:",
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (savedConfigs.isNotEmpty()) {
                                TextButton(
                                    onClick = {
                                        onClearAll()
                                        currentSnackbarJob?.cancel()
                                        currentSnackbarJob = settingsDialogCoroutineScope.launch {
                                            try {
                                                settingsDialogSnackbarHostState.showSnackbar(
                                                    "所有配置已清除",
                                                    duration = SnackbarDuration.Short
                                                )
                                            } catch (e: CancellationException) { /* Job cancelled */
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    ),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                ) { Text("清空") }
                            }
                        }
                        if (savedConfigs.isEmpty()) {
                            Text(
                                "暂无配置",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(vertical = 24.dp)
                            )
                        } else {
                            Column(modifier = Modifier.padding(vertical = 12.dp)) { // Removed border/clip
                                savedConfigs.forEachIndexed { index, config ->
                                    ApiConfigItemContent(
                                        config = config,
                                        onDeleteClick = {
                                            onDeleteConfig(config)
                                            currentSnackbarJob?.cancel()
                                            currentSnackbarJob =
                                                settingsDialogCoroutineScope.launch {
                                                    try {
                                                        settingsDialogSnackbarHostState.showSnackbar(
                                                            "配置 '${config.model}' 已删除",
                                                            duration = SnackbarDuration.Short
                                                        )
                                                    } catch (e: CancellationException) { /* Job cancelled */
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                    }
                                                }
                                        },
                                        onItemEditClick = { onConfigSelectForEdit(config) },
                                        onSelectClick = {
                                            onSelectConfig(config)
                                            currentSnackbarJob?.cancel()
                                            currentSnackbarJob =
                                                settingsDialogCoroutineScope.launch {
                                                    try {
                                                        settingsDialogSnackbarHostState.showSnackbar(
                                                            "已选择: ${config.model} (${config.provider})",
                                                            duration = SnackbarDuration.Short
                                                        )
                                                    } catch (e: CancellationException) { /* Job cancelled */
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                    }
                                                }
                                        },
                                        isSelectedForEditing = (config.id == currentEditingConfigId),
                                        isCurrentlySelectedInApp = (config.id == selectedConfigIdInApp)
                                    )
                                    if (index < savedConfigs.lastIndex) {
                                        Divider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(
                                                alpha = 0.5f
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    } // 内容 Column 结束

                    SnackbarHost(
                        hostState = settingsDialogSnackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 24.dp) // Snackbar 左右内边距
                            .padding(bottom = buttonBottomPadding + buttonSize)
                            .fillMaxWidth()
                    ) { data ->
                        Snackbar(
                            snackbarData = data,
                            shape = RoundedCornerShape(24.dp),
                            containerColor = Color.White,
                            contentColor = Color.Black,
                        )
                    }
                } // 根 Box 结束
            } // Surface 结束
        } // content lambda 结束
    ) // BasicAlertDialog 结束
}

/**
 * 用于在列表中显示单个 API 配置项的可组合项。
 */
@Composable
private fun ApiConfigItemContent(
    config: ApiConfig,
    onDeleteClick: () -> Unit,
    onItemEditClick: () -> Unit,
    onSelectClick: () -> Unit,
    isSelectedForEditing: Boolean,
    isCurrentlySelectedInApp: Boolean
) {
    val targetBackgroundColor = when {
        isSelectedForEditing -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        isCurrentlySelectedInApp -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
        else -> Color.Transparent
    }
    val targetBorderColor = when {
        isSelectedForEditing -> MaterialTheme.colorScheme.primary
        isCurrentlySelectedInApp -> MaterialTheme.colorScheme.secondary
        else -> Color.Transparent
    }
    val backgroundColor by animateColorAsState(targetBackgroundColor, label = "ItemBgAnimation")
    val borderColor by animateColorAsState(targetBorderColor, label = "ItemBorderAnimation")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .border(1.dp, borderColor)
            .clickable { onItemEditClick() }
            .padding(vertical = 10.dp, horizontal = 12.dp), // 每个列表项内部的 padding，通常保留
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp) // 与右侧按钮的间距
        ) {
            Text(
                text = config.model.ifBlank { "(未命名模型)" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp,
                fontWeight = if (isSelectedForEditing || isCurrentlySelectedInApp) FontWeight.Bold else FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Provider: ${config.provider.ifBlank { "未指定" }}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "地址: ${config.address.ifBlank { "(未设置)" }}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "密钥: ${maskApiKey(config.key)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSelectClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (isCurrentlySelectedInApp) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = "选择此配置: ${config.model}",
                    tint = if (isCurrentlySelectedInApp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val iconSize = 14.dp
            IconButton(onClick = onDeleteClick, modifier = Modifier.size(32.dp)) {
                Box(
                    modifier = Modifier
                        .size(iconSize + 4.dp)
                        .background(color = Color.Gray.copy(alpha = 0.2f), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "删除配置 ${config.model}",
                        modifier = Modifier.size(iconSize),
                        tint = Color.Black
                    )
                }
            }
        }
    }
}

/**
 * 为显示目的隐藏 API 密钥。
 */
private fun maskApiKey(key: String): String {
    return when {
        key.isBlank() -> "(未设置)"
        key.length <= 8 -> "********"
        else -> "*******${key.takeLast(4)}"
    }
}