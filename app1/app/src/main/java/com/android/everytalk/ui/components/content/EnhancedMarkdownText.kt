package com.android.everytalk.ui.components
import com.android.everytalk.ui.components.coordinator.ContentCoordinator
import com.android.everytalk.ui.components.streaming.rememberTypewriterState

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import com.android.everytalk.config.PerformanceConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.statecontroller.AppViewModel

private fun hasMathSyntax(text: String): Boolean = MathStreamingPolicy.hasMathSyntax(text)

private fun hasUnclosedMathDelimiter(text: String): Boolean =
    MathStreamingPolicy.hasUnclosedMathDelimiter(text)

private fun findMathAffectedRange(previous: String, current: String): MathStreamingPolicy.AffectedRange? =
    MathStreamingPolicy.findMathAffectedRange(previous, current)

private fun shouldForceMathBoundaryRefresh(previous: String, current: String): Boolean =
    MathStreamingPolicy.shouldForceMathBoundaryRefresh(previous, current)

private const val MATH_STREAM_LOG_TAG = "MathStreamThrottle"


/**
 * 澧炲己鐨凪arkdown鏂囨湰鏄剧ず缁勪欢
 * 
 * 鏀寔鍔熻兘锛?
 * - Markdown鏍煎紡锛堟爣棰樸€佸垪琛ㄣ€佺矖浣撱€佹枩浣撶瓑锛? 閫氳繃澶栭儴搴撳疄鏃惰浆鎹?
 * - 浠ｇ爜鍧楋紙鑷€傚簲婊氬姩锛?
 * - 琛ㄦ牸娓叉煋
 * - 鏁板鍏紡锛圞aTeX锛?
 * - 娴佸紡瀹炴椂鏇存柊
 * 
 *  鏋舵瀯璇存槑锛堥噸鏋勫悗锛夛細
 * - 浣跨敤 collectAsState 璁㈤槄娴佸紡鍐呭锛屽疄鐜板疄鏃舵洿鏂?
 * - 濮旀墭缁?ContentCoordinator 缁熶竴璋冨害涓嶅悓绫诲瀷鐨勫唴瀹?
 * - 鍗曞悜鏁版嵁娴侊細Flow 鈫?State 鈫?UI锛堟棤鍙嶅悜渚濊禆锛岄伩鍏嶆棤闄愰噸缁勶級
 * - 娣诲姞閲嶇粍鐩戞帶锛屽強鏃跺彂鐜版綔鍦ㄩ棶棰?
 */
