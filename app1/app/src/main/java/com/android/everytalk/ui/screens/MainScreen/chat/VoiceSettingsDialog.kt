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
import com.android.everytalk.data.DataClass.ApiConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsDialog(
    selectedApiConfig: ApiConfig?,
    onDismiss: () -> Unit
) {
    // 本地持久化：voice_settings
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("voice_settings", android.content.Context.MODE_PRIVATE) }
    val savedPlatform = remember { prefs.getString("voice_platform", null) }
    val savedKeyGemini = remember { prefs.getString("voice_key_Gemini", "") ?: "" }
    val savedKeyOpenAI = remember { prefs.getString("voice_key_OpenAI", "") ?: "" }
    val savedKeyMinimax = remember { prefs.getString("voice_key_Minimax", "") ?: "" }
    val savedKeySiliconFlow = remember { prefs.getString("voice_key_SiliconFlow", "") ?: "" }
    val savedKeyAliyun = remember { prefs.getString("voice_key_Aliyun", "") ?: "" }
    
    // 旧全局默认值
    val defaultBaseUrl = remember { prefs.getString("voice_base_url", "") ?: "" }
    val defaultChatModel = remember { prefs.getString("voice_chat_model", "") ?: "" }

    // 辅助函数：根据平台解析配置（优先独立配置，回退到旧全局）
    fun resolveKeyFor(platform: String): String {
        return when (platform) {
            "OpenAI" -> savedKeyOpenAI
            "Minimax" -> savedKeyMinimax
            "SiliconFlow" -> savedKeySiliconFlow
            "Aliyun" -> savedKeyAliyun
            else -> savedKeyGemini
        }.trim()
    }
    
    fun resolveBaseUrlFor(platform: String): String {
        val saved = prefs.getString("voice_base_url_${platform}", null)
        if (saved != null) return saved
        
        return when (platform) {
            "SiliconFlow" -> "https://api.siliconflow.cn/v1/audio/speech"
            "Aliyun" -> "https://dashscope.aliyuncs.com/api/v1"
            else -> defaultBaseUrl
        }
    }
    
    fun resolveModelFor(platform: String): String {
        val saved = prefs.getString("voice_chat_model_${platform}", null)
        if (saved != null) return saved
        
        return when (platform) {
            "SiliconFlow" -> "IndexTeam/IndexTTS-2"
            "Aliyun" -> ""
            else -> defaultChatModel
        }
    }

    var selectedPlatform by remember {
        mutableStateOf(savedPlatform ?: "Gemini")
    }
    var apiKey by remember {
        mutableStateOf(resolveKeyFor(selectedPlatform))
    }
    var baseUrl by remember { mutableStateOf(resolveBaseUrlFor(selectedPlatform)) }
    var chatModel by remember { mutableStateOf(resolveModelFor(selectedPlatform)) }
    var expanded by remember { mutableStateOf(false) }
    
    val platforms = listOf("Gemini", "OpenAI", "Minimax", "SiliconFlow", "Aliyun")
    
    // 预设模型列表
    // 自定义模型管理
    val customModelsKey = "custom_models_tts_${selectedPlatform}"
    val savedCustomModelsStr = remember(selectedPlatform) { prefs.getString(customModelsKey, "") ?: "" }
    
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
                // 标题
                Text(
                    text = "TTS 设置 (语音合成)",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                // 平台下拉框
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "TTS 平台",
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
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
                                        selectedPlatform = platform
                                        // 实时切换到对应平台的Key
                                        apiKey = resolveKeyFor(platform)
                                        baseUrl = resolveBaseUrlFor(platform)
                                        chatModel = resolveModelFor(platform)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                
                // API Key 输入框
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "TTS API Key",
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

                // 语音 API 地址输入框
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "TTS API 地址",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                                Text("自动使用 https://generativelanguage.googleapis.com", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (selectedPlatform == "Aliyun") {
                                Text("默认: https://dashscope.aliyuncs.com/api/v1", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (baseUrl.isNotEmpty() && !baseUrl.startsWith("http")) {
                                Text("请填写完整的 http(s) 地址", color = MaterialTheme.colorScheme.error)
                            } else if (selectedPlatform == "Minimax" && baseUrl.isBlank()) {
                                Text("Minimax 平台必须填写 API 地址", color = MaterialTheme.colorScheme.error)
                            } else if (selectedPlatform == "SiliconFlow") {
                                Text("默认: https://api.siliconflow.cn/v1/audio/speech", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    Text("大模型厂商的 API 地址", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        shape = RoundedCornerShape(12.dp),
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
                            prefs.edit().putString(customModelsKey, newList.joinToString(",")).apply()
                            chatModel = newModel.trim()
                        }
                    },
                    onRemoveModel = { modelToRemove ->
                        val newList = customModels - modelToRemove
                        customModels = newList
                        prefs.edit().putString(customModelsKey, newList.joinToString(",")).apply()
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
                            containerColor = MaterialTheme.colorScheme.surface,
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
                    
                    // 确定按钮
                    Button(
                        onClick = {
                            if (chatModel.isBlank()) {
                                android.widget.Toast.makeText(context, "请填写 TTS 模型名称", android.widget.Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (selectedPlatform == "Minimax" && baseUrl.isBlank()) {
                                android.widget.Toast.makeText(context, "Minimax 平台请填写 API 地址", android.widget.Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            // 保存用户选择的平台和对应Key（按平台独立保存）
                            runCatching {
                                val editor = prefs.edit()
                                editor.putString("voice_platform", selectedPlatform)
                                when (selectedPlatform) {
                                    "OpenAI" -> editor.putString("voice_key_OpenAI", apiKey)
                                    "Minimax" -> editor.putString("voice_key_Minimax", apiKey)
                                    "SiliconFlow" -> editor.putString("voice_key_SiliconFlow", apiKey)
                                    "Aliyun" -> editor.putString("voice_key_Aliyun", apiKey)
                                    else -> editor.putString("voice_key_Gemini", apiKey)
                                }
                                // 保存该平台特定的 Base URL 和 Model
                                editor.putString("voice_base_url_${selectedPlatform}", baseUrl.trim())
                                editor.putString("voice_chat_model_${selectedPlatform}", chatModel.trim())
                                
                                // 兼容性：同时更新全局旧键（可选，视需求而定，这里暂且不覆盖旧键以免污染其他平台回退）
                                // editor.putString("voice_base_url", baseUrl.trim())
                                // editor.putString("voice_chat_model", chatModel.trim())
                                
                                editor.apply()
                            }
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = confirmButtonColor,
                            contentColor = confirmButtonTextColor
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