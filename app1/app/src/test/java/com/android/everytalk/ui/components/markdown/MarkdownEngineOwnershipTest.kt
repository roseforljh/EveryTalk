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
        assertTrue(source.contains("preparedMessage: PreparedMessage"))
        assertTrue(source.contains("MikePenzMarkdownRenderer("))
        assertFalse(source.contains("parseNativeStreamingMarkdownBlocks("))
        assertFalse(source.contains("NativeMarkdownBlocksSegment("))
    }

    @Test
    fun `生产消息入口只调用统一Markdown入口`() {
        val entryPoints = listOf(
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
    fun `数学公式由本地MathJax转SVG并由Compose绘制`() {
        val adapter = mainSource(
            "com/android/everytalk/ui/components/markdown/MikePenzMarkdownRenderer.kt"
        )
        val mathRenderer = mainSource(
            "com/android/everytalk/ui/components/math/MathRenderer.kt"
        )
        val mathJaxRuntime = mainSource(
            "com/android/everytalk/ui/components/math/MathJaxSvgRenderer.kt"
        )

        assertTrue(adapter.contains("MathInline("))
        assertTrue(adapter.contains("MathBlock("))
        assertTrue(adapter.contains("MarkdownInlineContent") || adapter.contains("markdownInlineContent"))
        assertTrue(adapter.contains("markdownAnnotator"))
        assertTrue(adapter.contains("appendInlineContent(link, inlineFormulaAlternateText(formula))"))
        assertTrue(adapter.contains("metrics.widthEm.em"))
        assertTrue(mathRenderer.contains("AsyncImage("))
        assertTrue(mathRenderer.contains("MathJaxRenderResult"))
        assertTrue(mathRenderer.contains("catch (_: TimeoutCancellationException)"))
        assertTrue(mathJaxRuntime.contains("class MathJaxSvgRenderer("))
        assertTrue(mathJaxRuntime.contains("private var webView: WebView?"))
        assertFalse(mathRenderer.contains("androidx.compose.ui.viewinterop.AndroidView"))
        assertFalse(mathRenderer.contains("import android.webkit.WebView"))
        assertFalse(mathRenderer.contains("katex", ignoreCase = true))
        assertFalse(adapter.contains("Base64"))
        assertFalse(adapter.contains("StableLatexRenderer("))
    }

    @Test
    fun `Markdown标准组件使用MikePenz且标题只映射移动端Material3字级`() {
        val adapter = mainSource(
            "com/android/everytalk/ui/components/markdown/MikePenzMarkdownRenderer.kt"
        )

        assertTrue(adapter.contains("colors = markdownColor()"))
        assertTrue(adapter.contains("h3 = MaterialTheme.typography.titleMedium"))
        assertTrue(adapter.contains("typography = typography"))
        assertTrue(adapter.contains("CodeBlockCard("))
        assertTrue(adapter.contains("imageTransformer = Coil3ImageTransformerImpl"))
        assertFalse(adapter.contains("headingStyle("))
        assertFalse(adapter.contains("FontStyle.Italic"))
        assertFalse(adapter.contains("table = {"))
        assertFalse(adapter.contains("image = {"))
        assertFalse(adapter.contains("markdownPadding("))
        assertFalse(adapter.contains("markdownDimens("))
        assertFalse(adapter.contains("TableRenderer("))
        assertFalse(adapter.contains("ProportionalAsyncImage("))
        assertFalse(adapter.contains("InlineMarkdownParser"))
        assertFalse(adapter.contains("normalizeNestedMarkdownCodeFences"))
        assertFalse(adapter.contains("unwrapMarkdownDocumentFences"))
        assertFalse(adapter.contains("normalizeInProgressTaskMarkers"))
        assertFalse(adapter.contains("StreamBlockParser"))
    }

    @Test
    fun `旧Markdown分块器和自定义表格解析器已删除`() {
        val messageProcessor = mainSource(
            "com/android/everytalk/util/messageprocessor/MessageProcessor.kt"
        )

        assertFalse(projectFileExists("app/src/main/java/com/android/everytalk/ui/components/ContentParser.kt"))
        assertFalse(projectFileExists("app/src/main/java/com/android/everytalk/util/cache/ContentParseCache.kt"))
        assertFalse(projectFileExists("app/src/main/java/com/android/everytalk/ui/components/table/TableUtils.kt"))
        assertFalse(messageProcessor.contains("ContentParser"))
        assertFalse(messageProcessor.contains("ContentPart"))
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

    private fun projectFileExists(relativePath: String): Boolean {
        val workingDirectory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        return generateSequence(workingDirectory, File::getParentFile)
            .flatMap { directory ->
                sequenceOf(
                    File(directory, relativePath),
                    File(File(directory, "app1"), relativePath),
                )
            }
            .any(File::isFile)
    }
}
