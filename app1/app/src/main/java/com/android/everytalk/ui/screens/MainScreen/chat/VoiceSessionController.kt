package com.android.everytalk.ui.screens.MainScreen.chat

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.network.VoiceChatSession
import com.android.everytalk.statecontroller.AppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

/**
 * 语音会话控制器
 * 负责管理录音会话的生命周期和状态
 */
class VoiceSessionController(
    private val context: Context,
    private val viewModel: AppViewModel?,
    private val coroutineScope: CoroutineScope,
    private val onVolumeChanged: (Float) -> Unit,
    private val onTranscriptionReceived: (String) -> Unit,
    private val onResponseReceived: (String) -> Unit,
    private val onProcessingChanged: (Boolean) -> Unit,
    private val onRecordingChanged: (Boolean) -> Unit,
    private val onTtsQuotaWarning: (Boolean) -> Unit
) {
    private var currentSession: VoiceChatSession? = null
    private var processingJob: Job? = null
    private val configManager = VoiceConfigManager(context)
    
    /**
     * 启动录音会话
     */
    fun startRecording() {
        val config = configManager.loadConfig()
        val errorMsg = configManager.validateConfig(config)
        
        if (errorMsg.isNotEmpty()) {
            android.widget.Toast.makeText(context, errorMsg, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        
        // 构建聊天历史
        val chatHistory = buildChatHistory()
        
        // 构建系统提示词
        val systemPrompt = buildSystemPrompt()
        
        // 创建会话
        val session = VoiceChatSession(
            chatHistory = chatHistory,
            systemPrompt = systemPrompt,
            
            sttPlatform = config.sttPlatform,
            sttApiKey = config.sttApiKey,
            sttApiUrl = config.sttApiUrl,
            sttModel = config.sttModel,
            
            chatPlatform = config.chatPlatform,
            chatApiKey = config.chatApiKey,
            chatApiUrl = config.chatApiUrl,
            chatModel = config.chatModel,
            
            ttsPlatform = config.ttsPlatform,
            ttsApiKey = config.ttsApiKey,
            ttsApiUrl = config.ttsApiUrl,
            ttsModel = config.ttsModel,
            voiceName = config.voiceName,
            
            onVolumeChanged = onVolumeChanged,
            onTranscriptionReceived = onTranscriptionReceived,
            onResponseReceived = onResponseReceived
        )
        
        currentSession = session
        onRecordingChanged(true)
        onTranscriptionReceived("")
        onResponseReceived("")
        
        coroutineScope.launch {
            try {
                session.startRecording()
            } catch (t: Throwable) {
                android.util.Log.e("VoiceSessionController", "Failed to start recording", t)
                onRecordingChanged(false)
                currentSession = null
            }
        }
    }
    
    /**
     * 停止录音并处理完整流程
     */
    fun stopAndProcess() {
        val session = currentSession ?: return
        
        onRecordingChanged(false)
        onVolumeChanged(0f)
        onProcessingChanged(true)
        
        processingJob = coroutineScope.launch {
            try {
                val result = session.stopRecordingAndProcess()
                
                // 保存对话到历史
                saveToHistory(result.userText, result.assistantText)
                
                // 检查是否有音频
                val hasAudio = result.audioData.isNotEmpty()
                android.util.Log.i("VoiceSessionController",
                    "Voice chat completed - User: '${result.userText}', AI: '${result.assistantText}', HasAudio: $hasAudio")
                
                if (!hasAudio) {
                    onTtsQuotaWarning(true)
                    kotlinx.coroutines.delay(3000)
                    onTtsQuotaWarning(false)
                }
                
                android.util.Log.i("VoiceSessionController", "Voice chat saved to history")
            } catch (e: CancellationException) {
                android.util.Log.i("VoiceSessionController", "Processing cancelled")
                // 取消时不显示错误
            } catch (t: Throwable) {
                android.util.Log.e("VoiceSessionController", "Voice chat failed", t)
                onTranscriptionReceived("")
                onResponseReceived("处理失败: ${t.message}")
            } finally {
                currentSession = null
                onProcessingChanged(false)
                processingJob = null
            }
        }
    }
    
    /**
     * 停止当前播放或处理
     */
    fun stopPlayback() {
        // 如果正在处理中，取消处理任务
        if (processingJob?.isActive == true) {
            processingJob?.cancel()
            processingJob = null
        }
        // 停止音频播放
        currentSession?.stopPlayback()
    }
    
    /**
     * 取消录音
     */
    fun cancel() {
        val session = currentSession ?: return
        
        onRecordingChanged(false)
        onVolumeChanged(0f)
        onTranscriptionReceived("")
        onResponseReceived("")
        
        // 取消正在进行的处理任务
        processingJob?.cancel()
        processingJob = null
        
        coroutineScope.launch {
            try {
                session.cancelRecording()
            } catch (t: Throwable) {
                android.util.Log.e("VoiceSessionController", "Cancel recording failed", t)
            } finally {
                currentSession = null
            }
        }
    }
    
    /**
     * 强制释放资源
     */
    fun forceRelease() {
        currentSession?.forceRelease()
        currentSession = null
    }
    
    /**
     * 构建聊天历史
     */
    private fun buildChatHistory(): MutableList<Pair<String, String>> {
        val chatHistory = mutableListOf<Pair<String, String>>()
        viewModel?.stateHolder?.let { holder ->
            holder.messages.takeLast(6).forEach { msg ->
                when (msg.sender) {
                    Sender.User -> chatHistory.add("user" to msg.text)
                    Sender.AI -> chatHistory.add("assistant" to msg.text)
                    else -> {}
                }
            }
        }
        return chatHistory
    }
    
    /**
     * 构建系统提示词
     */
    private fun buildSystemPrompt(): String {
        val baseSystemPrompt = viewModel?.stateHolder?.let { holder ->
            val convId = holder._currentConversationId.value
            holder.systemPrompts[convId] ?: ""
        } ?: ""
        
        val voiceModePrompt = """
            【重要指令】你现在正在进行语音通话。请完全扮演一个真实的人类，用最自然、最口语化的语气交流。
            回复必须极度简练、直接，避免长篇大论和书面语。
            禁止使用任何Markdown格式（如加粗、列表、标题等），只输出纯文本。
            禁止使用列表、分点陈述或复杂结构，必须用连贯的口语表达。
            像朋友聊天一样说话，尽量缩短回复长度以优化语音体验。
        """.trimIndent()
        
        return if (baseSystemPrompt.isNotEmpty()) {
            "$baseSystemPrompt\n\n$voiceModePrompt"
        } else {
            voiceModePrompt
        }
    }
    
    /**
     * 保存对话到历史记录
     */
    private suspend fun saveToHistory(userText: String, assistantText: String) {
        viewModel?.let { vm ->
            withContext(Dispatchers.Main) {
                // 添加用户消息
                val userMessage = Message(
                    text = userText,
                    sender = Sender.User,
                    timestamp = System.currentTimeMillis()
                )
                vm.stateHolder.messages.add(userMessage)
                
                // 添加AI回复
                val aiMessage = Message(
                    text = assistantText,
                    sender = Sender.AI,
                    timestamp = System.currentTimeMillis(),
                    contentStarted = true
                )
                vm.stateHolder.messages.add(aiMessage)
                
                // 标记对话为已修改
                vm.stateHolder.isTextConversationDirty.value = true
                
                // 立即保存到历史记录
                vm.saveCurrentChatToHistory(forceSave = true, isImageGeneration = false)
            }
        }
    }
}

/**
 * Composable辅助函数：记住会话控制器
 */
@Composable
fun rememberVoiceSessionController(
    context: Context,
    viewModel: AppViewModel?,
    coroutineScope: CoroutineScope,
    onVolumeChanged: (Float) -> Unit,
    onTranscriptionReceived: (String) -> Unit,
    onResponseReceived: (String) -> Unit,
    onProcessingChanged: (Boolean) -> Unit,
    onRecordingChanged: (Boolean) -> Unit,
    onTtsQuotaWarning: (Boolean) -> Unit
): VoiceSessionController {
    return remember(context, viewModel, coroutineScope) {
        VoiceSessionController(
            context = context,
            viewModel = viewModel,
            coroutineScope = coroutineScope,
            onVolumeChanged = onVolumeChanged,
            onTranscriptionReceived = onTranscriptionReceived,
            onResponseReceived = onResponseReceived,
            onProcessingChanged = onProcessingChanged,
            onRecordingChanged = onRecordingChanged,
            onTtsQuotaWarning = onTtsQuotaWarning
        )
    }
}