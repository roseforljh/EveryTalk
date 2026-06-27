package com.android.everytalk.ui.screens.ImageGeneration

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ImageGenerationMessagesListRenderRouteTest {

    @Test
    fun `image generation ai messages should not call enhanced markdown text directly`() {
        val source = imageGenerationMessagesListSource()

        assertFalse(source.contains("EnhancedMarkdownText("))
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
