package com.android.everytalk.ui.screens.MainScreen.chat

import android.content.Context
import android.content.SharedPreferences

/**
 * 语音配置管理类
 * 负责读取和校验STT、Chat、TTS的所有配置项
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
    val voiceName: String
)

class VoiceConfigManager(private val context: Context) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("voice_settings", Context.MODE_PRIVATE)
    
    /**
     * 读取所有语音配置
     */
    fun loadConfig(): VoiceConfig {
        // STT配置
        val sttPlatform = prefs.getString("stt_platform", "Google") ?: "Google"
        val sttApiUrl = prefs.getString("stt_api_url_${sttPlatform}", null) 
            ?: prefs.getString("stt_api_url", "")?.trim() ?: ""
        val sttModel = prefs.getString("stt_model_${sttPlatform}", null) 
            ?: prefs.getString("stt_model", "")?.trim() ?: ""
        val sttApiKey = when (sttPlatform) {
            "OpenAI" -> prefs.getString("stt_key_OpenAI", "") ?: ""
            "SiliconFlow" -> prefs.getString("stt_key_SiliconFlow", "") ?: ""
            else -> prefs.getString("stt_key_Google", "") ?: ""
        }.trim()
        
        // Chat配置
        val chatPlatform = prefs.getString("chat_platform", "Google") ?: "Google"
        val chatApiUrl = prefs.getString("chat_api_url_${chatPlatform}", null) 
            ?: prefs.getString("chat_api_url", "")?.trim() ?: ""
        val chatModel = prefs.getString("chat_model_${chatPlatform}", null) 
            ?: prefs.getString("chat_model", "")?.trim() ?: ""
        val chatApiKey = when (chatPlatform) {
            "OpenAI" -> prefs.getString("chat_key_OpenAI", "") ?: ""
            else -> prefs.getString("chat_key_Google", "") ?: ""
        }.trim()
        
        // TTS配置
        val ttsPlatform = prefs.getString("voice_platform", "Gemini") ?: "Gemini"
        val ttsApiUrl = prefs.getString("voice_base_url_${ttsPlatform}", null) 
            ?: prefs.getString("voice_base_url", "")?.trim() ?: ""
        val ttsModel = prefs.getString("voice_chat_model_${ttsPlatform}", null) 
            ?: prefs.getString("voice_chat_model", "")?.trim() ?: ""
        
        // 确保voiceName对当前平台有效
        val voiceName = prefs.getString("voice_name_${ttsPlatform}", null) ?: getDefaultVoiceName(ttsPlatform)
        
        val ttsApiKey = when (ttsPlatform) {
            "OpenAI" -> prefs.getString("voice_key_OpenAI", "") ?: ""
            "Minimax" -> prefs.getString("voice_key_Minimax", "") ?: ""
            "SiliconFlow" -> prefs.getString("voice_key_SiliconFlow", "") ?: ""
            else -> prefs.getString("voice_key_Gemini", "") ?: ""
        }.trim()
        
        return VoiceConfig(
            sttPlatform = sttPlatform,
            sttApiKey = sttApiKey,
            sttApiUrl = sttApiUrl,
            sttModel = sttModel,
            chatPlatform = chatPlatform,
            chatApiKey = chatApiKey,
            chatApiUrl = chatApiUrl,
            chatModel = chatModel,
            ttsPlatform = ttsPlatform,
            ttsApiKey = ttsApiKey,
            ttsApiUrl = ttsApiUrl,
            ttsModel = ttsModel,
            voiceName = voiceName
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
        if (config.sttPlatform != "Google" && config.sttApiUrl.isEmpty()) {
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
        
        // 后端地址校验
        if (com.android.everytalk.BuildConfig.VOICE_BACKEND_URL.isEmpty()) {
            return "未配置语音网关地址(VOICE_BACKEND_URL)"
        }
        
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
            else -> "Kore" // Gemini
        }
    }
}