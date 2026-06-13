package com.android.everytalk.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPreviewContentRulesTest {

    @Test
    fun `complete html document is loaded directly`() {
        val raw = """
            <!DOCTYPE html>
            <html>
            <head>
                <style>body { background: #0a0a0a; }</style>
            </head>
            <body>
                <canvas id="gameCanvas"></canvas>
                <script>function handleKey accomplishesKeyUp(e) {}</script>
            </body>
            </html>
        """.trimIndent()

        val result = prepareWebPreviewContent(raw, "html")

        assertEquals(null, result.templateFileName)
        assertTrue(result.content.contains("background: #0a0a0a"))
        assertTrue(result.content.contains("<canvas id=\"gameCanvas\"></canvas>"))
        assertFalse(result.content.contains("preview-container"))
    }

    @Test
    fun `html fragment keeps template wrapping`() {
        val result = prepareWebPreviewContent("<button>Run</button>", "html")

        assertEquals("templates/html.html", result.templateFileName)
        assertEquals("<button>Run</button>", result.content)
    }

    @Test
    fun `diagnostics are injected before preview code`() {
        val result = injectWebPreviewDiagnostics("<body><script>throw new Error('boom')</script></body>")

        assertTrue(result.contains("window.addEventListener('error'"))
        assertTrue(result.contains("console.error"))
        assertTrue(result.indexOf("window.addEventListener('error'") < result.indexOf("throw new Error"))
    }

    @Test
    fun `console message is formatted for preview overlay`() {
        val formatted = formatWebPreviewConsoleMessage("Unexpected identifier", 17, "inline.html")

        assertEquals("JS: Unexpected identifier (inline.html:17)", formatted)
    }
}
