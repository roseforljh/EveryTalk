package com.android.everytalk.ui.screens.MainScreen.chat.dialog

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

    // 使用 SharedPreferences 按平台独立存储 LLM 配置
    val llmPrefs = remember { context.getSharedPreferences("llm_platform_configs", android.content.Context.MODE_PRIVATE) }
    
    // 从当前配置获取平台，或默认 Google
    var selectedPlatform by remember(effectiveConfig.id) { 
        mutableStateOf(effectiveConfig.chatPlatform.ifBlank { "Google" }) 
    }
    var expanded by remember { mutableStateOf(false) }
    
    // 加载当前平台的配置（从 SharedPreferences）
    fun loadPlatformConfig(platform: String): Triple<String, String, String> {
        val apiKey = llmPrefs.getString("${platform}_apiKey", "") ?: ""
        val apiUrl = llmPrefs.getString("${platform}_apiUrl", "") ?: ""
        val model = llmPrefs.getString("${platform}_model", "") ?: ""
        return Triple(apiKey, apiUrl, model)
    }
    
    // 初始化时，优先从 VoiceBackendConfig 加载，如果为空则从 SharedPreferences 加载
    val initialConfig = remember(effectiveConfig.id, selectedPlatform) {
        if (effectiveConfig.chatPlatform == selectedPlatform && 
            (effectiveConfig.chatApiKey.isNotBlank() || effectiveConfig.chatModel.isNotBlank())) {
            Triple(effectiveConfig.chatApiKey, effectiveConfig.chatApiUrl, effectiveConfig.chatModel)
        } else {
            loadPlatformConfig(selectedPlatform)
        }
    }
    
    var apiKey by remember(selectedPlatform) { mutableStateOf(initialConfig.first) }
    var apiUrl by remember(selectedPlatform) { mutableStateOf(initialConfig.second) }
    var model by remember(selectedPlatform) { mutableStateOf(initialConfig.third) }
    
    // 当平台切换时，加载对应平台的配置
    LaunchedEffect(selectedPlatform) {
        val (loadedKey, loadedUrl, loadedModel) = loadPlatformConfig(selectedPlatform)
        // 只有当 SharedPreferences 中有数据时才覆盖
        if (loadedKey.isNotBlank() || loadedUrl.isNotBlank() || loadedModel.isNotBlank()) {
            apiKey = loadedKey
            apiUrl = loadedUrl
            model = loadedModel
        } else if (effectiveConfig.chatPlatform != selectedPlatform) {
            // 切换到新平台且没有保存的配置，清空
            apiKey = ""
            apiUrl = ""
            model = ""
        }
    }
      
    // 调试日志
    LaunchedEffect(effectiveConfig.id) {
        android.util.Log.d("LlmSettingsDialog", "打开对话框 - 配置ID: ${effectiveConfig.id}, 名称: ${effectiveConfig.name}")
        android.util.Log.d("LlmSettingsDialog", "  Chat: platform=${effectiveConfig.chatPlatform}, model=${effectiveConfig.chatModel}, url=${effectiveConfig.chatApiUrl}")
        android.util.Log.d("LlmSettingsDialog", "  Chat API Key 非空: ${effectiveConfig.chatApiKey.isNotBlank()}, 长度: ${effectiveConfig.chatApiKey.length}")
    }
    
    val platforms = listOf("Google", "OpenAI")
    
    // 自定义模型管理 (存储在 SharedPreferences 中，按平台分开)
    val uiPrefs = remember { context.getSharedPreferences("voice_ui_prefs", android.content.Context.MODE_PRIVATE) }
    val customModelsKey = "custom_models_chat_${selectedPlatform}"
    val savedCustomModelsStr = remember(selectedPlatform) { uiPrefs.getString(customModelsKey, "") ?: "" }
    
    var customModels by remember(selectedPlatform) {
        mutableStateOf(
            if (savedCustomModelsStr.isNotEmpty()) savedCustomModelsStr.split(",").filter { it.isNotEmpty() }
            else emptyList()
        )
    }
    // 确保当前选中的模型也在列表中（即使不在 SharedPreferences 中）
    val allModels = remember(customModels, model) {
        if (model.isNotBlank() && !customModels.contains(model)) {
            listOf(model) + customModels
        } else {
            customModels
        }
    }
    
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
                                        // 切换前保存当前平台的配置
                                        llmPrefs.edit()
                                            .putString("${selectedPlatform}_apiKey", apiKey)
                                            .putString("${selectedPlatform}_apiUrl", apiUrl)
                                            .putString("${selectedPlatform}_model", model)
                                            .apply()
                                        
                                        selectedPlatform = platform
                                        expanded = false
                                        
                                        // 加载新平台的配置
                                        val (newKey, newUrl, newModel) = loadPlatformConfig(platform)
                                        apiKey = newKey
                                        apiUrl = newUrl
                                        model = newModel
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
                com.android.everytalk.ui.screens.MainScreen.chat.models.DynamicModelSelector(
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
                                // 1. 保存当前平台的配置到 SharedPreferences（独立存储）
                                llmPrefs.edit()
                                    .putString("${selectedPlatform}_apiKey", apiKey.trim())
                                    .putString("${selectedPlatform}_apiUrl", apiUrl.trim())
                                    .putString("${selectedPlatform}_model", model.trim())
                                    .apply()
                                
                                android.util.Log.d("LlmSettingsDialog", "保存平台配置 - 平台: $selectedPlatform")
                                android.util.Log.d("LlmSettingsDialog", "  API Key 非空: ${apiKey.isNotBlank()}, 长度: ${apiKey.length}")
                                android.util.Log.d("LlmSettingsDialog", "  API URL: $apiUrl")
                                android.util.Log.d("LlmSettingsDialog", "  模型: $model")
                                
                                // 2. 同时更新当前选中配置的 Chat LLM 部分（用于语音模式）
                                val newConfig = effectiveConfig.copy(
                                    chatPlatform = selectedPlatform,
                                    chatApiKey = apiKey.trim(),
                                    chatApiUrl = apiUrl.trim(),
                                    chatModel = model.trim(),
                                    updatedAt = System.currentTimeMillis()
                                )
                                
                                android.util.Log.d("LlmSettingsDialog", "保存配置到VoiceBackendConfig - ID: ${newConfig.id}, 名称: ${newConfig.name}")
                                android.util.Log.d("LlmSettingsDialog", "  Chat: platform=${newConfig.chatPlatform}, model=${newConfig.chatModel}, url=${newConfig.chatApiUrl}")
                                
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
                                
                                android.util.Log.d("LlmSettingsDialog", "配置已保存到 Room，配置总数: ${newConfigs.size}")
                                
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