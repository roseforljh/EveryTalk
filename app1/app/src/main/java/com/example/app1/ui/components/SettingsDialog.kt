package com.example.app1.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape // 导入 CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.DeleteOutline // 用于删除建议图标
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color // 导入 Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext // 导入 LocalContext 以使用 DataSource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow // 导入 TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.app1.data.local.SharedPreferencesDataSource // 导入 DataSource
import com.example.app1.data.models.ApiConfig
import java.util.UUID

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
    var editProvider by remember { mutableStateOf("openai") } // 默认 Provider 不变
    var currentEditingConfigId by remember { mutableStateOf<String?>(null) }

    // --- 管理已保存的模型名称 ---
    val context = LocalContext.current
    val dataSource = remember { SharedPreferencesDataSource(context) }
    val savedModelNamesMap = remember { mutableStateMapOf<String, Set<String>>() }

    // --- 加载逻辑 (不变) ---
    LaunchedEffect(Unit) {
        savedModelNamesMap.putAll(dataSource.loadSavedModelNamesByProvider())
        println("SettingsDialog: Initial load of saved model names completed.")
    }
    LaunchedEffect(currentEditingConfigId) {
        val configToEdit = savedConfigs.find { it.id == currentEditingConfigId }
        if (configToEdit != null) {
            editApiAddress = configToEdit.address; editApiKey = configToEdit.key; editModel =
                configToEdit.model; editProvider = configToEdit.provider
            println("SettingsDialog: Editing config ${configToEdit.id}, form populated.")
        } else {
            editApiAddress = ""; editApiKey = ""; editModel = ""; editProvider =
                "openai" // 添加时Provider默认为openai
            println("SettingsDialog: Cleared form for adding new config.")
        }
    }

    // --- UI 委托给 SettingsDialogContent ---
    SettingsDialogContent(
        savedConfigs = savedConfigs,
        onDismissRequest = onDismissRequest,
        // --- 回调逻辑 (不变) ---
        onAddConfig = { configToAdd ->
            println("SettingsDialog: onAddConfig called for model ${configToAdd.model}")
            onAddConfig(configToAdd)
            dataSource.addSavedModelName(configToAdd.provider, configToAdd.model)
            savedModelNamesMap.compute(configToAdd.provider) { _, set ->
                (set ?: emptySet()) + configToAdd.model.trim()
            }
            editApiAddress = ""; editApiKey = ""; editModel = ""; editProvider =
            "openai"; currentEditingConfigId = null // 添加后Provider重置为openai
        },
        onUpdateConfig = { configToUpdate ->
            println("SettingsDialog: onUpdateConfig called for config ${configToUpdate.id}")
            val oldConfig = savedConfigs.find { it.id == configToUpdate.id }
            onUpdateConfig(configToUpdate)
            dataSource.addSavedModelName(configToUpdate.provider, configToUpdate.model)
            savedModelNamesMap.compute(configToUpdate.provider) { _, set ->
                (set ?: emptySet()) + configToUpdate.model.trim()
            }
            // 可选清理逻辑
            if (oldConfig != null && oldConfig.provider == configToUpdate.provider && oldConfig.model != configToUpdate.model) { /* ... */
            } else if (oldConfig != null && oldConfig.provider != configToUpdate.provider) { /* ... */
            }
        },
        onDeleteConfig = onDeleteConfig,
        onClearAll = onClearAll,
        onConfigSelectForEdit = { config -> currentEditingConfigId = config.id },
        // --- 状态和修改回调 (不变) ---
        editApiAddress = editApiAddress, onEditApiAddressChange = { editApiAddress = it },
        editApiKey = editApiKey, onEditApiKeyChange = { editApiKey = it },
        editModel = editModel, onEditModelChange = { editModel = it },
        editProvider = editProvider, onEditProviderChange = { editProvider = it },
        currentEditingConfigId = currentEditingConfigId,
        onClearEditFields = {
            editApiAddress = ""; editApiKey = ""; editModel = ""; editProvider =
            "openai"; currentEditingConfigId = null
        }, // 清除时Provider重置为openai
        onSelectConfig = onSelectConfig,
        selectedConfigIdInApp = selectedConfig?.id,
        // --- 模型名称建议相关 (不变) ---
        savedModelNamesForProvider = savedModelNamesMap[editProvider] ?: emptySet(),
        onDeleteSavedModelName = { modelNameToDelete ->
            println("SettingsDialog: onDeleteSavedModelName called for model '$modelNameToDelete' under provider '$editProvider'")
            dataSource.removeSavedModelName(editProvider, modelNameToDelete)
            savedModelNamesMap.computeIfPresent(editProvider) { _, set -> set - modelNameToDelete }
        }
    )
}


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

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .widthIn(max = 600.dp),
        onDismissRequest = onDismissRequest,
        title = { Text("API 配置管理") },
        text = {
            Column(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // --- 编辑/添加表单部分 ---
                Text(
                    text = if (isEditingExisting) "编辑配置" else "添加新配置:",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = editApiAddress,
                    onValueChange = onEditApiAddressChange,
                    label = { Text("API 地址") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = editApiKey,
                    onValueChange = onEditApiKeyChange,
                    label = { Text("API 密钥") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(Modifier.height(8.dp))

                // ***** VVVV 顺序修改 VVVV *****

                // --- Provider 下拉菜单 (移到前面) ---
                ExposedDropdownMenuBox(
                    expanded = providerMenuExpanded,
                    onExpandedChange = { providerMenuExpanded = !providerMenuExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = editProvider,
                        onValueChange = {}, // 只读
                        readOnly = true,
                        label = { Text("API 提供商") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = providerMenuExpanded,
                        onDismissRequest = { providerMenuExpanded = false }
                    ) {
                        providers.forEach { provider -> // 遍历可用 Provider
                            DropdownMenuItem(
                                text = { Text(provider) },
                                onClick = {
                                    onEditProviderChange(provider) // 更新 Provider 状态
                                    onEditModelChange("") // 切换 Provider 时清空模型名称输入
                                    providerMenuExpanded = false // 关闭菜单
                                }
                            )
                        }
                    }
                }
                // --- Provider 结束 ---

                Spacer(Modifier.height(8.dp)) // 两者之间的间距

                // --- 模型名称输入框 (带可删除建议) (移到后面) ---
                ExposedDropdownMenuBox(
                    expanded = modelMenuExpanded,
                    onExpandedChange = { modelMenuExpanded = !modelMenuExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = editModel, // 允许用户输入
                        onValueChange = onEditModelChange, // 直接更新文本字段
                        label = { Text("模型名称") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) }, // 下拉箭头
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(), // 作为下拉菜单的锚点
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true // 通常模型名称是单行
                    )
                    // 下拉菜单，显示已保存的建议
                    ExposedDropdownMenu(
                        expanded = modelMenuExpanded && savedModelNamesForProvider.isNotEmpty(), // 只有在展开且有建议时显示
                        onDismissRequest = { modelMenuExpanded = false } // 点击外部关闭菜单
                    ) {
                        // 遍历当前 Provider 的已存模型名称，按字母排序
                        savedModelNamesForProvider.sorted().forEach { modelName ->
                            DropdownMenuItem(
                                text = {
                                    // 在菜单项中使用 Row 来布局文本和删除按钮
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween // 推送按钮到右侧
                                    ) {
                                        Text(modelName) // 显示模型名称
                                        // 删除此模型建议的按钮
                                        IconButton(
                                            onClick = {
                                                onDeleteSavedModelName(modelName) // 调用删除回调
                                            },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .padding(start = 8.dp) // 较小的触摸目标
                                        ) {
                                            Icon(
                                                Icons.Outlined.DeleteOutline, // 使用描边删除图标
                                                contentDescription = "删除模型建议 $modelName",
                                                modifier = Modifier.size(18.dp), // 图标尺寸
                                                tint = MaterialTheme.colorScheme.error // 使用错误颜色提示删除
                                            )
                                        }
                                    }
                                },
                                onClick = { // 点击菜单项的文本区域
                                    onEditModelChange(modelName) // 将选中的模型名称填入输入框
                                    modelMenuExpanded = false // 关闭菜单
                                },
                                // 调整内边距，使 Row 能更好地利用空间
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            )
                        }
                    }
                }
                // --- 模型名称结束 ---

                // ***** ^^^^ 顺序修改结束 ^^^^ *****

                // --- 表单操作按钮 (不变) ---
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
                            onUpdateConfig(
                                ApiConfig(
                                    id = currentEditingConfigId!!,
                                    address = editApiAddress.trim(),
                                    key = editApiKey.trim(),
                                    model = editModel.trim(),
                                    provider = editProvider
                                )
                            )
                        } else {
                            onAddConfig(
                                ApiConfig(
                                    id = UUID.randomUUID().toString(),
                                    address = editApiAddress.trim(),
                                    key = editApiKey.trim(),
                                    model = editModel.trim(),
                                    provider = editProvider
                                )
                            )
                        }
                    }, enabled = canSaveOrUpdate) {
                        Icon(Icons.Default.Save, null, Modifier.size(18.dp)); Spacer(
                        Modifier.width(
                            4.dp
                        )
                    ); Text(if (isEditingExisting) "更新" else "保存")
                    }
                }

                // --- 分隔线 (不变) ---
                Divider(modifier = Modifier.padding(vertical = 20.dp))

                // --- 已保存配置列表部分 (不变) ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已存配置 (点击编辑, 图标选择):",
                        style = MaterialTheme.typography.titleMedium
                    ); if (savedConfigs.isNotEmpty()) {
                    TextButton(
                        onClick = onClearAll,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("全部清除") }
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(8.dp)
                            )
                            .clip(RoundedCornerShape(8.dp))
                    ) {
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
                                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            } // End Column for main content
        },
        confirmButton = { Button(onClick = onDismissRequest) { Text("关闭") } },
        dismissButton = null
    )
}

