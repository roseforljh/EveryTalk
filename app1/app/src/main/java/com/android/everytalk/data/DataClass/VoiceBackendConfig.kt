package com.android.everytalk.data.DataClass

import androidx.annotation.Keep
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 语音后端配置
 * 
 * 用于描述一条完整的语音对话链路配置，包含：
 * - STT (Speech-to-Text): 语音识别配置
 * - Chat: 对话模型配置
 * - TTS (Text-to-Speech): 语音合成配置
 * 
 * 这个配置会被 VoiceChatSession 使用来构建语音对话流程。
 */
@OptIn(ExperimentalSerializationApi::class)
@Keep
@Serializable
data class VoiceBackendConfig(
    /** 配置唯一标识符 */
    @SerialName("id")
    val id: String = UUID.randomUUID().toString(),
    
    /** 配置名称（用于在UI中显示） */
    @EncodeDefault
    @SerialName("name")
    val name: String = "默认语音配置",
    
    /** 配置提供商/平台标识（用于分组显示） */
    @EncodeDefault
    @SerialName("provider")
    val provider: String = "默认",
    
    // ========== STT (语音识别) 配置 ==========
    
    /** STT 平台名称 (如 "Google", "OpenAI", "SiliconFlow") */
    @EncodeDefault
    @SerialName("stt_platform")
    val sttPlatform: String = "Google",
    
    /** STT API 密钥 */
    @EncodeDefault
    @SerialName("stt_api_key")
    val sttApiKey: String = "",
    
    /** STT API 地址 */
    @EncodeDefault
    @SerialName("stt_api_url")
    val sttApiUrl: String = "",
    
    /** STT 模型名称 */
    @EncodeDefault
    @SerialName("stt_model")
    val sttModel: String = "",
    
    // ========== Chat (对话) 配置 ==========
    
    /** Chat 平台名称 (如 "Google", "OpenAI") */
    @EncodeDefault
    @SerialName("chat_platform")
    val chatPlatform: String = "Google",
    
    /** Chat API 密钥 */
    @EncodeDefault
    @SerialName("chat_api_key")
    val chatApiKey: String = "",
    
    /** Chat API 地址 */
    @EncodeDefault
    @SerialName("chat_api_url")
    val chatApiUrl: String = "",
    
    /** Chat 模型名称 */
    @EncodeDefault
    @SerialName("chat_model")
    val chatModel: String = "",
    
    // ========== TTS (语音合成) 配置 ==========
    
    /** TTS 平台名称 (如 "Gemini", "OpenAI", "Minimax", "SiliconFlow") */
    @EncodeDefault
    @SerialName("tts_platform")
    val ttsPlatform: String = "Gemini",
    
    /** TTS API 密钥 */
    @EncodeDefault
    @SerialName("tts_api_key")
    val ttsApiKey: String = "",
    
    /** TTS API 地址 */
    @EncodeDefault
    @SerialName("tts_api_url")
    val ttsApiUrl: String = "",
    
    /** TTS 模型名称 */
    @EncodeDefault
    @SerialName("tts_model")
    val ttsModel: String = "",
    
    /** 语音名称/音色 (如 "Kore", "alloy", "echo") */
    @EncodeDefault
    @SerialName("voice_name")
    val voiceName: String = "Kore",
    
    // ========== 元数据 ==========
    
    /** 配置是否有效/启用 */
    @EncodeDefault
    @SerialName("is_valid")
    val isValid: Boolean = true,
    
    /** 创建时间戳 */
    @EncodeDefault
    @SerialName("created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    /** 最后修改时间戳 */
    @EncodeDefault
    @SerialName("updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * 创建一个使用默认后端的配置
         * 当 provider 为 "默认" 时，实际的 API Key 和 URL 由后端注入
         */
        fun createDefault(): VoiceBackendConfig {
            return VoiceBackendConfig(
                id = UUID.randomUUID().toString(),
                name = "默认语音配置",
                provider = "默认",
                sttPlatform = "Google",
                chatPlatform = "Google",
                ttsPlatform = "Gemini",
                voiceName = "Kore"
            )
        }
        
        /**
         * 支持的 STT 平台列表
         */
        val SUPPORTED_STT_PLATFORMS = listOf(
            "Google",
            "OpenAI",
            "SiliconFlow"
        )
        
        /**
         * 支持的 Chat 平台列表
         */
        val SUPPORTED_CHAT_PLATFORMS = listOf(
            "Google",
            "OpenAI",
            "SiliconFlow"
        )
        
        /**
         * 支持的 TTS 平台列表
         */
        val SUPPORTED_TTS_PLATFORMS = listOf(
            "Gemini",
            "OpenAI",
            "Minimax",
            "SiliconFlow"
        )
        
        /**
         * 常用的语音名称/音色
         */
        val COMMON_VOICE_NAMES = mapOf(
            "Gemini" to listOf("Kore", "Charon", "Fenrir", "Aoede", "Puck"),
            "OpenAI" to listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer"),
            "Minimax" to listOf("male-qn-qingse", "female-shaonv", "male-qn-jingying"),
            "SiliconFlow" to listOf("alex", "benjamin", "charles", "david")
        )
    }
    
    /**
     * 检查配置是否为默认配置（使用后端注入的密钥）
     */
    fun isDefaultProvider(): Boolean {
        return provider.trim().lowercase() in listOf("默认", "default")
    }
    
    /**
     * 获取配置的显示名称
     */
    fun getDisplayName(): String {
        return if (name.isNotBlank()) name else "$provider - $voiceName"
    }
}