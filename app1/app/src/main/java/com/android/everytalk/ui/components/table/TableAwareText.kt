package com.android.everytalk.ui.components.table

import android.content.ClipData


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlignVerticalCenter
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.ContentParser
import com.android.everytalk.ui.components.ContentPart
import com.android.everytalk.ui.components.WebPreviewDialog
import com.android.everytalk.ui.components.content.CodeBlockCard
import com.android.everytalk.ui.components.StableMarkdownText
import com.android.everytalk.ui.components.markdown.BreakableLatexRenderer
import com.android.everytalk.ui.components.markdown.MarkdownRenderer
import com.android.everytalk.ui.components.markdown.NativeLatexSupport
import com.android.everytalk.ui.components.markdown.StableLatexRenderer
import com.android.everytalk.ui.components.syntax.HighlightCache
import com.android.everytalk.ui.components.syntax.SyntaxHighlightTheme
import com.android.everytalk.ui.components.syntax.SyntaxHighlighter
import com.android.everytalk.ui.components.icons.MdiIcon
import com.android.everytalk.ui.components.icons.MdiIconAdaptive
import com.android.everytalk.ui.components.icons.isMdiIconAvailable
import com.android.everytalk.ui.theme.chatColors
import com.android.everytalk.util.cache.ContentParseCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

internal fun shouldRenderTrailingStreamingTextWithMarkdown(content: String): Boolean {
    return !content.contains("```") &&
        !content.contains("~~~") &&
        !content.contains("```infographic", ignoreCase = true) &&
        !(content.contains('|') && content.contains("---")) &&
        !content.contains("$$") &&
        !content.contains("\\[")
}

internal fun containsFencedCodeSyntax(content: String): Boolean {
    return content.contains("```") || content.contains("~~~")
}

internal fun shouldRerouteTextPartThroughTableAwareParser(
    content: String,
    recursionDepth: Int,
): Boolean {
    return recursionDepth < 3 && containsCompleteMarkdownTableSyntax(content)
}

private fun containsCompleteMarkdownTableSyntax(content: String): Boolean {
    val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    return lines.indices.any { index -> TableUtils.isValidTableStart(lines, index) }
}

internal fun shouldPreferStableMarkdownFallback(
    content: String,
    isStreaming: Boolean,
    isTrailingStreamingText: Boolean,
): Boolean {
    if (!isStreaming) return false
    if (containsFencedCodeSyntax(content)) return hasUnclosedFencedCodeSyntax(content)
    if (!isTrailingStreamingText) return false
    return content.contains("$$") || content.contains("\\[")
}

private fun hasUnclosedFencedCodeSyntax(content: String): Boolean =
    countFenceMarkers(content, "```") % 2 == 1 ||
        countFenceMarkers(content, "~~~") % 2 == 1

private fun countFenceMarkers(content: String, marker: String): Int {
    var index = 0
    var count = 0
    while (true) {
        val found = content.indexOf(marker, index)
        if (found < 0) return count
        count++
        index = found + marker.length
    }
}

internal data class TableAwareParseRequest(
    val text: String,
)

internal fun shouldRenderStableNativeMathPart(
    isBlockMath: Boolean,
    isStreaming: Boolean,
    forceNativeRenderer: Boolean,
    preferStableNativeRenderer: Boolean,
    canRenderNatively: Boolean,
): Boolean {
    if (!isBlockMath || !canRenderNatively) return false
    return forceNativeRenderer || preferStableNativeRenderer || !isStreaming
}

