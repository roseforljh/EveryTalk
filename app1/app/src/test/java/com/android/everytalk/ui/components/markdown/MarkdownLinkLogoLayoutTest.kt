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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.R
import com.android.everytalk.ui.components.streaming.PreparedMessage
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
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
    fun `单独自动链接的 Logo 与链接处于同一行`() {
        val content = "https://x.com"
        val request = MarkdownLinkLogoRequest(
            host = "x.com",
        )
        val logoDescription = ApplicationProvider.getApplicationContext<Application>()
            .getString(R.string.link_logo, request.host)
        val logoFreeAnnotator = createPreparedMessageMarkdownAnnotator(
            preparedMessage = PreparedMessage(
                markdown = content,
                formulas = emptyMap(),
                hasPendingFormula = false,
                contentVersion = 1L,
            ),
        )
        val components = markdownComponents(
            paragraph = { model ->
                MarkdownSingleAutolinkLogoParagraph(
                    content = model.content,
                    node = model.node,
                    style = model.typography.paragraph,
                    request = request,
                    logoFreeAnnotator = logoFreeAnnotator,
                    modifier = Modifier.testTag("single-autolink-row"),
                )
            },
        )

        composeRule.setContent {
            MaterialTheme {
                val bodyStyle = MaterialTheme.typography.bodyLarge
                val typography = markdownTypography(
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
                )
                Markdown(
                    content,
                    colors = markdownColor(
                        inlineCodeBackground = androidx.compose.ui.graphics.Color.Transparent,
                        tableBackground = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    typography = typography,
                    modifier = Modifier.width(320.dp),
                    padding = markdownPadding(
                        block = 0.dp,
                        list = 0.dp,
                        listItemTop = 0.dp,
                        listItemBottom = 0.dp,
                        listIndent = 0.dp,
                    ),
                    annotator = logoFreeAnnotator,
                    components = components,
                    lookupLinks = true,
                    immediate = true,
                )
            }
        }
        composeRule.waitForIdle()

        val logoNode = composeRule
            .onNodeWithContentDescription(logoDescription)
            .fetchSemanticsNode("")
        val linkNode = composeRule
            .onNodeWithText(content, substring = true)
            .fetchSemanticsNode("")
        val rowBounds = composeRule
            .onNodeWithTag("single-autolink-row")
            .fetchSemanticsNode("")
            .boundsInRoot
        val logoBounds = logoNode.boundsInRoot
        val linkBounds = linkNode.boundsInRoot

        assertTrue(rowBounds.width < 320f)
        assertTrue(logoBounds.top < linkBounds.bottom)
        assertTrue(kotlin.math.abs(logoBounds.top - linkBounds.top) < 32f)
        assertTrue(logoBounds.left < linkBounds.left)
    }
}
