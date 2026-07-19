package com.android.everytalk.ui.components

import java.io.File
import java.net.URI
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
    fun `ordinary html becomes ready when the main page finishes`() {
        val prepared = prepareWebPreviewContent("<p>Hello</p>", "html")

        assertFalse(prepared.requiresExplicitCompletionSignal)
        assertTrue(
            shouldMarkWebPreviewReadyOnPageFinished(
                requiresExplicitCompletionSignal = prepared.requiresExplicitCompletionSignal,
                hasError = false,
            )
        )
    }

    @Test
    fun `chart templates stay loading until they report completion`() {
        listOf("mermaid", "echarts", "chartjs", "flowchart", "flow", "vega", "vega-lite")
            .forEach { language ->
                val prepared = prepareWebPreviewContent("sample", language)

                assertTrue(language, prepared.requiresExplicitCompletionSignal)
                assertFalse(
                    language,
                    shouldMarkWebPreviewReadyOnPageFinished(
                        requiresExplicitCompletionSignal = prepared.requiresExplicitCompletionSignal,
                        hasError = false,
                    )
                )
            }
    }

    @Test
    fun `template build failure starts in error state`() {
        assertEquals(WebPreviewLoadState.ERROR, resolveInitialWebPreviewLoadState("Missing template"))
        assertEquals(WebPreviewLoadState.LOADING, resolveInitialWebPreviewLoadState(null))
    }

    @Test
    fun `completion timeout only fails an unresolved explicit preview`() {
        assertTrue(
            shouldMarkWebPreviewCompletionTimeoutError(
                requiresExplicitCompletionSignal = true,
                completionReported = false,
                hasError = false,
            )
        )
        assertFalse(
            shouldMarkWebPreviewCompletionTimeoutError(
                requiresExplicitCompletionSignal = true,
                completionReported = true,
                hasError = false,
            )
        )
        assertFalse(
            shouldMarkWebPreviewCompletionTimeoutError(
                requiresExplicitCompletionSignal = true,
                completionReported = false,
                hasError = true,
            )
        )
        assertFalse(
            shouldMarkWebPreviewCompletionTimeoutError(
                requiresExplicitCompletionSignal = false,
                completionReported = false,
                hasError = false,
            )
        )
    }

    @Test
    fun `explicit ready console signal completes a chart preview`() {
        assertEquals(
            WebPreviewCompletionSignal.Ready,
            parseWebPreviewCompletionSignal(
                message = "__EVERYTALK_PREVIEW_READY__:load-2",
                expectedToken = "load-2",
            ),
        )
    }

    @Test
    fun `explicit error console signal fails a chart preview with its message`() {
        assertEquals(
            WebPreviewCompletionSignal.Error("Invalid Vega spec"),
            parseWebPreviewCompletionSignal(
                message = "__EVERYTALK_PREVIEW_ERROR__:load-2:Invalid Vega spec",
                expectedToken = "load-2",
            ),
        )
    }

    @Test
    fun `completion signal from an old preview load is ignored`() {
        assertEquals(
            null,
            parseWebPreviewCompletionSignal(
                message = "__EVERYTALK_PREVIEW_READY__:load-1",
                expectedToken = "load-2",
            ),
        )
    }

    @Test
    fun `page finished callback from an old preview load is ignored`() {
        assertEquals(
            "/android_asset/mermaid/mermaid.min.js",
            URI(buildWebPreviewBaseUrl("load-2")).resolve("../mermaid/mermaid.min.js").path,
        )
        assertTrue(
            shouldHandleWebPreviewPageFinished(
                url = buildWebPreviewBaseUrl("load-2"),
                expectedToken = "load-2",
            )
        )
        assertFalse(
            shouldHandleWebPreviewPageFinished(
                url = buildWebPreviewBaseUrl("load-1"),
                expectedToken = "load-2",
            )
        )
    }

    @Test
    fun `every chart template reports explicit success and failure`() {
        listOf("mermaid.html", "vega.html", "echarts.html", "chartjs.html", "flowchart.html")
            .forEach { templateName ->
                val template = readMainAsset("templates/$templateName")

                assertTrue(templateName, template.contains("window.__everytalkPreviewReady"))
                assertTrue(templateName, template.contains("window.__everytalkPreviewError"))
                assertFalse(templateName, template.contains("setTimeout("))
            }
    }

    @Test
    fun `chartjs asset is the standalone browser build`() {
        val chartJs = readMainAsset("chartjs/chart.min.js").trimStart()

        assertTrue(chartJs.contains("Chart.js v4.5.0"))
        assertFalse(chartJs.startsWith("import"))
        assertTrue(chartJs.contains(".Chart=e()"))
    }

    @Test
    fun `diagnostics are injected before preview code`() {
        val result = injectWebPreviewDiagnostics(
            raw = "<body><script>throw new Error('boom')</script></body>",
            completionToken = "load-1",
        )

        assertTrue(result.contains("window.addEventListener('error'"))
        assertTrue(result.contains("console.error"))
        assertTrue(result.indexOf("window.addEventListener('error'") < result.indexOf("throw new Error"))
    }

    @Test
    fun `diagnostics are injected before scripts already present in head`() {
        val result = injectWebPreviewDiagnostics(
            raw = "<html><head><script src=\"../chartjs/chart.min.js\"></script></head><body></body></html>",
            completionToken = "load-1",
        )

        assertTrue(
            result.indexOf("id=\"everytalk-preview-diagnostics\"") <
                result.indexOf("src=\"../chartjs/chart.min.js\"")
        )
    }

    @Test
    fun `all diagnostics errors report a token bound completion signal`() {
        val result = injectWebPreviewDiagnostics(
            raw = "<html><head></head><body></body></html>",
            completionToken = "load-1",
        )

        assertTrue(result.contains("reportPreviewError('JS'"))
        assertTrue(result.contains("reportPreviewError('Promise'"))
        assertTrue(result.contains("reportPreviewError('Console ERROR'"))
        assertTrue(result.contains("__EVERYTALK_PREVIEW_ERROR__:"))
        assertTrue(result.contains("load-1"))
    }

    @Test
    fun `diagnostics expose explicit completion reporting helpers`() {
        val result = injectWebPreviewDiagnostics(
            raw = "<body></body>",
            completionToken = "load-1",
        )

        assertTrue(result.contains("window.__everytalkPreviewReady"))
        assertTrue(result.contains("window.__everytalkPreviewError"))
        assertTrue(result.contains("__EVERYTALK_PREVIEW_READY__:"))
        assertTrue(result.contains("__EVERYTALK_PREVIEW_ERROR__:"))
        assertTrue(result.contains("load-1"))
    }

    @Test
    fun `user content mentioning the diagnostics variable still receives diagnostics`() {
        val result = injectWebPreviewDiagnostics(
            raw = "<body><p>__everytalkPreviewDiagnosticsInstalled</p></body>",
            completionToken = "load-1",
        )

        assertTrue(result.contains("id=\"everytalk-preview-diagnostics\""))
        assertTrue(result.contains("window.addEventListener('error'"))
    }

    @Test
    fun `mermaid source is inserted before diagnostics are added to the completed html`() {
        val source = "graph TD\nA[开始] --> B[结束]"
        val prepared = prepareWebPreviewContent(source, "mermaid")
        val template = """
            <!DOCTYPE html>
            <html>
            <head></head>
            <body>
                <div class="mermaid"><!-- CONTENT_PLACEHOLDER --></div>
            </body>
            </html>
        """.trimIndent()

        val result = buildWebPreviewDocument(
            content = prepared.content,
            template = template,
            completionToken = "load-1",
        )
        val mermaidContent = result
            .substringAfter("<div class=\"mermaid\">")
            .substringBefore("</div>")

        assertEquals(prepared.content, mermaidContent)
        assertFalse(mermaidContent.contains("__everytalkPreviewDiagnosticsInstalled"))
        assertTrue(result.indexOf("__everytalkPreviewDiagnosticsInstalled") < result.indexOf("<body>"))
    }

    @Test
    fun `console message is formatted for preview overlay`() {
        val formatted = formatWebPreviewConsoleMessage("Unexpected identifier", 17, "inline.html")

        assertEquals("JS: Unexpected identifier (inline.html:17)", formatted)
    }

    @Test
    fun `only current main frame loading errors change preview to error state`() {
        val currentToken = "load-2"

        assertTrue(
            shouldMarkWebPreviewLoadError(
                isForMainFrame = true,
                requestUrl = buildWebPreviewBaseUrl(currentToken),
                expectedToken = currentToken,
            )
        )
        assertFalse(
            shouldMarkWebPreviewLoadError(
                isForMainFrame = true,
                requestUrl = buildWebPreviewBaseUrl("load-1"),
                expectedToken = currentToken,
            )
        )
        assertFalse(
            shouldMarkWebPreviewLoadError(
                isForMainFrame = false,
                requestUrl = "file:///android_asset/chartjs/chart.min.js",
                expectedToken = currentToken,
            )
        )
    }

    @Test
    fun `only console errors from the current preview page use the generic fallback`() {
        val currentToken = "load-2"

        assertTrue(
            shouldMarkWebPreviewConsoleError(
                sourceId = buildWebPreviewBaseUrl(currentToken),
                expectedToken = currentToken,
            )
        )
        assertFalse(
            shouldMarkWebPreviewConsoleError(
                sourceId = buildWebPreviewBaseUrl("load-1"),
                expectedToken = currentToken,
            )
        )
        assertFalse(
            shouldMarkWebPreviewConsoleError(
                sourceId = "file:///android_asset/chartjs/chart.min.js",
                expectedToken = currentToken,
            )
        )
    }

    private fun readMainAsset(relativePath: String): String {
        val file = sequenceOf(
            File("src/main/assets", relativePath),
            File("app/src/main/assets", relativePath),
            File("app1/app/src/main/assets", relativePath),
        ).firstOrNull(File::isFile)
            ?: error("Missing main asset: $relativePath")
        return file.readText(Charsets.UTF_8)
    }
}
