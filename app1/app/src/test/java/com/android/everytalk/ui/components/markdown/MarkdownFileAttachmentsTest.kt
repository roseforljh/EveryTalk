package com.android.everytalk.ui.components.markdown

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.computer.ComputerDownloadStore
import com.android.everytalk.data.computer.DownloadTestProvider
import com.android.everytalk.ui.components.math.MathJaxSvgRenderer
import com.android.everytalk.ui.components.streaming.PreparedMarkdownDocument
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.StaticAiMarkdownNodeItem
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.facade.MessageItemsControllerTestAccess
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.AiMessageItem
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import io.mockk.mockk
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.junit.Before
import org.junit.After
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import com.android.everytalk.R
import com.android.everytalk.data.database.Converters
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.attachmentFileIcon
import com.android.everytalk.ui.components.attachmentFolderIntent
import com.android.everytalk.ui.components.attachmentViewIntent
import com.android.everytalk.ui.components.attachmentTypeColor
import com.android.everytalk.ui.components.attachmentPreviewKind
import com.android.everytalk.ui.components.AttachmentPreviewKind
import com.android.everytalk.ui.components.localAttachmentUri
import com.android.everytalk.ui.components.readAttachmentText
import com.android.everytalk.ui.components.MessageFilePreview
import com.android.everytalk.ui.components.streaming.PreparedMessage
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MarkdownFileAttachmentsTest {
    @get:Rule
    val composeRule = createComposeRule()
    private lateinit var mathRenderer: MathJaxSvgRenderer

    @Before
    fun setUpRenderer() {
        stopKoin()
        mathRenderer = MathJaxSvgRenderer(ApplicationProvider.getApplicationContext())
        startKoin { modules(module { single { mathRenderer } }) }
    }

    @After
    fun closeRenderer() {
        MessageItemsControllerTestAccess.closeAll()
        stopKoin()
        mathRenderer.close()
    }
    private val pdf = SelectedMediaItem.GenericFile(Uri.parse("content://test/report"), "file-pdf", "报告.pdf", "application/pdf",
        savedDownloadPath = "Download/App/AppTalk/报告 (1).pdf")
    private val sheet = SelectedMediaItem.GenericFile(Uri.parse("content://test/sheet"), "file-sheet", "预算.xlsx", "application/octet-stream")

    private fun render(content: String, files: List<SelectedMediaItem.GenericFile> = listOf(pdf, sheet)) = content.buildMarkdownAnnotatedString(
        style = TextStyle.Default,
        flavour = EveryTalkMarkdownFlavourDescriptor,
        annotatorSettings = DefaultAnnotatorSettings(
            linkTextSpanStyle = TextLinkStyles(SpanStyle()), codeSpanStyle = SpanStyle(),
            annotator = createPreparedMessageMarkdownAnnotator(PreparedMessage(content, emptyMap(), false, 1L),
                linkLogoDefinitions = markdownLinkLogoIndex(content).definitions, messageFiles = files),
        ).withRegularMarkdownStrongWeight(),
    )

    @Test
    fun `两个文件在各自正文位置形成链接和图标而非合并到末尾`() {
        val content = "先看[分析报告](everytalk-file:file-pdf)，然后这里是[预算表](everytalk-file:file-sheet)，最后继续解释。"
        val result = render(content)
        assertTrue(result.text.startsWith("先看"))
        assertTrue(result.text.endsWith("最后继续解释。"))
        assertTrue(result.text.indexOf("分析报告") < result.text.indexOf("然后这里"))
        assertEquals(listOf("everytalk-file:file-pdf", "everytalk-file:file-sheet"), result.getLinkAnnotations(0, result.length).map { (it.item as LinkAnnotation.Url).url })
        assertEquals(2, result.getStringAnnotations("androidx.compose.foundation.text.inlineContent", 0, result.length).size)
        assertEquals(setOf(pdf.id, sheet.id), inlineMessageFileIds(content, listOf(pdf, sheet)))
    }

    @Test
    fun `旧正文文件名及反引号文件名可打开而代码块与外部网址保持原状`() {
        // 库的字符串便捷方法只渲染首段；外部链接必须放在该段中才能验证其点击目标。
        val content = "请看报告.pdf，以及 `预算.xlsx`，另见[报告.pdf](https://example.com/report.pdf)。\n\n```text\n报告.pdf\n```"
        val result = render(content)
        val urls = result.getLinkAnnotations(0, result.length).map { (it.item as LinkAnnotation.Url).url }
        assertEquals(1, urls.count { it == "everytalk-file:file-pdf" })
        assertEquals(1, urls.count { it == "everytalk-file:file-sheet" })
        assertTrue("实际链接：$urls", "https://example.com/report.pdf" in urls)
        assertTrue(inlineMessageFileIds("```\n报告.pdf\n```", listOf(pdf)).isEmpty())
        assertTrue(inlineMessageFileIds("[报告.pdf](https://example.com/report.pdf)", listOf(pdf)).isEmpty())
    }

    @Test
    fun `同名文件必须使用ID且不存在的ID不能解析到其他文件`() {
        val other = pdf.copy(id = "other")
        assertTrue(inlineMessageFileIds("报告.pdf", listOf(pdf, other)).isEmpty())
        assertEquals(setOf(other.id), inlineMessageFileIds("[第二份](everytalk-file:other)", listOf(pdf, other)))
        assertNull(resolveMessageFile("everytalk-file:foreign", listOf(pdf)))
        assertNull(resolveMessageFile("file:///private/secret", listOf(pdf)))
        assertTrue(inlineMessageFileIds("[其他会话](everytalk-file:foreign)", listOf(pdf)).isEmpty())
    }

    @Test
    fun `系统打开只授予读取并定位保存回执中的真实文件夹`() {
        val view = attachmentViewIntent(pdf.uri, pdf.mimeType)
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(pdf.uri, view.data)
        assertEquals(pdf.mimeType, view.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, view.flags)
        assertEquals(pdf.uri, view.clipData!!.getItemAt(0).uri)
        assertThrows(IllegalArgumentException::class.java) { attachmentViewIntent(Uri.parse("file:///secret"), "text/plain") }
        val folder = requireNotNull(attachmentFolderIntent(pdf))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, folder.action)
        @Suppress("DEPRECATION")
        val initial = folder.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)
        assertEquals("primary:Download/App/AppTalk", DocumentsContract.getDocumentId(initial!!))
        assertNull(attachmentFolderIntent(pdf.copy(savedDownloadPath = "Download/App/AppTalk/../../secret")))
        assertNull(attachmentFolderIntent(sheet))
    }

    @Test
    fun `本地位置持久化且未知MIME仍使用GPT文件类型图标`() {
        val converter = Converters()
        assertEquals(listOf(pdf), converter.toSelectedMediaItemList(converter.fromSelectedMediaItemList(listOf(pdf))))
        assertEquals(R.drawable.ic_gpt_file_spreedsheet, attachmentFileIcon(sheet.mimeType, sheet.displayName))
        assertEquals(R.drawable.ic_gpt_file_pdf, attachmentFileIcon("application/octet-stream", "报告.PDF"))
        assertEquals(R.drawable.ic_gpt_code, attachmentFileIcon("text/plain", "main.ts"))
        assertEquals(R.drawable.ic_gpt_file_document, attachmentFileIcon("text/plain", "说明.md"))
        assertEquals(R.drawable.ic_gpt_file_zip, attachmentFileIcon("application/octet-stream", "附件.7z"))
        val icons = listOf("a.pdf", "a.xlsx", "a.pptx", "a.zip", "a.png", "a.mp3", "a.mp4", "a.ts", "a.docx")
            .map { attachmentFileIcon("application/octet-stream", it) }
        assertEquals(icons.size, icons.map { attachmentTypeColor(it, false) }.distinct().size)
        assertEquals(AttachmentPreviewKind.TEXT, attachmentPreviewKind(pdf.copy(displayName = "a.html", mimeType = "text/html")))
        assertNull(attachmentPreviewKind(sheet))
    }

    @Test
    fun `点击正文文件图标能显示打开应用和查看位置操作`() {
        val rendered = render("文件就在这里：[报告](everytalk-file:file-pdf)。后面继续正文。")
        composeRule.setContent {
            MaterialTheme {
                MessageFileAttachmentScope(listOf(pdf)) {
                    Text(rendered, inlineContent = messageFileInlineContent(listOf(pdf)))
                }
            }
        }
        composeRule.onNodeWithContentDescription("AI 文件操作：报告.pdf", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("打开文件").assertIsDisplayed()
        composeRule.onNodeWithText("用其他应用打开").assertIsDisplayed()
        composeRule.onNodeWithText("查看所在文件夹").assertIsDisplayed()
        composeRule.onNodeWithText("Download/App/AppTalk/报告 (1).pdf").assertIsDisplayed()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `Agent正文分段晚到附件刷新后显示可辨识链接并打开已保存CSV`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        DownloadTestProvider.install(context)
        val source = File.createTempFile("received-", ".csv", context.cacheDir).apply { writeText("序号,主机\n1,服务器") }
        val saved = ComputerDownloadStore(context).save(source, "everytalk-test3.csv", "text/csv")
        source.delete()
        val file = sheet.copy(uri = saved.uri, id = "downloaded-csv", displayName = saved.name,
            mimeType = "text/csv", savedDownloadPath = saved.path)
        val content = "这是第三个：[${file.displayName}](everytalk-file:${file.id})\n\n保存位置：${saved.path}"
        val message = Message(id = "agent-reply", text = content, sender = Sender.AI, contentStarted = true,
            executionTrace = listOf(ExecutionTraceEvent.Content(content)))
        val controller = MessageItemsControllerTestAccess.newController()
        controller.stateHolder.messages.add(message)
        val viewModel = mockk<AppViewModel>(relaxed = true)
        val darkTheme = androidx.compose.runtime.mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme(colorScheme = if (darkTheme.value) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
                // 与 ChatMessagesList 的 Agent 分段分支一致，不给测试额外补附件 Scope。
                val items by controller.chatListItems.collectAsState()
                items.filterIsInstance<ChatListItem.AiMessageContentSegment>().firstOrNull()?.let { item ->
                    AiMessageItem(message = item.message, text = item.text, maxWidth = 360.dp,
                        isStreaming = item.isStreaming, messageOutputType = item.message.outputType,
                        viewModel = viewModel, streamingRenderStateOverride = item.renderState)
                }
            }
        }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("这是第三个", substring = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodesWithContentDescription("AI 文件操作：${file.displayName}", useUnmergedTree = true).assertCountEquals(0)
        // 正文、执行记录均不变，只补下载回执；生产消息缓存必须让附件更新到正文和页脚。
        composeRule.runOnIdle { controller.stateHolder.messages[0] = message.copy(attachments = listOf(file)) }
        Snapshot.sendApplyNotifications()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("AI 文件操作：${file.displayName}", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val segment = controller.chatListItems.value.filterIsInstance<ChatListItem.AiMessageContentSegment>().first()
        assertEquals(listOf(file), segment.message.attachments)
        assertEquals(listOf(file), controller.chatListItems.value.filterIsInstance<ChatListItem.AiMessageFooter>().single().message.attachments)
        val paragraph = composeRule.onNodeWithText("这是第三个", substring = true)
        val body = paragraph.fetchSemanticsNode().config[SemanticsProperties.Text].first()
        val link = body.getLinkAnnotations(0, body.length).first().item as LinkAnnotation.Url
        assertEquals("everytalk-file:${file.id}", link.url)
        assertEquals(androidx.compose.ui.text.style.TextDecoration.Underline, link.styles?.style?.textDecoration)
        assertEquals(com.android.everytalk.ui.theme.lightChatColors.loadingIndicator, link.styles?.style?.color)
        // 截图使用深色主题：切换主题后仍应有蓝色下划线，不能被默认白色正文样式覆盖。
        composeRule.runOnIdle { darkTheme.value = true }
        val darkBody = paragraph.fetchSemanticsNode().config[SemanticsProperties.Text].first()
        val darkLink = darkBody.getLinkAnnotations(0, darkBody.length).first().item as LinkAnnotation.Url
        assertEquals(com.android.everytalk.ui.theme.darkChatColors.loadingIndicator, darkLink.styles?.style?.color)
        assertEquals(androidx.compose.ui.text.style.TextDecoration.Underline, darkLink.styles?.style?.textDecoration)
        val layouts = mutableListOf<TextLayoutResult>()
        paragraph.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        paragraph.performTouchInput { click(layouts.single().getBoundingBox(body.text.indexOf(file.displayName) + file.displayName.length / 2).center) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("序号,主机\n1,服务器").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("本地预览").assertIsDisplayed()
        assertNull(shadowOf(context).peekNextStartedActivity())
        composeRule.onNodeWithText("关闭预览").performClick()
        composeRule.onAllNodesWithContentDescription("AI 文件操作：${file.displayName}", useUnmergedTree = true)[0].performClick()
        composeRule.onNodeWithText("查看所在文件夹").assertIsDisplayed()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `已完成回复的实际分段组件保留正文文件链接及操作入口`() {
        // 复现截图：模型只写普通文件名和保存路径，底部去重后，静态正文必须仍能打开附件。
        val context = ApplicationProvider.getApplicationContext<Application>()
        val provider = DownloadTestProvider.install(context)
        val source = File.createTempFile("received-", ".md", context.cacheDir).apply { writeText("# 测试文件") }
        val saved = ComputerDownloadStore(context).save(source, "everytalk-test2.md", "text/markdown")
        source.delete()
        val file = pdf.copy(uri = saved.uri, id = "downloaded-md", displayName = "everytalk-test2.md", mimeType = "text/markdown",
            savedDownloadPath = "Download/App/AppTalk/everytalk-test2.md")
        val content = "第二个也发好了：everytalk-test2.md\n\n- 大小 390 字节，类型 text/markdown\n- 保存位置：Download/App/AppTalk/everytalk-test2.md"
        val prepared = StreamBlockParser.prepareMessage(content = content, messageId = "reply", contentVersion = 1L)
        val state = parseMarkdown(prepared.markdown, flavour = EveryTalkMarkdownFlavourDescriptor) as State.Success
        val document = PreparedMarkdownDocument(state, state.node.children, linkLogoIndex = markdownLinkLogoIndex(prepared.markdown, state))
        val message = Message(id = "reply", text = content, sender = Sender.AI, attachments = listOf(file))
        val item = ChatListItem.AiMarkdownNode(message, message.id, prepared, document, document.nodes,
            0, document.nodes.lastIndex, 0, emptyMap(), false)
        val viewModel = mockk<AppViewModel>(relaxed = true)
        assertEquals(setOf(file.id), inlineMessageFileIds(content, listOf(file)))
        composeRule.setContent {
            MaterialTheme {
                // 直接使用聊天列表的静态分段入口，不能在测试外部补附件 Scope 掩盖生产代码遗漏。
                StaticAiMarkdownNodeItem(item, 360.dp, viewModel, FootnoteNavigationState(), null)
            }
        }
        val body = composeRule.onNodeWithText("第二个也发好了", substring = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].first()
        assertTrue(body.getLinkAnnotations(0, body.length).any { (it.item as? LinkAnnotation.Url)?.url == "everytalk-file:${file.id}" })

        // 在实际排版位置点击文件名，确认经过消息 UriHandler，打开的也是本次下载文件。
        val layouts = mutableListOf<TextLayoutResult>()
        val paragraph = composeRule.onNodeWithText("第二个也发好了", substring = true)
        paragraph.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        repeat(3) {
            paragraph.performTouchInput { click(layouts.single().getBoundingBox(body.text.indexOf(file.displayName) + file.displayName.length / 2).center) }
            composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("# 测试文件").fetchSemanticsNodes().isNotEmpty() }
            assertNull(shadowOf(context).peekNextStartedActivity())
            assertEquals(1, provider.rows.size)
            composeRule.onNodeWithText("关闭预览").performClick()
        }

        composeRule.onAllNodesWithContentDescription("AI 文件操作：${file.displayName}", useUnmergedTree = true)[0].performClick()
        composeRule.onNodeWithText("打开文件").assertIsDisplayed()
        composeRule.onNodeWithText("用其他应用打开").assertIsDisplayed()
        composeRule.onNodeWithText("查看所在文件夹").assertIsDisplayed()
        composeRule.onNodeWithText("用其他应用打开").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { shadowOf(context).peekNextStartedActivity() != null }
        val chooser = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(file.uri, target?.data)

        composeRule.onAllNodesWithContentDescription("AI 文件操作：${file.displayName}", useUnmergedTree = true)[0].performClick()
        composeRule.onNodeWithText("查看所在文件夹").performClick()
        val folder = shadowOf(context).nextStartedActivity
        @Suppress("DEPRECATION")
        val initial = folder.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)
        assertEquals("primary:Download/App/AppTalk", DocumentsContract.getDocumentId(initial!!))
    }

    @Test
    fun `文本预览有大小上限且不能回源下载`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val provider = DownloadTestProvider.install(context)
        val source = File.createTempFile("preview-", ".txt", context.cacheDir).apply { writeText("a".repeat(600 * 1024)) }
        val saved = ComputerDownloadStore(context).save(source, "large.txt", "text/plain")
        source.delete()
        val result = readAttachmentText(context, saved.uri)
        assertEquals(512 * 1024, result.text.length)
        assertTrue(result.truncated)
        assertEquals(1, provider.rows.size)
        assertThrows(IllegalArgumentException::class.java) {
            localAttachmentUri(context, pdf.copy(uri = Uri.parse("https://example.com/file.pdf")))
        }
    }

    @Test
    fun `PDF不可读时明确提示并保留其他应用打开入口`() {
        // Robolectric 未实现原生 PDF 引擎；这里验证真实 UI 的读取失败分支，不伪造渲染成功。
        composeRule.setContent {
            MaterialTheme { MessageFilePreview(pdf.copy(uri = Uri.parse("content://missing/file.pdf"))) {} }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("无法预览此本地文件，可尝试用其他应用打开。").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("用其他应用打开").assertIsDisplayed()
        composeRule.onNodeWithText("关闭预览").assertIsDisplayed()
    }
}
