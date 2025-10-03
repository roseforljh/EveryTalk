package com.example.everytalk.util

import android.graphics.Bitmap
import org.junit.Test
import org.junit.Assert.*

/**
 * ImageScaleCalculator 的单元测试
 * 验证等比缩放算法的正确性
 */
class ImageScaleCalculatorTest {

    @Test
    fun testProportionalScale_landscapeImage() {
        // 测试横向图片 (1920x1080) 缩放到最大边长1024
        val config = ImageScaleConfig(maxDimension = 1024, preserveAspectRatio = true)
        val (newWidth, newHeight) = ImageScaleCalculator.calculateProportionalScale(1920, 1080, config)
        
        // 期望: 宽度缩放到1024，高度按比例缩放
        assertEquals(1024, newWidth)
        assertEquals(576, newHeight) // 1024 * 1080 / 1920 = 576
        
        // 验证比例保持
        val originalRatio = 1920.0 / 1080.0
        val scaledRatio = newWidth.toDouble() / newHeight.toDouble()
        assertEquals(originalRatio, scaledRatio, 0.01)
    }

    @Test
    fun testProportionalScale_portraitImage() {
        // 测试竖向图片 (1080x1920) 缩放到最大边长1024
        val config = ImageScaleConfig(maxDimension = 1024, preserveAspectRatio = true)
        val (newWidth, newHeight) = ImageScaleCalculator.calculateProportionalScale(1080, 1920, config)
        
        // 期望: 高度缩放到1024，宽度按比例缩放
        assertEquals(576, newWidth) // 1024 * 1080 / 1920 = 576
        assertEquals(1024, newHeight)
        
        // 验证比例保持
        val originalRatio = 1080.0 / 1920.0
        val scaledRatio = newWidth.toDouble() / newHeight.toDouble()
        assertEquals(originalRatio, scaledRatio, 0.01)
    }

    @Test
    fun testProportionalScale_squareImage() {
        // 测试正方形图片 (2000x2000) 缩放到最大边长1024
        val config = ImageScaleConfig(maxDimension = 1024, preserveAspectRatio = true)
        val (newWidth, newHeight) = ImageScaleCalculator.calculateProportionalScale(2000, 2000, config)
        
        // 期望: 等比例缩放到1024x1024
        assertEquals(1024, newWidth)
        assertEquals(1024, newHeight)
    }

    @Test
    fun testProportionalScale_smallImage() {
        // 测试小图片 (500x300) 不放大
        val config = ImageScaleConfig(maxDimension = 1024, preserveAspectRatio = true, allowUpscale = false)
        val (newWidth, newHeight) = ImageScaleCalculator.calculateProportionalScale(500, 300, config)
        
        // 期望: 保持原尺寸
        assertEquals(500, newWidth)
        assertEquals(300, newHeight)
    }

    @Test
    fun testProportionalScale_smallImageWithUpscale() {
        // 测试小图片 (500x300) 允许放大
        val config = ImageScaleConfig(maxDimension = 1024, preserveAspectRatio = true, allowUpscale = true)
        val (newWidth, newHeight) = ImageScaleCalculator.calculateProportionalScale(500, 300, config)
        
        // 期望: 宽度放大到1024，高度按比例放大
        assertEquals(1024, newWidth)
        assertEquals(614, newHeight) // 1024 * 300 / 500 = 614.4 ≈ 614
    }

    @Test
    fun testProportionalScale_disableAspectRatio() {
        // 测试禁用比例保持的情况 (旧版本行为)
        val config = ImageScaleConfig(maxDimension = 1024, preserveAspectRatio = false)
        val (newWidth, newHeight) = ImageScaleCalculator.calculateProportionalScale(1920, 1080, config)
        
        // 期望: 横图固定宽度为1024
        assertEquals(1024, newWidth)
        assertEquals(576, newHeight) // 1024 * 1080 / 1920
    }

    @Test
    fun testInSampleSizeCalculation() {
        // 测试采样率计算
        val sampleSize = ImageScaleCalculator.calculateInSampleSize(4000, 3000, 1024, 1024)
        
        // 期望: 4000/4=1000, 3000/4=750，都小于1024，所以采样率为4
        assertEquals(4, sampleSize)
    }

    @Test
    fun testSmartCompressionQuality() {
        // 测试智能压缩质量计算
        
        // 大图片 - 降低质量
        val bigImageQuality = ImageScaleCalculator.calculateSmartCompressionQuality(
            2048, 1536, 85, true // 3M+ 像素
        )
        assertTrue("大图片应该降低压缩质量", bigImageQuality < 85)
        
        // 中等图片 - 略微降低质量
        val mediumImageQuality = ImageScaleCalculator.calculateSmartCompressionQuality(
            1280, 960, 85, true // 1.2M 像素
        )
        assertTrue("中等图片应该略微降低压缩质量", mediumImageQuality <= 85)
        
        // 小图片 - 提高质量
        val smallImageQuality = ImageScaleCalculator.calculateSmartCompressionQuality(
            640, 480, 85, true // 0.3M 像素
        )
        assertTrue("小图片应该提高压缩质量", smallImageQuality > 85)
        
        // 禁用智能压缩 - 保持原质量
        val disabledSmartQuality = ImageScaleCalculator.calculateSmartCompressionQuality(
            2048, 1536, 85, false
        )
        assertEquals("禁用智能压缩应该保持原质量", 85, disabledSmartQuality)
    }

    @Test
    fun testPresetConfigs() {
        // 测试预设配置的有效性
        
        val chatConfig = ImageScaleConfig.CHAT_MODE
        assertEquals(1024, chatConfig.maxDimension)
        assertEquals(85, chatConfig.compressionQuality)
        assertTrue(chatConfig.preserveAspectRatio)
        assertFalse(chatConfig.allowUpscale)
        
        val imageGenConfig = ImageScaleConfig.IMAGE_GENERATION_MODE
        assertEquals(1280, imageGenConfig.maxDimension)
        assertEquals(90, imageGenConfig.compressionQuality)
        assertTrue(imageGenConfig.preserveAspectRatio)
        
        val highQualityConfig = ImageScaleConfig.HIGH_QUALITY
        assertEquals(2048, highQualityConfig.maxDimension)
        assertEquals(95, highQualityConfig.compressionQuality)
        
        val fastConfig = ImageScaleConfig.FAST_MODE
        assertEquals(512, fastConfig.maxDimension)
        assertEquals(70, fastConfig.compressionQuality)
    }

    @Test
    fun testEdgeCases() {
        val config = ImageScaleConfig.CHAT_MODE
        
        // 测试零或负数尺寸
        val (w1, h1) = ImageScaleCalculator.calculateProportionalScale(0, 0, config)
        assertEquals(1024, w1) // 应该返回默认值
        assertEquals(1024, h1)
        
        val (w2, h2) = ImageScaleCalculator.calculateProportionalScale(-100, 200, config)
        assertEquals(1024, w2) // 应该返回默认值
        assertEquals(1024, h2)
        
        // 测试极端比例
        val (w3, h3) = ImageScaleCalculator.calculateProportionalScale(4000, 100, config)
        assertEquals(1024, w3) // 宽度限制为1024
        assertEquals(25, h3)   // 高度按比例缩放: 1024 * 100 / 4000 = 25.6 ≈ 25
    }
}