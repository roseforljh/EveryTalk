package com.android.everytalk.ui.components.table


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.ContentParser
import com.android.everytalk.ui.components.ContentPart
import com.android.everytalk.ui.components.WebPreviewDialog
import com.android.everytalk.ui.components.content.CodeBlockCard
import com.android.everytalk.ui.components.markdown.BreakableLatexRenderer
import com.android.everytalk.ui.components.markdown.MarkdownRenderer
import com.android.everytalk.ui.components.icons.MdiIcon
import com.android.everytalk.ui.components.icons.MdiIconAdaptive
import com.android.everytalk.ui.components.icons.isMdiIconAvailable
import com.android.everytalk.util.cache.ContentParseCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

/**
 * 琛ㄦ牸鎰熺煡鏂囨湰娓叉煋鍣紙浼樺寲鐗?+ 瀹炴椂娓叉煋锛?
 *
 * 鏍稿績绛栫暐锛?
 * - 娴佸紡妯″紡锛氬疄鏃跺垎鍧楁覆鏌擄紝琛ㄦ牸/浠ｇ爜鍧楀嵆鏃舵樉绀?
 * - 闈炴祦寮忔ā寮忥細鍒嗗潡娓叉煋锛屾瘡绉?ContentPart 绫诲瀷浣跨敤鏈€浼樼粍浠?
 * - 鍚庡彴瑙ｆ瀽锛氫娇鐢?flowOn(Dispatchers.Default) 鍦ㄥ悗鍙扮嚎绋嬭В鏋?AST
 * - referentialEqualityPolicy锛氶伩鍏嶄笉蹇呰鐨勭姸鎬佹洿鏂板鑷撮噸缁?
 * - 绋冲畾 Key锛氫娇鐢ㄧ储寮曚綔涓?key锛岄伩鍏嶅唴瀹瑰彉鍖栧鑷寸粍浠堕噸寤?
 *
 * 缂撳瓨鏈哄埗锛氶€氳繃 contentKey 鎸佷箙鍖栬В鏋愮粨鏋滐紝閬垮厤 LazyColumn 鍥炴敹瀵艰嚧閲嶅瑙ｆ瀽
 */
