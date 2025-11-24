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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SttSettingsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("voice_settings", android.content.Context.MODE_PRIVATE) }
    
    val savedPlatform = remember { prefs.getString("stt_platform", "Google") ?: "Google" }
    val savedKeyGoogle = remember { prefs.getString("stt_key_Google", "") ?: "" }
    val savedKeyOpenAI = remember { prefs.getString("stt_key_OpenAI", "") ?: "" }
    
    // 旧全局默认值
    val defaultApiUrl = remember { prefs.getString("stt_api_url", "") ?: "" }
    val defaultModel = remember { prefs.getString("stt_model", "") ?: "" }

    fun resolveKeyFor(platform: String): String {
        return when (platform) {
            "OpenAI" -> savedKeyOpenAI
            "SiliconFlow" -> prefs.getString("stt_key_SiliconFlow", "") ?: ""
            else -> savedKeyGoogle
        }.trim()
    }
    
    fun resolveApiUrlFor(platform: String): String {
        val saved = prefs.getString("stt_api_url_${platform}", null)
        if (saved != null) return saved
        
        return when (platform) {
            "SiliconFlow" -> "https://api.siliconflow.cn/v1/audio/transcriptions"
            else -> defaultApiUrl
        }
    }
    
    fun resolveModelFor(platform: String): String {
        val saved = prefs.getString("stt_model_${platform}", null)
        if (saved != null) return saved
        
        return when (platform) {
            "SiliconFlow" -> "FunAudioLLM/SenseVoiceSmall"
            else -> defaultModel
        }
    }

    var selectedPlatform by remember { mutableStateOf(savedPlatform) }
    var apiKey by remember { mutableStateOf(resolveKeyFor(selectedPlatform)) }
    var apiUrl by remember { mutableStateOf(resolveApiUrlFor(selectedPlatform)) }
    var model by remember { mutableStateOf(resolveModelFor(selectedPlatform)) }
    var expanded by remember { mutableStateOf(false) }
    
    val platforms = listOf("Google", "OpenAI", "SiliconFlow")
    
    // 自定义模型管理
    val customModelsKey = "custom_models_stt_${selectedPlatform}"
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
                Text(
                    text = "STT 设置 (语音识别)",
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
                                        selectedPlatform = platform
                                        apiKey = resolveKeyFor(platform)
                                        apiUrl = resolveApiUrlFor(platform)
                                        model = resolveModelFor(platform)
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
                            } else if (selectedPlatform == "SiliconFlow") {
                                Text("默认: https://api.siliconflow.cn/v1/audio/transcriptions", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text("留空则使用默认地址", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            prefs.edit().putString(customModelsKey, newList.joinToString(",")).apply()
                            model = newModel.trim()
                        }
                    },
                    onRemoveModel = { modelToRemove ->
                        val newList = customModels - modelToRemove
                        customModels = newList
                        prefs.edit().putString(customModelsKey, newList.joinToString(",")).apply()
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
                            runCatching {
                                val editor = prefs.edit()
                                editor.putString("stt_platform", selectedPlatform)
                                when (selectedPlatform) {
                                    "OpenAI" -> editor.putString("stt_key_OpenAI", apiKey)
                                    "SiliconFlow" -> editor.putString("stt_key_SiliconFlow", apiKey)
                                    else -> editor.putString("stt_key_Google", apiKey)
                                }
                                // 按平台保存
                                editor.putString("stt_api_url_${selectedPlatform}", apiUrl.trim())
                                editor.putString("stt_model_${selectedPlatform}", model.trim())
                                editor.apply()
                            }
                            onDismiss()
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