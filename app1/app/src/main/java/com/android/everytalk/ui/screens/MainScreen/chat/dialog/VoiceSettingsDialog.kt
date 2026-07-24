package com.android.everytalk.ui.screens.MainScreen.chat.dialog
import com.android.everytalk.statecontroller.*

import androidx.compose.foundation.border
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
import androidx.core.content.edit
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.VoiceBackendConfig
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.ui.screens.MainScreen.chat.models.DynamicModelSelector
import com.android.everytalk.ui.screens.MainScreen.chat.voice.logic.VoiceConfigManager
import com.android.everytalk.ui.screens.settings.DialogTextFieldColors
import com.android.everytalk.ui.screens.settings.DialogShape
import com.android.everytalk.ui.screens.settings.SettingsFieldLabel
import kotlinx.coroutines.launch

/**
 * TTS (语音合成) 配置对话框
 *
 * 此对话框用于修改全局的 VoiceBackendConfig 配置。
 * 复用于：
 * 1. 语音输入模式 (VoiceInputScreen) 的顶部设置按钮
 * 2. 应用主设置页面 (SettingsScreen)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsDialog(
    selectedApiConfig: ApiConfig? = null,
    onDismiss: () -> Unit,
    viewModel: AppViewModel? = null
) {
    if (viewModel == null) {
        // 如果 ViewModel 不存在（如预览模式），直接返回或显示错误
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
    var selectedPlatform by remember(effectiveConfig) { mutableStateOf(effectiveConfig.ttsPlatform) }
    var apiKey by remember(effectiveConfig) { mutableStateOf(effectiveConfig.ttsApiKey) }
    var baseUrl by remember(effectiveConfig) { mutableStateOf(effectiveConfig.ttsApiUrl) }
    var chatModel by remember(effectiveConfig) { mutableStateOf(effectiveConfig.ttsModel) }
    var expanded by remember { mutableStateOf(false) }
    
    // 当平台切换时，从 Room 存储的 allConfigs 中查找对应平台的配置
    fun loadFieldsForPlatform(platform: String) {
        // 1. 从 allConfigs 中查找该平台的配置（已持久化到 Room）
        val existingConfig = allConfigs.find { it.ttsPlatform == platform }
        
        if (existingConfig != null) {
            // 找到了该平台的配置，加载它
            apiKey = existingConfig.ttsApiKey
            baseUrl = existingConfig.ttsApiUrl
            chatModel = existingConfig.ttsModel
        } else {
            // 没有找到该平台的配置，使用平台默认值
            apiKey = ""
            baseUrl = when (platform) {
                "SiliconFlow" -> "https://api.siliconflow.cn/v1/audio/speech"
                "Aliyun" -> "https://dashscope.aliyuncs.com/api/v1"
                else -> ""
            }
            chatModel = when (platform) {
                "SiliconFlow" -> "IndexTeam/IndexTTS-2"
                "Aliyun" -> ""
                else -> ""
            }
        }
    }
    
    // 保存当前平台的配置到缓存（在切换平台前调用，仅保存到 allConfigs 列表中供后续加载）
    fun savePlatformConfigToCache(platform: String) {
        // 只有当有实际内容时才保存
        if (apiKey.isBlank() && baseUrl.isBlank() && chatModel.isBlank()) {
            return
        }
        
        // 查找或创建该平台的配置
        val existingConfig = allConfigs.find { it.ttsPlatform == platform }
        
        val configToSave = if (existingConfig != null) {
            // 更新现有配置
            existingConfig.copy(
                ttsApiKey = apiKey.trim(),
                ttsApiUrl = baseUrl.trim(),
                ttsModel = chatModel.trim(),
                updatedAt = System.currentTimeMillis()
            )
        } else {
            // 创建新配置（为该平台创建独立记录）
            VoiceBackendConfig(
                id = java.util.UUID.randomUUID().toString(),
                name = "${platform} TTS 配置",
                provider = platform,
                ttsPlatform = platform,
                ttsApiKey = apiKey.trim(),
                ttsApiUrl = baseUrl.trim(),
                ttsModel = chatModel.trim(),
                // 保留其他默认值
                sttPlatform = "Google",
                chatPlatform = "Google",
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

    val platforms = listOf("Gemini", "OpenAI", "Minimax", "SiliconFlow", "Aliyun")
    
    // 自定义模型管理 (存储在 SharedPreferences 中，仅作为 UI 辅助，不影响核心功能)
    // 使用独立的 preferences 文件避免与旧的 voice_settings 混淆
    val uiPrefs = remember { context.getSharedPreferences("voice_ui_prefs", android.content.Context.MODE_PRIVATE) }
    val customModelsKey = "custom_models_tts_${selectedPlatform}"
    val savedCustomModelsStr = remember(selectedPlatform) { uiPrefs.getString(customModelsKey, "") ?: "" }
    
    var customModels by remember(selectedPlatform) {
        mutableStateOf(
            if (savedCustomModelsStr.isNotEmpty()) savedCustomModelsStr.split(",").filter { it.isNotEmpty() }
            else emptyList()
        )
    }
    // 确保当前选中的模型也在列表中（即使不在 SharedPreferences 中）
    val allModels = remember(customModels, chatModel) {
        if (chatModel.isNotBlank() && !customModels.contains(chatModel)) {
            listOf(chatModel) + customModels
        } else {
            customModels
        }
    }
    
    val isDarkTheme = isSystemInDarkTheme()
    val dialogBg = if (isDarkTheme) Color.Black else Color.White
    val borderColor = if (isDarkTheme) Color(0xFF414141) else Color(0xFFF3F3F3)
    val contentColor = if (isDarkTheme) Color.White else Color(0xFF0D0D0D)
    val subtextColor = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color(0xFF0D0D0D).copy(alpha = 0.6f)
    
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
                .wrapContentHeight()
                .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
            colors = CardDefaults.cardColors(
                containerColor = dialogBg
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 标题
                Text(
                    text = "TTS 设置 (语音合成)",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = contentColor
                )
                
                // 平台下拉框
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SettingsFieldLabel("TTS 平台")
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedPlatform,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
                            colors = DialogTextFieldColors,
                            shape = DialogShape
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            platforms.forEach { platform ->
                                DropdownMenuItem(
                                    text = { Text(platform, color = contentColor) },
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
                
                // API Key 输入框
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SettingsFieldLabel("TTS API Key")
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("请输入 API Key") },
                        colors = DialogTextFieldColors,
                        shape = DialogShape,
                        singleLine = true
                    )
                }

                // 语音 API 地址输入框
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SettingsFieldLabel("TTS API 地址")
                    OutlinedTextField(
                        value = if (selectedPlatform == "Gemini") "自动使用默认地址" else baseUrl,
                        onValueChange = { if (selectedPlatform != "Gemini") baseUrl = it },
                        enabled = selectedPlatform != "Gemini",
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                if (selectedPlatform == "Aliyun") "例如 https://dashscope.aliyuncs.com/api/v1"
                                else "例如 https://api.minimaxi.com/v1/t2a_v2"
                            )
                        },
                        supportingText = {
                            if (selectedPlatform == "Gemini") {
                                Text("自动使用 https://generativelanguage.googleapis.com", color = subtextColor)
                            } else if (selectedPlatform == "Aliyun") {
                                Text("默认: https://dashscope.aliyuncs.com/api/v1", color = subtextColor)
                            } else if (baseUrl.isNotEmpty() && !baseUrl.startsWith("http")) {
                                Text("请填写完整的 http(s) 地址", color = MaterialTheme.colorScheme.error)
                            } else if (selectedPlatform == "Minimax" && baseUrl.isBlank()) {
                                Text("Minimax 平台必须填写 API 地址", color = MaterialTheme.colorScheme.error)
                            } else if (selectedPlatform == "SiliconFlow") {
                                Text("默认: https://api.siliconflow.cn/v1/audio/speech", color = subtextColor)
                            } else {
                                // 智能提示最终使用的完整URL
                                val finalUrl = if (selectedPlatform == "OpenAI" && baseUrl.isNotBlank()) {
                                    if (!baseUrl.endsWith("/speech")) {
                                        "${baseUrl.trimEnd('/')}/audio/speech"
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                                
                                if (finalUrl != null) {
                                    Text("最终请求地址: $finalUrl", color = MaterialTheme.colorScheme.primary)
                                } else {
                                    Text("大模型厂商的 API 地址", color = subtextColor)
                                }
                            }
                        },
                        colors = DialogTextFieldColors,
                        shape = DialogShape,
                        singleLine = true
                    )
                }

                // 语音模型名称输入框 (动态列表)
                DynamicModelSelector(
                    label = "TTS 模型名称",
                    currentModel = chatModel,
                    onModelChange = { chatModel = it },
                    modelList = allModels,
                    onAddModel = { newModel ->
                        if (newModel.isNotBlank() && !customModels.contains(newModel)) {
                            val newList = customModels + newModel.trim()
                            customModels = newList
                            uiPrefs.edit { putString(customModelsKey, newList.joinToString(",")) }
                            chatModel = newModel.trim()
                        }
                    },
                    onRemoveModel = { modelToRemove ->
                        val newList = customModels - modelToRemove
                        customModels = newList
                        uiPrefs.edit { putString(customModelsKey, newList.joinToString(",")) }
                        if (chatModel == modelToRemove) {
                            chatModel = ""
                        }
                    }
                )
                
                // 底部按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 取消按钮
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = contentColor
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
                    ) {
                        Text(
                            text = "取消",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                    
                    // 确定按钮
                    Button(
                        onClick = {
                            if (chatModel.isBlank()) {
                                // 简单提示，实际应使用 Snackbar
                                return@Button
                            }
                            if (selectedPlatform == "Minimax" && baseUrl.isBlank()) {
                                return@Button
                            }

                            coroutineScope.launch {
                                // 先保存当前平台的配置到缓存
                                savePlatformConfigToCache(selectedPlatform)
                                
                                // 更新当前选中配置的 TTS 部分（保留 STT 和 LLM 的设置）
                                val configToUpdate = currentConfig ?: effectiveConfig
                                
                                val newConfig = configToUpdate.copy(
                                    ttsPlatform = selectedPlatform,
                                    ttsApiKey = apiKey.trim(),
                                    ttsApiUrl = baseUrl.trim(),
                                    ttsModel = chatModel.trim(),
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
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = contentColor,
                            contentColor = dialogBg
                        )
                    ) {
                        Text(
                            text = "确定",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }
        }
    }
}
