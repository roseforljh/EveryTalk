package com.example.everytalk.ui.components

import android.content.Context
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
import com.example.everytalk.StateControler.AppViewModel
import com.example.everytalk.webviewpool.WebViewConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException // Ensure this is imported

@Composable
fun PooledKatexWebView(
    appViewModel: AppViewModel,
    contentId: String,
    initialLatexInput: String, // For the first full load
    htmlChunkToAppend: Pair<String, String>?, // Key-Value Pair: <UniqueTriggerKey, HtmlChunkToAppend>
    htmlTemplate: String, // The base HTML structure with JS functions
    modifier: Modifier = Modifier
) {
    val webViewTag = "PooledKatexWebView-$contentId"

    var webViewInstance by remember(contentId) { mutableStateOf<WebView?>(null) }
    var isPageReadyForJs by remember(contentId) { mutableStateOf(false) }
    var isViewAttached by remember(contentId) { mutableStateOf(false) }
    var initialContentRendered by remember(contentId) { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    var jsFullRenderJob by remember(contentId) { mutableStateOf<Job?>(null) }
    var jsAppendJob by remember(contentId) { mutableStateOf<Job?>(null) }

    DisposableEffect(contentId, htmlTemplate) {
        Log.d(webViewTag, "DisposableEffect: Acquiring WebView for $contentId.")
        isPageReadyForJs = false
        initialContentRendered = false

        val wv = appViewModel.webViewPool.acquire(
            contentId,
            WebViewConfig(
                htmlTemplate,
                ""
            ) // Initial config.latexInput not critical for JS-driven rendering
        ) { acquiredWebView, success ->
            if (webViewInstance == acquiredWebView || webViewInstance == null) {
                Log.d(webViewTag, "Pool: onPageFinished for $contentId. Success: $success.")
                isPageReadyForJs = success
            } else {
                Log.d(
                    webViewTag,
                    "Pool: onPageFinished for a STALE WebView instance for $contentId."
                )
            }
        }
        if (!wv.settings.javaScriptEnabled) wv.settings.javaScriptEnabled = true
        webViewInstance = wv

        onDispose {
            Log.d(
                webViewTag,
                "DisposableEffect onDispose: Releasing WebView ${System.identityHashCode(wv)} for $contentId"
            )
            jsFullRenderJob?.cancel(CancellationException("Disposing $contentId - full render job"))
            jsAppendJob?.cancel(CancellationException("Disposing $contentId - append job"))
            appViewModel.webViewPool.release(wv)
            webViewInstance = null
            isViewAttached = false
            isPageReadyForJs = false
            initialContentRendered = false
        }
    }

    // Effect for INITIAL FULL CONTENT rendering using initialLatexInput
    LaunchedEffect(
        webViewInstance,
        initialLatexInput,
        isPageReadyForJs,
        isViewAttached,
        initialContentRendered
    ) {
        val wv = webViewInstance
        Log.d(
            webViewTag,
            "InitialRenderEffect for $contentId. InitialRendered: $initialContentRendered, PageReady: $isPageReadyForJs, Attached: $isViewAttached, WV: ${
                System.identityHashCode(wv)
            }, InitialInputLen: ${initialLatexInput.length}"
        )

        if (wv != null && isPageReadyForJs && isViewAttached && wv.parent != null && !initialContentRendered) {
            jsFullRenderJob?.cancel(CancellationException("New initial full render for $contentId"))
            jsFullRenderJob = coroutineScope.launch {
                Log.i(
                    webViewTag,
                    "Performing INITIAL FULL RENDER for $contentId. Length: ${initialLatexInput.length}. Preview: ${
                        initialLatexInput.take(100).replace("\n", "\\n")
                    }"
                )
                val escapedLatex = initialLatexInput
                    .replace("\\", "\\\\").replace("'", "\\'").replace("`", "\\`")
                    .replace("\n", "\\n").replace("\r", "")
                val script = "renderFullContent(`$escapedLatex`);"
                wv.evaluateJavascript(script) { result ->
                    Log.d(
                        webViewTag,
                        "Initial full render JS for $contentId completed. Result: $result"
                    )
                    if (isActive) initialContentRendered = true
                }
            }
        } else if (initialLatexInput.isBlank() && wv != null && isPageReadyForJs && isViewAttached && wv.parent != null && !initialContentRendered) {
            // If initialLatexInput is blank (e.g. new streaming message), still call renderFullContent with empty string
            // to set up the page and mark initial render "done" to allow appends.
            jsFullRenderJob?.cancel(CancellationException("New initial (empty) full render for $contentId"))
            jsFullRenderJob = coroutineScope.launch {
                Log.i(
                    webViewTag,
                    "Performing INITIAL EMPTY RENDER for $contentId to enable appends."
                )
                val script = "renderFullContent(``);" // Render with empty string
                wv.evaluateJavascript(script) { result ->
                    Log.d(
                        webViewTag,
                        "Initial empty render JS for $contentId completed. Result: $result"
                    )
                    if (isActive) initialContentRendered = true
                }
            }
        }
    }

    // Effect for APPENDING HTML CHUNKS
    LaunchedEffect(
        webViewInstance,
        htmlChunkToAppend,
        isPageReadyForJs,
        isViewAttached,
        initialContentRendered
    ) {
        val wv = webViewInstance
        val chunkPair = htmlChunkToAppend
        val chunkKey = chunkPair?.first
        val htmlChunk = chunkPair?.second

        Log.d(
            webViewTag,
            "AppendChunkEffect for $contentId. ChunkKey: ${chunkKey?.take(4)}, ChunkLen: ${htmlChunk?.length}, PageReady: $isPageReadyForJs, Attached: $isViewAttached, WV: ${
                System.identityHashCode(wv)
            }, InitialRendered: $initialContentRendered"
        )

        if (wv != null && isPageReadyForJs && isViewAttached && wv.parent != null && initialContentRendered && htmlChunk != null && htmlChunk.isNotBlank()) {
            jsAppendJob?.cancel(CancellationException("New append chunk for $contentId"))
            jsAppendJob = coroutineScope.launch {
                Log.i(
                    webViewTag,
                    "Performing APPEND for $contentId. Chunk Key: ${chunkKey?.take(4)}, Chunk Preview: ${
                        htmlChunk.take(50).replace("\n", "\\n")
                    }"
                )
                val escapedChunk = htmlChunk
                    .replace("\\", "\\\\").replace("'", "\\'").replace("`", "\\`")
                    .replace("\n", "\\n").replace("\r", "")
                val script = "appendHtmlChunk(`$escapedChunk`);"
                wv.evaluateJavascript(script) { result ->
                    Log.d(
                        webViewTag,
                        "Append JS for $contentId (Key ${chunkKey?.take(4)}) completed. Result: $result"
                    )
                }
            }
        }
    }

    val currentWebViewForView = webViewInstance
    if (currentWebViewForView != null) {
        AndroidView(
            factory = { context: Context ->
                Log.d(
                    webViewTag,
                    "AndroidView Factory for $contentId. WebView: ${
                        System.identityHashCode(currentWebViewForView)
                    }. Parent: ${currentWebViewForView.parent}"
                )
                (currentWebViewForView.parent as? ViewGroup)?.removeView(currentWebViewForView)
                currentWebViewForView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                isViewAttached = true
                Log.i(webViewTag, "AndroidView Factory: $contentId -> isViewAttached SET TO TRUE.")
                currentWebViewForView
            },
            update = { webView ->
                Log.d(
                    webViewTag,
                    "AndroidView Update for $contentId. isViewAttached: $isViewAttached, Parent: ${webView.parent}"
                )
                if (webView.parent != null && !isViewAttached) {
                    isViewAttached = true
                    Log.i(
                        webViewTag,
                        "AndroidView Update: $contentId -> isViewAttached SET TO TRUE."
                    )
                }
            },
            onRelease = { webView ->
                Log.i(
                    webViewTag,
                    "AndroidView onRelease for $contentId. Setting isViewAttached=false. WebView: ${
                        System.identityHashCode(webView)
                    }"
                )
                isViewAttached = false
            },
            modifier = modifier // Apply the modifier passed from AiMessageContent
        )
    } else {
        Log.d(webViewTag, "WebView for $contentId is NULL, showing Spacer.")
        Spacer(modifier = modifier.heightIn(min = 1.dp)) // Use incoming modifier or a default
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(webViewInstance, lifecycleOwner, isViewAttached) {
        val wv = webViewInstance
        if (wv != null) {
            val observer = LifecycleEventObserver { _, event ->
                if (isViewAttached && wv.parent != null) {
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> {
                            wv.onPause(); wv.pauseTimers(); Log.d(
                                webViewTag,
                                "Lifecycle ON_PAUSE for $contentId processed."
                            )
                        }

                        Lifecycle.Event.ON_RESUME -> {
                            wv.onResume(); wv.resumeTimers(); Log.d(
                                webViewTag,
                                "Lifecycle ON_RESUME for $contentId processed."
                            )
                        }

                        else -> Unit
                    }
                } else {
                    Log.d(
                        webViewTag,
                        "Lifecycle $event for $contentId SKIPPED (isViewAttached=$isViewAttached, parent=${wv.parent})"
                    )
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                Log.d(webViewTag, "Disposing lifecycle observer for $contentId.")
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        } else {
            onDispose {}
        }
    }
}