package com.android.everytalk.ui.components.math

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class MathJaxRenderRequest(
    val id: String,
    val latex: String,
    val display: Boolean,
    val fontSizePx: Float,
    val color: String,
    val maxWidthPx: Float? = null,
    val requestVersion: Long = 0L
)

@Serializable
enum class MathJaxRenderStatus {
    @SerialName("ready")
    READY,

    @SerialName("syntax_error")
    SYNTAX_ERROR,

    @SerialName("engine_error")
    ENGINE_ERROR
}

@Serializable
data class MathJaxRenderResult(
    val id: String,
    val status: MathJaxRenderStatus,
    val svg: String? = null,
    val widthPx: Float? = null,
    val heightPx: Float? = null,
    val depthPx: Float? = null,
    val viewBox: String? = null,
    val errorMessage: String? = null,
    val requestVersion: Long = 0L
)

@Serializable
enum class MathJaxBridgeMessageType {
    @SerialName("ready")
    READY,

    @SerialName("result")
    RESULT,

    @SerialName("engine_error")
    ENGINE_ERROR
}

internal sealed interface MathJaxBridgeMessage

@Serializable
internal data class MathJaxBatchRequest(
    val batchId: String,
    val requests: List<MathJaxRenderRequest>
)

@Serializable
internal data class MathJaxReadyMessage(
    val type: MathJaxBridgeMessageType = MathJaxBridgeMessageType.READY,
    val engineVersion: String,
    val configHash: String,
) : MathJaxBridgeMessage

@Serializable
internal data class MathJaxBatchResponse(
    val type: MathJaxBridgeMessageType = MathJaxBridgeMessageType.RESULT,
    val batchId: String,
    val results: List<MathJaxRenderResult>
) : MathJaxBridgeMessage

@Serializable
internal data class MathJaxEngineErrorMessage(
    val type: MathJaxBridgeMessageType = MathJaxBridgeMessageType.ENGINE_ERROR,
    val batchId: String? = null,
    val errorMessage: String
) : MathJaxBridgeMessage

/**
 * 使用单个不可见 WebView 将 TeX 批量转换为 SVG。
 *
 * 页面与 JavaScript 通信只允许来自 appassets.androidplatform.net 的主框架，
 * 所有公式请求都在主线程串行进入同一个 WebView。
 */
