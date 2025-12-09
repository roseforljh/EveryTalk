package com.android.everytalk.ui.screens.MainScreen.chat.voice.logic

import android.content.Context
import com.android.everytalk.statecontroller.ViewModelStateHolder

/**
 * 语音配置管理类
 * 负责读取和校验STT、Chat、TTS的所有配置项
 * 
 * 已迁移到 Room 数据库：
 * - 移除 SharedPreferences 依赖
 * - 直接从 ViewModelStateHolder 获取当前选中的 VoiceBackendConfig
 */
data class VoiceConfig(
    // STT配置
    val sttPlatform: String,
    val sttApiKey: String,
    val sttApiUrl: String,
    val sttModel: String,
    
    // Chat配置
    val chatPlatform: String,
    val chatApiKey: String,
    val chatApiUrl: String,
    val chatModel: String,
    
    // TTS配置
    val ttsPlatform: String,
    val ttsApiKey: String,
    val ttsApiUrl: String,
    val ttsModel: String,
    val voiceName: String,
    
    // 实时流式模式（仅阿里云 STT 支持）
    val useRealtimeStreaming: Boolean = false
)

class VoiceConfigManager(
    private val context: Context,
    private val stateHolder: ViewModelStateHolder
) {
    /**
     * 读取所有语音配置
     */
    fun loadConfig(): VoiceConfig {
        // 从 stateHolder 获取当前选中的语音配置
        val config = stateHolder._selectedVoiceConfig.value ?: run {
            // 如果没有选中配置，返回默认值
            return VoiceConfig(
                sttPlatform = "Google", sttApiKey = "", sttApiUrl = "", sttModel = "",
                chatPlatform = "Google", chatApiKey = "", chatApiUrl = "", chatModel = "",
                ttsPlatform = "Gemini", ttsApiKey = "", ttsApiUrl = "", ttsModel = "",
                voiceName = "Kore"
            )
        }
        
        // 如果音色为空，使用当前 TTS 平台的默认音色
        val effectiveVoiceName = config.voiceName.ifBlank {
            getDefaultVoiceName(config.ttsPlatform)
        }
        
        return VoiceConfig(
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
            voiceName = effectiveVoiceName,
            // 从配置中读取实时流式模式设置（仅阿里云 STT 支持）
            useRealtimeStreaming = config.useRealtimeStreaming
        )
    }
    
    /**
     * 校验配置是否完整
     * @return 错误信息，如果为空则表示配置正确
     */
    fun validateConfig(config: VoiceConfig): String {
        // STT校验
        if (config.sttModel.isEmpty()) {
            return "请配置 STT 模型名称"
        }
        // Google和Aliyun不需要强制校验API URL
        if (config.sttPlatform != "Google" && config.sttPlatform != "Aliyun" && config.sttApiUrl.isEmpty()) {
            return "请配置 STT API 地址"
        }
        
        // Chat校验
        if (config.chatModel.isEmpty()) {
            return "请配置 Chat 模型名称"
        }
        if (config.chatPlatform != "Google" && config.chatApiUrl.isEmpty()) {
            return "请配置 Chat API 地址"
        }
        
        // TTS校验
        if (config.ttsModel.isEmpty()) {
            return "请配置 TTS 模型名称"
        }
        if (config.ttsPlatform == "Minimax" && config.ttsApiUrl.isEmpty()) {
            return "请配置 Minimax API 地址"
        }
        if (config.ttsPlatform == "SiliconFlow" && config.ttsApiKey.isEmpty()) {
            return "请配置 SiliconFlow API Key"
        }
        if (config.ttsPlatform == "Aliyun" && config.ttsApiKey.isEmpty()) {
            return "请配置阿里云 API Key"
        }
        
        // 已废弃后端模式，语音功能始终使用直连 API
        
        return ""
    }
    
    /**
     * 获取默认音色名称
     */
    private fun getDefaultVoiceName(platform: String): String {
        return when (platform) {
            "SiliconFlow" -> "alex"
            "Minimax" -> "male-qn-qingse"
            "OpenAI" -> "alloy"
            "Aliyun" -> "Cherry" // 阿里云默认音色：芊悦
            else -> "Kore" // Gemini
        }
    }
}