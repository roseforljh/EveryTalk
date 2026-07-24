package com.android.everytalk.ui.components.math

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.android.everytalk.ui.components.EveryTalkLoadingIndicator
import com.android.everytalk.ui.components.streaming.FormulaDisplayMode
import com.android.everytalk.ui.components.streaming.FormulaRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal sealed interface MathFormulaRenderState {
    data object Loading : MathFormulaRenderState

    data class Ready(
        val result: MathJaxRenderResult,
        val cacheKey: MathFormulaCacheKey,
    ) : MathFormulaRenderState

    data class Error(val kind: MathFormulaErrorKind) : MathFormulaRenderState
}

internal enum class MathFormulaErrorKind {
    SYNTAX,
    TIMEOUT,
    ENGINE,
}

internal fun mathFormulaRequestVersion(formula: FormulaRequest): Long =
    formula.id.hashCode().toLong()

/** Compose 渲染身份不包含消息版本；消息版本仍由 FormulaRequest 保留并用于过期校验。 */
internal data class FormulaRenderIdentity(
    val id: String,
    val latex: String,
    val displayMode: FormulaDisplayMode,
)

internal fun FormulaRequest.renderIdentity(): FormulaRenderIdentity = FormulaRenderIdentity(
    id = id,
    latex = latex,
    displayMode = displayMode,
)

internal fun formulaRenderIdentities(
    formulas: Map<String, FormulaRequest>,
): List<FormulaRenderIdentity> = formulas.values.map(FormulaRequest::renderIdentity)

internal data class MathFormulaRenderSnapshot(
    val requestsById: Map<String, MathJaxRenderRequest> = emptyMap(),
    val statesById: Map<String, MathFormulaRenderState> = emptyMap(),
)

internal fun reconcileMathFormulaRenderStates(
    previous: MathFormulaRenderSnapshot,
    requests: List<MathJaxRenderRequest>,
    cachedReadyResults: Map<MathFormulaCacheKey, MathJaxRenderResult> = emptyMap(),
): Map<String, MathFormulaRenderState> = requests.associate { request ->
    val retainedState = previous.statesById[request.id]
        ?.takeIf { previous.requestsById[request.id] == request }
    val cacheKey = cacheKeyOf(request)
    val cachedState = cachedReadyResults[cacheKey]
        ?.takeIf { it.status == MathJaxRenderStatus.READY }
        ?.let { result -> MathFormulaRenderState.Ready(result, cacheKey) }
    request.id to (retainedState ?: cachedState ?: MathFormulaRenderState.Loading)
}

