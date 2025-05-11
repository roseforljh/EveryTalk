package com.example.app1.ui.screens // 确保包名正确

import android.annotation.SuppressLint
import android.util.Log
import android.widget.Toast // 保留 Toast，因为顶部栏复制功能还在使用
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack // 注意是 automirrored
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString // 保留 Toast 相关的 AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.app1.StateControler.AppViewModel
import com.example.app1.data.local.SharedPreferencesDataSource
import com.example.app1.data.DataClass.ApiConfig
// import kotlinx.coroutines.Job // 不再需要，因为移除了 Snackbar 的 Job
// import kotlinx.coroutines.launch // 不再需要，因为移除了 Snackbar 的 launch
import java.util.UUID
import androidx.compose.material.icons.filled.CheckCircleOutline

// --- 辅助函数 (如果其他地方不用，可以考虑移动或删除) ---
fun mapStringToCodeLang(languageName: String?): String { // 简单返回字符串，如果CodeLang不再使用
    return languageName?.lowercase()?.trim() ?: "plaintext"
}

fun mapLanguageToExtension(lang: String?): String { // 简单返回字符串
    return when (lang) {
        "kotlin" -> "kt"
        "java" -> "java"
        "python" -> "py"
        "javascript" -> "js"
        "html" -> "html"
        "css" -> "css"
        "xml" -> "xml"
        "json" -> "json"
        "c" -> "c"
        "cpp" -> "cpp"
        "csharp" -> "cs"
        else -> "txt"
    }
}
// --- 辅助函数结束 ---


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

    var editApiAddress by remember { mutableStateOf("") }
    var editApiKey by remember { mutableStateOf("") }
    var editModel by remember { mutableStateOf("") }
    var editProvider by remember { mutableStateOf("openai") }
    var currentEditingConfigId by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val dataSource = remember(context) { SharedPreferencesDataSource(context.applicationContext) }
    val savedModelNamesMap = remember { mutableStateMapOf<String, Set<String>>() }

    var backButtonEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        savedModelNamesMap.putAll(dataSource.loadSavedModelNamesByProvider())
    }

    LaunchedEffect(currentEditingConfigId, savedConfigs) {
        val configToEdit = savedConfigs.find { it.id == currentEditingConfigId }
        if (configToEdit != null) {
            editApiAddress = configToEdit.address
            editApiKey = configToEdit.key
            editModel = configToEdit.model
            editProvider = configToEdit.provider
        } else {
            if (currentEditingConfigId == null) {
                editApiAddress = ""
                editApiKey = ""
                editModel = ""
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.White,
        topBar = {
            TopAppBar(
                title = { Text("API 配置管理", color = Color.Black) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (backButtonEnabled) {
                                backButtonEnabled = false
                                Log.e("SettingsScreen", "返回按钮被点击! Navigating back.")
                                navController.popBackStack()
                            } else {
                                Log.w("SettingsScreen", "返回按钮已被禁用，忽略此次点击。")
                            }
                        },
                        enabled = backButtonEnabled
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
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
                if (trimmedModel.isNotEmpty()) {
                    dataSource.addSavedModelName(configToAdd.provider, trimmedModel)
                    savedModelNamesMap.compute(configToAdd.provider) { _, set ->
                        (set ?: emptySet()) + trimmedModel
                    }
                }
                editApiAddress = ""; editApiKey = ""; editModel = "";
                currentEditingConfigId = null
            },
            onUpdateConfig = { configToUpdate ->
                viewModel.updateConfig(configToUpdate)
                val trimmedModel = configToUpdate.model.trim()
                if (trimmedModel.isNotEmpty()) {
                    dataSource.addSavedModelName(configToUpdate.provider, trimmedModel)
                    savedModelNamesMap.compute(configToUpdate.provider) { _, set ->
                        (set ?: emptySet()) + trimmedModel
                    }
                }
                currentEditingConfigId = null
            },
            onDeleteConfig = { configToDelete ->
                viewModel.deleteConfig(configToDelete)
                if (configToDelete.id == currentEditingConfigId) {
                    editApiAddress = ""; editApiKey = ""; editModel = "";
                    currentEditingConfigId = null
                }
            },
            onClearAll = { viewModel.clearAllConfigs() },
            onConfigSelectForEdit = { config ->
                currentEditingConfigId = config.id
            },
            editApiAddress = editApiAddress, onEditApiAddressChange = { editApiAddress = it },
            editApiKey = editApiKey, onEditApiKeyChange = { editApiKey = it },
            editModel = editModel, onEditModelChange = { editModel = it },
            editProvider = editProvider, onEditProviderChange = { newProvider ->
                editProvider = newProvider
                editModel = ""
            },
            currentEditingConfigId = currentEditingConfigId,
            onClearEditFields = {
                editApiAddress = ""; editApiKey = ""; editModel = "";
                currentEditingConfigId = null
            },
            onSelectConfig = { configToSelect -> viewModel.selectConfig(configToSelect) },
            selectedConfigIdInApp = selectedConfig?.id,
            savedModelNamesForProvider = savedModelNamesMap[editProvider] ?: emptySet(),
            onDeleteSavedModelName = { modelNameToDelete ->
                dataSource.removeSavedModelName(editProvider, modelNameToDelete)
                savedModelNamesMap.computeIfPresent(editProvider) { _, set -> set - modelNameToDelete }
            }
        )
    }
}


@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenContent(
    paddingValues: PaddingValues,
    savedConfigs: List<ApiConfig>,
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .background(Color.White)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 24.dp,
                    top = 16.dp,
                    end = 24.dp,
                    bottom = 16.dp // Snackbar 移除了，底部 padding 可以适当减小
                )
        ) {
            Text(
                text = if (isEditingExisting) "编辑配置" else "添加新配置",
                style = MaterialTheme.typography.titleLarge.copy(color = Color.Black),
                modifier = Modifier.padding(bottom = 16.dp)
            )
            OutlinedTextField(
                value = editApiAddress,
                onValueChange = onEditApiAddressChange,
                label = { Text("API 地址", color = Color.Gray) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Gray,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = Color.Gray
                )
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = editApiKey,
                onValueChange = onEditApiKeyChange,
                label = { Text("API 密钥", color = Color.Gray) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Gray,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = Color.Gray
                )
            )
            Spacer(Modifier.height(12.dp))
            ExposedDropdownMenuBox(
                expanded = providerMenuExpanded,
                onExpandedChange = { providerMenuExpanded = !providerMenuExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = editProvider,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("API 提供商", color = Color.Gray) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = Color.Black,
                        disabledBorderColor = Color.Gray,
                        disabledLabelColor = Color.Gray,
                        disabledTrailingIconColor = Color.Black,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray,
                    )
                )
                ExposedDropdownMenu(
                    expanded = providerMenuExpanded,
                    onDismissRequest = { providerMenuExpanded = false },
                    modifier = Modifier.background(Color.White)
                ) {
                    providers.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider, color = Color.Black) },
                            onClick = {
                                onEditProviderChange(provider);
                                onEditModelChange("");
                                providerMenuExpanded = false
                            },
                            colors = MenuDefaults.itemColors(textColor = Color.Black)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            ExposedDropdownMenuBox(
                expanded = modelMenuExpanded,
                onExpandedChange = { modelMenuExpanded = !modelMenuExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = editModel,
                    onValueChange = onEditModelChange,
                    label = { Text("模型名称", color = Color.Gray) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = Color.Gray
                    )
                )
                ExposedDropdownMenu(
                    expanded = modelMenuExpanded && savedModelNamesForProvider.isNotEmpty(),
                    onDismissRequest = { modelMenuExpanded = false },
                    modifier = Modifier.background(Color.White)
                ) {
                    savedModelNamesForProvider.sorted().forEach { modelName ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(modelName, color = Color.Black);
                                    IconButton(
                                        onClick = { onDeleteSavedModelName(modelName) },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .padding(start = 8.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.DeleteOutline,
                                            contentDescription = "删除保存的模型 ${modelName}",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            },
                            onClick = { onEditModelChange(modelName); modelMenuExpanded = false },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            colors = MenuDefaults.itemColors(textColor = Color.Black)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onClearEditFields,
                    enabled = editApiAddress.isNotEmpty() || editApiKey.isNotEmpty() || editModel.isNotEmpty() || isEditingExisting,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) { Text(if (isEditingExisting) "取消编辑" else "清除表单") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val trimmedModel = editModel.trim();
                        val trimmedAddress = editApiAddress.trim();
                        val trimmedKey = editApiKey.trim()
                        if (isEditingExisting) {
                            val idToUpdate = currentEditingConfigId ?: run {
                                Log.e(
                                    "SettingsScreen",
                                    "更新错误: currentEditingConfigId 为 null"
                                ); return@Button
                            }
                            val configToUpdate = ApiConfig(
                                id = idToUpdate,
                                address = trimmedAddress,
                                key = trimmedKey,
                                model = trimmedModel,
                                provider = editProvider
                            )
                            onUpdateConfig(configToUpdate)
                        } else {
                            val newConfig = ApiConfig(
                                id = UUID.randomUUID().toString(),
                                address = trimmedAddress,
                                key = trimmedKey,
                                model = trimmedModel,
                                provider = editProvider
                            )
                            onAddConfig(newConfig)
                        }
                    },
                    enabled = canSaveOrUpdate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Save, null, Modifier.size(ButtonDefaults.IconSize));
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing));
                    Text(if (isEditingExisting) "更新" else "保存")
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 24.dp),
                color = Color.LightGray
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "已存配置:",
                    style = MaterialTheme.typography.titleMedium.copy(color = Color.Black)
                )
                if (savedConfigs.isNotEmpty()) {
                    TextButton(
                        onClick = onClearAll,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("清空全部") }
                }
            }
            if (savedConfigs.isEmpty()) {
                Text(
                    "暂无配置",
                    color = Color.DarkGray,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 24.dp)
                )
            } else {
                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                    savedConfigs.forEachIndexed { index, config ->
                        ApiConfigItemContent(
                            config = config,
                            onDeleteClick = { onDeleteConfig(config) },
                            onItemEditClick = { onConfigSelectForEdit(config) },
                            onSelectClick = { onSelectConfig(config) },
                            isSelectedForEditing = (config.id == currentEditingConfigId),
                            isCurrentlySelectedInApp = (config.id == selectedConfigIdInApp)
                        )
                        if (index < savedConfigs.lastIndex) {
                            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        // SnackbarHost 已被移除
    }
}


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
        isSelectedForEditing -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
        isCurrentlySelectedInApp -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.1f)
        else -> Color.Transparent
    }
    val targetBorderColor = when {
        isSelectedForEditing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        isCurrentlySelectedInApp -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
        else -> Color.LightGray.copy(alpha = 0.6f)
    }
    val backgroundColor by animateColorAsState(targetBackgroundColor, label = "ConfigItemBgAnim")
    val borderColor by animateColorAsState(targetBorderColor, label = "ConfigItemBorderAnim")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, MaterialTheme.shapes.medium)
            .border(1.dp, borderColor, MaterialTheme.shapes.medium)
            .clickable { onItemEditClick() }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                config.model.ifBlank { "(未命名模型)" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium.copy(color = Color.Black),
                fontWeight = if (isSelectedForEditing || isCurrentlySelectedInApp) FontWeight.Bold else FontWeight.Normal
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "提供商: ${config.provider.ifBlank { "未指定" }}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(color = Color.DarkGray),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "地址: ${config.address.take(20)}${if (config.address.length > 20) "..." else ""}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "密钥: ${maskApiKey(config.key)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSelectClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (isCurrentlySelectedInApp) Icons.Filled.CheckCircleOutline else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = "选择配置 ${config.model}",
                    tint = if (isCurrentlySelectedInApp) MaterialTheme.colorScheme.primary else Color.DarkGray
                )
            }
            IconButton(onClick = onDeleteClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "删除配置 ${config.model}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun maskApiKey(key: String): String {
    return when {
        key.isBlank() -> "(未设置)"
        key.length <= 8 -> key.map { '*' }.joinToString("")
        else -> "${key.take(4)}****${key.takeLast(4)}"
    }
}