/**
 * 用于在列表中显示单个 API 配置项的可组合项。
 * (内容不变，仅确认之前的修改都在)
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
    // --- 背景和边框颜色动画 (不变) ---
    val targetBackgroundColor = when {
        isSelectedForEditing -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f); isCurrentlySelectedInApp -> MaterialTheme.colorScheme.secondaryContainer.copy(
            alpha = 0.2f
        ); else -> Color.Transparent
    }
    val targetBorderColor = when {
        isSelectedForEditing -> MaterialTheme.colorScheme.primary; isCurrentlySelectedInApp -> MaterialTheme.colorScheme.secondary; else -> Color.Transparent
    }
    val backgroundColor by animateColorAsState(targetBackgroundColor, label = "ItemBgAnimation")
    val borderColor by animateColorAsState(targetBorderColor, label = "ItemBorderAnimation")

    // --- 列表项的行布局 (不变) ---
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .border(1.dp, borderColor)
            .clickable { onItemEditClick() }
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // --- 左侧配置信息列 (不变) ---
        Column(modifier = Modifier
            .weight(1f)
            .padding(end = 8.dp)) {
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
        // --- 右侧操作按钮行 (不变) ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 选择按钮
            IconButton(
                onClick = onSelectClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (isCurrentlySelectedInApp) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = "选择此配置: ${config.model}",
                    tint = if (isCurrentlySelectedInApp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 配置项删除按钮
            val iconSize = 18.dp
            IconButton(onClick = onDeleteClick, modifier = Modifier.size(32.dp)) {
                Box(
                    modifier = Modifier
                        .size(iconSize + 6.dp)
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
 * 为显示目的隐藏 API 密钥。 (不变)
 */
private fun maskApiKey(key: String): String {
    return when {
        key.isBlank() -> "(未设置)"
        key.length <= 8 -> "********"
        else -> "*******${key.takeLast(4)}"
    }
}