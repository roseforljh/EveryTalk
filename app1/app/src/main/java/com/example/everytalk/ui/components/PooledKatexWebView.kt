// com/example/everytalk/ui/components/PooledKatexWebView.kt

package com.example.everytalk.ui.components

import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.everytalk.StateControler.AppViewModel // 请确保您的 AppViewModel 导入路径正确
import com.example.everytalk.webviewpool.WebViewConfig // 请确保您的 WebViewConfig 导入路径正确
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// 防抖超时时间 (毫秒) - 这个值可以根据实际体验调整
private const val DEBOUNCE_TIMEOUT_MS = 150L

@Composable
fun PooledKatexWebView(
    appViewModel: AppViewModel,
    contentId: String,        // 内容的唯一ID，用于区分不同的消息段或消息
    latexInput: String,       // 需要渲染的 Markdown/HTML/KaTeX 字符串
    htmlTemplate: String,     // WebView 加载的基础 HTML 页面模板
    modifier: Modifier = Modifier
) {
    val webViewTag = "PooledKatexWebView-$contentId" // 日志标签，包含 contentId

    // WebView 实例及其相关状态
    // 使用 contentId 作为 key，确保当内容块改变时，这些状态能正确重置或保持
    var webViewInstance by remember(contentId) { mutableStateOf<WebView?>(null) }
    var isPageReadyForJs by remember(contentId) { mutableStateOf(false) } // WebView 是否已加载好 HTML 模板并准备好执行 JS
    var isViewAttached by remember(contentId) { mutableStateOf(false) }   // WebView 是否已被 AndroidView attach 到视图层级

    val coroutineScope = rememberCoroutineScope()
    var jsEvaluationJob by remember(contentId) { mutableStateOf<Job?>(null) } // JS注入的协程任务
    var debounceJob by remember(contentId) { mutableStateOf<Job?>(null) }     // 防抖处理的协程任务

    // 真正传递给 WebView 用于渲染的 latexInput。
    // 当 contentId 改变时（即这是一个全新的内容块），它会立即使用新的 latexInput 初始化。
    var actualLatexForWebView by remember(contentId) { mutableStateOf(latexInput) }

    // 这个 LaunchedEffect 用于处理对【同一个 contentId】的 latexInput 的后续更新。
    // 当外部传入的 latexInput 发生变化时，它会启动一个防抖的协程。
    LaunchedEffect(latexInput, contentId) {
        Log.d("WEBVIEW", "debounce: latexInput = ${latexInput.takeLast(20)}")
        // 只有当传入的 latexInput 与当前实际用于 WebView 的内容不同时，才启动防抖
        if (latexInput != actualLatexForWebView) {
            Log.d(webViewTag, "Input changed (len ${latexInput.length}). Current for WebView (len ${actualLatexForWebView.length}). Debouncing for $contentId...")
            debounceJob?.cancel() // 取消上一个针对此 contentId 的防抖任务
            debounceJob = coroutineScope.launch {
                delay(DEBOUNCE_TIMEOUT_MS)
                Log.d(webViewTag, "Debounce for $contentId complete. Updating actualLatexForWebView (len ${latexInput.length})")
                actualLatexForWebView = latexInput // 防抖结束后，更新实际用于 WebView 的内容
            }
        }
        // 如果 latexInput 与 actualLatexForWebView 相同，通常意味着父级发生了不必要的重组，
        // 或者这是 contentId 刚改变后的第一次初始化（已由 remember(contentId) { mutableStateOf(latexInput) } 处理）。
        // 一般不需要额外操作。
    }

    // WebView 的获取、配置和释放
    DisposableEffect(contentId, htmlTemplate) {
        Log.d(webViewTag, "DisposableEffect: Acquiring WebView for $contentId. HTML template (len ${htmlTemplate.length}).")
        isPageReadyForJs = false // 重置页面准备状态

        val wv = appViewModel.webViewPool.acquire(
            contentId,
            // 将当前的 (可能是初始的) actualLatexForWebView 传递给 WebViewConfig，
            // WebViewPool 可以在获取新 WebView 时用它来预加载内容。
            WebViewConfig(htmlTemplate, actualLatexForWebView)
        ) { acquiredWebView, success -> // WebViewPool 加载页面后的回调
            // 确保回调是针对当前 Composables 正在使用的 WebView 实例
            if (webViewInstance == acquiredWebView || webViewInstance == null) {
                isPageReadyForJs = success
                Log.d(webViewTag, "Pool: onPageFinished for $contentId. isPageReadyForJs = $success")
            } else {
                Log.d(webViewTag, "Pool: onPageFinished for a stale WebView instance (current contentId: $contentId). Ignoring.")
            }
        }
        wv.settings.javaScriptEnabled = true // 确保 JavaScript 始终启用
        webViewInstance = wv

        onDispose { // Composable 销毁时的清理逻辑
            Log.d(webViewTag, "DisposableEffect onDispose: Releasing WebView for $contentId")
            jsEvaluationJob?.cancel() // 取消正在进行的 JS 注入任务
            debounceJob?.cancel()     // 取消正在进行的防抖任务
            appViewModel.webViewPool.release(wv) // 将 WebView 实例释放回池中
            webViewInstance = null // 清理状态，避免内存泄漏和悬空引用
            isViewAttached = false
            isPageReadyForJs = false
        }
    }

    // 执行 JavaScript 注入的 LaunchedEffect
    // 依赖于: WebView 实例、经过防抖处理的 LaTeX 输入、页面是否准备好、视图是否已附加
    LaunchedEffect(webViewInstance, actualLatexForWebView, isPageReadyForJs, isViewAttached) {
        val wv = webViewInstance
        // 确保所有条件都满足才执行 JS
        if (wv != null && isPageReadyForJs && isViewAttached && wv.parent != null) {
            Log.d(webViewTag, "Attempting JS injection for $contentId. actualLatexForWebView (len ${actualLatexForWebView.length})")
            jsEvaluationJob?.cancel() // 取消上一个JS注入任务
            jsEvaluationJob = coroutineScope.launch {
                if (!isActive) { // 协程可能在启动后但在执行前被取消
                    Log.d(webViewTag, "JS evaluation coroutine no longer active for $contentId. Skipping.")
                    return@launch
                }

                val escapedLatex = actualLatexForWebView
                    .replace("\\", "\\\\") // 转义反斜杠
                    .replace("'", "\\'")  // 转义单引号
                    .replace("`", "\\`")  // 转义反引号
                    .replace("\n", "\\n") // 转义换行符
                    .replace("\r", "")   // 移除回车符
                val script = "renderMixedContentWithLatex(`$escapedLatex`);" // JS 调用
                Log.d(webViewTag, "Injecting JS for $contentId.")
                wv.evaluateJavascript(script, null) // 执行 JavaScript
            }
        } else {
            Log.d(webViewTag, "Skipped JS injection for $contentId due to: wvNull=${webViewInstance == null}, !isPageReadyForJs=${!isPageReadyForJs}, !isViewAttached=${!isViewAttached}, wvParentNull=${webViewInstance?.parent == null}")
        }
    }

    // 使用 AndroidView Composable 来嵌入 WebView
    val currentWebViewForView = webViewInstance
    if (currentWebViewForView != null) {
        AndroidView(
            factory = { _ -> // 创建 View 的 lambda
                Log.d(webViewTag, "AndroidView Factory for $contentId. Current parent: ${currentWebViewForView.parent}")
                // WebView 在被添加到新的父容器之前，必须从旧的父容器中移除（如果存在）
                (currentWebViewForView.parent as? ViewGroup)?.removeView(currentWebViewForView)
                isViewAttached = true // 标记 WebView 已被 attach
                currentWebViewForView
            },
            update = { webView -> // View 更新时的 lambda (例如 Modifier 变化时)
                Log.d(webViewTag, "AndroidView Update for $contentId. Attached: $isViewAttached, Parent: ${webView.parent}")
                // 如果 WebView 仍然有父视图且 isViewAttached 为 false (例如，Compose 内部 detach/re-attach)，则更新状态
                if (webView.parent != null && !isViewAttached) {
                    isViewAttached = true
                } else if (webView.parent == null && isViewAttached) {
                    // This case might occur if Compose detaches it temporarily for lists,
                    // onRelease should ideally handle setting isViewAttached to false.
                    // Log.w(webViewTag, "AndroidView Update for $contentId: parent is null but isViewAttached is true.")
                }
            },
            onRelease = { // View 释放时的 lambda (例如 Composable 离开组合但 WebView 实例可能被池复用)
                Log.d(webViewTag, "AndroidView onRelease for $contentId. WebView instance: ${it.hashCode()}")
                isViewAttached = false // 标记 WebView 已被 detach
                // 注意：此处不应将 WebView 释放回池中。
                // WebView 的生命周期管理（获取与释放回池）由顶部的 DisposableEffect 控制。
                // onRelease 是 AndroidView 自身从视图层级移除时的回调。
            },
            modifier = modifier.heightIn(min = 38.dp) // 保持一个最小高度，防止列表跳动
        )
    } else {
        // 如果 WebView 实例尚未从池中获取到，显示一个占位符
        Log.d(webViewTag, "WebView for $contentId is null, showing Spacer.")
        Spacer(modifier = modifier.heightIn(min = 38.dp))
    }

    // WebView 生命周期管理 (onPause, onResume)
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleAwareWebView = webViewInstance // 使用当前的 WebView 实例
    if (lifecycleAwareWebView != null) { // 仅当 WebView 实例存在时才进行生命周期观察
        DisposableEffect(lifecycleAwareWebView, lifecycleOwner, isViewAttached) {
            val observer = LifecycleEventObserver { _, event ->
                // 仅当 WebView 实际附加到UI并有父容器时才响应生命周期事件
                if (isViewAttached && lifecycleAwareWebView.parent != null) {
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> {
                            Log.d(webViewTag, "Lifecycle ON_PAUSE for $contentId")
                            lifecycleAwareWebView.onPause()
                            lifecycleAwareWebView.pauseTimers() // 暂停 WebView 的计时器
                        }
                        Lifecycle.Event.ON_RESUME -> {
                            Log.d(webViewTag, "Lifecycle ON_RESUME for $contentId")
                            lifecycleAwareWebView.onResume()
                            lifecycleAwareWebView.resumeTimers() // 恢复 WebView 的计时器
                        }
                        else -> {
                            // 其他生命周期事件（ON_CREATE, ON_START, ON_STOP, ON_DESTROY）
                            // WebView 通常不需要对这些事件做特殊处理，除非有特定需求
                        }
                    }
                } else {
                    Log.d(webViewTag, "Lifecycle $event for $contentId, but view not effectively in UI (isViewAttached=$isViewAttached, parent=${lifecycleAwareWebView.parent}). Skipping WebView action.")
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer) // 添加观察者
            onDispose { // Composable 销毁或 key 变化时
                Log.d(webViewTag, "Disposing lifecycle observer for $contentId.")
                lifecycleOwner.lifecycle.removeObserver(observer) // 移除观察者
                // WebView 的最终清理（如 destroy）应该由 WebViewPool 在不再需要时处理，
                // 或者在整个应用退出时。此处主要是移除观察者。
                // 如果在销毁时仍附加，理论上应该暂停，但这可能与池的逻辑冲突，
                // 一般由池来管理回收的 WebView 的状态。
            }
        }
    }
}