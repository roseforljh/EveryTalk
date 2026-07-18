package com.android.everytalk.ui.components.streamdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamdownRenderConfigParityTest {
    @Test
    fun `config declares official streamdown props and android extension props`() {
        val propNames = StreamdownRenderConfig.supportedPropNames

        assertEquals(expectedPropNames, propNames)
    }

    @Test
    fun `defaults match safe android streamdown baseline`() {
        val config = StreamdownRenderConfig()

        assertEquals(StreamdownRenderMode.Streaming, config.mode)
        assertTrue(config.parseIncompleteMarkdown)
        assertFalse(config.isAnimating)
        assertEquals(StreamdownCaretStyle.Block, config.caret)
        assertEquals(StreamdownTextDirection.Auto, config.dir)
        assertEquals(listOf("github-light", "github-dark"), config.shikiTheme)
        assertEquals(StreamdownControlsConfig.Enabled, config.controls)
        assertNull(config.cdnUrl)
        assertFalse(config.loadRemoteAssets)
    }

    private val expectedPropNames = setOf(
        "children",
        "mode",
        "parseIncompleteMarkdown",
        "remend",
        "isAnimating",
        "className",
        "shikiTheme",
        "components",
        "allowedTags",
        "plugins",
        "remarkPlugins",
        "rehypePlugins",
        "allowedElements",
        "disallowedElements",
        "allowElement",
        "unwrapDisallowed",
        "skipHtml",
        "urlTransform",
        "caret",
        "controls",
        "mermaid",
        "linkSafety",
        "cdnUrl",
        "BlockComponent",
        "parseMarkdownIntoBlocksFn",
        "preprocess",
        "defer",
        "smooth",
        "animated",
        "security",
        "remarkRehypeOptions",
        "componentsByLanguage",
        "icons",
        "translations",
        "dir",
        "literalTagContent",
    )
}
