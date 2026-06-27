package com.android.everytalk.ui.screens.BubbleMain.Main

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class BubbleContentTypesRenderRouteTest {

    @Test
    fun `bubble content should not call enhanced markdown text directly`() {
        val source = bubbleContentTypesSource()

        assertFalse(source.contains("EnhancedMarkdownText("))
    }

    private fun bubbleContentTypesSource(): String {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/ui/screens/BubbleMain/Main/BubbleContentTypes.kt"),
            File("app/src/main/java/com/android/everytalk/ui/screens/BubbleMain/Main/BubbleContentTypes.kt"),
            File("app1/app/src/main/java/com/android/everytalk/ui/screens/BubbleMain/Main/BubbleContentTypes.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
        requireNotNull(sourceFile) { "找不到 BubbleContentTypes.kt" }
        return sourceFile.readText(Charsets.UTF_8)
    }
}
