package com.android.everytalk.ui.screens.MainScreen.chat.voice.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.everytalk.data.network.VoiceChatSession
import kotlinx.coroutines.delay

/**
 * 底部控制栏
 * 包含麦克风按钮和关闭/取消按钮
 */
@Composable
fun VoiceBottomControls(
    isRecording: Boolean,
    isPlaying: Boolean = false,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancel: () -> Unit,
    onStopPlayback: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var micClickAnim by remember { mutableStateOf(false) }
    val micScale by animateFloatAsState(
        targetValue = if (micClickAnim) 0.9f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "micClickScale"
    )
    
    var endClickAnim by remember { mutableStateOf(false) }
    val endScale by animateFloatAsState(
        targetValue = if (endClickAnim) 0.9f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "endClickScale"
    )
    
    LaunchedEffect(micClickAnim) {
        if (micClickAnim) {
            delay(120)
            micClickAnim = false
        }
    }
    
    LaunchedEffect(endClickAnim) {
        if (endClickAnim) {
            delay(120)
            endClickAnim = false
        }
    }
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧麦克风按钮
        Box(
            modifier = Modifier
                .padding(start = 16.dp)
                .size(56.dp)
                .scale(micScale)
                .clip(CircleShape)
                .background(
                    color = if (isRecording) Color(0xFF8B4545) else Color(0xFF3A3A3A),
                    shape = CircleShape
                )
                .clickable {
                    micClickAnim = true
                    if (isRecording) {
                        onStopRecording()
                    } else {
                        onStartRecording()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = if (isRecording) "停止录音" else "开始录音",
                modifier = Modifier.size(28.dp),
                tint = if (isRecording) Color(0xFFFF8A8A) else Color.White
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // 右侧按钮（关闭/取消/停止播放）
        Box(
            modifier = Modifier
                .padding(end = 16.dp)
                .size(56.dp)
                .scale(endScale)
                .clip(CircleShape)
                .background(
                    color = Color(0xFF3A3A3A),
                    shape = CircleShape
                )
                .clickable {
                    endClickAnim = true
                    when {
                        isRecording -> onCancel()
                        isPlaying -> onStopPlayback()
                        else -> onClose()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when {
                    isRecording -> Icons.Default.Delete
                    isPlaying -> Icons.Default.Delete
                    else -> Icons.Default.Close
                },
                contentDescription = when {
                    isRecording -> "取消本次语音"
                    isPlaying -> "中断AI回答"
                    else -> "关闭"
                },
                modifier = Modifier.size(28.dp),
                tint = Color.White
            )
        }
    }
}

/**
 * 内容显示区域
 * 包含波形动画、处理状态、TTS警告和对话文本
 */
@Composable
fun VoiceContentDisplay(
    isRecording: Boolean,
    isProcessing: Boolean,
    showTtsQuotaWarning: Boolean,
    userText: String,
    assistantText: String,
    currentVolume: Float,
    waveCircleColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val isDarkTheme = isSystemInDarkTheme()
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize()
    ) {
        // 中央波形动画
        VoiceWaveAnimation(
            isRecording = isRecording,
            color = waveCircleColor,
            currentVolume = currentVolume
        )
        
        // 处理状态指示器
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
        
        // TTS配额警告
        if (showTtsQuotaWarning) {
            Spacer(modifier = Modifier.height(16.dp))
            TtsQuotaWarningCard()
        }
        
        // 对话文本显示
        AnimatedVisibility(
            visible = userText.isNotEmpty() || assistantText.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(32.dp))
                ConversationTextCard(
                    userText = userText,
                    assistantText = assistantText,
                    isDarkTheme = isDarkTheme,
                    contentColor = contentColor
                )
            }
        }
    }
}

/**
 * TTS配额警告卡片
 */
@Composable
private fun TtsQuotaWarningCard() {
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
                imageVector = Icons.Default.Info,
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

/**
 * 对话文本卡片
 */
@Composable
private fun ConversationTextCard(
    userText: String,
    assistantText: String,
    isDarkTheme: Boolean,
    contentColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .heightIn(max = 400.dp)
            .background(
                color = if (isDarkTheme) Color(0xFF2A2A2A) else Color(0xFFF5F5F5),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
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

/**
 * WebSocket 状态指示器
 * 显示实时 STT 的 WebSocket 连接状态
 */
@Composable
fun WebSocketStatusIndicator(
    state: VoiceChatSession.WebSocketState,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wsIndicator")
    
    // 连接中时的脉冲动画
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    
    val (icon, text, color) = when (state) {
        VoiceChatSession.WebSocketState.DISCONNECTED -> Triple(
            Icons.Default.CloudOff,
            "未连接",
            contentColor.copy(alpha = 0.5f)
        )
        VoiceChatSession.WebSocketState.CONNECTING -> Triple(
            Icons.Default.CloudSync,
            "正在连接...",
            Color(0xFFFF9800) // 橙色
        )
        VoiceChatSession.WebSocketState.CONNECTED -> Triple(
            Icons.Default.Cloud,
            "已连接",
            Color(0xFF4CAF50) // 绿色
        )
        VoiceChatSession.WebSocketState.ERROR -> Triple(
            Icons.Default.Warning,
            "连接错误",
            Color(0xFFF44336) // 红色
        )
    }
    
    Row(
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            modifier = Modifier.size(16.dp),
            tint = if (state == VoiceChatSession.WebSocketState.CONNECTING) {
                color.copy(alpha = pulseAlpha)
            } else {
                color
            }
        )
        Text(
            text = text,
            color = if (state == VoiceChatSession.WebSocketState.CONNECTING) {
                color.copy(alpha = pulseAlpha)
            } else {
                color
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}