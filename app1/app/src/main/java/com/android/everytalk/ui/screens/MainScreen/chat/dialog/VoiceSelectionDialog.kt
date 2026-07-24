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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.everytalk.data.DataClass.VoiceBackendConfig
import com.android.everytalk.statecontroller.AppViewModel
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.lazy.rememberLazyListState
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
    var aliyunCategory by remember { mutableIntStateOf(0) } // 0=国内, 1=国外, 2=乡音
    
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

    // Minimax 音色
    val minimaxVoices = listOf(
        // ⭐ 强烈推荐 - 最像真人
        "Chinese (Mandarin)_Warm_Bestie" to "⭐ 温暖闺蜜 - 亲切暖风、生活化",
        "Chinese (Mandarin)_Gentle_Senior" to "⭐ 温柔学姐 - 自然柔和",
        "Chinese (Mandarin)_Sweet_Lady" to "⭐ 甜美女声 - 自然甜、真人感强",
        
        // 🎙️ 沉稳自然（专业主播风格）
        "Chinese (Mandarin)_Mature_Woman" to "傲娇御姐 - 稳定、有存在感",
        "female-yujie" to "御姐音色 - 稳定自然、略带磁性",
        
        // 🎧 亲和真实（朋友聊天风格）
        "Chinese (Mandarin)_Warm_Girl" to "温暖少女 - 亲和自然",
        "Chinese (Mandarin)_Crisp_Girl" to "清脆少女 - 清新自然",
        "qiaopi_mengmei" to "俏皮萌妹 - 活泼可爱",
        
        // 🎙️ 男声推荐
        "Chinese (Mandarin)_Gentleman" to "温润男声 - 自然成熟",
        "Chinese (Mandarin)_Lyrical_Voice" to "抒情男声 - 情感丰富",
        "male-qn-jingying" to "精英青年 - 自然稳定",
        
        // 其他音色
        "male-qn-qingse" to "青涩男声",
        "female-shaonv" to "少女音"
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
                    items(voices) { (voiceName, description) ->
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
                                        text = description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = subtextColor.copy(alpha = 0.7f)
                                    )
                                }
                                
                                if (isSelected) {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.Check,
                                        contentDescription = "已选择",
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
                            text = "选择音色",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = contentColor
                        )

                        // 当前选择提示
                        Text(
                            text = "当前: $selectedVoice",
                            style = MaterialTheme.typography.bodyMedium,
                            color = subtextColor
                        )

                        // 阿里云音色分类选项卡（圆角样式）
                        if (ttsPlatform == "Aliyun") {
                            val categories = listOf("🇨🇳 国内", "🌍 国外", "🏠 乡音")
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
