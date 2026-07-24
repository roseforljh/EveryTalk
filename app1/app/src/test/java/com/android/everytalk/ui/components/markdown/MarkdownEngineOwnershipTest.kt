package com.android.everytalk.ui.components.markdown

import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.junit.Assert.assertEquals
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
    fun `历史完成态Markdown分块继续复用MikePenz原生组件环境`() {
        val entry = mainSource(
            "com/android/everytalk/ui/components/streaming/StreamBlocksRenderer.kt"
        )
        val adapter = mainSources(
            "com/android/everytalk/ui/components/markdown/MikePenzMarkdownRenderer.kt",
            "com/android/everytalk/ui/components/markdown/MarkdownRendererNodes.kt",
            "com/android/everytalk/ui/components/markdown/MarkdownLinkImages.kt",
            "com/android/everytalk/ui/components/markdown/MarkdownRendererConstants.kt",
            "com/android/everytalk/ui/components/markdown/MarkdownStreamingState.kt",
        )

        assertTrue(entry.contains("fun UnifiedMarkdownNodesRenderer("))
        assertTrue(entry.contains("preparedMarkdownDocument: PreparedMarkdownDocument"))
        assertTrue(adapter.contains("fun MikePenzMarkdownNodesRenderer("))
        assertTrue(adapter.contains("nodes.forEachIndexed { index, node ->"))
        assertTrue(adapter.contains("MarkdownElement("))
        assertTrue(adapter.contains("node = node"))
        assertTrue(adapter.contains("components = components"))
        assertTrue(adapter.contains("content = state.content"))
    }

    @Test
    fun `Markdown水平线由自身统一控制对称间距`() {
        val renderer = mainSources(
            "com/android/everytalk/ui/components/markdown/MikePenzMarkdownRenderer.kt",
            "com/android/everytalk/ui/components/markdown/MarkdownRendererNodes.kt",
        )
        val style = mainSource(
            "com/android/everytalk/ui/components/markdown/ChatMarkdownTextStyle.kt"
        )

        assertTrue(renderer.contains("nodeType == MarkdownTokenTypes.HORIZONTAL_RULE"))
        assertTrue(renderer.contains("hasHorizontalRuleNeighbor(nodes, index"))
        assertTrue(renderer.contains("contextNodes = preparedMarkdownDocument.nodes"))
        assertTrue(renderer.contains("firstNodeIndex = selectedNodeStartIndex"))
        assertTrue(renderer.contains("top = LocalMarkdownHorizontalRuleTopPadding.current"))
        assertTrue(renderer.contains("bottom = ChatMarkdownTextStyle.HORIZONTAL_RULE_VERTICAL_PADDING_DP.dp"))
        assertTrue(style.contains("HORIZONTAL_RULE_VERTICAL_PADDING_DP = 24f"))
    }

    @Test
    fun `Markdown水平线相邻空行不再叠加块间距`() {
        val state = parseMarkdown("段落\n\n---\n\n## 标题", lookupLinks = false) as State.Success
        val nodes = state.node.children
        val paragraphIndex = nodes.indexOfFirst { it.type == MarkdownElementTypes.PARAGRAPH }
        val horizontalRuleIndex = nodes.indexOfFirst { it.type == MarkdownTokenTypes.HORIZONTAL_RULE }
        val headingIndex = nodes.indexOfFirst { it.type == MarkdownElementTypes.ATX_2 }

        assertTrue(shouldIncludeMarkdownNodeSpacer(nodes, paragraphIndex))
        assertFalse(shouldIncludeMarkdownNodeSpacer(nodes, horizontalRuleIndex))
        nodes.indices
            .filter { nodes[it].type == MarkdownTokenTypes.EOL }
            .forEach { assertFalse(shouldIncludeMarkdownNodeSpacer(nodes, it)) }
        assertFalse(shouldIncludeMarkdownNodeSpacer(nodes, headingIndex))

        val horizontalRuleBlock = listOf(nodes[horizontalRuleIndex])
        assertEquals(
            horizontalRuleIndex,
            markdownNodeStartIndex(nodes, horizontalRuleBlock),
        )
        assertFalse(
            shouldIncludeMarkdownNodeSpacer(
                nodes,
                markdownNodeStartIndex(nodes, horizontalRuleBlock),
            )
        )
    }

    @Test
    fun `生产消息入口只调用统一Markdown入口`() {
        val entryPoints = listOf(
            "com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatMessagesList.kt",
            "com/android/everytalk/ui/screens/ImageGeneration/ImageGenerationMessagesList.kt",
            "com/android/everytalk/ui/screens/BubbleMain/Main/BubbleContentTypes.kt",
        )

        entryPoints.forEach { path ->
            val source = if (path.endsWith("ChatMessagesList.kt")) {
                mainSources(
                    path,
                    "com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatAiMessageComponents.kt",
                )
            } else if (path.endsWith("ImageGenerationMessagesList.kt")) {
                mainSources(
                    path,
                    "com/android/everytalk/ui/screens/ImageGeneration/ImageGenerationMessagesListContent.kt",
                    "com/android/everytalk/ui/screens/ImageGeneration/ImageGenerationAiMessageItem.kt",
                )
            } else {
                mainSource(path)
            }
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
        val adapter = mainSources(
            "com/android/everytalk/ui/components/markdown/MikePenzMarkdownRenderer.kt",
            "com/android/everytalk/ui/components/markdown/MarkdownRendererNodes.kt",
            "com/android/everytalk/ui/components/markdown/MarkdownLinkImages.kt",
            "com/android/everytalk/ui/components/markdown/MarkdownRendererConstants.kt",
            "com/android/everytalk/ui/components/markdown/MarkdownStreamingState.kt",
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
        val adapter = mainSources(
            "com/android/everytalk/ui/components/markdown/MikePenzMarkdownRenderer.kt",
            "com/android/everytalk/ui/components/markdown/MarkdownRendererNodes.kt",
            "com/android/everytalk/ui/components/markdown/MarkdownLinkImages.kt",
            "com/android/everytalk/ui/components/markdown/MarkdownRendererConstants.kt",
            "com/android/everytalk/ui/components/markdown/MarkdownStreamingState.kt",
        )

        assertTrue(adapter.contains("val markdownColors = markdownColor("))
        assertTrue(adapter.contains("inlineCodeBackground = Color.Transparent"))
        assertTrue(adapter.contains("val bodyStyle = MaterialTheme.typography.bodyLarge.copy("))
        assertTrue(adapter.contains("fontSize = ChatMarkdownTextStyle.BODY_FONT_SIZE_SP.sp"))
        assertTrue(adapter.contains("lineHeight = ChatMarkdownTextStyle.BODY_LINE_HEIGHT_SP.sp"))
        assertTrue(adapter.contains("h1 = bodyStyle.copy("))
        assertTrue(adapter.contains("fontSize = ChatMarkdownTextStyle.headingFontSizeSp(1).sp"))
        assertTrue(adapter.contains("h3 = bodyStyle.copy("))
        assertTrue(adapter.contains("fontSize = ChatMarkdownTextStyle.headingFontSizeSp(3).sp"))
        assertTrue(adapter.contains("inlineCode = bodyStyle.copy("))
        assertTrue(adapter.contains("fontSize = 13.sp"))
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
        assertTrue(adapter.contains("block = ChatMarkdownTextStyle.SPACING_PARAGRAPH_DP.dp"))
        assertTrue(adapter.contains("list = ChatMarkdownTextStyle.LIST_TOP_LEVEL_ITEM_SPACING_DP.dp"))
        assertTrue(adapter.contains("listIndent = ChatMarkdownTextStyle.LIST_NESTED_INDENT_DP.dp"))
        val style = mainSource(
            "com/android/everytalk/ui/components/markdown/ChatMarkdownTextStyle.kt"
        )
        assertTrue(style.contains("BODY_FONT_SIZE_SP = 16f"))
        assertTrue(style.contains("BODY_LINE_HEIGHT_SP = 26f"))
        assertTrue(style.contains("1 -> 22f"))
        assertTrue(style.contains("3 -> 18f"))
        assertTrue(style.contains("LIST_ITEM_SPACING_DP = 8f"))
        assertTrue(style.contains("SPACING_PARAGRAPH_DP = 8f"))
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
        val adapter = mainSources(
            "com/android/everytalk/ui/components/markdown/MikePenzMarkdownRenderer.kt",
            "com/android/everytalk/ui/components/markdown/MarkdownRendererNodes.kt",
            "com/android/everytalk/ui/components/markdown/MarkdownLinkImages.kt",
            "com/android/everytalk/ui/components/markdown/MarkdownRendererConstants.kt",
            "com/android/everytalk/ui/components/markdown/MarkdownStreamingState.kt",
        )

        assertTrue(adapter.contains("rememberStreamingMarkdownState("))
        assertTrue(adapter.contains("PrepareStreamingMarkdownRebase("))
        assertTrue(adapter.contains("mutableStateOf<StreamingMarkdownBundle?>(null)"))
        assertTrue(adapter.contains("request.result.await()"))
        assertTrue(adapter.contains("activeBundle.value = StreamingMarkdownBundle("))
        assertTrue(adapter.contains("visibleStreamingBundle?.preparedMessage ?: preparedMessage"))
        assertFalse(adapter.contains("key(visibleStreamingMarkdownState)"))
        assertTrue(adapter.contains("streamingMarkdownState = visibleStreamingMarkdownState"))
        assertFalse(adapter.contains("MarkdownStreamingNodesSuccess("))
        assertFalse(adapter.contains("streamingContent = visibleStreamingMarkdownState.content.toString()"))
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

    private fun mainSources(vararg relativePaths: String): String =
        relativePaths.joinToString("\n") { relativePath -> mainSource(relativePath) }

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
