package com.android.everytalk.ui.screens.MainScreen.chat

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.everytalk.data.DataClass.VoiceBackendConfig
import com.android.everytalk.statecontroller.AppViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmSettingsDialog(
    onDismiss: () -> Unit,
    viewModel: AppViewModel? = null
) {
    if (viewModel == null) {
        onDismiss()
        return
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val currentConfig by viewModel.stateHolder._selectedVoiceConfig.collectAsState()
    val allConfigs by viewModel.stateHolder._voiceBackendConfigs.collectAsState()

    // 如果没有当前配置，创建一个默认的
    val effectiveConfig = currentConfig ?: VoiceBackendConfig.createDefault()

    // 状态管理
    var selectedPlatform by remember(effectiveConfig) { mutableStateOf(effectiveConfig.chatPlatform) }
    var apiKey by remember(effectiveConfig) { mutableStateOf(effectiveConfig.chatApiKey) }
    var apiUrl by remember(effectiveConfig) { mutableStateOf(effectiveConfig.chatApiUrl) }
    var model by remember(effectiveConfig) { mutableStateOf(effectiveConfig.chatModel) }
    var expanded by remember { mutableStateOf(false) }
    
    // 冷启动首次进入对话框时，用于避免重复初始化的标记
    var initialPlatformLoaded by remember { mutableStateOf(false) }
    
    // 当平台切换时，从 Room 存储的 allConfigs 中查找对应平台的配置
    fun loadFieldsForPlatform(platform: String) {
        // 1. 从 allConfigs 中查找该平台的配置（已持久化到 Room）
        val existingConfig = allConfigs.find { it.chatPlatform == platform }
        
        if (existingConfig != null) {
            // 找到了该平台的配置，加载它
            apiKey = existingConfig.chatApiKey
            apiUrl = existingConfig.chatApiUrl
            model = existingConfig.chatModel
        } else {
            // 没有找到该平台的配置，使用平台默认值
            apiKey = ""
            apiUrl = when (platform) {
                "OpenAI" -> "" // OpenAI 需要用户自己填写
                else -> ""
            }
            model = ""
        }
    }
    
    // 冷启动后第一次打开对话框时，自动从缓存中恢复当前平台配置
    LaunchedEffect(allConfigs, effectiveConfig.id) {
        if (!initialPlatformLoaded) {
            val existingConfig = allConfigs.find { it.chatPlatform == selectedPlatform }
            if (existingConfig != null) {
                apiKey = existingConfig.chatApiKey
                apiUrl = existingConfig.chatApiUrl
                model = existingConfig.chatModel
            }
            initialPlatformLoaded = true
        }
    }
    
    // 保存当前平台的配置到缓存（在切换平台前调用，仅保存到 allConfigs 列表中供后续加载）
    fun savePlatformConfigToCache(platform: String) {
        // 只有当有实际内容时才保存
        if (apiKey.isBlank() && apiUrl.isBlank() && model.isBlank()) {
            return
        }
        
        // 查找或创建该平台的配置
        val existingConfig = allConfigs.find { it.chatPlatform == platform }
        
        val configToSave = if (existingConfig != null) {
            // 更新现有配置
            existingConfig.copy(
                chatApiKey = apiKey.trim(),
                chatApiUrl = apiUrl.trim(),
                chatModel = model.trim(),
                updatedAt = System.currentTimeMillis()
            )
        } else {
            // 创建新配置（为该平台创建独立记录）
            VoiceBackendConfig(
                id = java.util.UUID.randomUUID().toString(),
                name = "${platform} LLM 配置",
                provider = platform,
                chatPlatform = platform,
                chatApiKey = apiKey.trim(),
                chatApiUrl = apiUrl.trim(),
                chatModel = model.trim(),
                // 保留其他默认值
                sttPlatform = "Google",
                ttsPlatform = "Gemini",
                voiceName = "Kore"
            )
        }
        
        // 更新配置列表（仅更新内存中的列表）
        val newConfigs = if (existingConfig != null) {
            allConfigs.map { if (it.id == configToSave.id) configToSave else it }
        } else {
            allConfigs + configToSave
        }
        
        // 保存到 Room（用于平台切换时的配置缓存）
        viewModel.stateHolder._voiceBackendConfigs.value = newConfigs
        coroutineScope.launch {
            viewModel.persistenceManager.saveVoiceBackendConfigs(newConfigs)
        }
    }
    
    val platforms = listOf("Google", "OpenAI")
    
    // 自定义模型管理 (存储在 SharedPreferences 中，仅作为 UI 辅助)
    val uiPrefs = remember { context.getSharedPreferences("voice_ui_prefs", android.content.Context.MODE_PRIVATE) }
    val customModelsKey = "custom_models_chat_${selectedPlatform}"
    val savedCustomModelsStr = remember(selectedPlatform) { uiPrefs.getString(customModelsKey, "") ?: "" }
    
    var customModels by remember(selectedPlatform) {
        mutableStateOf(
            if (savedCustomModelsStr.isNotEmpty()) savedCustomModelsStr.split(",").filter { it.isNotEmpty() }
            else emptyList()
        )
    }
    val allModels = customModels
    
    val isDarkTheme = isSystemInDarkTheme()
    val cancelButtonColor = if (isDarkTheme) Color(0xFFFF5252) else Color(0xFFD32F2F)
    val confirmButtonColor = if (isDarkTheme) Color.White else Color(0xFF212121)
    val confirmButtonTextColor = if (isDarkTheme) Color.Black else Color.White
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = "LLM 设置 (对话模型)",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                // 平台选择
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "平台",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedPlatform,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            platforms.forEach { platform ->
                                DropdownMenuItem(
                                    text = { Text(platform) },
                                    onClick = {
                                        if (platform != selectedPlatform) {
                                            // 先保存当前平台的配置到缓存
                                            savePlatformConfigToCache(selectedPlatform)
                                            // 然后加载新平台的配置
                                            loadFieldsForPlatform(platform)
                                            selectedPlatform = platform
                                        }
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                
                // API Key
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "API Key",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("请输入 API Key") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }

                // API 地址
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "API 地址",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = apiUrl,
                        onValueChange = { apiUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如 https://api.openai.com/v1") },
                        supportingText = {
                            if (apiUrl.isNotEmpty() && !apiUrl.startsWith("http")) {
                                Text("请填写完整的 http(s) 地址", color = MaterialTheme.colorScheme.error)
                            } else if (selectedPlatform == "OpenAI" && apiUrl.isBlank()) {
                                Text("OpenAI 平台必须填写 API 地址", color = MaterialTheme.colorScheme.error)
                            } else if (selectedPlatform == "OpenAI") {
                                Text("将使用你配置的 OpenAI API 地址", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                // 智能提示最终使用的完整URL
                                val finalUrl = if (selectedPlatform == "OpenAI" && apiUrl.isNotBlank()) {
                                    if (!apiUrl.endsWith("/completions")) {
                                        "${apiUrl.trimEnd('/')}/chat/completions"
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                                
                                if (finalUrl != null) {
                                    Text("最终请求地址: $finalUrl", color = MaterialTheme.colorScheme.primary)
                                } else {
                                    Text("留空则使用默认地址", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }

                // 模型名称 (动态列表)
                DynamicModelSelector(
                    label = "模型名称",
                    currentModel = model,
                    onModelChange = { model = it },
                    modelList = allModels,
                    onAddModel = { newModel ->
                        if (newModel.isNotBlank() && !customModels.contains(newModel)) {
                            val newList = customModels + newModel.trim()
                            customModels = newList
                            uiPrefs.edit().putString(customModelsKey, newList.joinToString(",")).apply()
                            model = newModel.trim()
                        }
                    },
                    onRemoveModel = { modelToRemove ->
                        val newList = customModels - modelToRemove
                        customModels = newList
                        uiPrefs.edit().putString(customModelsKey, newList.joinToString(",")).apply()
                        if (model == modelToRemove) {
                            model = ""
                        }
                    }
                )
                
                // 底部按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = cancelButtonColor
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, cancelButtonColor)
                    ) {
                        Text("取消", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
                    }
                    
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                // 先保存当前平台的配置到缓存
                                savePlatformConfigToCache(selectedPlatform)
                                
                                // 更新当前选中配置的 LLM 部分（保留 STT 和 TTS 的设置）
                                val configToUpdate = currentConfig ?: effectiveConfig
                                
                                val newConfig = configToUpdate.copy(
                                    chatPlatform = selectedPlatform,
                                    chatApiKey = apiKey.trim(),
                                    chatApiUrl = apiUrl.trim(),
                                    chatModel = model.trim(),
                                    updatedAt = System.currentTimeMillis()
                                )
                                
                                // 更新配置列表
                                val latestConfigs = viewModel.stateHolder._voiceBackendConfigs.value
                                val configExists = latestConfigs.any { it.id == newConfig.id }
                                val newConfigs = if (configExists) {
                                    latestConfigs.map { if (it.id == newConfig.id) newConfig else it }
                                } else {
                                    latestConfigs + newConfig
                                }
                                
                                // 保存到 Room
                                viewModel.stateHolder._voiceBackendConfigs.value = newConfigs
                                viewModel.stateHolder._selectedVoiceConfig.value = newConfig
                                viewModel.persistenceManager.saveVoiceBackendConfigs(newConfigs)
                                viewModel.persistenceManager.saveSelectedVoiceConfigId(newConfig.id)
                                
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = confirmButtonColor,
                            contentColor = confirmButtonTextColor
                        )
                    ) {
                        Text("确定", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
    }
}