package com.android.everytalk.data.computer

import android.annotation.SuppressLint
import android.content.Context
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.IsolateStartupParameters
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicReference
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

@Serializable
data class LocalShellRequest(
    val id: String,
    val command: String,
    val cwd: String = "/",
    /** 文件快照使用相对 Workspace 路径，运行时内部再映射到 /。 */
    val files: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 10_000,
    val maxOutputChars: Int = 20_000,
)

@Serializable
data class LocalShellResult(
    val id: String,
    val ok: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val files: Map<String, String> = emptyMap(),
    val truncated: Boolean = false,
)

interface JustBashRuntime {
    suspend fun execute(request: LocalShellRequest): LocalShellResult
    suspend fun reset(workspaceId: String)
}

/**
 * 使用 AndroidX JavaScriptSandbox 的正式后台运行时。
 *
 * JavaScriptSandbox 运行在 WebView 提供的隔离进程中，但不创建 WebView 页面，
 * 也不开放文件、网络或 JavaScriptInterface。请求通过命名二进制数据传入，
 * 因而不会因为 Workspace 文件内容进入 Binder 代码字符串而绕过大小边界。
 */
class SandboxJustBashRuntime(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : JustBashRuntime, AutoCloseable {
    private val mutex = Mutex()
    private var sandbox: JavaScriptSandbox? = null
    private var isolate: JavaScriptIsolate? = null
    private var loaded = false
    private var closed = false

    override suspend fun execute(request: LocalShellRequest): LocalShellResult = mutex.withLock {
        check(!closed) { "just-bash 运行时已关闭" }
        val requestBytes = json.encodeToString(request).toByteArray(Charsets.UTF_8)
        require(requestBytes.size <= 32 * 1024 * 1024) { "本地 Shell 请求过大" }
        try {
            val raw = withTimeout(request.timeoutMs.coerceIn(100, 30_000) + 5_000L) {
                val activeIsolate = ensureIsolate()
                val dataName = "request-${request.id}"
                activeIsolate.provideNamedData(dataName, requestBytes)
                val expression = "globalThis.EveryTalkJustBashAsyncFromNamedData(${json.encodeToString(dataName)}).then(v => JSON.stringify(v))"
                coroutineScope {
                    val evaluation = activeIsolate.evaluateJavaScriptAsync(expression)
                    // 等待 Promise 时引擎仍接受下一次 evaluation；同步死循环即使阻塞
                    // pump，也会被宿主 withTimeout 和 isolate.close 强制终止。
                    val pump = launch {
                        while (!evaluation.isDone) {
                            delay(10)
                            awaitFuture(activeIsolate.evaluateJavaScriptAsync("globalThis.EveryTalkPumpTimers();''"))
                        }
                    }
                    try { awaitFuture(evaluation) } finally { pump.cancel() }
                }
            }
            json.decodeFromString<LocalShellResult>(raw)
        } finally {
            // 销毁每次调用的 JS 全局状态，包括计时器；下次从文件快照重建，覆盖取消与异常路径。
            isolate?.close()
            isolate = null
            loaded = false
        }
    }

    override suspend fun reset(workspaceId: String) {
        require(workspaceId.isNotBlank()) { "Workspace ID 不能为空" }
        mutex.withLock {
            isolate?.close()
            isolate = null
            loaded = false
        }
    }

    private suspend fun ensureIsolate(): JavaScriptIsolate {
        if (!JavaScriptSandbox.isSupported()) throw IllegalStateException("当前设备不支持 Android JavaScriptSandbox")
        val currentSandbox = sandbox ?: awaitFuture(JavaScriptSandbox.createConnectedInstanceAsync(context)).also { sandbox = it }
        if (!currentSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN) ||
            !currentSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROVIDE_CONSUME_ARRAY_BUFFER) ||
            !currentSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION) ||
            !currentSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE) ||
            !currentSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)) {
            throw IllegalStateException("当前 WebView 缺少 just-bash 所需的 JavaScriptSandbox 能力")
        }
        if (isolate == null) {
            val parameters = IsolateStartupParameters()
            if (currentSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
                parameters.setMaxHeapSizeBytes(256L * 1024 * 1024)
            }
            parameters.setMaxEvaluationReturnSizeBytes(32 * 1024 * 1024)
            isolate = currentSandbox.createIsolate(parameters)
            loaded = false
        }
        val result = requireNotNull(isolate)
        if (!loaded) {
            val source = context.assets.open("just-bash/runtime.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
            require(source.length <= 4 * 1024 * 1024) { "just-bash Bundle 过大" }
            awaitFuture(result.evaluateJavaScriptAsync(source + ";\"ready\""))
            loaded = true
        }
        return result
    }

    override fun close() {
        if (closed) return
        closed = true
        isolate?.close()
        isolate = null
        sandbox?.close()
        sandbox = null
    }

    private suspend fun <T> awaitFuture(future: ListenableFuture<T>): T = suspendCancellableCoroutine { continuation ->
        future.addListener({
            runCatching { future.get() }
                .onSuccess { if (continuation.isActive) continuation.resume(it) }
                .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
        }, Executor { it.run() })
        continuation.invokeOnCancellation { future.cancel(true) }
    }
}

