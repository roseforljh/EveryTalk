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
}
