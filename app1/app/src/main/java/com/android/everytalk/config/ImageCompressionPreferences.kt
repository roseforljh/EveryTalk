package com.android.everytalk.config

import android.content.Context
import android.content.SharedPreferences
import com.android.everytalk.util.ImageScaleConfig

/**
 * 图片压缩偏好设置管理器
 * 用于管理用户的图片压缩选项配置
 */
class ImageCompressionPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    companion object {
        private const val PREFS_NAME = "image_compression_prefs"
        private const val KEY_CHAT_MODE_ENABLED = "chat_mode_enabled"
        private const val KEY_CHAT_MAX_DIMENSION = "chat_max_dimension"
        private const val KEY_CHAT_COMPRESSION_QUALITY = "chat_compression_quality"
        private const val KEY_CHAT_PRESERVE_ASPECT_RATIO = "chat_preserve_aspect_ratio"
        private const val KEY_CHAT_ENABLE_SMART_COMPRESSION = "chat_enable_smart_compression"
        
        private const val KEY_IMAGE_GEN_MODE_ENABLED = "image_gen_mode_enabled"
        private const val KEY_IMAGE_GEN_MAX_DIMENSION = "image_gen_max_dimension"
        private const val KEY_IMAGE_GEN_COMPRESSION_QUALITY = "image_gen_compression_quality"
        private const val KEY_IMAGE_GEN_PRESERVE_ASPECT_RATIO = "image_gen_preserve_aspect_ratio"
        private const val KEY_IMAGE_GEN_ENABLE_SMART_COMPRESSION = "image_gen_enable_smart_compression"
        
