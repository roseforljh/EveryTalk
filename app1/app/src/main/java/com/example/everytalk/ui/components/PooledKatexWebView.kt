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
import com.example.everytalk.StateControler.AppViewModel
import com.example.everytalk.webviewpool.WebViewConfig
import kotlinx.coroutines.Job
// import kotlinx.coroutines.delay // No longer using explicit debounce here
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException

@Composable
fun PooledKatexWebView(
    appViewModel: AppViewModel,
    contentId: String,
    initialLatexInput: String, // For the first full load (e.g., historical message, or initial part of a new message)
    htmlChunkToAppend: Pair<String, String>?, // Key-Value Pair: <UniqueTriggerKey, HtmlChunkToAppend>
    htmlTemplate: String, // The base HTML structure with JS functions
    modifier: Modifier = Modifier
) {
    val webViewTag = "PooledKatexWebView-$contentId"

    var webViewInstance by remember(contentId) { mutableStateOf<WebView?>(null) }
    var isPageReadyForJs by remember(contentId) { mutableStateOf(false) } // HTML base template loaded
    var isViewAttached by remember(contentId) { mutableStateOf(false) }  // WebView attached to Compose tree
    var initialContentRendered by remember(contentId) { mutableStateOf(false) } // Tracks if initialLatexInput has been rendered

    val coroutineScope = rememberCoroutineScope()
    var jsFullRenderJob by remember(contentId) { mutableStateOf<Job?>(null) }
    var jsAppendJob by remember(contentId) { mutableStateOf<Job?>(null) }

    // Effect to acquire and release WebView
    DisposableEffect(contentId, htmlTemplate) {
        Log.d(webViewTag, "DisposableEffect: Acquiring WebView for $contentId.")
        isPageReadyForJs = false
        initialContentRendered = false // Reset for new instance or template change

        val wv = appViewModel.webViewPool.acquire(
            contentId,
            WebViewConfig(
                htmlTemplate,
                ""
            ) // Initial config.latexInput is not critical as we use props
        ) { acquiredWebView, success ->
            if (webViewInstance == acquiredWebView || webViewInstance == null) {
                Log.d(webViewTag, "Pool: onPageFinished for $contentId. Success: $success.")
                isPageReadyForJs = success
            } else {
                Log.d(webViewTag, "Pool: onPageFinished for a STALE WebView instance.")
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
    // This runs when dependencies change, aiming for a one-time initial render.
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
            if (initialLatexInput.isNotBlank()) {
                jsFullRenderJob?.cancel(CancellationException("New initial full render for $contentId"))
                jsFullRenderJob = coroutineScope.launch {
                    Log.i(
                        webViewTag,
                        "Performing INITIAL FULL RENDER for $contentId. Length: ${initialLatexInput.length}"
                    )
                    val escapedLatex = initialLatexInput
                        .replace("\\", "\\\\").replace("'", "\\'").replace("`", "\\`")
                        .replace("\n", "\\n").replace("\r", "")
                    val script = "renderFullContent(`$escapedLatex`);" // Call new JS function
                    wv.evaluateJavascript(script) { result ->
                        Log.d(
                            webViewTag,
                            "Initial full render JS for $contentId completed. Result: $result"
                        )
                        if (isActive) initialContentRendered = true
                    }
                }
            } else {
                // If initialLatexInput is blank, consider initial render "done" to allow appends.
                Log.i(
                    webViewTag,
                    "InitialLatexInput is blank for $contentId, marking initialContentRendered=true."
                )
                initialContentRendered = true
            }
        }
    }

    // Effect for APPENDING HTML CHUNKS
    // Keyed on htmlChunkToAppend to trigger on new chunks.
    // Also keyed on other states to ensure WebView is ready.
    LaunchedEffect(
        webViewInstance,
        htmlChunkToAppend,
        isPageReadyForJs,
        isViewAttached,
        initialContentRendered
    ) {
        val wv = webViewInstance
        val chunkPair = htmlChunkToAppend // Pair<UniqueTriggerKey, HtmlChunk>
        val chunkKey = chunkPair?.first
        val htmlChunk = chunkPair?.second

        Log.d(
            webViewTag,
            "AppendChunkEffect for $contentId. ChunkKey: $chunkKey, ChunkLen: ${htmlChunk?.length}, PageReady: $isPageReadyForJs, Attached: $isViewAttached, WV: ${
                System.identityHashCode(wv)
            }, InitialRendered: $initialContentRendered"
        )

        if (wv != null && isPageReadyForJs && isViewAttached && wv.parent != null && initialContentRendered && htmlChunk != null && htmlChunk.isNotBlank()) {
            // Cancel previous append job if a new chunk arrives quickly
            jsAppendJob?.cancel(CancellationException("New append chunk for $contentId"))
            jsAppendJob = coroutineScope.launch {
                Log.i(
                    webViewTag,
                    "Performing APPEND for $contentId. Chunk Key: $chunkKey, Chunk Preview: ${
                        htmlChunk.take(50).replace("\n", "\\n")
                    }"
                )
                val escapedChunk = htmlChunk
                    .replace("\\", "\\\\").replace("'", "\\'").replace("`", "\\`")
                    .replace("\n", "\\n").replace("\r", "")
                val script = "appendHtmlChunk(`$escapedChunk`);" // Call new JS function
                wv.evaluateJavascript(script) { result ->
                    Log.d(
                        webViewTag,
                        "Append JS for $contentId (Key $chunkKey) completed. Result: $result"
                    )
                }
            }
        }
    }

    // AndroidView to embed the WebView
    val currentWebViewForView = webViewInstance
    if (currentWebViewForView != null) {
        AndroidView(
            factory = { _ ->
                Log.d(
                    webViewTag,
                    "AndroidView Factory for $contentId. WebView: ${
                        System.identityHashCode(currentWebViewForView)
                    }. Parent: ${currentWebViewForView.parent}"
                )
                (currentWebViewForView.parent as? ViewGroup)?.removeView(currentWebViewForView)
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
                    "AndroidView onRelease for $contentId. Setting isViewAttached=false."
                )
                isViewAttached = false
            },
            modifier = modifier.heightIn(min = 1.dp) // Small min height for content flow
        )
    } else {
        Log.d(webViewTag, "WebView for $contentId is NULL, showing Spacer.")
        Spacer(modifier = modifier.heightIn(min = 1.dp))
    }

    // Lifecycle handling for WebView (onPause, onResume)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(webViewInstance, lifecycleOwner, isViewAttached) {
        val wv = webViewInstance
        if (wv != null) {
            val observer = LifecycleEventObserver { _, event ->
                if (isViewAttached && wv.parent != null) { // Check if view is effectively in UI
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> {
                            wv.onPause(); wv.pauseTimers()
                            Log.d(webViewTag, "Lifecycle ON_PAUSE for $contentId processed.")
                        }

                        Lifecycle.Event.ON_RESUME -> {
                            wv.onResume(); wv.resumeTimers()
                            Log.d(webViewTag, "Lifecycle ON_RESUME for $contentId processed.")
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