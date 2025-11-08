package com.android.everytalk.ui.screens.MainScreen.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import com.android.everytalk.data.network.GeminiLiveSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceInputScreen(
    onClose: () -> Unit,
    selectedApiConfig: ApiConfig? = null
) {
    // 防抖状态：防止快速连点导致二次 popBackStack 黑屏
    var isClosing by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }

    // 语音会话：点击左下角麦克风后启动/停止
    val coroutineScope = rememberCoroutineScope()
    var liveSession by remember { mutableStateOf<GeminiLiveSession?>(null) }
    val context = LocalContext.current

    // 启动录音会话（已含 API Key 判空保护）
    val startRecordingSession = remember(selectedApiConfig) {
        {
            val baseUrl = (selectedApiConfig?.address ?: selectedApiConfig?.provider ?: "").ifBlank { "http://127.0.0.1:8000" }
            var apiKey = (selectedApiConfig?.key ?: "").trim()
            // 覆盖为“语音设置”里按平台保存的Key（若存在）
            try {
                val prefs = context.getSharedPreferences("voice_settings", android.content.Context.MODE_PRIVATE)
                val platform = prefs.getString("voice_platform", selectedApiConfig?.provider ?: "Gemini") ?: "Gemini"
                val keyOverride = prefs.getString("voice_key_${platform}", null)?.trim()
                if (!keyOverride.isNullOrEmpty()) {
                    apiKey = keyOverride
                }
            } catch (_: Throwable) {
                // 忽略本地读取异常，回退到 selectedApiConfig.key
            }
            if (apiKey.isEmpty()) {
                android.util.Log.w("VoiceInputScreen", "Gemini API Key is empty, cannot start live session.")
            } else {
                val session = GeminiLiveSession(baseUrl = baseUrl, apiKey = apiKey)
                liveSession = session
                isRecording = true
                coroutineScope.launch {
                    try {
                        session.start()
                    } catch (t: Throwable) {
                        android.util.Log.e("VoiceInputScreen", "Failed to start recording/session", t)
                        isRecording = false
                        liveSession = null
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
            startRecordingSession()
        } else {
            android.util.Log.w("VoiceInputScreen", "RECORD_AUDIO permission denied by user.")
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
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置", tint = contentColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = Color.Transparent,
                contentColor = contentColor
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
                                startRecordingSession()
                            } else {
                                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            // 停止录音并发送给后端，然后播放返回的24k音频
                            val session = liveSession
                            isRecording = false
                            if (session != null) {
                                coroutineScope.launch {
                                    try {
                                        session.stopAndSendAndPlay()
                                    } catch (t: Throwable) {
                                        android.util.Log.e("VoiceInputScreen", "Failed to stop/send/play", t)
                                    } finally {
                                        liveSession = null
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Icon(
                        if (isRecording) Icons.Default.Mic else Icons.Default.Mic,
                        contentDescription = if (isRecording) "停止录音" else "开始录音",
                        modifier = Modifier.size(32.dp),
                        tint = if (isRecording) Color.Red else contentColor
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        if (!isClosing) {
                            isClosing = true
                            onClose()
                        }
                    }
                ) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(32.dp))
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
            VoiceWaveAnimation(
                isRecording = isRecording,
                color = waveCircleColor
            )
        }
    }
    
    // 设置对话框
    if (showSettingsDialog) {
        VoiceSettingsDialog(
            selectedApiConfig = selectedApiConfig,
            onDismiss = { showSettingsDialog = false }
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

    // 根据平台解析Key的函数：仅从本地保存中读取，首次安装时为空
    fun resolveKeyFor(platform: String): String {
        val fromPrefs = when (platform) {
            "OpenAI" -> savedKeyOpenAI
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
    var expanded by remember { mutableStateOf(false) }
    val platforms = listOf("Gemini", "OpenAI")
    
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
                    text = "语音设置",
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
                                if (selectedPlatform == "OpenAI") {
                                    editor.putString("voice_key_OpenAI", apiKey)
                                } else {
                                    editor.putString("voice_key_Gemini", apiKey)
                                }
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

@Composable
fun VoiceWaveAnimation(
    isRecording: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    // 模拟音频振幅（实际应用中应从麦克风获取）—使用平滑动画避免跳变卡顿
    var amplitudeTarget by remember { mutableStateOf(0.5f) }
    val amplitude by animateFloatAsState(
        targetValue = amplitudeTarget,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "amplitudeSmoothing"
    )
    
    // 连续相位：基于帧时间推进，不重启，避免周期性“卡顿”
    var phase by remember { mutableStateOf(0f) }
    
    // 连续帧驱动振幅（每帧更新），消除跳变带来的“卡顿”观感
    LaunchedEffect(isRecording) {
        if (isRecording) {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1_000_000_000f // s
                last = now

                // 叠加两个缓慢正弦作为包络，避免机械感与重复感（不取模，持续推进）
                val tSec = now / 1_000_000_000f
                val a = kotlin.math.sin(2f * PI.toFloat() * (tSec / 6f))
                val b = kotlin.math.sin(2f * PI.toFloat() * (tSec / 7.8f))
                val env = ((a + b) * 0.5f * 0.5f) + 0.5f // 归一到 0..1 并压缩
                amplitudeTarget = 0.55f + 0.45f * env

                // 匀速相位推进（不重启），保持连续
                val omega = 0.8f // rad/s
                phase += omega * dt
            }
        } else {
            // 平滑回落到默认值（~0.3s）
            val start = amplitudeTarget
            val duration = 0.3f
            var acc = 0f
            var last = withFrameNanos { it }
            while (acc < duration) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1_000_000_000f
                last = now
                acc += dt
                val p = (acc / duration).coerceIn(0f, 1f)
                amplitudeTarget = start + (0.5f - start) * p
            }
        }
    }
    
    // 基础大小和缩放
    val baseSize = 120.dp
    val scale by animateFloatAsState(
        targetValue = if (isRecording) 1f + amplitude * 0.3f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessVeryLow
        ),
        label = "scaleAnimation"
    )
    
    Canvas(
        modifier = modifier.size(baseSize * 1.5f)
    ) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radius = (baseSize.toPx() / 2) * scale
        
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