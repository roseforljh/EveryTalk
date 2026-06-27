package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatMessagesListRenderRouteTest {

    @Test
    fun `chat messages list should not call enhanced markdown text directly`() {
        val source = chatMessagesListSource()

        assertFalse(source.contains("EnhancedMarkdownText("))
    }

    @Test
    fun `source stripped ai answer should rebuild stream blocks for display content`() {
        val effectiveContent = """
            正文第一段。

            ## Sources
            - [Example](https://example.com)
        """.trimIndent()
        val displayContent = "正文第一段。"

        assertTrue(
            shouldBuildSourceStrippedRenderBlocks(
                messageOutputType = "",
                extractedSourceCount = 1,
                effectiveContent = effectiveContent,
                displayContent = displayContent,
            )
        )
    }

    @Test
    fun `code output should not rebuild source stripped stream blocks`() {
        assertFalse(
            shouldBuildSourceStrippedRenderBlocks(
                messageOutputType = "code",
                extractedSourceCount = 1,
                effectiveContent = "正文\n\n## Sources\n- [Example](https://example.com)",
                displayContent = "正文",
            )
        )
    }

    @Test
    fun `plain ai answer without upstream blocks should build local stream blocks`() {
        assertTrue(
            shouldBuildLocalRenderBlocks(
                messageOutputType = "",
                displayContent = "普通 AI 回复",
                hasUpstreamBlocks = false,
                hasStreamingBlocks = false,
                hasSourceStrippedBlocks = false,
                hasExtractedSources = false,
            )
        )
    }

    @Test
    fun `code output should build local stream blocks`() {
        assertTrue(
            shouldBuildLocalRenderBlocks(
                messageOutputType = "code",
                displayContent = "println(\"hi\")",
                hasUpstreamBlocks = false,
                hasStreamingBlocks = false,
                hasSourceStrippedBlocks = false,
                hasExtractedSources = false,
            )
        )
    }

    @Test
    fun `source extracted answer should not build duplicate local stream blocks`() {
        assertFalse(
            shouldBuildLocalRenderBlocks(
                messageOutputType = "",
                displayContent = "正文",
                hasUpstreamBlocks = false,
                hasStreamingBlocks = false,
                hasSourceStrippedBlocks = true,
                hasExtractedSources = true,
            )
        )
    }

    private fun chatMessagesListSource(): String {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatMessagesList.kt"),
            File("app/src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatMessagesList.kt"),
            File("app1/app/src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatMessagesList.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
        requireNotNull(sourceFile) { "找不到 ChatMessagesList.kt" }
        return sourceFile.readText(Charsets.UTF_8)
    }
}