        private const val KEY_GLOBAL_COMPRESSION_MODE = "global_compression_mode"
    }
    
    /**
     * 压缩模式选项
     */
    enum class CompressionMode(val displayName: String) {
        AUTO("自动选择"),           // 根据用途自动选择
        HIGH_QUALITY("高质量"),     // 最小压缩
        BALANCED("平衡"),          // 默认平衡模式
        FAST("快速"),             // 最大压缩
        CUSTOM("自定义")           // 用户自定义设置
    }
    
    /**
     * 获取全局压缩模式
     */
    var compressionMode: CompressionMode
        get() = CompressionMode.values().getOrNull(
            prefs.getInt(KEY_GLOBAL_COMPRESSION_MODE, CompressionMode.AUTO.ordinal)
        ) ?: CompressionMode.AUTO
        set(value) = prefs.edit().putInt(KEY_GLOBAL_COMPRESSION_MODE, value.ordinal).apply()
    
    /**
     * 聊天模式配置
     */
    var chatModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_CHAT_MODE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CHAT_MODE_ENABLED, value).apply()
    
    var chatMaxDimension: Int
        get() = prefs.getInt(KEY_CHAT_MAX_DIMENSION, 1024)
        set(value) = prefs.edit().putInt(KEY_CHAT_MAX_DIMENSION, value.coerceIn(256, 4096)).apply()
    
    var chatCompressionQuality: Int
        get() = prefs.getInt(KEY_CHAT_COMPRESSION_QUALITY, 85)
        set(value) = prefs.edit().putInt(KEY_CHAT_COMPRESSION_QUALITY, value.coerceIn(50, 100)).apply()
    
    var chatPreserveAspectRatio: Boolean
        get() = prefs.getBoolean(KEY_CHAT_PRESERVE_ASPECT_RATIO, true)
        set(value) = prefs.edit().putBoolean(KEY_CHAT_PRESERVE_ASPECT_RATIO, value).apply()
    
    var chatEnableSmartCompression: Boolean
        get() = prefs.getBoolean(KEY_CHAT_ENABLE_SMART_COMPRESSION, true)
        set(value) = prefs.edit().putBoolean(KEY_CHAT_ENABLE_SMART_COMPRESSION, value).apply()
    
    /**
     * 图像生成模式配置
     */
    var imageGenModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_IMAGE_GEN_MODE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_IMAGE_GEN_MODE_ENABLED, value).apply()
    
    var imageGenMaxDimension: Int
        get() = prefs.getInt(KEY_IMAGE_GEN_MAX_DIMENSION, 1280)
        set(value) = prefs.edit().putInt(KEY_IMAGE_GEN_MAX_DIMENSION, value.coerceIn(256, 4096)).apply()
    
    var imageGenCompressionQuality: Int
        get() = prefs.getInt(KEY_IMAGE_GEN_COMPRESSION_QUALITY, 90)
        set(value) = prefs.edit().putInt(KEY_IMAGE_GEN_COMPRESSION_QUALITY, value.coerceIn(50, 100)).apply()
    
    var imageGenPreserveAspectRatio: Boolean
        get() = prefs.getBoolean(KEY_IMAGE_GEN_PRESERVE_ASPECT_RATIO, true)
        set(value) = prefs.edit().putBoolean(KEY_IMAGE_GEN_PRESERVE_ASPECT_RATIO, value).apply()
    
    var imageGenEnableSmartCompression: Boolean
        get() = prefs.getBoolean(KEY_IMAGE_GEN_ENABLE_SMART_COMPRESSION, true)
        set(value) = prefs.edit().putBoolean(KEY_IMAGE_GEN_ENABLE_SMART_COMPRESSION, value).apply()
    
    /**
     * 获取聊天模式的图片缩放配置
     */
    fun getChatModeConfig(): ImageScaleConfig {
        return when (compressionMode) {
            CompressionMode.AUTO -> ImageScaleConfig.CHAT_MODE
            CompressionMode.HIGH_QUALITY -> ImageScaleConfig.HIGH_QUALITY
            CompressionMode.BALANCED -> ImageScaleConfig.CHAT_MODE
            CompressionMode.FAST -> ImageScaleConfig.FAST_MODE
            CompressionMode.CUSTOM -> ImageScaleConfig(
                maxDimension = chatMaxDimension,
                compressionQuality = chatCompressionQuality,
                preserveAspectRatio = chatPreserveAspectRatio,
                allowUpscale = false,
                enableSmartCompression = chatEnableSmartCompression
            )
        }
    }
    
    /**
     * 获取AI图片显示的配置 (新增方法)
     * @return AI图片显示时使用的配置
     */
    fun getAiImageDisplayConfig(): ImageScaleConfig {
        return when (compressionMode) {
            CompressionMode.AUTO -> ImageScaleConfig.IMAGE_GENERATION_MODE
            CompressionMode.HIGH_QUALITY -> ImageScaleConfig.HIGH_QUALITY
            CompressionMode.BALANCED -> ImageScaleConfig.CHAT_MODE
            CompressionMode.FAST -> ImageScaleConfig.FAST_MODE
            CompressionMode.CUSTOM -> ImageScaleConfig(
                maxDimension = imageGenMaxDimension,
                compressionQuality = imageGenCompressionQuality,
                preserveAspectRatio = imageGenPreserveAspectRatio,
                allowUpscale = false,
                enableSmartCompression = imageGenEnableSmartCompression
            )
        }
    }
    
    /**
     * 获取图像生成模式的图片缩放配置 (更新逻辑)
     */
    fun getImageGenerationModeConfig(): ImageScaleConfig {
        // 为了保持向后兼容，图像生成模式继续使用原有逻辑
        // AI显示使用新的getAiImageDisplayConfig方法
        return when (compressionMode) {
            CompressionMode.AUTO -> ImageScaleConfig.IMAGE_GENERATION_MODE
            CompressionMode.HIGH_QUALITY -> ImageScaleConfig.HIGH_QUALITY
            CompressionMode.BALANCED -> ImageScaleConfig.CHAT_MODE
            CompressionMode.FAST -> ImageScaleConfig.FAST_MODE
            CompressionMode.CUSTOM -> ImageScaleConfig(
                maxDimension = imageGenMaxDimension,
                compressionQuality = imageGenCompressionQuality,
                preserveAspectRatio = imageGenPreserveAspectRatio,
                allowUpscale = false,
                enableSmartCompression = imageGenEnableSmartCompression
            )
        }
    }
    
    /**
     * 重置为默认设置
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
    
    /**
     * 获取当前配置的摘要信息
     */
    fun getConfigSummary(): String {
        val chatConfig = getChatModeConfig()
        val imageGenConfig = getImageGenerationModeConfig()
        
        return buildString {
            appendLine("压缩模式: ${compressionMode.displayName}")
            appendLine("聊天模式: ${chatConfig.maxDimension}px, 质量${chatConfig.compressionQuality}%")
            appendLine("图像生成: ${imageGenConfig.maxDimension}px, 质量${imageGenConfig.compressionQuality}%")
            appendLine("保持比例: ${if (chatConfig.preserveAspectRatio) "是" else "否"}")
            appendLine("智能压缩: ${if (chatConfig.enableSmartCompression) "启用" else "禁用"}")
        }
    }
}