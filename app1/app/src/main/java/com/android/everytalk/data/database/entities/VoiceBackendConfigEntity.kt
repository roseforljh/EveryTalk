package com.android.everytalk.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.android.everytalk.data.DataClass.VoiceBackendConfig

@Entity(tableName = "voice_backend_configs")
data class VoiceBackendConfigEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val provider: String,
    val sttPlatform: String,
    val sttApiKey: String,
    val sttApiUrl: String,
    val sttModel: String,
    val chatPlatform: String,
    val chatApiKey: String,
    val chatApiUrl: String,
    val chatModel: String,
    val ttsPlatform: String,
    val ttsApiKey: String,
    val ttsApiUrl: String,
    val ttsModel: String,
    val voiceName: String,
    val useRealtimeStreaming: Boolean,
    val isValid: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

fun VoiceBackendConfigEntity.toVoiceBackendConfig(): VoiceBackendConfig {
    return VoiceBackendConfig(
        id = id,
        name = name,
        provider = provider,
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
        voiceName = voiceName,
        useRealtimeStreaming = useRealtimeStreaming,
        isValid = isValid,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun VoiceBackendConfig.toEntity(): VoiceBackendConfigEntity {
    return VoiceBackendConfigEntity(
        id = id,
        name = name,
        provider = provider,
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
        voiceName = voiceName,
        useRealtimeStreaming = useRealtimeStreaming,
        isValid = isValid,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}