@Composable
internal fun rememberMathFormulaRenderStates(
    renderer: MathJaxSvgRenderer,
    formulas: Map<String, FormulaRequest>,
    fontSizePx: Float,
    color: String,
    blockMaxWidthPx: Float?,
): Map<String, MathFormulaRenderState> {
    val cacheRoot = LocalContext.current.applicationContext.cacheDir
    val renderIdentities = remember(formulas) { formulaRenderIdentities(formulas) }
    val stableFormulas = remember(renderIdentities) { formulas }
    val requests = remember(renderIdentities, fontSizePx, color, blockMaxWidthPx) {
        stableFormulas.values.map { formula ->
            MathJaxRenderRequest(
                id = formula.id,
                latex = formula.latex,
                display = formula.displayMode == FormulaDisplayMode.BLOCK,
                fontSizePx = fontSizePx,
                color = color,
                maxWidthPx = blockMaxWidthPx.takeIf {
                    formula.displayMode == FormulaDisplayMode.BLOCK
                },
                // 公式 ID 已由 LaTeX 与显示模式生成。后续只追加普通文本时保持请求身份稳定，
                // 防止已经完成的公式反复回到 Loading。
                requestVersion = mathFormulaRequestVersion(formula),
            )
        }
    }
    var snapshot by remember(renderer) { mutableStateOf(MathFormulaRenderSnapshot()) }
    val states = remember(requests, snapshot) {
        reconcileMathFormulaRenderStates(
            previous = snapshot,
            requests = requests,
            cachedReadyResults = MathFormulaSvgCache.getMemoryReadyResults(requests),
        )
    }

    LaunchedEffect(renderer, requests) {
        if (requests.isEmpty()) {
            snapshot = MathFormulaRenderSnapshot()
            return@LaunchedEffect
        }

        val requestsById = requests.associateBy(MathJaxRenderRequest::id)
        val retainedStates = states
        val pendingRequests = requests.filter { request ->
            retainedStates[request.id] == MathFormulaRenderState.Loading
        }
        snapshot = MathFormulaRenderSnapshot(
            requestsById = requestsById,
            statesById = retainedStates,
        )
        if (pendingRequests.isEmpty()) return@LaunchedEffect

        val renderedStates = try {
            // WebView 会自行切回主线程；缓存、SVG 安全校验与 XML 解析留在后台线程。
            val renderedResults = withContext(Dispatchers.Default) {
                MathFormulaSvgCache.render(cacheRoot, renderer, pendingRequests)
            }
            renderedResults.associate { (cacheKey, result) ->
                val request = requestsById.getValue(result.id)
                val state = when {
                    result.requestVersion != request.requestVersion ->
                        MathFormulaRenderState.Error(MathFormulaErrorKind.ENGINE)

                    result.status == MathJaxRenderStatus.READY && result.hasUsableMathSvg() ->
                        MathFormulaRenderState.Ready(result, cacheKey)

                    result.status == MathJaxRenderStatus.SYNTAX_ERROR ->
                        MathFormulaRenderState.Error(MathFormulaErrorKind.SYNTAX)

                    else -> MathFormulaRenderState.Error(MathFormulaErrorKind.ENGINE)
                }
                result.id to state
            }
        } catch (_: TimeoutCancellationException) {
            pendingRequests.associate { request ->
                request.id to MathFormulaRenderState.Error(MathFormulaErrorKind.TIMEOUT)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            pendingRequests.associate { request ->
                request.id to MathFormulaRenderState.Error(MathFormulaErrorKind.ENGINE)
            }
        }
        snapshot = MathFormulaRenderSnapshot(
            requestsById = requestsById,
            statesById = retainedStates + renderedStates,
        )
    }

    return states
}

@Composable
internal fun MathInline(
    formula: FormulaRequest,
    state: MathFormulaRenderState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        MathFormulaRenderState.Loading -> Box(
            modifier = modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            EveryTalkLoadingIndicator(
                size = 10.dp,
                strokeWidth = 1.dp,
                contentDescription = "数学公式转换中：${formula.latex}",
            )
        }

        is MathFormulaRenderState.Error -> InlineMathError(formula, modifier)
        is MathFormulaRenderState.Ready -> MathSvgImage(
            formula = formula,
            state = state,
            modifier = modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun MathBlock(
    formula: FormulaRequest,
    state: MathFormulaRenderState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        MathFormulaRenderState.Loading -> Box(
            modifier = modifier
                .fillMaxWidth()
                .height(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            EveryTalkLoadingIndicator(
                size = 18.dp,
                strokeWidth = 1.5.dp,
                contentDescription = "数学公式转换中：${formula.latex}",
            )
        }

        is MathFormulaRenderState.Error -> BlockMathError(formula, state.kind, modifier)
        is MathFormulaRenderState.Ready -> {
            val density = LocalDensity.current
            val formulaWidth = with(density) { state.result.requireWidthPx().toDp() }
            val formulaHeight = with(density) { state.result.requireHeightPx().toDp() }

            BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
                val scrollState = rememberScrollState()
                val contentWidth = maxOf(maxWidth, formulaWidth)
                val overflowModifier = if (formulaWidth > maxWidth) {
                    Modifier.horizontalScroll(scrollState)
                } else {
                    Modifier
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(overflowModifier),
                ) {
                    Box(
                        modifier = Modifier
                            .width(contentWidth)
                            .height(formulaHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        MathSvgImage(
                            formula = formula,
                            state = state,
                            modifier = Modifier
                                .width(formulaWidth)
                                .height(formulaHeight),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MathSvgImage(
    formula: FormulaRequest,
    state: MathFormulaRenderState.Ready,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val svg = requireNotNull(state.result.svg)
    val request = remember(svg, state.cacheKey) {
        ImageRequest.Builder(context)
            .data(svg.toByteArray(Charsets.UTF_8))
            .memoryCacheKey(state.cacheKey.coilMemoryCacheKey())
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    }
    var decodeFailed by remember(request) { mutableStateOf(false) }

    if (decodeFailed) {
        when (formula.displayMode) {
            FormulaDisplayMode.INLINE -> InlineMathError(formula, modifier)
            FormulaDisplayMode.BLOCK -> BlockMathError(
                formula = formula,
                kind = MathFormulaErrorKind.ENGINE,
                modifier = modifier,
            )
        }
    } else {
        AsyncImage(
            model = request,
            contentDescription = "数学公式：${formula.latex}",
            modifier = modifier.semantics {
                contentDescription = "数学公式：${formula.latex}"
            },
            contentScale = ContentScale.FillBounds,
            onError = { decodeFailed = true },
        )
    }
}

@Composable
private fun InlineMathError(
    formula: FormulaRequest,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(3.dp),
            )
            .semantics { contentDescription = "公式渲染失败：${formula.latex}" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "公式错误",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun BlockMathError(
    formula: FormulaRequest,
    kind: MathFormulaErrorKind,
    modifier: Modifier,
) {
    val detail = when (kind) {
        MathFormulaErrorKind.SYNTAX -> "公式语法无效"
        MathFormulaErrorKind.TIMEOUT -> "公式转换超时"
        MathFormulaErrorKind.ENGINE -> "公式转换引擎异常"
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
            )
            .semantics {
                contentDescription = "公式渲染失败：$detail；${formula.latex}"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "公式渲染失败\n$detail",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

internal fun mathWidthBucketPx(widthPx: Float?): Float? {
    if (widthPx == null || !widthPx.isFinite() || widthPx <= 0f) return null
    return ((widthPx / 32f).roundToInt().coerceAtLeast(1) * 32).toFloat()
}

internal fun MathJaxRenderResult.requireWidthPx(): Float =
    requireNotNull(widthPx).coerceAtLeast(1f)

internal fun MathJaxRenderResult.requireHeightPx(): Float =
    requireNotNull(heightPx).coerceAtLeast(1f)

internal fun Color.toMathJaxCssColor(): String {
    val alpha = (alpha * 255f).toInt().coerceIn(0, 255)
    val red = (red * 255f).toInt().coerceIn(0, 255)
    val green = (green * 255f).toInt().coerceIn(0, 255)
    val blue = (blue * 255f).toInt().coerceIn(0, 255)
    return if (alpha == 255) {
        "#%02x%02x%02x".format(red, green, blue)
    } else {
        "#%02x%02x%02x%02x".format(red, green, blue, alpha)
    }
}
