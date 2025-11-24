package com.android.everytalk.ui.screens.MainScreen.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSelectionDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("voice_settings", android.content.Context.MODE_PRIVATE) }
    val ttsPlatform = remember { prefs.getString("voice_platform", "Gemini") ?: "Gemini" }
    val savedVoice = remember { prefs.getString("voice_name_${ttsPlatform}", null) ?: prefs.getString("voice_name", "Kore") ?: "Kore" }
    
    var selectedVoice by remember { mutableStateOf(savedVoice) }
    
    // Gemini 音色
    val geminiVoices = listOf(
        "Zephyr" to "明亮",
        "Puck" to "欢快",
        "Charon" to "知性",
        "Kore" to "坚定",
        "Fenrir" to "兴奋",
        "Leda" to "年轻",
        "Orus" to "坚定",
        "Aoede" to "轻快",
        "Callirrhoe" to "随和",
        "Autonoe" to "明亮",
        "Enceladus" to "气息感",
        "Iapetus" to "清晰",
        "Umbriel" to "随和",
        "Algieba" to "流畅",
        "Despina" to "平滑",
        "Erinome" to "清晰",
        "Algenib" to "沙哑",
        "Rasalgethi" to "知性",
        "Laomedeia" to "欢快",
        "Achernar" to "柔和",
        "Alnilam" to "坚定",
        "Schedar" to "平稳",
        "Gacrux" to "成熟",
        "Pulcherrima" to "前卫",
        "Achird" to "友好",
        "Zubenelgenubi" to "随意",
        "Vindemiatrix" to "温柔",
        "Sadachbia" to "活泼",
        "Sadaltager" to "博学",
        "Sulafat" to "温暖"
    )

    // Minimax 音色 (示例)
    val minimaxVoices = listOf(
        "male-qn-qingse" to "青涩男声",
        "male-qn-jingying" to "精英男声",
        "female-shaonv" to "少女音",
        "female-yujie" to "御姐音",
        "presenter_male" to "男主持人",
        "presenter_female" to "女主持人",
        "audiobook_male_1" to "有声书男1",
        "audiobook_male_2" to "有声书男2",
        "audiobook_female_1" to "有声书女1",
        "audiobook_female_2" to "有声书女2"
    )

    // OpenAI 音色
    val openaiVoices = listOf(
        "alloy" to "中性",
        "echo" to "沉稳",
        "fable" to "英式",
        "onyx" to "深沉",
        "nova" to "活力",
        "shimmer" to "清澈"
    )

    // SiliconFlow 音色
    val siliconFlowVoices = listOf(
        "alex" to "Alex (男声)",
        "anna" to "Anna (女声)",
        "bella" to "Bella (女声)",
        "benjamin" to "Benjamin (男声)",
        "charles" to "Charles (男声)",
        "claire" to "Claire (女声)",
        "david" to "David (男声)",
        "diana" to "Diana (女声)"
    )

    val voices = when (ttsPlatform) {
        "Minimax" -> minimaxVoices
        "OpenAI" -> openaiVoices
        "SiliconFlow" -> siliconFlowVoices
        else -> geminiVoices
    }
    
    val isDarkTheme = isSystemInDarkTheme()
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
                .fillMaxHeight(0.8f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 标题
                Text(
                    text = "选择音色",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                // 当前选择提示
                Text(
                    text = "当前: $selectedVoice",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // 音色列表
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(voices) { (voiceName, description) ->
                        val isSelected = voiceName == selectedVoice
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedVoice = voiceName },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) 
                                    MaterialTheme.colorScheme.primaryContainer 
                                else 
                                    MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = voiceName,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = if (isSelected)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected)
                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                
                                if (isSelected) {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.Check,
                                        contentDescription = "已选择",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                
                // 确定按钮
                Button(
                    onClick = {
                        // 保存选择
                        runCatching {
                            val editor = prefs.edit()
                            editor.putString("voice_name_${ttsPlatform}", selectedVoice)
                            // 可选：更新全局，作为默认
                            // editor.putString("voice_name", selectedVoice)
                            editor.apply()
                        }
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
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