@Composable
fun TableAwareText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    recursionDepth: Int = 0,
    contentKey: String = "",  // 鏂板锛氱敤浜庣紦瀛榢ey锛堥€氬父涓烘秷鎭疘D锛?
    onLongPress: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null,
    sender: Sender = Sender.AI,
    onCodePreviewRequested: ((String, String) -> Unit)? = null, // 浠ｇ爜棰勮鍥炶皟
    onCodeCopied: (() -> Unit)? = null // 浠ｇ爜澶嶅埗鍥炶皟
) {
    // 棰勮鐘舵€佺鐞?
    var previewState by remember { mutableStateOf<Pair<String, String>?>(null) } // (code, language)

    var parsedParts by remember {
        mutableStateOf(
            value = emptyList<ContentPart>(),
            policy = referentialEqualityPolicy()
        )
    }

    // 澧為噺瑙ｆ瀽锛氳褰曚笂涓€娆¤В鏋愭椂鐨勬枃鏈紝鐢ㄤ簬鍒ゆ柇鏄惁涓?append-only
    var previousText by remember { mutableStateOf("") }

    val updatedText by rememberUpdatedState(text)
    val updatedIsStreaming by rememberUpdatedState(isStreaming)
    val effectiveCacheKey = if (contentKey.isNotBlank() && !isStreaming) {
        "${contentKey}_${text.hashCode()}_v${ContentParseCache.PARSER_VERSION}"
    } else ""

    LaunchedEffect(Unit) {
        // 浠呯洃鍚枃鏈湰韬殑鍙樺寲鏉ヨЕ鍙戦噸鏂拌В鏋?
        // 涓嶅啀灏?isStreaming 绾冲叆瑙﹀彂鏉′欢锛岄伩鍏嶆祦寮忕粨鏉熺灛闂村洜 isStreaming 鍒囨崲
        // 鑰岃Е鍙戝叏閲忛噸瑙ｆ瀽+Compose鍏ㄩ噺閲嶇粍锛屽紩鍙戞暣涓皵娉￠棯涓€涓?
        snapshotFlow { updatedText }
            .distinctUntilChanged()
            .mapLatest { currentText ->
                val streaming = updatedIsStreaming
                val cacheKey = if (contentKey.isNotBlank() && !streaming) {
                    "${contentKey}_${currentText.hashCode()}_v${ContentParseCache.PARSER_VERSION}"
                } else ""

                if (!streaming && cacheKey.isNotBlank()) {
                    ContentParseCache.get(cacheKey)?.let {
                        previousText = currentText
                        return@mapLatest it
                    }
                }

                // 澧為噺瑙ｆ瀽淇濇姢锛氭祦寮忔湡闂达紝濡傛灉鏂版枃鏈彧鏄棫鏂囨湰鐨勮拷鍔狅紝
                // 鍙洿鏂版渶鍚庝竴涓?Text 鍧楋紝涓嶅叏閲忛噸寤?AST
                val prevParts = parsedParts
                if (streaming && prevParts.isNotEmpty()
                    && previousText.isNotEmpty()
                    && currentText.startsWith(previousText)
                ) {
                    val lastPart = prevParts.last()
                    if (lastPart is ContentPart.Text) {
                        val delta = currentText.substring(previousText.length)
                        val updatedLast = ContentPart.Text(
                            lastPart.content + delta,
                            lastPart.startOffset
                        )
                        val newParts = prevParts.toMutableList()
                        newParts[newParts.lastIndex] = updatedLast
                        previousText = currentText
                        // 妫€鏌ヨ拷鍔犵殑鏂囨湰鏄惁鏋勬垚浜嗗畬鏁寸殑鏁板鍏紡鍧?
                        return@mapLatest ContentParser.splitMathBlocksPublic(newParts)
                    }
                }

                val parts = ContentParser.parseCompleteContent(currentText, isStreaming = streaming)

                if (!streaming && cacheKey.isNotBlank()) {
                    ContentParseCache.put(cacheKey, parts)
                }

                previousText = currentText
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

    // 褰?isStreaming 浠?true 鍒囨崲涓?false 鏃讹紝鍋氫竴娆″叏閲忛噸瑙ｆ瀽妫€鏌ョ粨鏋勫彉鍖栥€?
    // 澧為噺璺緞鍙兘灏嗕唬鐮佸潡锛坕nfographic/code/table锛夊悎骞惰繘 Text锛?
    // 姝ゅ閫氳繃缁撴瀯姣旇緝锛堢被鍨?鏁伴噺锛夊喅瀹氭槸鍚︽浛鎹?parsedParts锛?
    // - 缁撴瀯涓€鑷?鈫?淇濇寔鍘熻В鏋愶紝鏃犻棯鐑?
    // - 缁撴瀯涓嶅悓 鈫?鏇挎崲涓烘纭В鏋愮粨鏋滐紙蹇呰鐨勪竴娆￠噸缁勶級
    LaunchedEffect(isStreaming) {
        if (!isStreaming && parsedParts.isNotEmpty()) {
            val freshParts = ContentParser.parseCompleteContent(text)
            val structureChanged = freshParts.size != parsedParts.size ||
                freshParts.zip(parsedParts).any { (a, b) -> a.javaClass != b.javaClass }
            if (structureChanged) {
                parsedParts = freshParts
            }
            if (contentKey.isNotBlank()) {
                val cacheKey = "${contentKey}_${text.hashCode()}_v${ContentParseCache.PARSER_VERSION}"
                ContentParseCache.put(cacheKey, parsedParts)
            }
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
            // 绉婚櫎 animateContentSize()锛?
            // 娴佸紡杈撳嚭涓瘡甯?Column 瀛愰」鐨勬暟閲忋€佸昂瀵搁兘鍦ㄥ彉鍖栵紝
            // animateContentSize 浼氬杩欎簺鍙樺寲鏂藉姞寮圭哀鍔ㄧ敾锛?
            // 姝ｆ槸瀵艰嚧鐢婚潰鐤媯璺冲姩闂儊鐨勬渶澶у厓鍑朵箣涓€銆?
    ) {
        parsedParts.forEachIndexed { index, part ->
            // 濮嬬粓浣跨敤 绫诲瀷+绱㈠紩 浣滀负 key锛屼笉鍖呭惈 contentHash
            // 1. 娴佸紡鏈熼棿锛歝ontentHash 姣忓抚鍙樺寲 鈫?缁勪欢琚攢姣侀噸寤?鈫?闂儊
            // 2. 娴佸紡缁撴潫锛歩sStreaming 浠?true鈫抐alse 浼氬鑷?key 绛栫暐鍒囨崲锛?
            //    鎵€鏈?key 鍚屾椂鍙樺寲 鈫?鍏ㄩ儴缁勪欢閲嶅缓 鈫?缁撴潫鐬棿闂竴涓?
            // MarkdownRenderer 鍐呴儴宸叉湁 MarkdownRenderSignature tag 妫€鏌ワ紝
            // 鍐呭涓嶅彉鏃朵笉浼氶噸鏂版覆鏌擄紝鎵€浠?key 閲屼笉闇€瑕?contentHash
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
                            contentKey = if (contentKey.isNotBlank()) {
                                if (isStreaming) {
                                    // 流式阶段：稳定片段可缓存，尾片段持续变化不缓存
                                    if (index < parsedParts.size - 1) {
                                        "${contentKey}_part_${index}_${part.content.hashCode()}"
                                    } else ""
                                } else {
                                    "${contentKey}_part_${index}_${part.content.hashCode()}"
                                }
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
                        val isBlockMath = remember(part.content) {
                            val trimmed = part.content.trim()
                            (trimmed.startsWith("$$") && trimmed.endsWith("$$")) ||
                                (trimmed.startsWith("\\[") && trimmed.endsWith("\\]"))
                        }
                        if (isBlockMath) {
                            // 块级公式：使用 BreakableLatexRenderer 实现自动换行（流式时也实时渲染）
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
                        } else {
                            // 行内公式 或 流式模式下：使用 MarkdownRenderer
                            MarkdownRenderer(
                                markdown = part.content,
                                style = style,
                                color = color,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = if (isBlockMath) 8.dp else 0.dp),
                                isStreaming = isStreaming,
                                onLongPress = onLongPress,
                                onImageClick = onImageClick,
                                sender = sender,
                                contentKey = if (contentKey.isNotBlank()) {
                                    "${contentKey}_math_${index}_${part.content.hashCode()}"
                                } else "",
                                disableVerticalPadding = true
                            )
                        }
                    }
                }
            }
        }
    }

    // 鏄剧ず棰勮瀵硅瘽妗?
    previewState?.let { (code, language) ->
        WebPreviewDialog(
            code = code,
            language = language,
            onDismiss = { previewState = null }
        )
    }
}

