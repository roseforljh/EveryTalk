package com.android.everytalk.ui.components.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MarkdownEngineOwnershipTest {

    @Test
    fun `唯一Markdown入口委托给MikePenz`() {
        val source = mainSource(
            "com/android/everytalk/ui/components/streaming/StreamBlocksRenderer.kt"
        )

        assertTrue(source.contains("fun UnifiedMarkdownRenderer("))
        assertTrue(source.contains("MikePenzMarkdownRenderer("))
        assertFalse(source.contains("parseNativeStreamingMarkdownBlocks("))
        assertFalse(source.contains("NativeMarkdownBlocksSegment("))
    }

    @Test
    fun `生产消息入口只调用统一Markdown入口`() {
        val entryPoints = listOf(
            "com/android/everytalk/ui/components/coordinator/ContentCoordinator.kt",
            "com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatMessagesList.kt",
            "com/android/everytalk/ui/screens/ImageGeneration/ImageGenerationMessagesList.kt",
            "com/android/everytalk/ui/screens/BubbleMain/Main/BubbleContentTypes.kt",
        )

        entryPoints.forEach { path ->
            val source = mainSource(path)
            assertTrue(path, source.contains("UnifiedMarkdownRenderer("))
            assertFalse(path, source.contains("MikePenzMarkdownRenderer("))
        }
    }

    @Test
    fun `项目依赖中没有Markwon和JLatexMath`() {
        val buildFile = projectFile("app/build.gradle.kts").readText(Charsets.UTF_8)

        assertTrue(buildFile.contains("libs.mikepenz.markdown.core"))
        assertTrue(buildFile.contains("libs.mikepenz.markdown.m3"))
        assertTrue(buildFile.contains("libs.mikepenz.markdown.coil3"))
        assertFalse(buildFile.contains("io.noties.markwon"))
        assertFalse(buildFile.contains("jlatexmath"))
    }

    @Test
    fun `数学公式由本地KaTeX组件独立渲染`() {
        val adapter = mainSource(
            "com/android/everytalk/ui/components/markdown/MikePenzMarkdownRenderer.kt"
        )
        val mathRenderer = mainSource(
            "com/android/everytalk/ui/components/math/MathRenderer.kt"
        )

        assertTrue(adapter.contains("MathInline("))
        assertTrue(adapter.contains("MathBlock("))
        assertTrue(mathRenderer.contains("file:///android_asset/katex/index.html"))
        assertFalse(adapter.contains("StableLatexRenderer("))
    }

    private fun mainSource(relativePath: String): String {
        return projectFile("app/src/main/java/$relativePath").readText(Charsets.UTF_8)
    }

    private fun projectFile(relativePath: String): File {
        val workingDirectory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        return generateSequence(workingDirectory, File::getParentFile)
            .flatMap { directory ->
                sequenceOf(
                    File(directory, relativePath),
                    File(File(directory, "app1"), relativePath),
                )
            }
            .firstOrNull(File::isFile)
            ?: error("找不到文件：$relativePath")
    }
}