class MathJaxSvgRenderer(
    context: Context,
    private val renderTimeoutMs: Long = DEFAULT_RENDER_TIMEOUT_MS,
    private val pageLoadTimeoutMs: Long = DEFAULT_PAGE_LOAD_TIMEOUT_MS
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderMutex = Mutex()
    private val requestQueue = MathJaxRequestQueue(MAX_QUEUE_SIZE)
    private val pendingBatches = mutableMapOf<String, CompletableDeferred<List<MathJaxRenderResult>>>()

    private var startupDeferred: CompletableDeferred<Unit>? = null
    private var pageReadyDeferred: CompletableDeferred<Unit>? = null
    private var webView: WebView? = null
    private val closed = AtomicBoolean(false)

    /** 延迟预热 WebView，不阻塞调用线程。 */
    fun prewarm(): Job = scope.launch {
        renderMutex.withLock {
            ensurePageReady()
        }
    }

    /**
     * 批量转换公式。单次调用最多 64 条，传给页面的每个子批次最多 16 条。
     */
    suspend fun render(requests: List<MathJaxRenderRequest>): List<MathJaxRenderResult> {
        if (requests.isEmpty()) return emptyList()
        require(requests.size <= MAX_QUEUE_SIZE) { "单次 MathJax 请求不能超过 $MAX_QUEUE_SIZE 条公式" }
        require(requests.map(MathJaxRenderRequest::id).toSet().size == requests.size) {
            "同一批 MathJax 请求中的公式 ID 必须唯一"
        }
        requests.forEach { request ->
            require(request.id.isNotBlank()) { "MathJax 公式 ID 不能为空" }
            require(request.fontSizePx.isFinite() && request.fontSizePx > 0f) {
                "MathJax 字号必须是正有限值"
            }
            require(CSS_COLOR_PATTERN.matches(request.color)) {
                "MathJax 颜色必须使用 6 位或 8 位十六进制格式"
            }
            require(
                request.maxWidthPx == null ||
                    request.maxWidthPx.isFinite() && request.maxWidthPx > 0f
            ) {
                "MathJax 最大宽度必须为空或正有限值"
            }
        }

        val localResults = requests.mapNotNull { request ->
            mathJaxLocalValidationResult(request)?.let { result -> request.id to result }
        }.toMap()
        val renderableRequests = requests.filterNot { request -> request.id in localResults }
        if (renderableRequests.isEmpty()) {
            return requests.map { request -> localResults.getValue(request.id) }
        }

        val renderedResults = requestQueue.withReservation(renderableRequests.size) {
            withContext(Dispatchers.Main.immediate) {
                renderMutex.withLock {
                    check(!closed.get()) { "MathJaxSvgRenderer 已关闭" }
                    renderWithSingleRetry(renderableRequests)
                }
            }
        }
        val renderedResultsById = renderedResults.associateBy(MathJaxRenderResult::id)
        return requests.map { request ->
            localResults[request.id] ?: renderedResultsById.getValue(request.id)
        }
    }

    private suspend fun renderWithSingleRetry(
        requests: List<MathJaxRenderRequest>
    ): List<MathJaxRenderResult> {
        var retriedAfterProcessExit = false
        while (true) {
            try {
                ensurePageReady()
                val results = requests.chunked(MAX_BATCH_SIZE).flatMap { batch ->
                    renderBatch(batch)
                }
                val resultsById = results.associateBy(MathJaxRenderResult::id)
                check(resultsById.keys == requests.map(MathJaxRenderRequest::id).toSet()) {
                    "MathJax 返回的公式集合与请求不一致"
                }
                return requests.map { request -> resultsById.getValue(request.id) }
            } catch (error: MathJaxRenderProcessGoneException) {
                if (retriedAfterProcessExit) throw error
                retriedAfterProcessExit = true
                resetWebView(error)
            }
        }
    }

    private suspend fun renderBatch(
        requests: List<MathJaxRenderRequest>
    ): List<MathJaxRenderResult> {
        val batchId = UUID.randomUUID().toString()
        val result = CompletableDeferred<List<MathJaxRenderResult>>()
        pendingBatches[batchId] = result
        val currentWebView = checkNotNull(webView) { "MathJax WebView 尚未初始化" }

        try {
            currentWebView.evaluateJavascript(buildRenderScript(batchId, requests), null)
            return awaitMathJaxBatchResult(result, renderTimeoutMs)
        } finally {
            pendingBatches.remove(batchId)
        }
    }

    private suspend fun ensurePageReady() {
        check(!closed.get()) { "MathJaxSvgRenderer 已关闭" }
        val existingReady = pageReadyDeferred
        if (webView != null && existingReady != null) {
            withTimeout(pageLoadTimeoutMs) { existingReady.await() }
            return
        }

        awaitWebViewStartup()
        val ready = CompletableDeferred<Unit>()
        pageReadyDeferred = ready
        webView = createWebView()
        webView?.loadUrl(PAGE_URL)
        withTimeout(pageLoadTimeoutMs) { ready.await() }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private suspend fun awaitWebViewStartup() {
        startupDeferred?.let { deferred ->
            deferred.await()
            return
        }

        val deferred = CompletableDeferred<Unit>()
        startupDeferred = deferred
        if (!WebViewFeature.isStartupFeatureSupported(
                applicationContext,
                WebViewFeature.STARTUP_FEATURE_SET_UI_THREAD_STARTUP_MODE_V2
            )
        ) {
            // 旧版 System WebView 不支持异步预热时，后续直接创建唯一 WebView。
            deferred.complete(Unit)
            deferred.await()
            return
        }

        val config = WebViewStartUpConfig.Builder(Dispatchers.Default.asExecutor()).build()
        try {
            WebViewCompat.startUpWebView(
                applicationContext,
                config,
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) {
                        deferred.complete(Unit)
                    }

                    override fun onError(error: WebViewStartupException) {
                        deferred.completeExceptionally(error)
                    }
                }
            )
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
        }
        deferred.await()
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun createWebView(): WebView {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "当前 Android System WebView 不支持安全消息监听"
        }

        val assetsHandler = WebViewAssetLoader.AssetsPathHandler(applicationContext)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", assetsHandler)
            .build()
        val view = WebView(applicationContext)
        configureWebViewSettings(view.settings)
        view.setBackgroundColor(Color.TRANSPARENT)
        view.isHorizontalScrollBarEnabled = false
        view.isVerticalScrollBarEnabled = false
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse = assetLoader.shouldInterceptRequest(request.url)
                ?: blockedResourceResponse()

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse =
                assetLoader.shouldInterceptRequest(url.toUri()) ?: blockedResourceResponse()

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.toString() != PAGE_URL

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                url != PAGE_URL

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    failPage(MathJaxEngineException("MathJax 页面加载失败：${error.description}"))
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                val error = MathJaxRenderProcessGoneException(
                    "MathJax WebView 渲染进程退出，didCrash=${detail.didCrash()}"
                )
                failPage(error)
                webView = null
                pageReadyDeferred = null
                view.destroy()
                return true
            }
        }

        WebViewCompat.addWebMessageListener(
            view,
            BRIDGE_NAME,
            setOf(TRUSTED_ORIGIN)
        ) { _, message, sourceOrigin, isMainFrame, _ ->
            if (isMainFrame && isTrustedOrigin(sourceOrigin)) {
                message.data?.let(::handleBridgeMessage)
            }
        }
        return view
    }

    private fun handleBridgeMessage(rawMessage: String) {
        try {
            when (val message = decodeBridgeMessage(rawMessage)) {
                is MathJaxReadyMessage -> {
                    if (
                        message.engineVersion != MATHJAX_VERSION ||
                        message.configHash != MATHJAX_CONFIG_HASH
                    ) {
                        failPage(
                            MathJaxEngineException(
                                "MathJax 运行时不一致：版本 ${message.engineVersion}，配置 ${message.configHash}"
                            )
                        )
                    } else {
                        pageReadyDeferred?.complete(Unit)
                    }
                }

                is MathJaxBatchResponse -> {
                    pendingBatches.remove(message.batchId)?.complete(message.results)
                }

                is MathJaxEngineErrorMessage -> {
                    val error = MathJaxEngineException(message.errorMessage)
                    if (message.batchId == null) {
                        failPage(error)
                    } else {
                        pendingBatches.remove(message.batchId)?.completeExceptionally(error)
                    }
                }
            }
        } catch (error: Throwable) {
            failPage(MathJaxEngineException("无法解析 MathJax 返回消息", error))
        }
    }

    private fun failPage(error: Throwable) {
        pageReadyDeferred?.completeExceptionally(error)
        pendingBatches.values.forEach { deferred ->
            deferred.completeExceptionally(error)
        }
        pendingBatches.clear()
    }

    private fun resetWebView(reason: Throwable) {
        failPage(reason)
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        pageReadyDeferred = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.launch {
            renderMutex.withLock {
                resetWebView(MathJaxEngineException("MathJaxSvgRenderer 已关闭"))
                scope.cancel()
            }
        }
    }

    companion object {
        const val MATHJAX_VERSION = "4.1.3"
        const val MATHJAX_CONFIG_HASH =
            "12b8c2ab9827b146e579bb0f01e101faefc4c8081fa47d915c3e142957a82bf7"
        const val PAGE_URL =
            "https://appassets.androidplatform.net/assets/mathjax/index.html"
        const val MAX_QUEUE_SIZE = 64
        const val MAX_BATCH_SIZE = 16
        const val MAX_FORMULA_LENGTH = 4096

        private const val TRUSTED_ORIGIN = "https://appassets.androidplatform.net"
        private const val BRIDGE_NAME = "everytalkMathJaxResult"
        private const val DEFAULT_RENDER_TIMEOUT_MS = 5_000L
        private const val DEFAULT_PAGE_LOAD_TIMEOUT_MS = 10_000L
        private val CSS_COLOR_PATTERN = Regex("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$")

        internal val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        internal fun buildRenderScript(
            batchId: String,
            requests: List<MathJaxRenderRequest>
        ): String = "window.renderBatch(${json.encodeToString(MathJaxBatchRequest(batchId, requests))});"

        internal fun decodeBridgeMessage(rawMessage: String): MathJaxBridgeMessage {
            val message = json.parseToJsonElement(rawMessage).jsonObject
            val type = message["type"]?.jsonPrimitive?.content ?: when {
                "results" in message -> "result"
                "engineVersion" in message -> "ready"
                else -> "engine_error"
            }
            return when (type) {
                "ready" -> json.decodeFromString<MathJaxReadyMessage>(rawMessage)
                "result" -> json.decodeFromString<MathJaxBatchResponse>(rawMessage)
                "engine_error" -> json.decodeFromString<MathJaxEngineErrorMessage>(rawMessage)
                else -> error("未知的 MathJax 消息类型：$type")
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        @Suppress("DEPRECATION")
        internal fun configureWebViewSettings(settings: WebSettings) {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setGeolocationEnabled(false)
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
            // appassets 使用 HTTPS 形式，本地拦截器会对所有非本地请求返回 403。
            settings.setBlockNetworkLoads(false)
            settings.safeBrowsingEnabled = true
        }

        private fun blockedResourceResponse(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            Charsets.UTF_8.name(),
            403,
            "Forbidden",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0))
        )

        private fun isTrustedOrigin(origin: Uri): Boolean =
            origin.scheme == "https" &&
                origin.host == "appassets.androidplatform.net" &&
                origin.port == -1
    }
}

