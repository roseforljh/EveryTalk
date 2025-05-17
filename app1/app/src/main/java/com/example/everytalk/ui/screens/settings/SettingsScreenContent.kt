package com.example.everytalk.ui.screens.settings // 确保包名与你的项目一致

import android.annotation.SuppressLint
import androidx.compose.foundation.background
// import androidx.compose.foundation.border // 根据实际需要，如果ApiConfigItemContent在此文件则可能需要
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear // 导入清除图标
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close // 用于模型历史记录项的删除
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
// import androidx.compose.ui.text.style.TextOverflow // 根据实际需要
import androidx.compose.ui.unit.dp
import com.example.everytalk.data.DataClass.ApiConfig // 确保ApiConfig导入正确
import java.util.UUID

// 假设 ApiConfigItemContent 在 SettingsListComposables.kt 中定义
// import com.example.everytalk.ui.screens.settings.ApiConfigItemContent

@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenContent(
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
    allProviders: List<String>,
    onShowAddProviderDialogChange: (Boolean) -> Unit,
    savedModelNamesForProvider: Set<String>,
    onDeleteSavedModelName: (modelName: String) -> Unit
) {
    val isEditingExisting = currentEditingConfigId != null
    val canSaveOrUpdate =
        editApiAddress.isNotBlank() && editApiKey.isNotBlank() && editModel.isNotBlank() && editProvider.isNotBlank()

    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    val actualProviders = remember(allProviders) { allProviders }

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
                    bottom = 16.dp
                )
        ) {
            Text(
                text = if (isEditingExisting) "编辑配置" else "添加新配置",
                style = MaterialTheme.typography.titleLarge.copy(color = Color.Black),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // 模型平台选择框
            ExposedDropdownMenuBox(
                expanded = providerMenuExpanded,
                onExpandedChange = { providerMenuExpanded = !providerMenuExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = editProvider,
                    onValueChange = { /* readOnly */ },
                    readOnly = true,
                    label = { Text("模型平台", color = Color.Gray) },
                    trailingIcon = {
                        IconButton(onClick = {
                            if (providerMenuExpanded) providerMenuExpanded = false
                            onShowAddProviderDialogChange(true)
                        }) {
                            Icon(
                                Icons.Outlined.Add,
                                "添加新模型平台",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = Color.Black,
                        disabledBorderColor = Color.Gray,
                        disabledLabelColor = Color.Gray,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = Color.Gray
                    )
                )
                ExposedDropdownMenu(
                    expanded = providerMenuExpanded && actualProviders.isNotEmpty(),
                    onDismissRequest = { providerMenuExpanded = false },
                    modifier = Modifier.background(Color.White)
                ) {
                    actualProviders.forEach { providerItem ->
                        DropdownMenuItem(
                            text = { Text(providerItem, color = Color.Black) },
                            onClick = {
                                onEditProviderChange(providerItem); providerMenuExpanded = false
                            },
                            colors = MenuDefaults.itemColors(textColor = Color.Black)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // API 地址输入框
            OutlinedTextField(
                value = editApiAddress,
                onValueChange = onEditApiAddressChange,
                label = { Text("API 接口地址", color = Color.Gray) },
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

            // API 密钥输入框
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

            // 模型名称输入框与历史记录下拉
            ExposedDropdownMenuBox(
                expanded = modelMenuExpanded,
                onExpandedChange = { modelMenuExpanded = !modelMenuExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = editModel,
                    onValueChange = onEditModelChange,
                    label = { Text("模型名称", color = Color.Gray) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) { //确保图标垂直居中
                            if (editModel.isNotEmpty()) {
                                IconButton(
                                    onClick = { onEditModelChange("") },
                                    modifier = Modifier.size(48.dp) // IconButton通常需要最小点击区域
                                ) {
                                    Icon(
                                        Icons.Filled.Clear,
                                        contentDescription = "清除模型名称",
                                        modifier = Modifier.size(24.dp) // 图标本身大小
                                    )
                                }
                            }
                            // 只有当模型名称为空时，才显示下拉箭头，避免与清除按钮重叠
                            if (editModel.isEmpty()) {
                                ExposedDropdownMenuDefaults.TrailingIcon(
                                    expanded = modelMenuExpanded,
                                    // modifier = Modifier.size(48.dp) // 如果需要，也可以给下拉箭头IconButton指定大小
                                )
                            }
                        }
                    },
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
                                    Text(
                                        modelName,
                                        color = Color.Black,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { onDeleteSavedModelName(modelName) },
                                        modifier = Modifier.size(32.dp) // 调整历史记录项中删除按钮的大小
                                    ) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = "从历史记录中删除 ${modelName}",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            },
                            onClick = { onEditModelChange(modelName); modelMenuExpanded = false },
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                end = 0.dp,
                                top = 0.dp,
                                bottom = 0.dp
                            ),
                            colors = MenuDefaults.itemColors(textColor = Color.Black)
                        )
                    }
                }
            }

            // 保存/更新/清除按钮行
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
                        val trimmedKey = editApiKey.trim();
                        val currentProvider = editProvider.trim()
                        if (isEditingExisting) {
                            val idToUpdate = currentEditingConfigId ?: return@Button
                            onUpdateConfig(
                                ApiConfig(
                                    id = idToUpdate,
                                    address = trimmedAddress,
                                    key = trimmedKey,
                                    model = trimmedModel,
                                    provider = currentProvider
                                )
                            )
                        } else {
                            onAddConfig(
                                ApiConfig(
                                    id = UUID.randomUUID().toString(),
                                    address = trimmedAddress,
                                    key = trimmedKey,
                                    model = trimmedModel,
                                    provider = currentProvider
                                )
                            )
                        }
                    },
                    enabled = canSaveOrUpdate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Save, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(
                    Modifier.width(ButtonDefaults.IconSpacing)
                ); Text(if (isEditingExisting) "更新" else "保存")
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 24.dp),
                color = Color.LightGray
            )

            // 已存配置列表标题和清空按钮
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

            // 已存配置列表
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
                        // 假设 ApiConfigItemContent 在 SettingsListComposables.kt
                        // 你需要确保该文件存在并且 ApiConfigItemContent 导入正确
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
    }
}