/**
 * 使用 APK 内本地 asset 运行 just-bash 的 PoC 实现。
 * 业务层只依赖 JustBashRuntime；后续替换 QuickJS 时不改变工具协议。
 */
class WebViewJustBashRuntime(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : JustBashRuntime {
    private val webViewRef = AtomicReference<WebView?>()
    private val initialized = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val executionMutex = Mutex()
    private val callbacks = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<String>>()

    init {
        Handler(Looper.getMainLooper()).post {
            try {
                val webView = WebView(context.applicationContext)
                webViewRef.set(webView)
                webView.addJavascriptInterface(Bridge(), "EveryTalkBridge")
                configureWebView(webView)
                initialized.complete(Unit)
            } catch (error: Throwable) {
                initialized.completeExceptionally(error)
            }
        }
    }

    override suspend fun execute(request: LocalShellRequest): LocalShellResult = executionMutex.withLock {
        withContext(Dispatchers.Main.immediate) {
        initialized.await()
        val webView = requireNotNull(webViewRef.get()) { "just-bash runner 未初始化" }
        val requestJson = json.encodeToString(request)
        val callback = kotlinx.coroutines.CompletableDeferred<String>()
        callbacks[request.id] = callback
        callback.invokeOnCompletion { cause ->
            if (cause != null) {
                webView.evaluateJavascript("window.EveryTalkJustBashCancel(${quoteJs(request.id)})", null)
            }
        }
        try {
            val resultJson = withTimeout(35_000) {
                ready.await()
                webView.evaluateJavascript(
                    "window.EveryTalkJustBashAsync(${quoteJs(requestJson)}).then(function(v){window.EveryTalkBridge.postResult(${quoteJs(request.id)},JSON.stringify(v));}).catch(function(e){window.EveryTalkBridge.postError(${quoteJs(request.id)},String(e));});",
                    null,
                )
                callback.await()
            }
            // JS 回调只序列化一次：evaluateJavascript 的回调参数已经是字符串，
            // 这里先还原 JS 字符串，再解码业务结果，避免双重 JSON 字符串。
            json.decodeFromString<LocalShellResult>(decodeJavascriptString(resultJson))
        } finally {
            callbacks.remove(request.id)
        }
        }
    }

    override suspend fun reset(workspaceId: String) {
        require(workspaceId.isNotBlank()) { "Workspace ID 不能为空" }
        // 每次 execute 都从调用方传入新的内存快照，运行时没有跨 Workspace 状态；
        // 因此 reset 不需要 reload WebView，也不会制造“页面尚未 ready 就执行”的竞态。
        initialized.await()
    }

    fun close() {
        callbacks.values.forEach { it.cancel() }
        callbacks.clear()
        Handler(Looper.getMainLooper()).post { webViewRef.getAndSet(null)?.destroy() }
    }

    /** JS 只回传指定请求的结果；不提供文件或 Android 方法，避免扩大 WebView 权限。 */
    private inner class Bridge {
        @JavascriptInterface
        fun postResult(id: String, value: String) { callbacks[id]?.complete(value) }

        @JavascriptInterface
        fun postError(id: String, message: String) { callbacks[id]?.completeExceptionally(IllegalStateException(message.take(500))) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!ready.isCompleted) ready.complete(Unit)
            }
            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException("just-bash runner 加载失败"))
            }
        }
        webView.loadUrl("file:///android_asset/just-bash/runner.html")
    }

    private fun quoteJs(value: String): String = json.encodeToString(value)

    private fun decodeJavascriptString(value: String): String {
        val element = json.parseToJsonElement(value)
        return (element as? JsonObject)?.get("value")?.jsonPrimitive?.content
            ?: if (value.startsWith("\"") && value.endsWith("\"")) {
                json.decodeFromString(value)
            } else value
    }
}
