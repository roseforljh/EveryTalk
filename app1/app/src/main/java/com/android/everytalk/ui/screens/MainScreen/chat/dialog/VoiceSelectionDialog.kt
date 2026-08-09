package com.android.everytalk.ui.screens.MainScreen.chat.dialog
import com.android.everytalk.statecontroller.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.everytalk.data.DataClass.VoiceBackendConfig
import com.android.everytalk.statecontroller.AppViewModel
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.annotation.StringRes
import com.android.everytalk.R
import kotlinx.coroutines.launch

private data class LocalizedVoiceOption(
    val name: String,
    @StringRes val descriptionRes: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSelectionDialog(
    onDismiss: () -> Unit,
    viewModel: AppViewModel? = null
) {
    if (viewModel == null) {
        onDismiss()
        return
    }

    val coroutineScope = rememberCoroutineScope()
    val currentConfig by viewModel.stateHolder._selectedVoiceConfig.collectAsState()
    val allConfigs by viewModel.stateHolder._voiceBackendConfigs.collectAsState()

    // 如果没有当前配置，创建一个默认的
    val effectiveConfig = currentConfig ?: VoiceBackendConfig.createDefault()
    
    val ttsPlatform = effectiveConfig.ttsPlatform
    
    // 获取默认音色的辅助函数
    fun getDefaultVoiceName(platform: String): String {
        return when (platform) {
            "SiliconFlow" -> "alex"
            "Minimax" -> "male-qn-qingse"
            "OpenAI" -> "alloy"
            "Aliyun" -> "Cherry"
            else -> "Kore" // Gemini
        }
    }
    
    val savedVoice = effectiveConfig.voiceName.ifBlank { getDefaultVoiceName(ttsPlatform) }
    
    var selectedVoice by remember(ttsPlatform) { mutableStateOf(savedVoice) }
    
    // 调试日志：对话框打开时输出当前配置
    android.util.Log.d("VoiceSelectionDialog", "Dialog opened: ttsPlatform=$ttsPlatform, savedVoice=$savedVoice, selectedVoice=$selectedVoice")
    
    // 阿里云音色分类选项卡状态
    var aliyunCategory by remember { mutableIntStateOf(0) } // 0=国内, 1=国外, 2=乡音
    
    // Gemini 音色
    val geminiVoices = listOf(
        LocalizedVoiceOption("Zephyr", R.string.voice_tone_bright),
        LocalizedVoiceOption("Puck", R.string.voice_tone_cheerful),
        LocalizedVoiceOption("Charon", R.string.voice_tone_informative),
        LocalizedVoiceOption("Kore", R.string.voice_tone_firm),
        LocalizedVoiceOption("Fenrir", R.string.voice_tone_excited),
        LocalizedVoiceOption("Leda", R.string.voice_tone_youthful),
        LocalizedVoiceOption("Orus", R.string.voice_tone_firm),
        LocalizedVoiceOption("Aoede", R.string.voice_tone_breezy),
        LocalizedVoiceOption("Callirrhoe", R.string.voice_tone_easygoing),
        LocalizedVoiceOption("Autonoe", R.string.voice_tone_bright),
        LocalizedVoiceOption("Enceladus", R.string.voice_tone_breathy),
        LocalizedVoiceOption("Iapetus", R.string.voice_tone_clear),
        LocalizedVoiceOption("Umbriel", R.string.voice_tone_easygoing),
        LocalizedVoiceOption("Algieba", R.string.voice_tone_fluid),
        LocalizedVoiceOption("Despina", R.string.voice_tone_smooth),
        LocalizedVoiceOption("Erinome", R.string.voice_tone_clear),
        LocalizedVoiceOption("Algenib", R.string.voice_tone_raspy),
        LocalizedVoiceOption("Rasalgethi", R.string.voice_tone_informative),
        LocalizedVoiceOption("Laomedeia", R.string.voice_tone_cheerful),
        LocalizedVoiceOption("Achernar", R.string.voice_tone_soft),
        LocalizedVoiceOption("Alnilam", R.string.voice_tone_firm),
        LocalizedVoiceOption("Schedar", R.string.voice_tone_steady),
        LocalizedVoiceOption("Gacrux", R.string.voice_tone_mature),
        LocalizedVoiceOption("Pulcherrima", R.string.voice_tone_forward),
        LocalizedVoiceOption("Achird", R.string.voice_tone_friendly),
        LocalizedVoiceOption("Zubenelgenubi", R.string.voice_tone_casual),
        LocalizedVoiceOption("Vindemiatrix", R.string.voice_tone_gentle),
        LocalizedVoiceOption("Sadachbia", R.string.voice_tone_lively),
        LocalizedVoiceOption("Sadaltager", R.string.voice_tone_knowledgeable),
        LocalizedVoiceOption("Sulafat", R.string.voice_tone_warm),
    )

    // Minimax 音色
    val minimaxVoices = listOf(
        LocalizedVoiceOption("Chinese (Mandarin)_Warm_Bestie", R.string.voice_desc_minimax_warm_bestie),
        LocalizedVoiceOption("Chinese (Mandarin)_Gentle_Senior", R.string.voice_desc_minimax_gentle_senior),
        LocalizedVoiceOption("Chinese (Mandarin)_Sweet_Lady", R.string.voice_desc_minimax_sweet_lady),
        LocalizedVoiceOption("Chinese (Mandarin)_Mature_Woman", R.string.voice_desc_minimax_mature_woman),
        LocalizedVoiceOption("female-yujie", R.string.voice_desc_minimax_female_yujie),
        LocalizedVoiceOption("Chinese (Mandarin)_Warm_Girl", R.string.voice_desc_minimax_warm_girl),
        LocalizedVoiceOption("Chinese (Mandarin)_Crisp_Girl", R.string.voice_desc_minimax_crisp_girl),
        LocalizedVoiceOption("qiaopi_mengmei", R.string.voice_desc_minimax_playful_girl),
        LocalizedVoiceOption("Chinese (Mandarin)_Gentleman", R.string.voice_desc_minimax_gentleman),
        LocalizedVoiceOption("Chinese (Mandarin)_Lyrical_Voice", R.string.voice_desc_minimax_lyrical_voice),
        LocalizedVoiceOption("male-qn-jingying", R.string.voice_desc_minimax_elite_youth),
        LocalizedVoiceOption("male-qn-qingse", R.string.voice_desc_minimax_young_man),
        LocalizedVoiceOption("female-shaonv", R.string.voice_desc_minimax_young_woman),
    )

    // OpenAI 音色
    val openaiVoices = listOf(
        LocalizedVoiceOption("alloy", R.string.voice_tone_neutral),
        LocalizedVoiceOption("echo", R.string.voice_tone_calm),
        LocalizedVoiceOption("fable", R.string.voice_tone_british),
        LocalizedVoiceOption("onyx", R.string.voice_tone_deep),
        LocalizedVoiceOption("nova", R.string.voice_tone_energetic),
        LocalizedVoiceOption("shimmer", R.string.voice_tone_clear),
    )

    // SiliconFlow 音色
    val siliconFlowVoices = listOf(
        LocalizedVoiceOption("alex", R.string.voice_tone_male),
        LocalizedVoiceOption("anna", R.string.voice_tone_female),
        LocalizedVoiceOption("bella", R.string.voice_tone_female),
        LocalizedVoiceOption("benjamin", R.string.voice_tone_male),
        LocalizedVoiceOption("charles", R.string.voice_tone_male),
        LocalizedVoiceOption("claire", R.string.voice_tone_female),
        LocalizedVoiceOption("david", R.string.voice_tone_male),
        LocalizedVoiceOption("diana", R.string.voice_tone_female),
    )

    // 阿里云音色 - 国内（普通话标准音色）
    val aliyunVoicesDomestic = listOf(
        LocalizedVoiceOption("Cherry", R.string.voice_desc_aliyun_cherry),
        LocalizedVoiceOption("Serena", R.string.voice_desc_aliyun_serena),
        LocalizedVoiceOption("Ethan", R.string.voice_desc_aliyun_ethan),
        LocalizedVoiceOption("Chelsie", R.string.voice_desc_aliyun_chelsie),
        LocalizedVoiceOption("Momo", R.string.voice_desc_aliyun_momo),
        LocalizedVoiceOption("Vivian", R.string.voice_desc_aliyun_vivian),
        LocalizedVoiceOption("Moon", R.string.voice_desc_aliyun_moon),
        LocalizedVoiceOption("Maia", R.string.voice_desc_aliyun_maia),
        LocalizedVoiceOption("Kai", R.string.voice_desc_aliyun_kai),
        LocalizedVoiceOption("Nofish", R.string.voice_desc_aliyun_nofish),
        LocalizedVoiceOption("Bella", R.string.voice_desc_aliyun_bella),
        LocalizedVoiceOption("Eldric Sage", R.string.voice_desc_aliyun_eldric_sage),
        LocalizedVoiceOption("Mia", R.string.voice_desc_aliyun_mia),
        LocalizedVoiceOption("Mochi", R.string.voice_desc_aliyun_mochi),
        LocalizedVoiceOption("Bellona", R.string.voice_desc_aliyun_bellona),
        LocalizedVoiceOption("Vincent", R.string.voice_desc_aliyun_vincent),
        LocalizedVoiceOption("Bunny", R.string.voice_desc_aliyun_bunny),
        LocalizedVoiceOption("Neil", R.string.voice_desc_aliyun_neil),
        LocalizedVoiceOption("Elias", R.string.voice_desc_aliyun_elias),
        LocalizedVoiceOption("Arthur", R.string.voice_desc_aliyun_arthur),
        LocalizedVoiceOption("Nini", R.string.voice_desc_aliyun_nini),
        LocalizedVoiceOption("Ebona", R.string.voice_desc_aliyun_ebona),
        LocalizedVoiceOption("Seren", R.string.voice_desc_aliyun_seren),
        LocalizedVoiceOption("Pip", R.string.voice_desc_aliyun_pip),
        LocalizedVoiceOption("Stella", R.string.voice_desc_aliyun_stella),
        LocalizedVoiceOption("Ryan", R.string.voice_desc_aliyun_ryan),
        LocalizedVoiceOption("Andre", R.string.voice_desc_aliyun_andre),
        LocalizedVoiceOption("Jennifer", R.string.voice_desc_aliyun_jennifer),
    )

    // 阿里云音色 - 国外（各国特色口音）
    val aliyunVoicesForeign = listOf(
        LocalizedVoiceOption("Aiden", R.string.voice_desc_aliyun_aiden),
        LocalizedVoiceOption("Katerina", R.string.voice_desc_aliyun_katerina),
        LocalizedVoiceOption("Bodega", R.string.voice_desc_aliyun_bodega),
        LocalizedVoiceOption("Sonrisa", R.string.voice_desc_aliyun_sonrisa),
        LocalizedVoiceOption("Alek", R.string.voice_desc_aliyun_alek),
        LocalizedVoiceOption("Dolce", R.string.voice_desc_aliyun_dolce),
        LocalizedVoiceOption("Sohee", R.string.voice_desc_aliyun_sohee),
        LocalizedVoiceOption("Ono Anna", R.string.voice_desc_aliyun_ono_anna),
        LocalizedVoiceOption("Lenn", R.string.voice_desc_aliyun_lenn),
        LocalizedVoiceOption("Emilien", R.string.voice_desc_aliyun_emilien),
        LocalizedVoiceOption("Radio Gol", R.string.voice_desc_aliyun_radio_gol),
    )

    // 阿里云音色 - 乡音（中国各地方言口音）
    val aliyunVoicesDialect = listOf(
        LocalizedVoiceOption("Jada", R.string.voice_desc_aliyun_jada),
        LocalizedVoiceOption("Dylan", R.string.voice_desc_aliyun_dylan),
        LocalizedVoiceOption("Li", R.string.voice_desc_aliyun_li),
        LocalizedVoiceOption("Marcus", R.string.voice_desc_aliyun_marcus),
        LocalizedVoiceOption("Roy", R.string.voice_desc_aliyun_roy),
        LocalizedVoiceOption("Peter", R.string.voice_desc_aliyun_peter),
        LocalizedVoiceOption("Sunny", R.string.voice_desc_aliyun_sunny),
        LocalizedVoiceOption("Eric", R.string.voice_desc_aliyun_eric),
        LocalizedVoiceOption("Rocky", R.string.voice_desc_aliyun_rocky),
        LocalizedVoiceOption("Kiki", R.string.voice_desc_aliyun_kiki),
    )

    // 根据平台和分类获取音色列表
    val voices = when (ttsPlatform) {
        "Minimax" -> minimaxVoices
        "OpenAI" -> openaiVoices
        "SiliconFlow" -> siliconFlowVoices
        "Aliyun" -> when (aliyunCategory) {
            0 -> aliyunVoicesDomestic
            1 -> aliyunVoicesForeign
            2 -> aliyunVoicesDialect
            else -> aliyunVoicesDomestic
        }
        else -> geminiVoices
    }
    
    val isDark = isSystemInDarkTheme()
    val dialogBg = if (isDark) Color.Black else Color.White
    val borderColor = if (isDark) Color(0xFF414141) else Color(0xFFF3F3F3)
    val contentColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val subtextColor = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF0D0D0D).copy(alpha = 0.6f)
    
    val topPadding = if (ttsPlatform == "Aliyun") 196.dp else 136.dp
    val bottomPadding = 92.dp

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
                .fillMaxHeight(0.8f)
                .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
            colors = CardDefaults.cardColors(
                containerColor = dialogBg
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // 音色列表（处于底层，占满全部空间）
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = topPadding,
                        bottom = bottomPadding,
                        start = 24.dp,
                        end = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(voices, key = { it.name }) { (voiceName, descriptionRes) ->
                        val isSelected = voiceName == selectedVoice
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedVoice = voiceName }
                                .border(
                                    1.dp,
                                    if (isSelected) (if (isDark) Color.White else Color.Black) else borderColor,
                                    RoundedCornerShape(12.dp)
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) 
                                    (if (isDark) Color(0xFF1A1A1A) else Color(0xFFF5F5F5))
                                else 
                                    Color.Transparent
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
                                        color = if (isSelected) contentColor else subtextColor
                                    )
                                    Text(
                                        text = stringResource(descriptionRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = subtextColor.copy(alpha = 0.7f)
                                    )
                                }
                                
                                if (isSelected) {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.Check,
                                        contentDescription = stringResource(R.string.voice_selected),
                                        tint = if (isDark) Color.White else Color.Black,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 顶栏透明渐变层 + 标题、当前选择及选项卡
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(topPadding)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to dialogBg,
                                    0.75f to dialogBg.copy(alpha = 0.96f),
                                    1.0f to Color.Transparent
                                )
                            )
                        )
                        .padding(top = 24.dp, start = 24.dp, end = 24.dp)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 标题
                        Text(
                            text = stringResource(R.string.voice_select_voice),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = contentColor
                        )

                        // 当前选择提示
                        Text(
                            text = stringResource(R.string.voice_current_selection, selectedVoice),
                            style = MaterialTheme.typography.bodyMedium,
                            color = subtextColor
                        )

                        // 阿里云音色分类选项卡（圆角样式）
                        if (ttsPlatform == "Aliyun") {
                            val categories = listOf(
                                stringResource(R.string.voice_category_domestic),
                                stringResource(R.string.voice_category_foreign),
                                stringResource(R.string.voice_category_dialect),
                            )
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF5F5F5)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    categories.forEachIndexed { index, title ->
                                        val isSelected = aliyunCategory == index
                                        val backgroundColor by animateColorAsState(
                                            if (isSelected) (if (isDark) Color.White else Color.Black) else Color.Transparent,
                                            animationSpec = tween(durationMillis = 300),
                                            label = "tabBackground"
                                        )
                                        val tabContentColor by animateColorAsState(
                                            if (isSelected) (if (isDark) Color.Black else Color.White) else subtextColor,
                                            animationSpec = tween(durationMillis = 300),
                                            label = "tabContent"
                                        )

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(backgroundColor)
                                                .clickable { aliyunCategory = index }
                                                .padding(vertical = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = title,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = tabContentColor,
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 底栏透明渐变层 + 确定按钮
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(bottomPadding)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    0.25f to dialogBg.copy(alpha = 0.96f),
                                    1.0f to dialogBg
                                )
                            )
                        )
                        .padding(bottom = 24.dp, start = 24.dp, end = 24.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                // 修复：如果 currentConfig 为 null，说明 effectiveConfig 是新创建的默认配置
                                // 需要检查 allConfigs 中是否已有该配置，如果没有则添加
                                val configToUpdate = currentConfig ?: effectiveConfig

                                // 更新当前配置的音色
                                val newConfig = configToUpdate.copy(
                                    voiceName = selectedVoice,
                                    updatedAt = System.currentTimeMillis()
                                )

                                // 更新列表：如果配置已存在则更新，否则添加
                                val configExists = allConfigs.any { it.id == newConfig.id }
                                val newConfigs = if (configExists) {
                                    allConfigs.map {
                                        if (it.id == newConfig.id) newConfig else it
                                    }
                                } else {
                                    // 配置不存在，添加到列表
                                    allConfigs + newConfig
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
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = contentColor,
                            contentColor = dialogBg
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.action_confirm),
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