private fun shouldEnableHorizontalScrollForMathPart(math: String): Boolean {
    val trimmed = math.trim()
    if (trimmed.isEmpty()) return false

    val isBlockMath = (trimmed.startsWith("$$") && trimmed.endsWith("$$")) ||
        (trimmed.startsWith("\\[") && trimmed.endsWith("\\]"))
    if (!isBlockMath) return false

    val normalized = trimmed
        .removePrefix("$$")
        .removeSuffix("$$")
        .removePrefix("\\[")
        .removeSuffix("\\]")
        .trim()

    val longestLineLength = normalized.lines().maxOfOrNull { it.length } ?: 0
    val hasLongComplexMath = longestLineLength >= 56 &&
        (normalized.contains("\\frac") ||
            normalized.contains("\\sum") ||
            normalized.contains("\\int") ||
            normalized.contains("\\prod") ||
            normalized.contains("\\begin") ||
            normalized.contains("\\left") ||
            normalized.contains("\\right"))

    return longestLineLength >= 72 || hasLongComplexMath
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
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    // 澶氬僵鍥炬爣棰滆壊
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

    // 鏃堕棿杞磋繛鎺ョ嚎棰滆壊
    val lineColor = if (isDark) {
        Color.White.copy(alpha = 0.2f)
    } else {
        Color.Black.copy(alpha = 0.15f)
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // 鏍囬锛堝鏋滄湁锛?
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

        // 鏃堕棿杞村竷灞€
        items.forEachIndexed { index, item ->
            val currentIconColor = iconColors[index % iconColors.size]
            val isLast = index == items.lastIndex

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                // 宸︿晶锛氬浘鏍?+ 杩炴帴绾?
                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    // 鍥炬爣鍦嗗湀
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

                    // 杩炴帴绾匡紙闈炴渶鍚庝竴椤规墠鏄剧ず锛?
                    if (!isLast) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(40.dp)
                                .background(lineColor)
                        )
                    }
                }

                // 鍙充晶锛氭枃瀛楀唴瀹?
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
