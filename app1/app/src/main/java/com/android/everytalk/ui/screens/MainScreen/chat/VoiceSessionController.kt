package com.android.everytalk.ui.screens.MainScreen.chat

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.network.VoiceChatSession
import com.android.everytalk.data.network.direct.AliyunSttConnectionManager
import com.android.everytalk.statecontroller.AppViewModel
import io.ktor.client.*
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

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
    private var preconnectJob: Job? = null
    
    // 共享的 Ktor 客户端（用于预连接和录音）
    private val sharedKtorClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(300, TimeUnit.SECONDS)
                    writeTimeout(120, TimeUnit.SECONDS)
                }
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = Long.MAX_VALUE
                connectTimeoutMillis = 60_000
                socketTimeoutMillis = Long.MAX_VALUE
            }
            install(WebSockets) {
                pingIntervalMillis = 30_000
            }
        }
    }
    
    /**
     * 预连接阿里云 STT WebSocket
     *
     * 在用户进入聊天页面时调用，提前建立 WebSocket 连接。
     * 这样当用户按下录音按钮时，连接已经就绪，可以立即发送音频。
     */
    fun preconnectSttIfNeeded() {
        val config = configManager.loadConfig()
        
        // 只有启用阿里云实时 STT 时才预连接
        if (!config.useRealtimeStreaming || !config.sttPlatform.equals("Aliyun", ignoreCase = true)) {
            return
        }
        
        // 验证配置
        if (config.sttApiKey.isEmpty()) {
            android.util.Log.w("VoiceSessionController", "Aliyun STT API key not configured, skipping preconnect")
            return
        }
        
        // 取消之前的预连接任务
        preconnectJob?.cancel()
        
        preconnectJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.i("VoiceSessionController", "Starting Aliyun STT preconnect...")
                
                // 初始化连接管理器
                AliyunSttConnectionManager.initialize(sharedKtorClient)
                
                // 预连接（这会在后台建立 WebSocket 并等待 task-started）
                AliyunSttConnectionManager.ensureConnected(
                    apiKey = config.sttApiKey,
                    model = config.sttModel.ifEmpty { "fun-asr-realtime" },
                    sampleRate = 16000
                )
                
                android.util.Log.i("VoiceSessionController", "Aliyun STT preconnect completed")
            } catch (e: Exception) {
                android.util.Log.e("VoiceSessionController", "Aliyun STT preconnect failed: ${e.message}", e)
            }
        }
    }
    
    /**
     * 关闭预连接
     */
    fun shutdownPreconnect() {
        preconnectJob?.cancel()
        preconnectJob = null
        AliyunSttConnectionManager.shutdown()
    }
    
    /**
     * 启动录音会话
     */
    fun startRecording() {
        // 启动新录音前，确保停止之前的播放或处理
        stopPlayback()

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
        
        // 判断是否启用实时 STT（仅阿里云平台支持）
        val useRealtimeStt = config.useRealtimeStreaming && config.sttPlatform.equals("Aliyun", ignoreCase = true)
        
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
            
            // 传入实时 STT 配置（仅阿里云支持）
            useRealtimeStt = useRealtimeStt,
            
            onVolumeChanged = onVolumeChanged,
            onTranscriptionReceived = onTranscriptionReceived,
            onResponseReceived = onResponseReceived
        )
        android.util.Log.i(
            "VoiceSessionController",
            "VoiceSession created: sttPlatform=${config.sttPlatform}, ttsPlatform=${config.ttsPlatform}, voice=${config.voiceName}, realtimeStt=$useRealtimeStt"
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
                
                // 检查是否有音频（实时模式下音频是边播边放的，audioData 为空是正常的）
                val hasAudio = result.audioData.isNotEmpty() || result.isRealtimeMode
                android.util.Log.i("VoiceSessionController",
                    "Voice chat completed - User: '${result.userText}', AI: '${result.assistantText}', HasAudio: $hasAudio, IsRealtimeMode: ${result.isRealtimeMode}")
                
                // 只有在非实时模式且没有音频数据时才显示 TTS 配额警告
                if (!hasAudio) {
                    // 如果没有抛出异常但也没有音频，可能是静音或者未知错误，暂时提示配额问题
                    // 理想情况下应该区分是静音还是错误
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
                
                // 检查是否是 TTS 配额错误
                val errorMsg = t.message ?: ""
                if (errorMsg.contains("40000001") || errorMsg.contains("DAILY_LIMIT_EXCEEDED") || errorMsg.contains("FREE_TRIAL_EXPIRED")) {
                    onResponseReceived("TTS 配额已用完或试用期已过")
                    onTtsQuotaWarning(true)
                    kotlinx.coroutines.delay(3000)
                    onTtsQuotaWarning(false)
                } else {
                    onResponseReceived("处理失败: $errorMsg")
                }
            } finally {
                // 只有当 currentSession 仍然是当前处理的 session 时才置空
                // 避免 race condition：如果用户在处理过程中开始了新录音，currentSession 已经被更新，此时不应置空
                if (currentSession === session) {
                    currentSession = null
                }
                onProcessingChanged(false)
                processingJob = null
            }
        }
    }
    
    /**
     * 停止当前播放或处理
     */
    fun stopPlayback() {
        // 先保存当前会话的引用，避免被 finally 块置空后无法调用
        val session = currentSession
        
        // 如果正在处理中，取消处理任务
        if (processingJob?.isActive == true) {
            processingJob?.cancel()
            processingJob = null
        }
        
        // 使用保存的引用停止音频播放
        session?.stopPlayback()
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
            重要指令：
            你现在是在和用户语音聊天，请完全把自己当成一个有温度、真诚、会共情的真人朋友，而不是机器人。
            说话方式要非常口语化、自然，像打电话或语音聊天，而不是在写文章。
            可以在合适的时候用“嗯”“哈哈”“哎呀”“哇”“诶”等语气词，表达开心、惊讶、安慰、理解，但不要每句都用，保持自然。
            
            输出必须非常简短、非常直接，先给结论，再根据用户的期待适当补一句两句就好。
            不要故意拉长内容，不要展开长篇分析，除非用户明确说“详细一点”“帮我细讲一下”。
            不要展示你的思考过程，不要自报“我先分析一下”“第一点第二点”，直接给最有用的回答。
            
            严禁使用任何 Markdown 格式，禁止标题、列表、编号、代码块、表格、链接等，只用连续的口语文本。
            禁止使用“1.”“2.”“首先”“其次”“最后”这类明显的结构化分点表达，尽量用自然的口语把意思说清楚。
            禁止说“作为一个 AI”“作为一个语言模型”等话，不要强调自己是程序，直接以“懂很多东西的朋友”的口吻说话。
            
            如果你不完全确定，也可以自然地说“我不敢百分百保证，不过大概率是……”“按常理推的话，更可能是……”，
            但不要为了显得聪明而瞎编细节。
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