/**
 * 表格感知文本渲染器（优化版 + 实时渲染）
 *
 * 核心策略：
 * - 流式模式：实时分块渲染，表格/代码块即时显示
 * - 非流式模式：分块渲染，每种 ContentPart 类型使用最优组件
 * - 后台解析：使用 flowOn(Dispatchers.Default) 在后台线程解析 AST
 * - referentialEqualityPolicy：避免不必要的状态更新导致重组
 * - 稳定 Key：使用索引作为 key，避免内容变化导致组件重建
 *
 * 缓存机制：通过 contentKey 持久化解析结果，避免 LazyColumn 回收导致重复解析
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun TableAwareText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    recursionDepth: Int = 0,
    contentKey: String = "",  // 新增：用于缓存 key（通常为消息 ID）
    onLongPress: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null,
    sender: Sender = Sender.AI,
    onCodePreviewRequested: ((String, String) -> Unit)? = null, // 代码预览回调
    onCodeCopied: (() -> Unit)? = null // 代码复制回调
) {
    // 预览状态管理
    val coroutineScope = rememberCoroutineScope()
    var previewState by remember { mutableStateOf<Pair<String, String>?>(null) } // (code, language)

    var parsedParts by remember {
        mutableStateOf(
            value = emptyList<ContentPart>(),
            policy = referentialEqualityPolicy()
        )
    }

    val updatedText by rememberUpdatedState(text)
    val updatedIsStreaming by rememberUpdatedState(isStreaming)
    val effectiveCacheKey = if (contentKey.isNotBlank() && !isStreaming) {
        "${contentKey}_${text.hashCode()}_v${ContentParseCache.PARSER_VERSION}"
    } else ""

    LaunchedEffect(Unit) {
        // 仅监听文本本身的变化来触发重新解析
        // 不再将 isStreaming 纳入触发条件，避免流式结束瞬间因 isStreaming 切换
        // 而触发全量重解析 + Compose 全量重组，导致整个气泡闪一下
        snapshotFlow { updatedText }
            .distinctUntilChanged()
            .mapLatest { currentText ->
                val streaming = updatedIsStreaming
                val cacheKey = if (contentKey.isNotBlank() && !streaming) {
                    "${contentKey}_${currentText.hashCode()}_v${ContentParseCache.PARSER_VERSION}"
                } else ""

                if (!streaming && cacheKey.isNotBlank()) {
                    ContentParseCache.get(cacheKey)?.let {
                        return@mapLatest it
                    }
                }

                // 流式阶段也对完整累计文本做后台结构化解析。
                // 不再走“仅追加最后一个 Text 块”的快捷路径，否则列表/标题/表格/代码围栏
                // 会长期停留在错误结构，直到结束才一次性纠正。
                val parts = ContentParser.parseCompleteContent(currentText)

                if (!streaming && cacheKey.isNotBlank()) {
                    ContentParseCache.put(cacheKey, parts)
                }

                parts
            }
            .catch { e ->
                e.printStackTrace()
                emit(listOf(ContentPart.Text(updatedText)))
            }
            .flowOn(Dispatchers.Default)
            .collect { parts ->
                parsedParts = parts
            }
    }

    if (parsedParts.isEmpty() && text.isNotEmpty()) {
        val initialParts = remember(text, contentKey, isStreaming) {
            if (!isStreaming && effectiveCacheKey.isNotBlank()) {
                ContentParseCache.get(effectiveCacheKey)
            } else null
        } ?: ContentParser.parseCompleteContent(text).also { parts ->
            if (!isStreaming && effectiveCacheKey.isNotBlank()) {
                ContentParseCache.put(effectiveCacheKey, parts)
            }
        }
        parsedParts = initialParts
    }

    val verticalPaddingDp = if (sender == Sender.AI) 2.dp else 0.dp
    val contentPartSpacingDp = if (sender == Sender.AI) 8.dp else 0.dp
    val disableMarkdownVerticalPadding = sender != Sender.AI

    // 流式期间记录已测量的最大高度，防止结构变化（如检测到代码块/表格）时高度坍塌
    var measuredHeightPx by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val heightStabilizer = if (isStreaming && measuredHeightPx > 0) {
        Modifier.heightIn(min = with(density) { measuredHeightPx.toDp() })
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(heightStabilizer)
            .padding(vertical = verticalPaddingDp)
            .onSizeChanged { size ->
                if (isStreaming && size.height > measuredHeightPx) {
                    measuredHeightPx = size.height
                }
                if (!isStreaming) {
                    measuredHeightPx = size.height
                }
            },
        verticalArrangement = Arrangement.spacedBy(contentPartSpacingDp),
    ) {
        parsedParts.forEachIndexed { index, part ->
            // 使用类型 + startOffset 作为主 key，尽量贴近“结构块身份”而不是索引身份。
            // 这样当前面块稳定、只更新尾部块时，前面的 Compose 子树可被最大化复用。
            val stableKey = "${part.javaClass.simpleName}_${part.startOffset}"

            androidx.compose.runtime.key(stableKey) {
                when (part) {
                    is ContentPart.Text -> {
                        val isTrailingStreamingText = isStreaming && index == parsedParts.lastIndex
                        val trailingLooksLightweightMarkdown = isTrailingStreamingText &&
                            shouldRenderTrailingStreamingTextWithMarkdown(part.content)
                        val shouldRerouteCompletedFencedText = !isStreaming &&
                            recursionDepth < 3 &&
                            containsFencedCodeSyntax(part.content)
                        val shouldRerouteTableText = shouldRerouteTextPartThroughTableAwareParser(
                            content = part.content,
                            recursionDepth = recursionDepth,
                        )
                        val shouldUseStableFallback = shouldPreferStableMarkdownFallback(
                            content = part.content,
                            isStreaming = isStreaming,
                            isTrailingStreamingText = isTrailingStreamingText,
                        )

                        when {
                            shouldRerouteCompletedFencedText -> {
                                TableAwareText(
                                    text = part.content,
                                    style = style,
                                    color = color,
                                    isStreaming = false,
                                    modifier = Modifier.fillMaxWidth(),
                                    recursionDepth = recursionDepth + 1,
                                    contentKey = if (contentKey.isNotBlank()) {
                                        "${contentKey}_nested_${index}_${part.content.hashCode()}"
                                    } else "",
                                    onLongPress = onLongPress,
                                    onImageClick = onImageClick,
                                    sender = sender,
                                    onCodePreviewRequested = onCodePreviewRequested,
                                    onCodeCopied = onCodeCopied,
                                )
                            }
                            shouldUseStableFallback -> {
                                StableMarkdownText(
                                    markdown = part.content,
                                    style = style,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            shouldRerouteTableText -> {
                                TableAwareText(
                                    text = part.content,
                                    style = style,
                                    color = color,
                                    isStreaming = isStreaming,
                                    modifier = Modifier.fillMaxWidth(),
                                    recursionDepth = recursionDepth + 1,
                                    contentKey = if (contentKey.isNotBlank()) {
                                        "${contentKey}_table_text_${index}_${part.content.hashCode()}"
                                    } else "",
                                    onLongPress = onLongPress,
                                    onImageClick = onImageClick,
                                    sender = sender,
                                    onCodePreviewRequested = onCodePreviewRequested,
                                    onCodeCopied = onCodeCopied,
                                )
                            }
                            trailingLooksLightweightMarkdown -> {
                                MarkdownRenderer(
                                    markdown = part.content,
                                    style = style,
                                    color = color,
                                    modifier = Modifier.fillMaxWidth(),
                                    isStreaming = true,
                                    onLongPress = onLongPress,
                                    onImageClick = onImageClick,
                                    sender = sender,
                                    contentKey = "",
                                    disableVerticalPadding = disableMarkdownVerticalPadding
                                )
                            }
                            else -> {
                                MarkdownRenderer(
                                    markdown = part.content,
                                    style = style,
                                    color = color,
                                    modifier = Modifier.fillMaxWidth(),
                                    isStreaming = isStreaming,
                                    onLongPress = onLongPress,
                                    onImageClick = onImageClick,
                                    sender = sender,
                                    contentKey = if (contentKey.isNotBlank()) {
                                        if (isStreaming) {
                                            if (index < parsedParts.size - 1) {
                                                "${contentKey}_part_${index}_${part.content.hashCode()}"
                                            } else ""
                                        } else {
                                            "${contentKey}_part_${index}_${part.content.hashCode()}"
                                        }
                                    } else "",
                                    disableVerticalPadding = disableMarkdownVerticalPadding
                                )
                            }
                        }
                    }
                    is ContentPart.Code -> {
                        val lang = part.language?.trim()?.lowercase()
                        if (lang == "infographic") {
                            InfographicBlock(
                                raw = part.content,
                                style = style,
                                color = color,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                isStreaming = isStreaming
                            )
                        } else {
                            val clipboard = LocalClipboard.current
                            CodeBlockCard(
                                language = part.language,
                                code = part.content,
                                modifier = Modifier.padding(vertical = 4.dp),
                                isStreaming = isStreaming,
                                onPreviewRequested = if (onCodePreviewRequested != null) {
                                    { onCodePreviewRequested(part.language ?: "", part.content) }
                                } else null,
                                onCopy = {
                                    coroutineScope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(
                                                ClipData.newPlainText("code", part.content)
                                            )
                                        )
                                        onCodeCopied?.invoke()
                                    }
                                },
                                onLongPress = onLongPress
                            )
                        }
                    }
                    is ContentPart.Table -> {
                        TableRenderer(
                            lines = part.lines,
                            modifier = Modifier
                                .wrapContentWidth()
                                .padding(vertical = 8.dp),
                            isStreaming = false,
                            contentKey = if (contentKey.isNotBlank() && !isStreaming) {
                                "${contentKey}_table_${index}_${part.lines.size}"
                            } else "",
                            onLongPress = onLongPress,
                            headerStyle = style.copy(fontWeight = FontWeight.Bold),
                            cellStyle = style
                        )
                    }
                    is ContentPart.Math -> {
                        val localContext = LocalContext.current
                        val onSurfaceColor = MaterialTheme.colorScheme.onSurface
                        val isBlockMath = remember(part.content) {
                            val trimmed = part.content.trim()
                            (trimmed.startsWith("$$") && trimmed.endsWith("$$")) ||
                                (trimmed.startsWith("\\[") && trimmed.endsWith("\\]"))
                        }
                        val forceNativeRenderer = remember(part.content) {
                            MathRenderStrategy.shouldForceNativeBlockRendererForMathPart(part.content)
                        }
                        val forceMarkdownRenderer = remember(part.content) {
                            MathRenderStrategy.shouldForceMarkdownRendererForMathPart(part.content)
                        }
                        val preferStableNativeRenderer = remember(part.content) {
                            MathRenderStrategy.shouldPreferStableNativeMathRenderer(part.content)
                        }
                        val canRenderNatively = remember(part.content, color, style, onSurfaceColor, localContext) {
                            NativeLatexSupport.ensureInitialized(localContext)
                            val baseSp = if (style.fontSize.value > 0f) style.fontSize.value else 16f
                            val textSizeSp = baseSp * 1.05f
                            val density = localContext.resources.displayMetrics.scaledDensity
                            val textSizePx = textSizeSp * density
                            val colorArgb = when {
                                color != Color.Unspecified -> color.toArgb()
                                style.color != Color.Unspecified -> style.color.toArgb()
                                else -> onSurfaceColor.toArgb()
                            }
                            NativeLatexSupport.canRenderNatively(part.content, textSizePx, colorArgb)
                        }
                        val shouldUseBreakableMathRenderer = remember(part.content, isStreaming) {
                            isBlockMath && !isStreaming &&
                                !forceMarkdownRenderer &&
                                !preferStableNativeRenderer &&
                                MathRenderStrategy.shouldEnableHorizontalScrollForMathPart(part.content)
                        }
                        val shouldUseStableNativeRenderer = remember(
                            isBlockMath,
                            isStreaming,
                            forceNativeRenderer,
                            preferStableNativeRenderer,
                            canRenderNatively,
                        ) {
                            shouldRenderStableNativeMathPart(
                                isBlockMath = isBlockMath,
                                isStreaming = isStreaming,
                                forceNativeRenderer = forceNativeRenderer,
                                preferStableNativeRenderer = preferStableNativeRenderer,
                                canRenderNatively = canRenderNatively,
                            )
                        }
                        when {
                            shouldUseStableNativeRenderer -> {
                                // 真机 release 上 breakable 路径可能被底层 JLatexMath 反射问题击穿。
                                // 只要块公式已经确认可原生渲染，就优先走稳定的 native + 横向滚动路径。
                                StableLatexRenderer(
                                    latex = part.content,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    style = style,
                                    color = color,
                                    contentKey = if (contentKey.isNotBlank()) {
                                        "${contentKey}_math_${index}_${part.content.hashCode()}"
                                    } else ""
                                )
                            }
                            shouldUseBreakableMathRenderer -> {
                                // 仅对超长且非结构化块公式启用 BreakableLatexRenderer。
                                BreakableLatexRenderer(
                                    latex = part.content,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    style = style,
                                    color = color,
                                    contentKey = if (contentKey.isNotBlank()) {
                                        "${contentKey}_math_${index}_${part.content.hashCode()}"
                                    } else ""
                                )
                            }
                            else -> {
                                // 数学回退到纯文本 Markdown 会显示原始 LaTeX；
                                // native 不可用时统一走可换行的原生数学渲染器，至少保证可见。
                                BreakableLatexRenderer(
                                    latex = part.content,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = if (isBlockMath) 8.dp else 0.dp),
                                    contentKey = if (contentKey.isNotBlank()) {
                                        "${contentKey}_math_${index}_${part.content.hashCode()}"
                                    } else "",
                                    style = style,
                                    color = color
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 显示预览对话框
    previewState?.let { (code, language) ->
        WebPreviewDialog(
            code = code,
            language = language,
            onDismiss = { previewState = null }
        )
    }
}

@Composable
private fun StreamingCodeBlockCard(
    language: String?,
    code: String,
    modifier: Modifier = Modifier,
    isCompleted: Boolean,
    onLongPress: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
) {
    val displayLanguage = language?.trim()?.ifBlank { "CODE" }?.uppercase() ?: "CODE"
    val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val bg = MaterialTheme.chatColors.codeBlockBackground
    val outline = if (isDarkTheme) {
        Color.White.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }
    val headerContentColor = if (isDarkTheme) Color.White else Color.Black
    val syntaxTheme = remember(isDarkTheme) {
        if (isDarkTheme) SyntaxHighlightTheme.Dark else SyntaxHighlightTheme.Light
    }
    val highlightedCode = remember(code, language, isDarkTheme) {
        val cacheKey = HighlightCache.generateKey(code, language, isDarkTheme)
        HighlightCache.get(cacheKey) ?: run {
            val result = SyntaxHighlighter.highlight(code, language, syntaxTheme)
            HighlightCache.put(cacheKey, result)
            result
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.5.dp, outline, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        color = bg
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayLanguage,
                    style = MaterialTheme.typography.labelSmall,
                    color = headerContentColor,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = if (isCompleted) "代码块" else "生成中",
                    style = MaterialTheme.typography.labelSmall,
                    color = headerContentColor.copy(alpha = 0.7f)
                )
            }

            val scrollState = rememberScrollState()
            Text(
                text = highlightedCode,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

private data class InfographicItem(
    val label: String,
    val desc: String,
    val icon: String?
)

private fun parseInfographic(raw: String): Pair<String, List<InfographicItem>> {
    val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return "" to emptyList()

    var index = 0
    while (index < lines.size && lines[index].startsWith("infographic", ignoreCase = true)) {
        index++
    }
    if (index < lines.size && lines[index].equals("data", ignoreCase = true)) {
        index++
    }

    var title = ""
    val items = mutableListOf<InfographicItem>()

    while (index < lines.size) {
        val line = lines[index]
        if (line.startsWith("title ", ignoreCase = true)) {
            title = line.removePrefix("title").trim()
            index++
            continue
        }
        if (line.startsWith("items", ignoreCase = true)) {
            index++
            while (index < lines.size) {
                val current = lines[index]
                if (!current.startsWith("- label ", ignoreCase = true)) {
                    index++
                    continue
                }
                val label = current.removePrefix("- label").trim()
                var desc = ""
                var icon: String? = null

                var next = index + 1
                if (next < lines.size && lines[next].startsWith("desc ", ignoreCase = true)) {
                    desc = lines[next].removePrefix("desc").trim()
                    next++
                }
                if (next < lines.size && lines[next].startsWith("icon ", ignoreCase = true)) {
                    icon = lines[next].removePrefix("icon").trim()
                    next++
                }

                items.add(InfographicItem(label, desc, icon))
                index = next
            }
            continue
        }
        index++
    }

    return title to items
}

private fun resolveInfographicIcon(icon: String): ImageVector? {
    val normalized = icon.trim()
    val lower = normalized.lowercase()
    val key = if (lower.startsWith("mdi:")) {
        lower.removePrefix("mdi:")
    } else {
        return null
    }

    val directMatch = when (key) {
        "database-import" -> Icons.Outlined.FileDownload
        "database-export" -> Icons.Filled.Upload
        "calendar-clock" -> Icons.Filled.CalendarMonth
        "format-vertical-align-center" -> Icons.Filled.AlignVerticalCenter
        "sigma" -> Icons.Filled.Functions
        "grid" -> Icons.Outlined.GridOn
        "cog" -> Icons.Filled.Settings
        "desktop-classic" -> Icons.Filled.Computer
        "shield-lock" -> Icons.Filled.Security
        "shieid-lock" -> Icons.Filled.Security
        "gesture-swipe-horizontal" -> Icons.Filled.Swipe
        "magnify-minus-outline" -> Icons.Filled.ZoomOut
        else -> null
    }

    if (directMatch != null) return directMatch

    return when {
        key.contains("database") -> Icons.Outlined.Storage
        key.contains("server") -> Icons.Outlined.Dns
        key.contains("calendar") || key.contains("clock") -> Icons.Filled.CalendarMonth
        key.contains("cloud") -> Icons.Filled.Cloud
        key.contains("grid") -> Icons.Outlined.GridOn
        key.contains("sigma") || key.contains("sum") -> Icons.Filled.Functions
        key.contains("align") && key.contains("center") -> Icons.Filled.AlignVerticalCenter
        key.contains("cog") || key.contains("gear") -> Icons.Filled.Settings
        key.contains("desktop") || key.contains("monitor") -> Icons.Filled.Computer
        (key.contains("shield") || key.contains("shieid")) && key.contains("lock") -> Icons.Filled.Security
        key.contains("gesture") || key.contains("swipe") -> Icons.Filled.Swipe
        key.contains("magnify") || key.contains("zoom") -> Icons.Filled.ZoomOut
        else -> Icons.AutoMirrored.Filled.HelpOutline
    }
}

@Composable
private fun resolveMdiImageVector(icon: String): ImageVector? {
    val normalized = icon.trim()
    val lower = normalized.lowercase()
    if (!lower.startsWith("mdi:")) return null
    val key = lower.removePrefix("mdi:")
    val resName = "zzz_" + key.replace("-", "_")
    val context = LocalContext.current
    val resId = remember(resName) {
        context.resources.getIdentifier(resName, "drawable", context.packageName)
    }
    if (resId == 0) return null
    return ImageVector.vectorResource(id = resId)
}

@Composable
private fun InfographicBlock(
    raw: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false
) {
    val (title, items) = remember(raw) { parseInfographic(raw) }

    if (isStreaming) {
        val reservedItemCount = remember(items.size) {
            when {
                items.size >= 4 -> items.size
                raw.contains("items", ignoreCase = true) -> maxOf(items.size, 3)
                else -> maxOf(items.size, 2)
            }
        }
        val showSkeleton = raw.contains("infographic", ignoreCase = true) ||
            raw.contains("title ", ignoreCase = true) ||
            raw.contains("items", ignoreCase = true) ||
            items.isNotEmpty()

        if (showSkeleton) {
            val headlineColor = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface
            val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
            val placeholderLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)

            Column(modifier = modifier.fillMaxWidth()) {
                Text(
                    text = title.ifBlank { "正在生成信息图..." },
                    style = style.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = style.fontSize * 1.1f
                    ),
                    color = headlineColor,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                repeat(reservedItemCount) { index ->
                    val item = items.getOrNull(index)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                    ) {
                        Column(
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(placeholderLineColor, CircleShape)
                            )

                            if (index != reservedItemCount - 1) {
                                Box(
                                    modifier = Modifier
                                        .width(2.dp)
                                        .height(44.dp)
                                        .background(placeholderLineColor)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(bottom = if (index != reservedItemCount - 1) 10.dp else 0.dp)
                        ) {
                            Text(
                                text = item?.label?.ifBlank { "正在生成..." } ?: "正在生成...",
                                style = style.copy(fontWeight = FontWeight.Medium),
                                color = headlineColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item?.desc?.ifBlank { "内容生成中" } ?: "内容生成中",
                                style = style.copy(color = secondaryColor),
                                color = secondaryColor,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            return
        }
    }

    if (title.isBlank() && items.isEmpty()) {
        MarkdownRenderer(
            markdown = raw,
            style = style,
            color = color,
            modifier = modifier,
            isStreaming = false,
            onLongPress = null,
            onImageClick = null,
            sender = Sender.AI,
            contentKey = "",
            disableVerticalPadding = true
        )
        return
    }

    val headlineColor = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    // 多彩图标颜色
    val iconColors = listOf(
        Color(0xFF4285F4), // Google Blue
        Color(0xFF34A853), // Google Green
        Color(0xFFEA4335), // Google Red
        Color(0xFFFBBC05), // Google Yellow
        Color(0xFF9C27B0), // Purple
        Color(0xFF00BCD4), // Cyan
        Color(0xFFFF5722), // Deep Orange
        Color(0xFF3F51B5)  // Indigo
    )

    // 时间轴连接线颜色
    val lineColor = if (isDark) {
        Color.White.copy(alpha = 0.2f)
    } else {
        Color.Black.copy(alpha = 0.15f)
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // 标题（如果有）
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = style.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = style.fontSize * 1.1f
                ),
                color = headlineColor,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        // 时间轴布局
        items.forEachIndexed { index, item ->
            val currentIconColor = iconColors[index % iconColors.size]
            val isLast = index == items.lastIndex

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                // 左侧：图标 + 连接线
                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    // 图标圆圈
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(currentIconColor.copy(alpha = 0.15f), CircleShape)
                            .border(1.5.dp, currentIconColor.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        if (!item.icon.isNullOrBlank()) {
                            val iconText = item.icon
                            if (isMdiIconAvailable(iconText)) {
                                val iconName = iconText.trim().lowercase().removePrefix("mdi:")
                                MdiIconAdaptive(
                                    name = iconName,
                                    tint = currentIconColor,
                                    padding = 0.25f
                                )
                            } else {
                                val mdiVector = resolveMdiImageVector(iconText)
                                val iconVector = mdiVector ?: resolveInfographicIcon(iconText)
                                if (iconVector != null) {
                                    Icon(
                                        imageVector = iconVector,
                                        contentDescription = iconText,
                                        tint = currentIconColor,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(6.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 连接线（非最后一项才显示）
                    if (!isLast) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(40.dp)
                                .background(lineColor)
                        )
                    }
                }

                // 右侧：文字内容
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 4.dp, bottom = if (isLast) 0.dp else 16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = item.label,
                        style = style.copy(fontWeight = FontWeight.Medium),
                        color = headlineColor
                    )
                    if (item.desc.isNotBlank()) {
                        Text(
                            text = item.desc,
                            style = style.copy(
                                fontWeight = FontWeight.Normal,
                                fontSize = style.fontSize * 0.9f
                            ),
                            color = secondaryColor
                        )
                    }
                }
            }
        }
    }
}
