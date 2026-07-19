package com.android.everytalk.ui.components.math

import android.app.Application
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MathJaxSvgRendererTest {

    @Test
    fun `超长公式在进入 WebView 队列前返回语法错误`() {
        val accepted = mathJaxLocalValidationResult(
            renderRequest(latex = "x".repeat(MathJaxSvgRenderer.MAX_FORMULA_LENGTH))
        )
        val rejected = mathJaxLocalValidationResult(
            renderRequest(
                latex = "x".repeat(MathJaxSvgRenderer.MAX_FORMULA_LENGTH + 1),
                requestVersion = 31L,
            )
        )

        assertNull(accepted)
        assertEquals(MathJaxRenderStatus.SYNTAX_ERROR, rejected?.status)
        assertEquals(31L, rejected?.requestVersion)
        assertTrue(rejected?.errorMessage.orEmpty().contains("4096"))
    }

    @Test
    fun `队列溢出立即失败且已有请求完成后恢复容量`() = runTest {
        val queue = MathJaxRequestQueue(MathJaxSvgRenderer.MAX_QUEUE_SIZE)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch {
            queue.withReservation(MathJaxSvgRenderer.MAX_QUEUE_SIZE) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val overflow = runCatching {
            queue.withReservation(1) { Unit }
        }.exceptionOrNull()

        assertTrue(overflow is IllegalStateException)
        assertTrue(overflow?.message.orEmpty().contains("64"))
        release.complete(Unit)
        first.join()
        assertEquals("已恢复", queue.withReservation(1) { "已恢复" })
    }

    @Test
    fun `消息取消继续传播并释放 MathJax 队列容量`() = runTest {
        val queue = MathJaxRequestQueue(MathJaxSvgRenderer.MAX_QUEUE_SIZE)
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            queue.withReservation(MathJaxSvgRenderer.MAX_QUEUE_SIZE) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals("已释放", queue.withReservation(1) { "已释放" })
    }

    @Test
    fun `单批转换超过时限抛出超时取消异常`() = runTest {
        val pending = CompletableDeferred<List<MathJaxRenderResult>>()

        val timeout = runCatching {
            awaitMathJaxBatchResult(pending, timeoutMs = 100L)
        }.exceptionOrNull()

        assertTrue(timeout is TimeoutCancellationException)
        assertFalse(pending.isCompleted)
    }

    @Test
    fun `批量脚本使用 JSON 保留公式原文`() {
        val request = MathJaxRenderRequest(
            id = "formula-1",
            latex = "\\frac{\\text{\"A&B\"}}{x\\\\y}",
            display = true,
            fontSizePx = 18f,
            color = "#112233",
            maxWidthPx = 720f,
            requestVersion = 9L
        )

        val script = MathJaxSvgRenderer.buildRenderScript(
            batchId = "batch-1",
            requests = listOf(request)
        )
        val payload = script
            .removePrefix("window.renderBatch(")
            .removeSuffix(");")
        val decoded = Json.decodeFromString<MathJaxBatchRequest>(payload)

        assertEquals("batch-1", decoded.batchId)
        assertEquals(request, decoded.requests.single())
        assertFalse(script.startsWith("javascript:"))
    }

    @Test
    fun `批量结果完整解码 SVG 和尺寸`() {
        val response = MathJaxBatchResponse(
            type = MathJaxBridgeMessageType.RESULT,
            batchId = "batch-2",
            results = listOf(
                MathJaxRenderResult(
                    id = "formula-2",
                    status = MathJaxRenderStatus.READY,
                    svg = "<svg viewBox=\"0 0 100 40\"></svg>",
                    widthPx = 50f,
                    heightPx = 20f,
                    depthPx = 3f,
                    viewBox = "0 0 100 40",
                    requestVersion = 12L
                )
            )
        )

        val decoded = MathJaxSvgRenderer.decodeBridgeMessage(
            Json.encodeToString(response)
        ) as MathJaxBatchResponse

        assertEquals(response, decoded)
        assertEquals(MathJaxRenderStatus.READY, decoded.results.single().status)
        assertTrue(decoded.results.single().svg.orEmpty().startsWith("<svg"))
    }

    @Test
    fun `就绪消息同时锁定引擎版本和渲染配置`() {
        val message = MathJaxReadyMessage(
            engineVersion = MathJaxSvgRenderer.MATHJAX_VERSION,
            configHash = MathJaxSvgRenderer.MATHJAX_CONFIG_HASH,
        )

        val decoded = MathJaxSvgRenderer.decodeBridgeMessage(
            Json.encodeToString(message)
        ) as MathJaxReadyMessage

        assertEquals(message, decoded)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `WebView 设置关闭文件内容混合内容和持久存储访问`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val webView = WebView(context)
        try {
            MathJaxSvgRenderer.configureWebViewSettings(webView.settings)

            assertTrue(webView.settings.javaScriptEnabled)
            assertFalse(webView.settings.allowFileAccess)
            assertFalse(webView.settings.allowContentAccess)
            assertFalse(webView.settings.domStorageEnabled)
            assertFalse(webView.settings.databaseEnabled)
            assertFalse(webView.settings.javaScriptCanOpenWindowsAutomatically)
            assertEquals(
                WebSettings.MIXED_CONTENT_NEVER_ALLOW,
                webView.settings.mixedContentMode
            )
        } finally {
            webView.destroy()
        }
    }

    @Test
    fun `本地资产锁定 MathJax 版本校验和与批量协议`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val index = context.assets.open("mathjax/index.html")
            .bufferedReader(Charsets.UTF_8)
            .use { reader -> reader.readText() }
        val version = context.assets.open("mathjax/VERSION.json")
            .bufferedReader(Charsets.UTF_8)
            .use { reader -> reader.readText() }
        val componentHash = context.assets.open("mathjax/tex-svg.js").use { input ->
            MessageDigest.getInstance("SHA-256")
                .digest(input.readBytes())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
        val doubleStruckHash = context.assets
            .open("mathjax/font/svg/dynamic/double-struck.js")
            .use { input ->
                MessageDigest.getInstance("SHA-256")
                    .digest(input.readBytes())
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }

        assertTrue(index.contains("window.renderBatch = async function"))
        assertTrue(index.contains("fontCache: 'local'"))
        assertTrue(index.contains("dynamicPrefix: './font/svg/dynamic'"))
        assertTrue(index.contains("maxMacros: 1000"))
        assertTrue(index.contains("const MAX_SVG_NODES = 8192"))
        assertTrue(index.contains(MathJaxSvgRenderer.MATHJAX_CONFIG_HASH))
        assertFalse(index.contains("cdn.jsdelivr.net"))
        assertTrue(version.contains("\"version\": \"4.1.3\""))
        assertTrue(version.contains("@mathjax/mathjax-newcm-font"))
        assertTrue(version.contains(MathJaxSvgRenderer.MATHJAX_CONFIG_HASH))
        assertTrue(version.contains("a6b136d600bbe1c660433df17a3e41afadecb01b41f386e523cf0468fde2af40"))
        assertTrue(version.contains("a4100bbac386b90c364ac74fb9d923706eb22b3c929c4325dcdf46216051275f"))
        assertEquals(
            "f102fa970da97f3cfa5adb8cd38ec6db24e3fe8571877bb1a89f512c1a3d788b",
            doubleStruckHash
        )
        assertEquals(
            "23c036deccc0f2374834a47e4032e452419f3ac027bf17e17c104e2746b19f4c",
            componentHash
        )
    }

    private fun renderRequest(
        latex: String,
        requestVersion: Long = 0L,
    ): MathJaxRenderRequest = MathJaxRenderRequest(
        id = "formula-${latex.length}",
        latex = latex,
        display = false,
        fontSizePx = 16f,
        color = "#112233",
        requestVersion = requestVersion,
    )
}
