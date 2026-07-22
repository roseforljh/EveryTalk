package com.android.everytalk.ui.screens.ImageGeneration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageGenerationMessagesListRenderRouteTest {

    @Test
    fun `image generation ai messages should not call enhanced markdown text directly`() {
        val source = imageGenerationMessagesListSource()

        assertFalse(source.contains("EnhancedMarkdownText("))
    }

    @Test
    fun `image chat top anchor always uses configured top inset`() {
        val source = imageGenerationMessagesListSource()

        assertFalse(source.contains("firstBubbleScreenY"))
        assertTrue(source.contains("targetAnchorY = topPaddingPx"))
    }

    @Test
    fun `image streaming state should respect pause aware collection and item snapshot`() {
        val source = imageGenerationMessagesListSource()

        assertTrue(source.contains("streamingRenderStateSource.freezeWhileStreamingPaused"))
        assertTrue(source.contains("currentImageStreamingAiMessageId.freezeWhileStreamingPaused"))
        assertTrue(source.contains("val message = item.message"))
        assertFalse(source.contains("[UI] Rendering AI message"))
    }

    @Test
    fun `image stream completion retains exact anchor reserve`() {
        val source = imageGenerationMessagesListSource()

        assertTrue(source.contains("keepReserveAfterRunEnd = true"))
        assertTrue(source.contains("hasResponseTarget = engineResponseTargetId != null"))
        assertTrue(source.contains("topAnchorEngine.attachResponseTarget"))
        assertFalse(source.contains("pinToRealBottomAfterAutomaticRun()"))
        assertTrue(source.contains("reserveInsideTrailingItem = true"))
        assertTrue(source.contains("appendTopAnchorReserve("))
        assertFalse(source.contains("image_dynamic_padding_spacer"))
        assertFalse(source.contains("topAnchorEngine.reservePx.toDp()"))
        assertFalse(source.contains("translationY = -retainedVisualOffsetPx.toFloat()"))
        assertTrue(source.contains("trailingRealItemIndex = chatItems.lastIndex"))
    }

    @Test
    fun `image stop action clears reserve and reaches the real bottom`() {
        val source = imageGenerationScreenSource()
        val stopCallbackStart = source.indexOf("onStopApiCall = {")
        require(stopCallbackStart >= 0) { "找不到图片会话停止回调" }
        val stopCallbackEnd = source.indexOf("},", startIndex = stopCallbackStart)
        require(stopCallbackEnd > stopCallbackStart) { "图片会话停止回调不完整" }
        val stopCallback = source.substring(stopCallbackStart, stopCallbackEnd)

        assertTrue(stopCallback.contains("viewModel.onCancelAPICall()"))
        assertTrue(stopCallback.contains("scrollStateManager.stopStreamingAndJumpToRealBottom()"))
    }

    private fun imageGenerationMessagesListSource(): String {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/ui/screens/ImageGeneration/ImageGenerationMessagesList.kt"),
            File("app/src/main/java/com/android/everytalk/ui/screens/ImageGeneration/ImageGenerationMessagesList.kt"),
            File("app1/app/src/main/java/com/android/everytalk/ui/screens/ImageGeneration/ImageGenerationMessagesList.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
        requireNotNull(sourceFile) { "找不到 ImageGenerationMessagesList.kt" }
        return sourceFile.readText(Charsets.UTF_8)
    }

    private fun imageGenerationScreenSource(): String {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/ui/screens/ImageGeneration/ImageGenerationScreen.kt"),
            File("app/src/main/java/com/android/everytalk/ui/screens/ImageGeneration/ImageGenerationScreen.kt"),
            File("app1/app/src/main/java/com/android/everytalk/ui/screens/ImageGeneration/ImageGenerationScreen.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
        requireNotNull(sourceFile) { "找不到 ImageGenerationScreen.kt" }
        return sourceFile.readText(Charsets.UTF_8)
    }
}
