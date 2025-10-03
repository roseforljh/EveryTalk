package com.example.everytalk.util

/**
 * 图片缩放配置类
 * 用于控制图片压缩和缩放的行为
 */
data class ImageScaleConfig(
    val maxDimension: Int = 1024,           // 最大边长限制
    val compressionQuality: Int = 85,        // JPEG压缩质量 (0-100)
    val preserveAspectRatio: Boolean = true, // 是否保持原始宽高比
    val allowUpscale: Boolean = false,       // 是否允许放大小图片
    val maxFileSize: Long = 4 * 1024 * 1024, // 最大文件大小 (字节)
    val enableSmartCompression: Boolean = true // 启用智能压缩
) {
    companion object {
        /**
         * 聊天模式的默认配置 - 保持原比例，适度压缩
         */
        val CHAT_MODE = ImageScaleConfig(
            maxDimension = 1024,
            compressionQuality = 85,
            preserveAspectRatio = true,
            allowUpscale = false,
            enableSmartCompression = true
        )
        
        /**
         * 图像生成模式的默认配置 - 更高质量
         */
        val IMAGE_GENERATION_MODE = ImageScaleConfig(
            maxDimension = 1280,
            compressionQuality = 90,
            preserveAspectRatio = true,
            allowUpscale = false,
            enableSmartCompression = true
        )
        
        /**
         * 高质量模式 - 最小压缩
         */
        val HIGH_QUALITY = ImageScaleConfig(
            maxDimension = 2048,
            compressionQuality = 95,
            preserveAspectRatio = true,
            allowUpscale = false,
            enableSmartCompression = false
        )
        
        /**
         * 快速模式 - 最大压缩
         */
        val FAST_MODE = ImageScaleConfig(
            maxDimension = 512,
            compressionQuality = 70,
            preserveAspectRatio = true,
            allowUpscale = false,
            enableSmartCompression = true
        )
    }
}

/**
 * 图片缩放计算工具类
 */
object ImageScaleCalculator {
    
    /**
     * 计算等比缩放后的尺寸
     * @param originalWidth 原始宽度
     * @param originalHeight 原始高度  
     * @param config 缩放配置
     * @return Pair<新宽度, 新高度>
     */
    fun calculateProportionalScale(
        originalWidth: Int,
        originalHeight: Int,
        config: ImageScaleConfig
    ): Pair<Int, Int> {
        // 如果原图尺寸无效，返回默认值
        if (originalWidth <= 0 || originalHeight <= 0) {
            return config.maxDimension to config.maxDimension
        }
        
        // 如果不保持比例，使用旧版本的逻辑（横图固定宽度，竖图固定高度）
        if (!config.preserveAspectRatio) {
            return if (originalWidth > originalHeight) {
                config.maxDimension to (config.maxDimension * originalHeight / originalWidth)
            } else {
                (config.maxDimension * originalWidth / originalHeight) to config.maxDimension
            }
        }
        
        // 计算等比缩放比例
        // 确保最长边不超过maxDimension，同时保持原始宽高比
        val scale = minOf(
            config.maxDimension.toFloat() / originalWidth,
            config.maxDimension.toFloat() / originalHeight,
            if (config.allowUpscale) Float.MAX_VALUE else 1.0f
        )
        
        val newWidth = (originalWidth * scale).toInt().coerceAtLeast(1)
        val newHeight = (originalHeight * scale).toInt().coerceAtLeast(1)
        
        return newWidth to newHeight
    }
    
    /**
     * 计算采样率以避免内存问题
     * @param originalWidth 原始宽度
     * @param originalHeight 原始高度
     * @param targetWidth 目标宽度
     * @param targetHeight 目标高度
     * @return 采样率
     */
    fun calculateInSampleSize(
        originalWidth: Int,
        originalHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        var inSampleSize = 1
        
        if (originalHeight > targetHeight || originalWidth > targetWidth) {
            val halfHeight = originalHeight / 2
            val halfWidth = originalWidth / 2
            
            // 计算最大的采样率，使得采样后的尺寸仍然大于等于目标尺寸
            while (halfHeight / inSampleSize >= targetHeight && 
                   halfWidth / inSampleSize >= targetWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }
    
    /**
     * 智能压缩质量计算
     * 根据图片尺寸和内容自动调整压缩质量
     */
    fun calculateSmartCompressionQuality(
        width: Int,
        height: Int,
        baseQuality: Int,
        enableSmart: Boolean
    ): Int {
        if (!enableSmart) return baseQuality
        
        val totalPixels = width * height
        
        // 根据像素数量调整压缩质量
        val qualityAdjustment = when {
            totalPixels > 2_000_000 -> -10  // 大图降低质量
            totalPixels > 1_000_000 -> -5   // 中图略微降低质量
            totalPixels < 500_000 -> 5      // 小图提高质量
            else -> 0
        }
        
        return (baseQuality + qualityAdjustment).coerceIn(50, 100)
    }
}