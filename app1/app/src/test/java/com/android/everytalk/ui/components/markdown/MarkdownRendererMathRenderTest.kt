package com.android.everytalk.ui.components.markdown

import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MarkdownRendererMathRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        stopKoin()
    }

    @Test
    fun `complex matrix markdown path still exposes raw latex markers`() {
        val markdown = """
            $$ M = \begin{bmatrix} \frac{\partial^2 f}{\partial x^2} & \frac{\partial^2 f}{\partial x \partial y} &
            \frac{\partial^2 f}{\partial x \partial z} \\ \frac{\partial^2 f}{\partial y \partial x} & \frac{\partial^2
            f}{\partial y^2} & \frac{\partial^2 f}{\partial y \partial z} \\ \frac{\partial^2 f}{\partial z \partial x} &
            \frac{\partial^2 f}{\partial z \partial y} & \frac{\partial^2 f}{\partial z^2} \end{bmatrix} $$
        """.trimIndent()

        var renderedText = ""
        composeRule.setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            val markwon = MarkwonCache.getOrCreate(
                context = context,
                isDark = false,
                textSize = MaterialTheme.typography.bodyMedium.fontSize.value.takeIf { it > 0f } ?: 16f
            )
            AndroidView(factory = {
                TextView(it)
            }, update = { tv ->
                val processed = preprocessAiMarkdown(markdown, isStreaming = false)
                val node = markwon.parse(processed)
                val spanned = markwon.render(node)
                markwon.setParsedMarkdown(tv, spanned)
                renderedText = tv.text.toString()
            })
        }

        composeRule.runOnIdle {
            assertTrue(renderedText.isNotBlank())
            assertEquals(renderedText, renderedText.trim())
            assertTrue(renderedText.contains("\\begin{bmatrix}"))
            assertTrue(renderedText.contains("\\partial"))
        }
    }

    @Test
    fun `preprocess should convert pure math block to bracket delimiters for markdown renderer`() {
        val markdown = """
            $$ M = \begin{bmatrix} \frac{\partial^2 f}{\partial x^2} & \frac{\partial^2 f}{\partial x \partial y} &
            \frac{\partial^2 f}{\partial x \partial z} \\ \frac{\partial^2 f}{\partial y \partial x} & \frac{\partial^2
            f}{\partial y^2} & \frac{\partial^2 f}{\partial y \partial z} \\ \frac{\partial^2 f}{\partial z \partial x} &
            \frac{\partial^2 f}{\partial z \partial y} & \frac{\partial^2 f}{\partial z^2} \end{bmatrix} $$
        """.trimIndent()

        val processed = preprocessAiMarkdown(markdown, isStreaming = false)

        assertTrue(processed.startsWith("\\["))
        assertTrue(processed.endsWith("\\]"))
        assertFalse(processed.contains("$$"))
        assertTrue(processed.contains("\\begin{bmatrix}"))
    }
}
