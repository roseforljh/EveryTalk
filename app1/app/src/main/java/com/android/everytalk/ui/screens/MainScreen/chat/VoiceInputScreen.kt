package com.android.everytalk.ui.screens.MainScreen.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.material3.Divider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.network.VoiceChatSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.android.everytalk.statecontroller.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceInputScreen(
    onClose: () -> Unit,
    selectedApiConfig: ApiConfig? = null,
    viewModel: AppViewModel? = null
) {
    // 防抖状态：防止快速连点导致二次 popBackStack 黑屏
    var isClosing by remember { mutableStateOf(false) }
    var showTtsSettingsDialog by remember { mutableStateOf(false) }
    var showSttChatSettingsDialog by remember { mutableStateOf(false) }
    var showVoiceSelectionDialog by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    
    // 🎤 实时音量状态（0.0 ~ 1.0）
    var currentVolume by remember { mutableStateOf(0f) }
    
    // 📝 对话状态
    var userText by remember { mutableStateOf("") }
    var assistantText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var showTtsQuotaWarning by remember { mutableStateOf(false) }

    // 语音会话：点击左下角麦克风后启动/停止
    val coroutineScope = rememberCoroutineScope()
    var voiceChatSession by remember { mutableStateOf<VoiceChatSession?>(null) }
    val context = LocalContext.current

    // 启动录音会话（新版：使用VoiceChatSession）
    val startRecordingSession: () -> Unit = remember(selectedApiConfig, viewModel) {
        {
            val prefs = context.getSharedPreferences("voice_settings", android.content.Context.MODE_PRIVATE)
            
            // 从 SharedPreferences 读取语音 API 地址
            val providerApiUrl = prefs.getString("voice_base_url", "")?.trim() ?: ""
            val ttsPlatform = prefs.getString("voice_platform", "Gemini") ?: "Gemini"
            
            // 检查 BuildConfig.VOICE_BACKEND_URL 是否配置
            if (com.android.everytalk.BuildConfig.VOICE_BACKEND_URL.isEmpty()) {
                android.widget.Toast.makeText(context, "未配置语音网关地址(VOICE_BACKEND_URL)", android.widget.Toast.LENGTH_LONG).show()
            } else if (ttsPlatform == "Minimax" && providerApiUrl.isEmpty()) {
                android.widget.Toast.makeText(context, "Minimax 平台必须填写语音 API 地址", android.widget.Toast.LENGTH_LONG).show()
            } else {
                var apiKey = (selectedApiConfig?.key ?: "").trim()
                
                // 覆盖为"语音设置"里按平台保存的Key（若存在）
                try {
                    val platform = prefs.getString("voice_platform", selectedApiConfig?.provider ?: "Gemini") ?: "Gemini"
                    val keyOverride = prefs.getString("voice_key_${platform}", null)?.trim()
                    if (!keyOverride.isNullOrEmpty()) {
                        apiKey = keyOverride
                    }
                } catch (_: Throwable) {}
                
                if (apiKey.isEmpty()) {
                    android.util.Log.w("VoiceInputScreen", "API Key is empty, cannot start voice chat session.")
                    android.widget.Toast.makeText(context, "请先配置 API Key", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                // 获取当前对话历史（优化：只取最近3轮，减少处理时间）
                val chatHistory = mutableListOf<Pair<String, String>>()
                viewModel?.stateHolder?.let { holder ->
                    holder.messages.takeLast(6).forEach { msg ->  // 3轮对话=6条消息
                        when (msg.sender) {
                            Sender.User -> chatHistory.add("user" to msg.text)
                            Sender.AI -> chatHistory.add("assistant" to msg.text)
                            else -> {}
                        }
                    }
                }
                
                // 获取系统提示词，并注入语音模式专用指令
                val baseSystemPrompt = viewModel?.stateHolder?.let { holder ->
                    val convId = holder._currentConversationId.value
                    holder.systemPrompts[convId] ?: ""
                } ?: ""
                
                val voiceModePrompt = """
                    【重要指令】你现在正在进行语音通话。请完全扮演一个真实的人类，用最自然、最口语化的语气交流。
                    回复必须极度简练、直接，避免长篇大论和书面语。
                    禁止使用Markdown格式、列表或复杂结构。
                    像朋友聊天一样说话，尽量缩短回复长度以优化语音体验。
                """.trimIndent()
                
                val systemPrompt = if (baseSystemPrompt.isNotEmpty()) {
                    "$baseSystemPrompt\n\n$voiceModePrompt"
                } else {
                    voiceModePrompt
                }
                
                val voiceName = prefs.getString("voice_name", "Kore") ?: "Kore"
                val chatModel = prefs.getString("voice_chat_model", "")?.trim()?.takeIf { it.isNotEmpty() }
                val ttsPlatform = prefs.getString("voice_platform", "Gemini") ?: "Gemini"
                
                // STT/Chat 配置
                val sttChatPlatform = prefs.getString("stt_chat_platform", "Google") ?: "Google"
                val sttChatApiUrl = prefs.getString("stt_chat_api_url", "")?.trim() ?: ""
                val sttChatModel = prefs.getString("stt_chat_model", "")?.trim() ?: ""
                val sttChatApiKey = when (sttChatPlatform) {
                    "OpenAI" -> prefs.getString("stt_chat_key_OpenAI", "") ?: ""
                    else -> prefs.getString("stt_chat_key_Google", "") ?: ""
                }.trim()

                // 创建新的语音对话会话
                val session = VoiceChatSession(
                    providerApiUrl = providerApiUrl,
                    apiKey = apiKey,
                    chatHistory = chatHistory,
                    systemPrompt = systemPrompt,
                    voiceName = voiceName,
                    ttsPlatform = ttsPlatform,
                    chatModel = chatModel,
                    sttChatPlatform = sttChatPlatform,
                    sttChatApiUrl = sttChatApiUrl,
                    sttChatApiKey = sttChatApiKey,
                    sttChatModel = sttChatModel,
                    onVolumeChanged = { volume ->
                        currentVolume = volume
                    },
                    onTranscriptionReceived = { text ->
                        userText = text
                    },
                    onResponseReceived = { text ->
                        assistantText = text
                    }
                )
                
                voiceChatSession = session
                isRecording = true
                userText = ""
                assistantText = ""
                
                // 启动录音
                coroutineScope.launch {
                    try {
                        session.startRecording()
                    } catch (t: Throwable) {
                        android.util.Log.e("VoiceInputScreen", "Failed to start recording", t)
                        isRecording = false
                        voiceChatSession = null
                    }
                }
                }
            }
        }
    }

    // 录音权限请求
    val requestAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecordingSession.invoke()
        } else {
            android.util.Log.w("VoiceInputScreen", "RECORD_AUDIO permission denied by user.")
        }
    }
     
    // 拦截系统返回键
    BackHandler(enabled = true) {
        if (isRecording) {
            // 录音中：取消本次语音
            val session = voiceChatSession
            isRecording = false
            currentVolume = 0f
            userText = ""
            assistantText = ""
            
            if (session != null) {
                coroutineScope.launch {
                    try {
                        session.cancelRecording()
                    } catch (t: Throwable) {
                        android.util.Log.e("VoiceInputScreen", "Cancel recording on back pressed failed", t)
                    } finally {
                        voiceChatSession = null
                    }
                }
            }
        } else {
            // 非录音中：正常关闭
            if (!isClosing) {
                isClosing = true
                onClose()
            }
        }
    }

    // 生命周期管理：组件销毁时强制释放资源
    DisposableEffect(Unit) {
        onDispose {
            voiceChatSession?.forceRelease()
        }
    }

    // 主题适配
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color.Black else MaterialTheme.colorScheme.background
    val contentColor = if (isDarkTheme) Color.White else MaterialTheme.colorScheme.onBackground
    val waveCircleColor = if (isDarkTheme) Color.White else MaterialTheme.colorScheme.primary
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = backgroundColor,
        topBar = {
            TopAppBar(
                title = { },
                actions = {
                    // STT/Chat 设置按钮 (左)
                    IconButton(onClick = { showSttChatSettingsDialog = true }) {
                        Icon(Icons.Default.Build, contentDescription = "模型配置", tint = contentColor)
                    }
                    // 音色选择按钮 (中)
                    IconButton(onClick = { showVoiceSelectionDialog = true }) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = "选择音色", tint = contentColor)
                    }
                    // TTS 设置按钮 (右)
                    IconButton(onClick = { showTtsSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "语音设置", tint = contentColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = Color.Transparent,
                contentColor = contentColor,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                // 左侧麦克风按钮 - 圆形背景
                Box(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .size(56.dp)
                        .background(
                            color = if (isRecording) Color(0xFF8B4545) else Color(0xFF3A3A3A),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = {
                            // 单击左下角麦克风：开始/结束语音模式（先校验运行时权限）
                            if (!isRecording) {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    startRecordingSession.invoke()
                                } else {
                                    requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            } else {
                                // 停止录音并处理完整的语音对话流程
                                val session = voiceChatSession
                                isRecording = false
                                currentVolume = 0f
                                isProcessing = true
                                
                                if (session != null) {
                                    coroutineScope.launch {
                                        try {
                                            // 停止录音并处理（STT → Chat → TTS）
                                            val result = session.stopRecordingAndProcess()
                                            
                                            // 保存对话到当前会话消息列表
                                            viewModel?.let { vm ->
                                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    // 添加用户消息
                                                    val userMessage = Message(
                                                        text = result.userText,
                                                        sender = Sender.User,
                                                        timestamp = System.currentTimeMillis()
                                                    )
                                                    vm.stateHolder.messages.add(userMessage)
                                                    
                                                    // 添加AI回复
                                                    val aiMessage = Message(
                                                        text = result.assistantText,
                                                        sender = Sender.AI,
                                                        timestamp = System.currentTimeMillis(),
                                                        contentStarted = true  // 标记内容已完成
                                                    )
                                                    vm.stateHolder.messages.add(aiMessage)
                                                    
                                                    // 标记对话为已修改
                                                    vm.stateHolder.isTextConversationDirty.value = true
                                                    
                                                    // 检查是否有音频
                                                    val hasAudio = result.audioData.isNotEmpty()
                                                    android.util.Log.i("VoiceInputScreen", "Voice chat completed - User: '${result.userText}', AI: '${result.assistantText}', HasAudio: $hasAudio")
                                                    
                                                    // 如果没有音频，显示TTS配额警告
                                                    if (!hasAudio) {
                                                        showTtsQuotaWarning = true
                                                    }
                                                    
                                                    // 立即保存到历史记录
                                                    vm.saveCurrentChatToHistory(forceSave = true, isImageGeneration = false)
                                                }
                                            }
                                            
                                            android.util.Log.i("VoiceInputScreen", "Voice chat saved to history")
                                            
                                            // 如果显示了配额警告，3秒后自动隐藏
                                            if (showTtsQuotaWarning) {
                                                kotlinx.coroutines.delay(3000)
                                                showTtsQuotaWarning = false
                                            }
                                        } catch (t: Throwable) {
                                            android.util.Log.e("VoiceInputScreen", "Voice chat failed", t)
                                            userText = ""
                                            assistantText = "处理失败: ${t.message}"
                                        } finally {
                                            voiceChatSession = null
                                            isProcessing = false
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = if (isRecording) "停止录音" else "开始录音",
                            modifier = Modifier.size(28.dp),
                            tint = if (isRecording) Color(0xFFFF8A8A) else Color.White
                        )
                    }
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // 右侧按钮（关闭/取消） - 圆形背景
                Box(
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(56.dp)
                        .background(
                            color = Color(0xFF3A3A3A),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = {
                            if (isRecording) {
                                // 录音中：取消本次语音
                                val session = voiceChatSession
                                isRecording = false
                                currentVolume = 0f
                                userText = ""
                                assistantText = ""
                                
                                if (session != null) {
                                    coroutineScope.launch {
                                        try {
                                            session.cancelRecording()
                                        } catch (t: Throwable) {
                                            android.util.Log.e("VoiceInputScreen", "Cancel recording failed", t)
                                        } finally {
                                            voiceChatSession = null
                                        }
                                    }
                                }
                            } else {
                                // 非录音中：关闭页面
                                if (!isClosing) {
                                    isClosing = true
                                    onClose()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.Delete else Icons.Default.Close,
                            contentDescription = if (isRecording) "取消本次语音" else "关闭",
                            modifier = Modifier.size(28.dp),
                            tint = Color.White
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                // 中央波形动画
                VoiceWaveAnimation(
                    isRecording = isRecording,
                    color = waveCircleColor,
                    currentVolume = currentVolume
                )
                
                // 显示处理状态和文字
                if (isProcessing) {
                    Spacer(modifier = Modifier.height(32.dp))
                    CircularProgressIndicator(
                        color = waveCircleColor,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "正在处理...",
                        color = contentColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                // TTS配额警告提示
                if (showTtsQuotaWarning) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFF9800).copy(alpha = 0.9f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Info,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "TTS配额已用完，仅显示文字",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                
                // 显示识别的文字和AI回复
                if (userText.isNotEmpty() || assistantText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .background(
                                color = if (isDarkTheme) Color(0xFF2A2A2A) else Color(0xFFF5F5F5),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (userText.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "你说：",
                                    color = contentColor.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = userText,
                                    color = contentColor,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        
                        if (assistantText.isNotEmpty()) {
                            if (userText.isNotEmpty()) {
                                Divider(
                                    color = contentColor.copy(alpha = 0.2f),
                                    thickness = 1.dp
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "AI 回复：",
                                    color = contentColor.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = assistantText,
                                    color = contentColor,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    
    // TTS 设置对话框
    if (showTtsSettingsDialog) {
        VoiceSettingsDialog(
            selectedApiConfig = selectedApiConfig,
            onDismiss = { showTtsSettingsDialog = false }
        )
    }

    // STT/Chat 设置对话框
    if (showSttChatSettingsDialog) {
        SttChatSettingsDialog(
            onDismiss = { showSttChatSettingsDialog = false }
        )
    }
    
    // 音色选择对话框
    if (showVoiceSelectionDialog) {
        VoiceSelectionDialog(
            onDismiss = { showVoiceSelectionDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSettingsDialog(
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
    val savedBaseUrl = remember { prefs.getString("voice_base_url", "") ?: "" }
    val savedChatModel = remember { prefs.getString("voice_chat_model", "") ?: "" }

    // 根据平台解析Key的函数：仅从本地保存中读取，首次安装时为空
    fun resolveKeyFor(platform: String): String {
        val fromPrefs = when (platform) {
            "OpenAI" -> savedKeyOpenAI
            "Minimax" -> savedKeyMinimax
            else -> savedKeyGemini
        }.trim()
        return fromPrefs
    }

    var selectedPlatform by remember {
        mutableStateOf(savedPlatform ?: "Gemini")
    }
    var apiKey by remember {
        mutableStateOf(resolveKeyFor(selectedPlatform))
    }
    var baseUrl by remember { mutableStateOf(savedBaseUrl) }
    var chatModel by remember { mutableStateOf(savedChatModel) }
    var expanded by remember { mutableStateOf(false) }
    val platforms = listOf("Gemini", "OpenAI", "Minimax")
    
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
                                        // 实时切换到对应平台的Key（优先本地；否则回退到selectedApiConfig；否则空）
                                        apiKey = resolveKeyFor(platform)
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
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如 https://api.minimaxi.com/v1/t2a_v2") },
                        supportingText = {
                            if (baseUrl.isNotEmpty() && !baseUrl.startsWith("http")) {
                                Text("请填写完整的 http(s) 地址", color = MaterialTheme.colorScheme.error)
                            } else if (selectedPlatform == "Minimax" && baseUrl.isEmpty()) {
                                Text("Minimax 平台必须填写 API 地址", color = MaterialTheme.colorScheme.error)
                            } else {
                                Text("大模型厂商的 API 地址 (可选)", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                // 语音模型名称输入框
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "TTS 模型名称",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = chatModel,
                        onValueChange = { chatModel = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如 gemini-flash-lite-latest") },
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
                            // 保存用户选择的平台和对应Key
                            runCatching {
                                val editor = prefs.edit()
                                editor.putString("voice_platform", selectedPlatform)
                                when (selectedPlatform) {
                                    "OpenAI" -> editor.putString("voice_key_OpenAI", apiKey)
                                    "Minimax" -> editor.putString("voice_key_Minimax", apiKey)
                                    else -> editor.putString("voice_key_Gemini", apiKey)
                                }
                                if (baseUrl.isNotBlank()) {
                                    editor.putString("voice_base_url", baseUrl.trim())
                                }
                                editor.putString("voice_chat_model", chatModel.trim())
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SttChatSettingsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("voice_settings", android.content.Context.MODE_PRIVATE) }
    
    val savedPlatform = remember { prefs.getString("stt_chat_platform", "Google") ?: "Google" }
    val savedKeyGoogle = remember { prefs.getString("stt_chat_key_Google", "") ?: "" }
    val savedKeyOpenAI = remember { prefs.getString("stt_chat_key_OpenAI", "") ?: "" }
    val savedApiUrl = remember { prefs.getString("stt_chat_api_url", "") ?: "" }
    val savedModel = remember { prefs.getString("stt_chat_model", "") ?: "" }

    fun resolveKeyFor(platform: String): String {
        return when (platform) {
            "OpenAI" -> savedKeyOpenAI
            else -> savedKeyGoogle
        }.trim()
    }

    var selectedPlatform by remember { mutableStateOf(savedPlatform) }
    var apiKey by remember { mutableStateOf(resolveKeyFor(selectedPlatform)) }
    var apiUrl by remember { mutableStateOf(savedApiUrl) }
    var model by remember { mutableStateOf(savedModel) }
    var expanded by remember { mutableStateOf(false) }
    
    val platforms = listOf("Google", "OpenAI")
    
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
                    text = "STT & Chat 设置",
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

                // 模型名称
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "模型名称",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如 gpt-4o-mini") },
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
                                editor.putString("stt_chat_platform", selectedPlatform)
                                when (selectedPlatform) {
                                    "OpenAI" -> editor.putString("stt_chat_key_OpenAI", apiKey)
                                    else -> editor.putString("stt_chat_key_Google", apiKey)
                                }
                                editor.putString("stt_chat_api_url", apiUrl.trim())
                                editor.putString("stt_chat_model", model.trim())
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSelectionDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("voice_settings", android.content.Context.MODE_PRIVATE) }
    val savedVoice = remember { prefs.getString("voice_name", "Kore") ?: "Kore" }
    val ttsPlatform = remember { prefs.getString("voice_platform", "Gemini") ?: "Gemini" }
    
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

    val voices = when (ttsPlatform) {
        "Minimax" -> minimaxVoices
        "OpenAI" -> openaiVoices
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
                            editor.putString("voice_name", selectedVoice)
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

@Composable
fun VoiceWaveAnimation(
    isRecording: Boolean,
    color: Color,
    currentVolume: Float = 0f,
    modifier: Modifier = Modifier
) {
    // 形变振幅：用于波形的不规则形变（保持原有逻辑）
    var amplitudeTarget by remember { mutableStateOf(0.5f) }
    val amplitude by animateFloatAsState(
        targetValue = amplitudeTarget,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "amplitudeSmoothing"
    )
    
    // 连续相位：基于帧时间推进，不重启，避免周期性"卡顿"
    var phase by remember { mutableStateOf(0f) }
    
    // 🎤 音量缩放：根据实时音量大小控制整体缩放（新增）
    var volumeScaleTarget by remember { mutableStateOf(1f) }
    val volumeScale by animateFloatAsState(
        targetValue = volumeScaleTarget,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "volumeScaleSmoothing"
    )
    
    // 🔍 使用 rememberUpdatedState 获取最新的 currentVolume 值
    val latestVolume by rememberUpdatedState(currentVolume)
    
    // 连续帧驱动：形变振幅 + 音量缩放
    LaunchedEffect(isRecording) {
        if (isRecording) {
            var last = withFrameNanos { it }
            while (isRecording) {  // 改为检查 isRecording 状态
                val now = withFrameNanos { it }
                val dt = (now - last) / 1_000_000_000f // s
                last = now

                // 形变振幅：叠加两个缓慢正弦作为包络（保持原有逻辑）
                val tSec = now / 1_000_000_000f
                val a = kotlin.math.sin(2f * PI.toFloat() * (tSec / 6f))
                val b = kotlin.math.sin(2f * PI.toFloat() * (tSec / 7.8f))
                val env = ((a + b) * 0.5f * 0.5f) + 0.5f // 归一到 0..1 并压缩
                amplitudeTarget = 0.55f + 0.45f * env

                // 🎤 音量缩放：根据实时麦克风音量调整（1.0 ~ 1.3，最小为默认大小）
                val newScale = 1f + latestVolume * 0.3f
                if (volumeScaleTarget != newScale) {
                    android.util.Log.d("VoiceWaveAnimation", "🎨 Scale update: volume=$latestVolume, scale=$newScale")
                }
                volumeScaleTarget = newScale

                // 匀速相位推进（不重启），保持连续
                val omega = 0.8f // rad/s
                phase += omega * dt
            }
        }
        
        // 退场动画：无论如何都执行（录音停止后）
        if (!isRecording) {
            val startAmplitude = amplitudeTarget
            val startVolumeScale = volumeScaleTarget
            val duration = 0.5f
            var acc = 0f
            var last = withFrameNanos { it }
            while (acc < duration) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1_000_000_000f
                last = now
                acc += dt
                // 使用缓动函数使过渡更自然
                val rawProgress = (acc / duration).coerceIn(0f, 1f)
                val easedProgress = rawProgress * rawProgress * (3f - 2f * rawProgress) // smoothstep
                amplitudeTarget = startAmplitude + (0.5f - startAmplitude) * easedProgress
                volumeScaleTarget = startVolumeScale + (1f - startVolumeScale) * easedProgress
            }
        }
    }
    
    // 基础大小和最终缩放：整体大小 = 基础大小 × 音量缩放
    val baseSize = 120.dp
    val finalScale = if (isRecording) volumeScale else 1f
    
    Canvas(
        modifier = modifier.size(baseSize * 1.5f)
    ) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radius = (baseSize.toPx() / 2) * finalScale
        
        if (isRecording) {
            // 绘制不规则波形圆
            drawIrregularCircle(
                centerX = centerX,
                centerY = centerY,
                radius = radius,
                color = color,
                phase = phase,
                amplitude = amplitude
            )
        } else {
            // 绘制普通圆形
            drawCircle(
                color = color,
                radius = radius,
                center = Offset(centerX, centerY)
            )
        }
    }
}

fun DrawScope.drawIrregularCircle(
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: Color,
    phase: Float,
    amplitude: Float
) {
    val path = Path()
    // 更高采样，适配高刷新，边缘更丝滑
    val points = 240
    val angleStep = (2 * PI / points).toFloat()

    var angle = 0f
    // 使用 phase 作为持续旋转项，避免任何周期重启
    val p = phase
    for (i in 0 until points) {
        // 更温和的多波叠加，形变不过分，同时保持“有生命力”
        val wave1 = kotlin.math.sin(angle * 2f + p * 1.4f) * amplitude * 0.10f
        val wave2 = kotlin.math.sin(angle * 3f - p * 1.0f) * amplitude * 0.07f
        val wave3 = kotlin.math.cos(angle * 4f + p * 1.2f) * amplitude * 0.05f
        val distortion = (wave1 + wave2 + wave3) * radius
        val currentRadius = radius + distortion

        val x = centerX + currentRadius * kotlin.math.cos(angle)
        val y = centerY + currentRadius * kotlin.math.sin(angle)

        if (i == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }

        angle += angleStep
    }

    path.close()
    drawPath(path, color)
}