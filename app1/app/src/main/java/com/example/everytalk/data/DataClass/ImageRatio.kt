package com.example.everytalk.data.DataClass

/**
 * 图像生成比例配置
 */
data class ImageRatio(
    val displayName: String,    // 显示名称，如 "1:1" 或 "AUTO"
    val width: Int,            // 宽度
    val height: Int,           // 高度
    val longerSide: Int = 1290, // 长边限制
    val isAuto: Boolean = false // 是否为自动模式
) {
    /**
     * 获取实际生成尺寸
     */
    fun getActualSize(): Pair<Int, Int> {
        val aspectRatio = width.toFloat() / height.toFloat()
        return if (width >= height) {
            // 横图或正方形，限制宽度
            val actualWidth = minOf(width, longerSide)
            val actualHeight = (actualWidth / aspectRatio).toInt()
            actualWidth to actualHeight
        } else {
            // 竖图，限制高度
            val actualHeight = minOf(height, longerSide)
            val actualWidth = (actualHeight * aspectRatio).toInt()
            actualWidth to actualHeight
        }
    }
    
    /**
     * 获取显示用的尺寸描述
     */
    fun getSizeDescription(): String {
        return if (isAuto) {
            "自动匹配输入"
        } else {
            val (actualWidth, actualHeight) = getActualSize()
            "${actualWidth}x${actualHeight}"
        }
    }
    
    companion object {
        /**
         * 预定义的图像比例选项
         */
        val AUTO = ImageRatio("AUTO", 1024, 1024, isAuto = true)
        
        val DEFAULT_RATIOS = listOf(
            AUTO,                               // 自动模式
            ImageRatio("1:1", 1024, 1024),      // 正方形
            ImageRatio("2:3", 832, 1248),       // 竖向
            ImageRatio("3:2", 1248, 832),       // 横向  
            ImageRatio("3:4", 864, 1184),       // 竖向
            ImageRatio("4:3", 1184, 864),       // 横向
            ImageRatio("4:5", 896, 1152),       // 竖向
            ImageRatio("5:4", 1152, 896),       // 横向
            ImageRatio("9:16", 768, 1344),      // 手机竖屏
            ImageRatio("16:9", 1344, 768),      // 宽屏横向
            ImageRatio("21:9", 1536, 672)       // 超宽屏
        )
        
        /**
         * 默认选中的比例（自动模式）
         */
        val DEFAULT_SELECTED = AUTO
    }
}