@Composable
fun EnhancedMarkdownText(
    message: Message,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    messageOutputType: String = "",
    inTableContext: Boolean = false,
    onLongPress: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    inSelectionDialog: Boolean = false,
    onImageClick: ((String) -> Unit)? = null, //  鏂板
    onCodePreviewRequested: ((String, String) -> Unit)? = null, // 鏂板锛氫唬鐮侀瑙堝洖璋?(language, code)
    onCodeCopied: (() -> Unit)? = null, // 鏂板锛氫唬鐮佸鍒跺洖璋?
    viewModel: AppViewModel? = null,
    contentOverride: String? = null,
    contentKeyOverride: String? = null,
    disableStreamingSubscription: Boolean = false
) {
    val resolvedContentKey = contentKeyOverride ?: message.id
    val staticContent = contentOverride ?: message.text

    val textColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }
    
    //  鑾峰彇瀹炴椂娴佸紡鍐呭
    // 浣跨敤 collectAsState 璁㈤槄Flow锛屽疄鐜版祦寮忔晥鏋?
    //  浼樺寲锛氭祦寮忕粨鏉熷悗缁х画璁㈤槄 StateFlow锛岀洿鍒扮粍浠堕攢姣佹垨鏄惧紡閲嶇疆
    // 閬垮厤 isStreaming 浠?true -> false 鐬棿鍒囨崲鏁版嵁婧愬鑷撮噸缁勯棯鐑?
    val streamingStateFlow = remember(message.id, viewModel, disableStreamingSubscription) {
        if (viewModel != null && !disableStreamingSubscription) {
            viewModel.streamingMessageStateManager.getOrCreateStreamingState(message.id)
        } else {
            null
        }
    }

    val content by if (streamingStateFlow != null && (isStreaming || viewModel?.streamingMessageStateManager?.isStreaming(message.id) == true)) {
        // 濡傛灉鏈夊彲鐢ㄧ殑 StateFlow 涓?(姝ｅ湪娴佸紡 OR 鐘舵€佺鐞嗗櫒璁や负杩樺湪娴佸紡)锛屼紭鍏堜娇鐢ㄦ祦寮忔暟鎹?
        // 鍗充娇 isStreaming 鍙樹负 false锛屽彧瑕?StateFlow 杩樺湪锛屽氨缁х画鐢ㄥ畠锛岄槻姝㈠垏鍥?message.text 鐨勭灛闂撮棯鐑?
        streamingStateFlow.collectAsState(initial = staticContent)
    } else {
        // 瀹屽叏闈炴祦寮忔垨鏃?ViewModel锛氫娇鐢?remember 鍖呰 message.text
        remember(staticContent) { mutableStateOf(staticContent) }
    }
    
    // 馃攳 璋冭瘯锛氫粎鍦?content 瀹為檯鍙樺寲鏃惰褰曪紝閬垮厤姣忔閲嶇粍閮芥墦鏃ュ織
    if (isStreaming && com.android.everytalk.BuildConfig.DEBUG) {
        LaunchedEffect(content) {
            android.util.Log.d(
                "EnhancedMarkdownText",
                "馃摑 Streaming content updated: msgId=${message.id.take(8)}, len=${content.length}, preview=${content.take(30)}"
            )
        }
    }

    //  濮旀墭缁?ContentCoordinator 缁熶竴璋冨害
    // 浼樺娍锛?
    // 1. 鑱岃矗鍒嗙锛氭暟瀛︺€佽〃鏍笺€佺函鏂囨湰鍚勮嚜鐙珛
    // 2. 鏄撲簬缁存姢锛氫慨鏀规煇涓ā鍧椾笉褰卞搷鍏朵粬妯″潡
    // 3. 鏄撲簬鎵╁睍锛氭坊鍔犳柊绫诲瀷锛堝鍥捐〃锛夊彧闇€娣诲姞鏂版ā鍧?
    // 4. 缂撳瓨鏈哄埗锛氫娇鐢ㄦ秷鎭疘D浣滀负key锛岄伩鍏峀azyColumn鍥炴敹鍚庨噸澶嶈В鏋?
    //  鏍规嵁鍙戦€佽€呭喅瀹氬搴︾瓥鐣?
    val widthModifier = if (message.sender == Sender.User) {
        Modifier.wrapContentWidth()
    } else {
        Modifier.fillMaxWidth()
    }
    
    Box(
        modifier = modifier.then(widthModifier)
    ) {
        val isActuallyStreaming = isStreaming ||
            (viewModel?.streamingMessageStateManager?.isStreaming(message.id) == true)

        var throttledMathContent by remember(resolvedContentKey) { mutableStateOf(content) }
        var lastRawMathContent by remember(resolvedContentKey) { mutableStateOf(content) }
        var lastMathUpdateAt by remember(resolvedContentKey) { mutableStateOf(0L) }
        var lastMathUnclosed by remember(resolvedContentKey) { mutableStateOf(false) }

        LaunchedEffect(content, isActuallyStreaming) {
            if (!isActuallyStreaming) {
                throttledMathContent = content
                lastRawMathContent = content
                lastMathUnclosed = hasUnclosedMathDelimiter(content)
                lastMathUpdateAt = SystemClock.elapsedRealtime()
                return@LaunchedEffect
            }

            if (!hasMathSyntax(content)) {
                throttledMathContent = content
                lastRawMathContent = content
                lastMathUnclosed = false
                lastMathUpdateAt = SystemClock.elapsedRealtime()
                return@LaunchedEffect
            }

            val now = SystemClock.elapsedRealtime()
            val currentUnclosed = hasUnclosedMathDelimiter(content)
            val affectedRange = findMathAffectedRange(lastRawMathContent, content)
            val boundaryRefresh =
                shouldForceMathBoundaryRefresh(lastRawMathContent, content) ||
                    affectedRange != null ||
                    (lastMathUnclosed && !currentUnclosed)
            val throttleWindowMs = if (currentUnclosed) 120L else 80L
            val elapsedSinceLastUpdate = now - lastMathUpdateAt
            val shouldUpdate = boundaryRefresh || elapsedSinceLastUpdate >= throttleWindowMs
            val shouldLogMathStreaming = com.android.everytalk.BuildConfig.DEBUG &&
                PerformanceConfig.ENABLE_RENDER_TRANSITION_LOGGING

            if (shouldLogMathStreaming) {
                val deltaLen = content.length - lastRawMathContent.length
                val reason = when {
                    boundaryRefresh -> "boundary"
                    shouldUpdate -> "throttle-window"
                    else -> "skip"
                }
                android.util.Log.d(
                    MATH_STREAM_LOG_TAG,
                    "msgId=${resolvedContentKey.take(24)} action=${if (shouldUpdate) "update" else "skip"} " +
                        "reason=$reason len=${content.length} delta=$deltaLen " +
                        "range=${affectedRange?.start ?: -1}-${affectedRange?.endExclusive ?: -1} " +
                        "unclosed=$currentUnclosed prevUnclosed=$lastMathUnclosed " +
                        "elapsed=${elapsedSinceLastUpdate}ms window=${throttleWindowMs}ms"
                )
            }

            if (shouldUpdate) {
                throttledMathContent = content
                lastMathUpdateAt = now
            }

            lastRawMathContent = content
            lastMathUnclosed = currentUnclosed
        }

        val hasMath = hasMathSyntax(throttledMathContent)
        val useTypewriter = isActuallyStreaming && !hasMath

        val typewriterState = rememberTypewriterState(
            targetText = throttledMathContent,
            isStreaming = useTypewriter,
            charsPerFrame = 6,
            frameDelayMs = 32L,
            maxCharsPerFrame = 60,
            catchUpDivisor = 4
        )

        val displayText = when {
            useTypewriter -> typewriterState.displayedText
            else -> throttledMathContent
        }

        ContentCoordinator(
            text = displayText,
            style = style,
            color = textColor,
            isStreaming = isActuallyStreaming,
            modifier = widthModifier,
            contentKey = resolvedContentKey,
            onLongPress = onLongPress,
            onImageClick = onImageClick,
            sender = message.sender,
            disableVerticalPadding = true,
            onCodePreviewRequested = onCodePreviewRequested,
            onCodeCopied = onCodeCopied
        )
    }
}

/**
 * 绠€鍖栫殑闈欐€佹枃鏈樉绀虹粍浠?
 */
@Composable
fun StableMarkdownText(
    markdown: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Text(
        text = markdown,
        modifier = modifier,
        style = style.copy(
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )
    )
}
