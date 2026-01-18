package com.android.everytalk.ui.components.table

import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlignVerticalCenter
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.HelpOutline
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.ContentParser
import com.android.everytalk.ui.components.ContentPart
import com.android.everytalk.ui.components.WebPreviewDialog
import com.android.everytalk.ui.components.content.CodeBlockCard
import com.android.everytalk.ui.components.markdown.MarkdownRenderer
import com.android.everytalk.ui.components.icons.MdiIcon
import com.android.everytalk.ui.components.icons.isMdiIconAvailable
import com.android.everytalk.util.cache.ContentParseCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import ru.noties.jlatexmath.JLatexMathDrawable

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
@Composable
fun TableAwareText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    recursionDepth: Int = 0,
    contentKey: String = "",  // 新增：用于缓存key（通常为消息ID）
    onLongPress: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null,
    sender: Sender = Sender.AI,
    onCodePreviewRequested: ((String, String) -> Unit)? = null, // 代码预览回调
    onCodeCopied: (() -> Unit)? = null // 代码复制回调
) {
    // 预览状态管理
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
        snapshotFlow { Triple(updatedText, updatedIsStreaming, effectiveCacheKey) }
            .distinctUntilChanged()
            .mapLatest { (currentText, streaming, cacheKey) ->
                if (!streaming && cacheKey.isNotBlank()) {
                    ContentParseCache.get(cacheKey)?.let { return@mapLatest it }
                }

                val parts = ContentParser.parseCompleteContent(currentText, isStreaming = streaming)

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
        } ?: ContentParser.parseCompleteContent(text, isStreaming = isStreaming).also { parts ->
            if (!isStreaming && effectiveCacheKey.isNotBlank()) {
                ContentParseCache.put(effectiveCacheKey, parts)
            }
        }
        parsedParts = initialParts
    }

    val verticalPaddingDp = 0.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = verticalPaddingDp)
            .animateContentSize()
    ) {
        parsedParts.forEachIndexed { index, part ->
            val stableKey = "${part.javaClass.simpleName}_$index"

            androidx.compose.runtime.key(stableKey) {
                when (part) {
                    is ContentPart.Text -> {
                        MarkdownRenderer(
                            markdown = part.content,
                            style = style,
                            color = color,
                            modifier = Modifier.fillMaxWidth(),
                            isStreaming = isStreaming,
                            onLongPress = onLongPress,
                            onImageClick = onImageClick,
                            sender = sender,
                            contentKey = if (contentKey.isNotBlank() && !isStreaming) {
                                "${contentKey}_part_${index}_${part.content.hashCode()}"
                            } else "",
                            disableVerticalPadding = true
                        )
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
                                    .padding(vertical = 4.dp)
                            )
                        } else {
                            val clipboard = LocalClipboardManager.current
                            CodeBlockCard(
                                language = part.language,
                                code = part.content,
                                modifier = Modifier.padding(vertical = 4.dp),
                                isStreaming = isStreaming,
                                onPreviewRequested = if (onCodePreviewRequested != null) {
                                    { onCodePreviewRequested(part.language ?: "", part.content) }
                                } else null,
                                onCopy = {
                                    clipboard.setText(AnnotatedString(part.content))
                                    onCodeCopied?.invoke()
                                },
                                onLongPress = onLongPress
                            )
                        }
                    }
                    is ContentPart.Table -> {
                        TableRenderer(
                            lines = part.lines,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            isStreaming = isStreaming,
                            contentKey = if (contentKey.isNotBlank() && !isStreaming) {
                                "${contentKey}_table_${index}_${part.lines.size}"
                            } else "",
                            onLongPress = onLongPress,
                            headerStyle = style.copy(fontWeight = FontWeight.Bold),
                            cellStyle = style
                        )
                    }
                    is ContentPart.Math -> {
                        // 数学公式块部分：支持横向滚动
                        Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        // 尝试直接使用 JLatexMathDrawable 渲染，绕过 Markwon 解析
                        // 这样可以避免 Markwon 对 $$ 块的解析问题
                        val mathContent = part.content.trim().removePrefix("$$").removeSuffix("$$").trim()
                        val density = androidx.compose.ui.platform.LocalDensity.current.density
                        val textSizePx = style.fontSize.value * density
                        
                        // 修复：避免在非 Composable 上下文中使用 Composable 函数
                        val defaultColor = MaterialTheme.colorScheme.onSurface
                        val finalColor = if (color != Color.Unspecified) color else defaultColor
                        val textColorInt = finalColor.toArgb()

                        AndroidView(
                            factory = { context ->
                                ImageView(context).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                                    scaleType = ImageView.ScaleType.FIT_START
                                    adjustViewBounds = true
                                }
                            },
                            update = { imageView ->
                                try {
                                    val drawable = JLatexMathDrawable.builder(mathContent)
                                        .textSize(textSizePx)
                                        .padding(0) // 移除多余的 padding
                                        .background(0) // Transparent
                                        .align(JLatexMathDrawable.ALIGN_LEFT)
                                        .color(textColorInt)
                                        .build()
                                    imageView.setImageDrawable(drawable)
                                } catch (e: Throwable) {
                                    // 降级处理：如果直接渲染失败（如依赖缺失），回退到 MarkdownRenderer
                                    android.util.Log.e("TableAwareText", "JLatexMath direct render failed", e)
                                    // 这里无法直接切换回 Composable，只能显示错误或尝试用 TextView 显示源码
                                    // 简单起见，我们不处理回退，因为如果库存在，通常只会因 LaTeX 语法错误失败
                                }
                            },
                            modifier = Modifier
                                .wrapContentWidth()
                                .padding(horizontal = 8.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
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
        else -> Icons.Filled.HelpOutline
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
    modifier: Modifier = Modifier
) {
    val (title, items) = remember(raw) { parseInfographic(raw) }

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
    val surface = MaterialTheme.colorScheme.surface
    val chipBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
    val chipText = MaterialTheme.colorScheme.onSurfaceVariant
    val palette = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.errorContainer
    )
    val scrollState = rememberScrollState()

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "infographic",
                    style = style.copy(fontSize = style.fontSize * 0.85f, fontWeight = FontWeight.Medium),
                    color = chipText,
                    modifier = Modifier
                        .background(chipBg, RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }

            if (title.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = title,
                    style = style.copy(fontWeight = FontWeight.SemiBold),
                    color = headlineColor,
                    modifier = Modifier
                        .fillMaxWidth()
                )
            }

            if (items.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                var colorIndex = 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                ) {
                    items.forEach { item ->
                        val bgColor = palette[colorIndex % palette.size]
                        colorIndex++
                        Card(
                            modifier = Modifier.width(220.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = bgColor
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                if (!item.icon.isNullOrBlank()) {
                                    val iconText = item.icon
                                    if (isMdiIconAvailable(iconText)) {
                                        val iconName = iconText.trim().lowercase().removePrefix("mdi:")
                                        MdiIcon(
                                            name = iconName,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(bottom = 4.dp)
                                        )
                                    } else {
                                        val mdiVector = resolveMdiImageVector(iconText)
                                        val iconVector = mdiVector ?: resolveInfographicIcon(iconText)
                                        if (iconVector != null) {
                                            Icon(
                                                imageVector = iconVector,
                                                contentDescription = iconText,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(bottom = 4.dp)
                                            )
                                        } else {
                                            Text(
                                                text = iconText,
                                                style = style.copy(fontWeight = FontWeight.Medium),
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(bottom = 4.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = item.label,
                                    style = style.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(bottom = if (item.desc.isNotBlank()) 2.dp else 0.dp)
                                )
                                if (item.desc.isNotBlank()) {
                                    Text(
                                        text = item.desc,
                                        style = style.copy(fontWeight = FontWeight.Normal),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
