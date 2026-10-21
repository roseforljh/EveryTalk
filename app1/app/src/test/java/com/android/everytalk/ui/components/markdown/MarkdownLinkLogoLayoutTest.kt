package com.android.everytalk.ui.components.markdown

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.R
import com.android.everytalk.ui.components.streaming.PreparedMessage
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.markdownInlineContent
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MarkdownLinkLogoLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `内置网站和未知网站在深浅主题下直接显示且没有加载动画`() {
        val host = mutableStateOf("github.com")
        val dark = mutableStateOf(false)
        val context = ApplicationProvider.getApplicationContext<Application>()
        composeRule.setContent {
            MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                MarkdownLinkLogo(MarkdownLinkLogoRequest(host.value), Modifier.size(18.dp))
            }
        }
        // 遍历每个内置资源，既验证同步解码，也覆盖未知域名的地球回退。
        for (isDark in listOf(false, true)) {
            for (site in listOf(
                "github.com", "gitlab.com", "stackoverflow.com", "wikipedia.org", "x.com",
                "youtube.com", "reddit.com", "discord.com", "bilibili.com", "facebook.com",
                "instagram.com", "linkedin.com", "tiktok.com", "chatgpt.com", "claude.ai",
                "deepseek.com", "huggingface.co", "cloudflare.com", "vercel.com", "figma.com",
                "linear.app", "notion.so", "slack.com", "salesforce.com", "box.com", "dropbox.com",
                "google.com", "mail.google.com", "calendar.google.com", "drive.google.com", "unknown.example",
                "midjourney.com",
                "firefly.adobe.com",
                "stability.ai",
                "civitai.com",
                "ideogram.ai",
                "recraft.ai",
                "runwayml.com",
                "klingai.com",
                "jimeng.jianying.com",
                "lumalabs.ai",
                "bfl.ai",
                "gemini.google.com",
                "perplexity.ai",
                "grok.com",
                "qwen.ai",
                "doubao.com",
                "kimi.com",
                "modelscope.cn",
                "ollama.com",
                "microsoft.com",
                "apple.com",
                "baidu.com",
                "bing.com",
                "aws.amazon.com",
                "azure.com",
                "minimax.io",
                "zhihu.com",
                "weibo.com",
                "weixin.qq.com",
                "npmjs.com",
                "docker.com",
                "python.org",
                "react.dev",
                "nodejs.org",
                "typescriptlang.org",
                "supabase.com",
                "firebase.google.com",
                "pinterest.com",
                "telegram.org",
                "medium.com",
            )) {
                composeRule.runOnIdle {
                    host.value = site
                    dark.value = isDark
                }
                composeRule.onNodeWithContentDescription(context.getString(R.string.link_logo, site))
                    .fetchSemanticsNode("")
                composeRule.onNodeWithContentDescription(context.getString(R.string.link_logo_loading))
                    .assertDoesNotExist()
            }
        }
    }

    @Test
    fun `缺失索引在后台计算且旧正文任务不能覆盖新正文`() {
        val content = mutableStateOf("https://old.example.com")
        val layout = mutableStateOf(0)
        val calls = AtomicInteger()
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val calculationThread = AtomicReference<Thread>()
        var uiThread: Thread? = null
        var result = MarkdownLinkLogoIndex(emptyList(), emptyMap())
        val calculate: (String) -> MarkdownLinkLogoIndex = { text ->
            calculationThread.set(Thread.currentThread())
            calls.incrementAndGet()
            if (text.contains("old.example")) {
                oldStarted.countDown()
                check(releaseOld.await(5, TimeUnit.SECONDS))
            }
            markdownLinkLogoIndex(text)
        }
        try {
            composeRule.setContent {
                uiThread = Thread.currentThread()
                layout.value
                val index = rememberMarkdownLinkLogoIndex(
                    false, PreparedMessage(content.value, emptyMap(), false, 1L), null, calculate,
                )
                SideEffect { result = index }
            }
            assertTrue(oldStarted.await(5, TimeUnit.SECONDS))
            assertTrue(calculationThread.get() !== uiThread)
            composeRule.runOnIdle { content.value = "https://new.example.com" }
            composeRule.waitForIdle()
            composeRule.waitUntil(5_000) { result.requests.firstOrNull()?.host == "new.example.com" }
            releaseOld.countDown()
            repeat(3) {
                composeRule.runOnIdle { layout.value++ }
                composeRule.waitForIdle()
            }
            assertEquals("new.example.com", result.requests.single().host)
            assertEquals(2, calls.get())
        } finally { releaseOld.countDown() }
    }

    @Test
    fun `粗体美元区间在Compose文本节点中完整显示`() {
        val content = "1. **${'$'}22 ~ ${'$'}25**"

        composeRule.setContent {
            MaterialTheme {
                val bodyStyle = MaterialTheme.typography.bodyLarge
                Markdown(
                    content = content,
                    colors = markdownColor(
                        inlineCodeBackground = androidx.compose.ui.graphics.Color.Transparent,
                        tableBackground = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    typography = markdownTypography(
                        h1 = bodyStyle,
                        h2 = bodyStyle,
                        h3 = bodyStyle,
                        h4 = bodyStyle,
                        h5 = bodyStyle,
                        h6 = bodyStyle,
                        text = bodyStyle,
                        quote = bodyStyle,
                        paragraph = bodyStyle,
                        ordered = bodyStyle,
                        bullet = bodyStyle,
                        list = bodyStyle,
                        table = bodyStyle,
                        inlineCode = bodyStyle,
                        textLink = androidx.compose.ui.text.TextLinkStyles(),
                    ),
                    flavour = EveryTalkMarkdownFlavourDescriptor,
                    immediate = true,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText("${'$'}22 ~ ${'$'}25", substring = true)
            .fetchSemanticsNode("")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `长网址续行不缩进且中文后的图标始终紧贴链接开头`() {
        val firstUrl = "https://github.com/CYBERVERSE-Research/skyline-speeder"
        val secondUrl = "https://github.com/Kylin010/tcpfit"
        val content = mutableStateOf(firstUrl)
        val width = mutableStateOf(220.dp)
        val fontSize = mutableStateOf(16.sp)

        composeRule.setContent {
            MaterialTheme {
                val bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.value)
                val index = markdownLinkLogoIndex(content.value)
                Markdown(
                    content.value,
                    colors = markdownColor(),
                    typography = markdownTypography(
                        h1 = bodyStyle,
                        h2 = bodyStyle,
                        h3 = bodyStyle,
                        h4 = bodyStyle,
                        h5 = bodyStyle,
                        h6 = bodyStyle,
                        text = bodyStyle,
                        quote = bodyStyle,
                        paragraph = bodyStyle,
                        ordered = bodyStyle,
                        bullet = bodyStyle,
                        list = bodyStyle,
                        table = bodyStyle,
                        inlineCode = bodyStyle,
                        textLink = androidx.compose.ui.text.TextLinkStyles(),
                    ),
                    modifier = Modifier.width(width.value),
                    padding = markdownPadding(
                        block = 0.dp,
                        list = 0.dp,
                        listItemTop = 0.dp,
                        listItemBottom = 0.dp,
                        listIndent = 0.dp,
                    ),
                    annotator = createPreparedMessageMarkdownAnnotator(
                        PreparedMessage(content.value, emptyMap(), false, 1L),
                        linkLogoHosts = index.requests.map { it.host }.toSet(),
                    ),
                    inlineContent = markdownInlineContent(markdownLinkLogoInlineContent(index.requests)),
                    flavour = EveryTalkMarkdownFlavourDescriptor,
                    lookupLinks = true,
                    immediate = true,
                )
            }
        }

        // 用实际文字布局覆盖单独网址、中文混排及同域名的多个图标。
        for (text in listOf(firstUrl, "帮我看看这个项目和$secondUrl", "$firstUrl 帮我看看这个项目和$secondUrl 谁更牛逼")) {
            for (availableWidth in listOf(220.dp, 280.dp, 320.dp)) {
                for (size in listOf(16.sp, 24.sp)) {
                    composeRule.runOnIdle {
                        content.value = text
                        width.value = availableWidth
                        fontSize.value = size
                    }
                    val results = mutableListOf<TextLayoutResult>()
                    composeRule.onNodeWithText("https://github.com", substring = true)
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                    val layout = results.single()
                    val rendered = layout.layoutInput.text
                    val icons = rendered.getStringAnnotations(
                        "androidx.compose.foundation.text.inlineContent", 0, rendered.length,
                    )
                    val case = "$text / $availableWidth / $size"
                    assertEquals(case, if (text.contains(firstUrl) && text.contains(secondUrl)) 2 else 1, icons.size)
                    assertTrue(case, layout.lineCount > 1)
                    if (text == firstUrl) {
                        // 后续行从段落左边开始，不能继续给首行的小图标预留一列。
                        assertEquals(case, 0f, layout.getLineLeft(1), 1f)
                    }
                    icons.forEachIndexed { index, icon ->
                        val rect = requireNotNull(layout.placeholderRects[index])
                        val urlStart = icon.end + 1 // 图标后有一个零宽防断行符。
                        assertTrue(case, rendered.text.startsWith("https://", urlStart))
                        val line = layout.getLineForOffset(urlStart)
                        assertTrue("图标与网址必须在同一行：$case", rect.center.y in layout.getLineTop(line)..layout.getLineBottom(line))
                        assertEquals("图标必须紧贴网址：$case", rect.right, layout.getBoundingBox(urlStart).left, 1f)
                    }
                }
            }
        }
    }
}
