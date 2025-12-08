package com.android.everytalk.ui.screens.MainScreen.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
    var aliyunCategory by remember { mutableStateOf(0) } // 0=国内, 1=国外, 2=乡音
    
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

    // 阿里云音色 - 国内（普通话标准音色）
    val aliyunVoicesDomestic = listOf(
        "Cherry" to "芊悦 - 阳光积极、亲切自然小姐姐",
        "Serena" to "苏瑶 - 温柔小姐姐",
        "Ethan" to "晨煦 - 阳光、温暖、活力、朝气",
        "Chelsie" to "千雪 - 二次元虚拟女友",
        "Momo" to "茉兔 - 撒娇搞怪，逗你开心",
        "Vivian" to "十三 - 拽拽的、可爱的小暴躁",
        "Moon" to "月白 - 率性帅气",
        "Maia" to "四月 - 知性与温柔的碰撞",
        "Kai" to "凯 - 耳朵的一场SPA",
        "Nofish" to "不吃鱼 - 不会翘舌音的设计师",
        "Bella" to "萌宝 - 喝酒不打醉拳的小萝莉",
        "Eldric Sage" to "沧明子 - 沉稳睿智的老者",
        "Mia" to "乖小妹 - 温顺如春水，乖巧如初雪",
        "Mochi" to "沙小弥 - 聪明伶俐的小大人",
        "Bellona" to "燕铮莺 - 声音洪亮，吐字清晰",
        "Vincent" to "田叔 - 独特的沙哑烟嗓",
        "Bunny" to "萌小姬 - 萌属性爆棚的小萝莉",
        "Neil" to "阿闻 - 专业的新闻主持人",
        "Elias" to "墨讲师 - 知识讲解专家",
        "Arthur" to "徐大爷 - 质朴嗓音讲奇闻异事",
        "Nini" to "邻家妹妹 - 糯米糍一样又软又黏",
        "Ebona" to "诡婆婆 - 幽暗恐惧风格",
        "Seren" to "小婉 - 温和舒缓助眠",
        "Pip" to "顽屁小孩 - 调皮捣蛋充满童真",
        "Stella" to "少女阿月 - 甜到发腻的迷糊少女",
        "Ryan" to "甜茶 - 节奏拉满，戏感炸裂",
        "Andre" to "安德雷 - 声音磁性，自然舒服",
        "Jennifer" to "詹妮弗 - 品牌级、电影质感般美语女声"
    )

    // 阿里云音色 - 国外（各国特色口音）
    val aliyunVoicesForeign = listOf(
        "Aiden" to "艾登 - 精通厨艺的美语大男孩",
        "Katerina" to "卡捷琳娜 - 御姐音色，韵律回味十足",
        "Bodega" to "博德加 - 热情的西班牙大叔",
        "Sonrisa" to "索尼莎 - 热情开朗的拉美大姐",
        "Alek" to "阿列克 - 战斗民族的冷暖交织",
        "Dolce" to "多尔切 - 慵懒的意大利大叔",
        "Sohee" to "素熙 - 温柔开朗的韩国欧尼",
        "Ono Anna" to "小野杏 - 鬼灵精怪的日本青梅竹马",
        "Lenn" to "莱恩 - 理性叛逆的德国青年",
        "Emilien" to "埃米尔安 - 浪漫的法国大哥哥",
        "Radio Gol" to "拉迪奥·戈尔 - 足球诗人（葡萄牙语风格）"
    )

    // 阿里云音色 - 乡音（中国各地方言口音）
    val aliyunVoicesDialect = listOf(
        "Jada" to "上海-阿珍 - 风风火火的沪上阿姐",
        "Dylan" to "北京-晓东 - 北京胡同里长大的少年",
        "Li" to "南京-老李 - 耐心的瑜伽老师（南京话）",
        "Marcus" to "陕西-秦川 - 面宽话短，老陕的味道",
        "Roy" to "闽南-阿杰 - 诙谐直爽的台湾哥仔",
        "Peter" to "天津-李彼得 - 天津相声，专业捧哏",
        "Sunny" to "四川-晴儿 - 甜到你心里的川妹子",
        "Eric" to "四川-程川 - 跳脱市井的四川男子",
        "Rocky" to "粤语-阿强 - 幽默风趣，在线陪聊",
        "Kiki" to "粤语-阿清 - 甜美的港妹闺蜜"
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
                
                // 阿里云音色分类选项卡（圆角样式）
                if (ttsPlatform == "Aliyun") {
                    val categories = listOf("🇨🇳 国内", "🌍 国外", "🏠 乡音")
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    animationSpec = tween(durationMillis = 300),
                                    label = "tabBackground"
                                )
                                val contentColor by animateColorAsState(
                                    if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                        color = contentColor,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                    }
                }
                
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