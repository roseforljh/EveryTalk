package com.example.app1.ui.screens // 确保包名正确

import android.annotation.SuppressLint
import android.util.Log
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.app1.AppViewModel
import com.example.app1.data.local.SharedPreferencesDataSource
import com.example.app1.data.models.ApiConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.compose.material.icons.filled.CheckCircleOutline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    Log.i("ScreenComposition", "SettingsScreen Composing/Recomposing.") // 添加组合日志
    val savedConfigs by viewModel.apiConfigs.collectAsState()
    val selectedConfig by viewModel.selectedApiConfig.collectAsState()

    var editApiAddress by remember { mutableStateOf("") }
    var editApiKey by remember { mutableStateOf("") }
    var editModel by remember { mutableStateOf("") }
    var editProvider by remember { mutableStateOf("openai") } // 默认提供商
    var currentEditingConfigId by remember { mutableStateOf<String?>(null) } // 当前正在编辑的配置ID

    val context = LocalContext.current
    // SharedPreferencesDataSource 应该由 ViewModel 或 Hilt/Koin 等依赖注入框架提供，
    // 而不是在 Composable 中直接创建。但为了保持与你提供代码的一致性，暂时保留。
    // 更好的做法是从 ViewModel 获取或注入 dataSource。
    val dataSource = remember(context) { SharedPreferencesDataSource(context.applicationContext) }
    val savedModelNamesMap = remember { mutableStateMapOf<String, Set<String>>() } // 保存的模型名称建议

    // 用于控制返回按钮是否可点击，防止重复点击或穿透点击
    var backButtonEnabled by remember { mutableStateOf(true) }

    // 加载已保存的模型名称建议
    LaunchedEffect(Unit) {
        savedModelNamesMap.putAll(dataSource.loadSavedModelNamesByProvider())
    }

    // 当编辑的配置ID或总配置列表变化时，更新编辑字段
    LaunchedEffect(currentEditingConfigId, savedConfigs) {
        val configToEdit = savedConfigs.find { it.id == currentEditingConfigId }
        if (configToEdit != null) {
            editApiAddress = configToEdit.address
            editApiKey = configToEdit.key
            editModel = configToEdit.model
            editProvider = configToEdit.provider
        } else {
            // 如果没有选中编辑的配置，或配置不存在，则清空编辑字段
            if (currentEditingConfigId == null) { // 只有当确实没有选中编辑时才清空
                editApiAddress = ""
                editApiKey = ""
                editModel = ""
                // editProvider 可以保留上次选择或默认值
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.White, // 设置背景为白色
        topBar = {
            TopAppBar(
                title = { Text("API 配置管理", color = Color.Black) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (backButtonEnabled) { // 只有在启用时才执行
                                backButtonEnabled = false // 立即禁用，防止重复点击/穿透
                                Log.e(
                                    "SettingsScreen",
                                    "返回按钮被点击! Navigating back."
                                ) // 使用Error级别使其显眼
                                navController.popBackStack()
                            } else {
                                Log.w("SettingsScreen", "返回按钮已被禁用，忽略此次点击。")
                            }
                        },
                        enabled = backButtonEnabled // 控制按钮的启用状态
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = if (backButtonEnabled) Color.Black else Color.Gray // 禁用时图标变灰
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
                // 清空表单并取消编辑状态
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
                // 更新后不清空表单，允许用户继续编辑或查看，但取消编辑ID
                currentEditingConfigId = null
            },
            onDeleteConfig = { configToDelete ->
                viewModel.deleteConfig(configToDelete)
                // 如果删除的是当前正在编辑的配置，则清空编辑状态
                if (configToDelete.id == currentEditingConfigId) {
                    editApiAddress = ""; editApiKey = ""; editModel = "";
                    currentEditingConfigId = null
                }
            },
            onClearAll = { viewModel.clearAllConfigs() },
            onConfigSelectForEdit = { config ->
                currentEditingConfigId = config.id // LaunchedEffect会处理字段填充
            },
            editApiAddress = editApiAddress, onEditApiAddressChange = { editApiAddress = it },
            editApiKey = editApiKey, onEditApiKeyChange = { editApiKey = it },
            editModel = editModel, onEditModelChange = { editModel = it },
            editProvider = editProvider, onEditProviderChange = { newProvider ->
                editProvider = newProvider
                editModel = "" // 切换提供商时清空模型名称
            },
            currentEditingConfigId = currentEditingConfigId,
            onClearEditFields = { // 清除编辑表单并取消编辑状态
                editApiAddress = ""; editApiKey = ""; editModel = "";
                currentEditingConfigId = null
            },
            onSelectConfig = { configToSelect -> viewModel.selectConfig(configToSelect) },
            selectedConfigIdInApp = selectedConfig?.id, // 应用当前实际选中的配置ID
            savedModelNamesForProvider = savedModelNamesMap[editProvider]
                ?: emptySet(), // 当前提供商的已存模型
            onDeleteSavedModelName = { modelNameToDelete -> // 删除已保存的模型名称建议
                dataSource.removeSavedModelName(editProvider, modelNameToDelete)
                savedModelNamesMap.computeIfPresent(editProvider) { _, set -> set - modelNameToDelete }
            }
        )
    }
}


@SuppressLint("ConfigurationScreenWidthHeight") // 避免屏幕旋转时配置警告 (如果不需要动态响应，则可忽略)
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
    savedModelNamesForProvider: Set<String>, // 特定提供商的已存模型名称
    onDeleteSavedModelName: (modelName: String) -> Unit // 删除特定模型名称建议的回调
) {
    val isEditingExisting = currentEditingConfigId != null // 是否正在编辑现有配置
    // 判断是否可以保存或更新（所有必填字段非空）
    val canSaveOrUpdate =
        editApiAddress.isNotBlank() && editApiKey.isNotBlank() && editModel.isNotBlank() && editProvider.isNotBlank()
    val providers = remember { listOf("openai", "google") } // 可选的提供商列表
    var providerMenuExpanded by remember { mutableStateOf(false) } // 提供商下拉菜单是否展开
    var modelMenuExpanded by remember { mutableStateOf(false) } // 模型名称下拉菜单是否展开

    val snackbarHostState = remember { SnackbarHostState() } // Snackbar状态
    val coroutineScope = rememberCoroutineScope() // 协程作用域
    var currentSnackbarJob: Job? by remember { mutableStateOf(null) } // 当前Snackbar任务，用于取消

    Box( // 根容器，用于放置 SnackbarHost
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues) // 应用Scaffold传递的padding
            .background(Color.White) // 背景设为白色
    ) {
        Column( // 主要内容列，可滚动
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()) // 允许垂直滚动
                .padding(
                    start = 24.dp,
                    top = 16.dp,
                    end = 24.dp,
                    bottom = 80.dp
                ) // 内边距，底部留出空间给Snackbar
        ) {
            // 标题：添加或编辑配置
            Text(
                text = if (isEditingExisting) "编辑配置" else "添加新配置",
                style = MaterialTheme.typography.titleLarge.copy(color = Color.Black),
                modifier = Modifier.padding(bottom = 16.dp)
            )
            // API 地址输入框
            OutlinedTextField(
                value = editApiAddress,
                onValueChange = onEditApiAddressChange,
                label = { Text("API 地址", color = Color.Gray) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.small, // 使用小圆角
                colors = OutlinedTextFieldDefaults.colors( // 自定义颜色
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
            // API 提供商选择 (下拉菜单)
            ExposedDropdownMenuBox(
                expanded = providerMenuExpanded,
                onExpandedChange = { providerMenuExpanded = !providerMenuExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField( // 显示当前选中的提供商
                    value = editProvider,
                    onValueChange = {}, // 不允许直接编辑，通过下拉菜单选择
                    readOnly = true,
                    label = { Text("API 提供商", color = Color.Gray) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor() // 作为下拉菜单的锚点
                        .fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    colors = OutlinedTextFieldDefaults.colors(
                        // 为禁用状态（readOnly）配置颜色
                        disabledTextColor = Color.Black, // 即便readOnly也显示黑色文本
                        disabledBorderColor = Color.Gray, // 未获得焦点时的边框颜色
                        disabledLabelColor = Color.Gray,
                        disabledTrailingIconColor = Color.Black, // 下拉箭头颜色
                        // 当 menuAnchor 获得焦点时（点击打开菜单），应用focused颜色
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray, // 确保未打开菜单时边框是灰色
                    )
                )
                ExposedDropdownMenu( // 实际的下拉菜单项
                    expanded = providerMenuExpanded,
                    onDismissRequest = { providerMenuExpanded = false }, // 点击外部关闭菜单
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant) // 菜单背景
                ) {
                    providers.forEach { provider ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    provider,
                                    color = Color.Black // 菜单项文本颜色
                                )
                            },
                            onClick = {
                                onEditProviderChange(provider); // 更新提供商状态
                                onEditModelChange(""); // 切换提供商时清空模型名称
                                providerMenuExpanded = false // 关闭菜单
                            },
                            colors = MenuDefaults.itemColors(textColor = Color.Black) // 确保菜单项文本颜色
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 模型名称输入/选择 (下拉菜单)
            ExposedDropdownMenuBox(
                expanded = modelMenuExpanded,
                onExpandedChange = { modelMenuExpanded = !modelMenuExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = editModel,
                    onValueChange = onEditModelChange, // 允许用户输入新的模型名称
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
                    expanded = modelMenuExpanded && savedModelNamesForProvider.isNotEmpty(), // 仅当有已存模型时才展开
                    onDismissRequest = { modelMenuExpanded = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    savedModelNamesForProvider.sorted().forEach { modelName -> // 对已存模型名称排序显示
                        DropdownMenuItem(
                            text = { // 菜单项内容，包含模型名称和删除按钮
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween // 名称和按钮两端对齐
                                ) {
                                    Text(
                                        modelName,
                                        color = Color.Black
                                    ); IconButton( // 删除此模型名称建议的按钮
                                    onClick = { onDeleteSavedModelName(modelName) },
                                    modifier = Modifier
                                        .size(32.dp) // 调整按钮大小以适应菜单项
                                        .padding(start = 8.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = "删除保存的模型 ${modelName}",
                                        modifier = Modifier.size(18.dp), // 图标大小
                                        tint = MaterialTheme.colorScheme.error // 删除图标用错误颜色
                                    )
                                }
                                }
                            },
                            onClick = {
                                onEditModelChange(modelName); modelMenuExpanded = false
                            }, // 点击选择模型
                            contentPadding = PaddingValues(
                                horizontal = 12.dp,
                                vertical = 0.dp
                            ), // 调整内边距
                            colors = MenuDefaults.itemColors(textColor = Color.Black)
                        )
                    }
                }
            }
            // 清除/取消 和 保存/更新 按钮行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.End, // 按钮靠右对齐
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton( // 清除表单 或 取消编辑 按钮
                    onClick = onClearEditFields,
                    enabled = editApiAddress.isNotEmpty() || editApiKey.isNotEmpty() || editModel.isNotEmpty() || isEditingExisting, // 任一字段非空或正在编辑时可用
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) { Text(if (isEditingExisting) "取消编辑" else "清除表单") }
                Spacer(Modifier.width(8.dp))
                Button( // 保存 或 更新 按钮
                    onClick = {
                        val trimmedModel = editModel.trim();
                        val trimmedAddress = editApiAddress.trim();
                        val trimmedKey = editApiKey.trim()
                        currentSnackbarJob?.cancel() // 取消上一个Snackbar消息（如果有）
                        if (isEditingExisting) { // 如果是编辑现有配置
                            val idToUpdate = currentEditingConfigId ?: run { // 防御性检查
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
                            onUpdateConfig(configToUpdate) // 调用ViewModel更新
                            currentSnackbarJob = coroutineScope.launch { // 显示更新成功Snackbar
                                snackbarHostState.showSnackbar(
                                    "配置 '${configToUpdate.model}' 已更新",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        } else { // 如果是添加新配置
                            val newConfig = ApiConfig(
                                id = UUID.randomUUID().toString(), // 生成新ID
                                address = trimmedAddress,
                                key = trimmedKey,
                                model = trimmedModel,
                                provider = editProvider
                            )
                            onAddConfig(newConfig) // 调用ViewModel添加
                            currentSnackbarJob = coroutineScope.launch { // 显示保存成功Snackbar
                                snackbarHostState.showSnackbar(
                                    "配置 '${newConfig.model}' 已保存",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                    },
                    enabled = canSaveOrUpdate, // 根据字段是否填写完整来启用/禁用
                    colors = ButtonDefaults.buttonColors( // 按钮颜色
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        Icons.Default.Save,
                        null,
                        Modifier.size(ButtonDefaults.IconSize)
                    ); Spacer( // 保存图标
                    Modifier.width(ButtonDefaults.IconSpacing)
                ); Text(if (isEditingExisting) "更新" else "保存") // 按钮文本
                }
            }

            // 分割线
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 24.dp),
                color = Color.LightGray
            )

            // 已存配置列表标题和清空按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, // 标题和按钮两端对齐
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "已存配置:",
                    style = MaterialTheme.typography.titleMedium.copy(color = Color.Black)
                )
                if (savedConfigs.isNotEmpty()) { // 只有当有配置时才显示清空按钮
                    TextButton(
                        onClick = {
                            currentSnackbarJob?.cancel(); onClearAll(); currentSnackbarJob =
                            coroutineScope.launch { // 显示清空成功Snackbar
                                snackbarHostState.showSnackbar(
                                    "所有配置已清除",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) // 错误颜色提示危险操作
                    ) { Text("清空全部") }
                }
            }
            // 显示已存配置列表或提示无配置
            if (savedConfigs.isEmpty()) {
                Text(
                    "暂无配置",
                    color = Color.DarkGray,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally) // 居中显示
                        .padding(vertical = 24.dp)
                )
            } else {
                Column(modifier = Modifier.padding(bottom = 16.dp)) { // 列表容器
                    savedConfigs.forEachIndexed { index, config ->
                        ApiConfigItemContent( // 单个配置项
                            config = config,
                            onDeleteClick = { // 删除回调
                                currentSnackbarJob?.cancel(); onDeleteConfig(config); currentSnackbarJob =
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            "配置 '${config.model}' 已删除",
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                            },
                            onItemEditClick = { onConfigSelectForEdit(config) }, // 点击编辑回调
                            onSelectClick = { // 点击选择此配置的回调
                                currentSnackbarJob?.cancel(); onSelectConfig(config); currentSnackbarJob =
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            "已选择: ${config.model} (${config.provider})",
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                            },
                            isSelectedForEditing = (config.id == currentEditingConfigId), // 是否当前正在编辑此项
                            isCurrentlySelectedInApp = (config.id == selectedConfigIdInApp) // 是否当前在应用中被选中
                        )
                        if (index < savedConfigs.lastIndex) { // 非最后一项，添加分割线
                            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp)) // 底部额外间距
        }

        // Snackbar 显示区域
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter) // 底部居中
                .padding(horizontal = 16.dp) // 水平边距
                .padding(bottom = 16.dp)      // 底部边距
                .fillMaxWidth()
        ) { data -> // 自定义Snackbar外观 (可选)
            Snackbar(snackbarData = data, shape = MaterialTheme.shapes.medium)
        }
    }
}


// --- ApiConfigItemContent Composable 函数 (用于显示单个API配置项) ---
@Composable
private fun ApiConfigItemContent(
    config: ApiConfig,
    onDeleteClick: () -> Unit,
    onItemEditClick: () -> Unit, // 点击整个项目进行编辑
    onSelectClick: () -> Unit,   // 点击选择图标进行选择
    isSelectedForEditing: Boolean, // 此项是否当前在表单中被编辑
    isCurrentlySelectedInApp: Boolean // 此项是否是应用当前选中的配置
) {
    // 根据状态动态改变背景色和边框色
    val targetBackgroundColor = when {
        isSelectedForEditing -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f) // 编辑中高亮
        isCurrentlySelectedInApp -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.1f) // 已选中高亮
        else -> Color.Transparent // 默认透明
    }
    val targetBorderColor = when {
        isSelectedForEditing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        isCurrentlySelectedInApp -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
        else -> Color.LightGray.copy(alpha = 0.6f) // 默认浅灰色边框
    }
    // 使用动画使颜色变化更平滑
    val backgroundColor by animateColorAsState(targetBackgroundColor, label = "ConfigItemBgAnim")
    val borderColor by animateColorAsState(targetBorderColor, label = "ConfigItemBorderAnim")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, MaterialTheme.shapes.medium) // 应用背景色和圆角
            .border(1.dp, borderColor, MaterialTheme.shapes.medium) // 应用边框和圆角
            .clickable { onItemEditClick() } // 使整个项目可点击以进行编辑
            .padding(vertical = 12.dp, horizontal = 16.dp), // 内边距
        verticalAlignment = Alignment.CenterVertically, // 垂直居中对齐
        horizontalArrangement = Arrangement.SpaceBetween // 内容两端对齐
    ) {
        Column( // 左侧配置信息区域
            modifier = Modifier
                .weight(1f) // 占据主要空间
                .padding(end = 12.dp) // 与右侧按钮的间距
        ) {
            Text(
                config.model.ifBlank { "(未命名模型)" }, // 模型名称，为空则显示占位符
                maxLines = 1,
                overflow = TextOverflow.Ellipsis, // 超长省略
                style = MaterialTheme.typography.titleMedium.copy(color = Color.Black),
                fontWeight = if (isSelectedForEditing || isCurrentlySelectedInApp) FontWeight.Bold else FontWeight.Normal // 选中或编辑时加粗
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
                "地址: ${config.address.take(20)}${if (config.address.length > 20) "..." else ""}", // 地址预览
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "密钥: ${maskApiKey(config.key)}", // 密钥脱敏显示
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) { // 右侧操作按钮区域
            IconButton(onClick = onSelectClick, modifier = Modifier.size(40.dp)) { // 选择按钮
                Icon(
                    imageVector = if (isCurrentlySelectedInApp) Icons.Filled.CheckCircleOutline else Icons.Outlined.RadioButtonUnchecked, // 根据是否选中显示不同图标
                    contentDescription = "选择配置 ${config.model}",
                    tint = if (isCurrentlySelectedInApp) MaterialTheme.colorScheme.primary else Color.DarkGray // 选中时用主题色
                )
            }
            IconButton(onClick = onDeleteClick, modifier = Modifier.size(40.dp)) { // 删除按钮
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "删除配置 ${config.model}",
                    tint = MaterialTheme.colorScheme.error // 错误颜色提示
                )
            }
        }
    }
}

// --- API Key 脱敏函数 ---
private fun maskApiKey(key: String): String {
    return when {
        key.isBlank() -> "(未设置)" // 空密钥处理
        key.length <= 8 -> key.map { '*' }.joinToString("") // 短密钥完全用*替换
        else -> "${key.take(4)}****${key.takeLast(4)}" // 长密钥显示首尾，中间用*替换
    }
}