private open class MathJaxEngineException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

private class MathJaxRenderProcessGoneException(
    message: String
) : MathJaxEngineException(message)

internal class MathJaxRequestQueue(
    private val capacity: Int,
) {
    private val queuedRequestCount = AtomicInteger(0)

    init {
        require(capacity > 0) { "MathJax 队列容量必须大于 0" }
    }

    suspend fun <T> withReservation(
        requestCount: Int,
        block: suspend () -> T,
    ): T {
        require(requestCount in 1..capacity) {
            "单次 MathJax 队列预留必须为 1 至 $capacity 条公式"
        }
        val queueSize = queuedRequestCount.addAndGet(requestCount)
        if (queueSize > capacity) {
            queuedRequestCount.addAndGet(-requestCount)
            error("MathJax 转换队列已达到 $capacity 条上限")
        }

        return try {
            block()
        } finally {
            queuedRequestCount.addAndGet(-requestCount)
        }
    }
}

internal fun mathJaxLocalValidationResult(
    request: MathJaxRenderRequest,
): MathJaxRenderResult? = if (
    request.latex.isEmpty() || request.latex.length > MathJaxSvgRenderer.MAX_FORMULA_LENGTH
) {
    MathJaxRenderResult(
        id = request.id,
        status = MathJaxRenderStatus.SYNTAX_ERROR,
        errorMessage = "公式为空或超过 ${MathJaxSvgRenderer.MAX_FORMULA_LENGTH} 字符",
        requestVersion = request.requestVersion,
    )
} else {
    null
}

internal suspend fun awaitMathJaxBatchResult(
    result: CompletableDeferred<List<MathJaxRenderResult>>,
    timeoutMs: Long,
): List<MathJaxRenderResult> = withTimeout(timeoutMs) { result.await() }
