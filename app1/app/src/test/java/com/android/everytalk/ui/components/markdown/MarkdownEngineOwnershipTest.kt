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
        assertTrue(mathRenderer.contains("withContext(Dispatchers.Default)"))
        assertTrue(mathJaxRuntime.contains("class MathJaxSvgRenderer("))
        assertTrue(mathJaxRuntime.contains("private var webView: WebView?"))
        assertFalse(mathRenderer.contains("androidx.compose.ui.viewinterop.AndroidView"))
        assertFalse(mathRenderer.contains("import android.webkit.WebView"))
        assertFalse(mathRenderer.contains("katex", ignoreCase = true))
        assertFalse(adapter.contains("Base64"))
        assertFalse(adapter.contains("StableLatexRenderer("))
    }

    @Test
    fun `Markdown标准组件使用MikePenz并集中配置移动端视觉层级`() {
        val adapter = mainSource(
            "com/android/everytalk/ui/components/markdown/MikePenzMarkdownRenderer.kt"
        )

        assertTrue(adapter.contains("val markdownColors = markdownColor("))
        assertTrue(adapter.contains("inlineCodeBackground = Color.Transparent"))
        assertTrue(adapter.contains("val bodyStyle = MaterialTheme.typography.bodyLarge.copy("))
        assertTrue(adapter.contains("fontSize = 16.sp"))
        assertTrue(adapter.contains("lineHeight = 24.sp"))
        assertTrue(adapter.contains("h1 = bodyStyle.copy("))
        assertTrue(adapter.contains("fontSize = 24.sp"))
        assertTrue(adapter.contains("h3 = bodyStyle.copy("))
        assertTrue(adapter.contains("fontSize = 20.sp"))
        assertTrue(adapter.contains("inlineCode = bodyStyle.copy("))
        assertTrue(adapter.contains("fontSize = 14.sp"))
        assertTrue(adapter.contains("fontFamily = FontFamily.Monospace"))
        assertTrue(adapter.contains("MARKDOWN_LINK_LIGHT_COLOR = Color(0xFF3D7DB5)"))
        assertTrue(adapter.contains("MARKDOWN_LINK_DARK_COLOR = Color(0xFF8FC9FF)"))
        assertTrue(adapter.contains("val markdownLinkColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)"))
        assertTrue(adapter.contains("color = markdownLinkColor"))
        assertTrue(adapter.contains("textDecoration = TextDecoration.None"))
        assertTrue(adapter.contains("markdownLinkLogoRequests("))
        assertTrue(adapter.contains("markdownLinkLogoInlineContent("))
        assertTrue(adapter.contains("createPreparedMessageMarkdownAnnotator("))
        assertTrue(adapter.contains("linkFaviconUrl("))
        assertTrue(adapter.contains("val padding = markdownPadding("))
        assertTrue(adapter.contains("block = 3.dp"))
        assertTrue(adapter.contains("list = 4.dp"))
        assertTrue(adapter.contains("listIndent = 22.dp"))
        assertTrue(adapter.contains("summaryAnnotatorSettings = annotatorSettings("))
        assertTrue(adapter.contains("annotator = annotator"))
        assertTrue(adapter.contains("typography = typography"))
        assertTrue(adapter.contains("padding = padding"))
        assertTrue(adapter.contains("retainState = true"))
        assertTrue(adapter.contains("CodeBlockCard("))
        assertTrue(adapter.contains("MarkdownHeader("))
        assertTrue(adapter.contains("MarkdownParagraph("))
        assertTrue(adapter.contains("EveryTalkMarkdownTable("))
        assertTrue(adapter.contains("MarkdownTableHeader("))
        assertTrue(adapter.contains("MarkdownTableRow("))
        assertTrue(adapter.contains("maxLines = Int.MAX_VALUE"))
        assertTrue(adapter.contains("overflow = TextOverflow.Clip"))
        assertTrue(adapter.contains("tableBackground = Color.Transparent"))
        assertTrue(adapter.contains("markdownTableEdgeVisibility("))
        assertTrue(adapter.contains("bodyFootnoteFallbackTargets"))
        assertTrue(adapter.contains("fallbackTargetUris = bodyFootnoteFallbackTargets"))
        assertTrue(adapter.contains("imageTransformer = EveryTalkMarkdownImageTransformer"))
        assertTrue(adapter.contains("ImageTransformer by Coil3ImageTransformerImpl"))
        assertTrue(adapter.contains("MarkdownInlineImageWithFailure("))
        assertTrue(adapter.contains("currentImageClickCallback.value"))
        assertTrue(adapter.contains(".markdownImageClick(model.content, onImageClick)"))
        assertTrue(adapter.contains("MarkdownImageLoading("))
        assertTrue(adapter.contains("图片加载失败"))
        assertFalse(adapter.contains("headingStyle("))
        assertFalse(adapter.contains("FontStyle.Italic"))
        assertFalse(adapter.contains("image = {"))
        assertFalse(adapter.contains("orderedList = {"))
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
    fun `AI流式Markdown使用MikePenz稳定AST并可从非追加改写恢复`() {
        val adapter = mainSource(
            "com/android/everytalk/ui/components/markdown/MikePenzMarkdownRenderer.kt"
        )

        assertTrue(adapter.contains("rememberStreamingMarkdownState("))
        assertTrue(adapter.contains("PrepareStreamingMarkdownRebase("))
        assertTrue(adapter.contains("mutableStateOf<StreamingMarkdownBundle?>(null)"))
        assertTrue(adapter.contains("request.result.await()"))
        assertTrue(adapter.contains("activeBundle.value = StreamingMarkdownBundle("))
        assertTrue(adapter.contains("visibleStreamingBundle?.preparedMessage ?: preparedMessage"))
        assertFalse(adapter.contains("key(visibleStreamingMarkdownState)"))
        assertTrue(adapter.contains("streamingMarkdownState = visibleStreamingMarkdownState"))
        assertTrue(adapter.contains("appendOnlyMarkdownDelta("))
        assertTrue(adapter.contains("bundle.state.append(appendedDelta)"))
        assertTrue(adapter.contains("bundle.copy(preparedMessage = nextPreparedMessage)"))
        assertTrue(adapter.contains("retainState = true"))
        assertFalse(adapter.contains("useStaticFallback"))
    }

    @Test
    fun `流式Markdown只接受严格追加内容`() {
        assertTrue(appendOnlyMarkdownDelta("", "第一段") == "第一段")
        assertTrue(appendOnlyMarkdownDelta("第一段", "第一段\n\n第二段") == "\n\n第二段")
        assertTrue(appendOnlyMarkdownDelta("完整内容", "完整内容") == "")
        assertTrue(appendOnlyMarkdownDelta("原始公式 ${'$'}x", "公式占位") == null)
        assertTrue(appendOnlyMarkdownDelta("更长内容", "短") == null)
    }

    @Test
    fun `非追加改写重建基线后继续接受后续增量`() {
        var appendedContent = "原始公式 ${'$'}x"
        val rebasedContent = "公式占位"

        assertTrue(appendOnlyMarkdownDelta(appendedContent, rebasedContent) == null)
        appendedContent = rebasedContent

        repeat(20) { index ->
            val nextContent = "$appendedContent 追加$index"
            assertTrue(appendOnlyMarkdownDelta(appendedContent, nextContent) == " 追加$index")
            appendedContent = nextContent
        }
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
