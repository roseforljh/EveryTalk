package com.android.everytalk.ui.screens.MainScreen.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.statecontroller.AppViewModel

/**
 * 语音输入屏幕 - 重构版
 *
 * 职责清晰分离：
 * - VoiceConfigManager: 管理所有配置读取和校验
 * - VoiceSessionController: 管理录音会话生命周期
 * - VoiceInputComponents: UI组件封装
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceInputScreen(
    onClose: () -> Unit,
    selectedApiConfig: ApiConfig? = null,
    viewModel: AppViewModel? = null
) {
    // ========== 状态管理 ==========
    var isClosing by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var userCancelledPlayback by remember { mutableStateOf(false) }
    var currentVolume by remember { mutableStateOf(0f) }
    var userText by remember { mutableStateOf("") }
    var assistantText by remember { mutableStateOf("") }
    var showTtsQuotaWarning by remember { mutableStateOf(false) }
    
    // 对话框状态
    var showTtsSettingsDialog by remember { mutableStateOf(false) }
    var showSttChatSettingsDialog by remember { mutableStateOf(false) }
    var showLlmSettingsDialog by remember { mutableStateOf(false) }
    var showVoiceSelectionDialog by remember { mutableStateOf(false) }
    
    // ========== 核心控制器 ==========
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val sessionController = rememberVoiceSessionController(
        context = context,
        viewModel = viewModel,
        coroutineScope = coroutineScope,
        onVolumeChanged = { currentVolume = it },
        onTranscriptionReceived = { userText = it },
        onResponseReceived = { assistantText = it },
        onProcessingChanged = { isProcessing = it },
        onRecordingChanged = {
            isRecording = it
            // 开始新的录音时，重置取消标记
            if (it) {
                userCancelledPlayback = false
            }
        },
        onTtsQuotaWarning = { showTtsQuotaWarning = it }
    )
    
    // 监听处理状态变化，自动管理播放状态
    LaunchedEffect(isProcessing, userCancelledPlayback) {
        if (userCancelledPlayback) {
            // 用户主动取消了播放，保持关闭状态
            isPlaying = false
        } else {
            // 播放状态跟随处理状态（处理包含录音后的STT+LLM+TTS播放全过程）
            isPlaying = isProcessing
        }
    }
    
    // ========== 权限管理 ==========
    val requestAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            sessionController.startRecording()
        } else {
            android.util.Log.w("VoiceInputScreen", "RECORD_AUDIO permission denied")
        }
    }
    
    val startRecordingWithPermission: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        
        if (granted) {
            sessionController.startRecording()
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    
    // ========== 生命周期管理 ==========
    // 仅在有活动任务（录音/播放/处理）时拦截返回键
    // 闲置状态下让系统接管返回逻辑，以触发 predictive back animation (popExitTransition)
    val shouldInterceptBack = isRecording || isPlaying || isProcessing
    
    BackHandler(enabled = shouldInterceptBack) {
        if (isRecording) {
            sessionController.cancel()
        } else if (isPlaying || isProcessing) {
            // 中断播放或处理
            userCancelledPlayback = true
            sessionController.stopPlayback()
            isPlaying = false
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            sessionController.forceRelease()
        }
    }
    
    // ========== 主题配置 ==========
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color.Black else MaterialTheme.colorScheme.background
    val contentColor = if (isDarkTheme) Color.White else MaterialTheme.colorScheme.onBackground
    val waveCircleColor = if (isDarkTheme) Color.White else MaterialTheme.colorScheme.primary
    
    // ========== UI布局 ==========
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = backgroundColor,
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = { showVoiceSelectionDialog = true }) {
                        Icon(
                            Icons.Default.RecordVoiceOver,
                            contentDescription = "选择音色",
                            tint = contentColor
                        )
                    }
                },
                actions = {
                    
                    IconButton(onClick = { showSttChatSettingsDialog = true }) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = "STT配置",
                            tint = contentColor
                        )
                    }
                    IconButton(onClick = { showLlmSettingsDialog = true }) {
                        Icon(
                            Icons.Default.Face,
                            contentDescription = "LLM配置",
                            tint = contentColor
                        )
                    }
                    IconButton(onClick = { showTtsSettingsDialog = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "语音设置",
                            tint = contentColor
                        )
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
                VoiceBottomControls(
                    isRecording = isRecording,
                    isPlaying = isPlaying || isProcessing,
                    onStartRecording = startRecordingWithPermission,
                    onStopRecording = { sessionController.stopAndProcess() },
                    onCancel = { sessionController.cancel() },
                    onStopPlayback = {
                        userCancelledPlayback = true
                        sessionController.stopPlayback()
                        isPlaying = false
                    },
                    onClose = {
                        if (!isClosing) {
                            isClosing = true
                            onClose()
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            VoiceContentDisplay(
                isRecording = isRecording,
                isProcessing = isProcessing,
                showTtsQuotaWarning = showTtsQuotaWarning,
                userText = userText,
                assistantText = assistantText,
                currentVolume = currentVolume,
                waveCircleColor = waveCircleColor,
                contentColor = contentColor
            )
        }
    }
    
    // ========== 对话框 ==========
    if (showTtsSettingsDialog) {
        VoiceSettingsDialog(
            selectedApiConfig = selectedApiConfig,
            onDismiss = { showTtsSettingsDialog = false },
            viewModel = viewModel
        )
    }
    
    if (showSttChatSettingsDialog) {
        SttSettingsDialog(
            onDismiss = { showSttChatSettingsDialog = false },
            viewModel = viewModel
        )
    }
    
    if (showLlmSettingsDialog) {
        LlmSettingsDialog(
            onDismiss = { showLlmSettingsDialog = false },
            viewModel = viewModel
        )
    }
    
    if (showVoiceSelectionDialog) {
        VoiceSelectionDialog(
            onDismiss = { showVoiceSelectionDialog = false },
            viewModel = viewModel
        )
    }
}
