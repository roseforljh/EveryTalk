package com.android.everytalk.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.android.everytalk.util.web.linkHost
import com.android.everytalk.util.web.linkIcon

internal const val COMPOSER_LINK_ICON = "composer-link-icon"
private const val LINK_ICON_PREFIX = "\u2003\u2060"
private val composerUrlPattern = Regex("https?://[^\\s<>\"'`\uFFFC，。！？；：、（）【】《》“”‘’]+", RegexOption.IGNORE_CASE)

/**
 * 在已有 Skill 标签变换之后插入纯显示用的图标位置，并组合两层光标映射。
 * 只从原始草稿识别完整 http(s) 地址，标签名称不会被误识别；复制和发送始终保留原文。
 */
internal class ComposerLinkVisualTransformation(
    private val delegate: VisualTransformation = VisualTransformation.None,
    private val color: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val base = delegate.filter(text)
        val links = composerUrlPattern.findAll(text.text).mapNotNull { match ->
            var href = match.value.trimEnd('.', ',', '!', '?', ';', ':')
            // 句末括号不属于链接，URL 路径自身成对的括号仍然保留。
            for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}')) {
                while (href.endsWith(close) && href.count { it == close } > href.count { it == open }) {
                    href = href.dropLast(1)
                }
            }
            if (linkHost(href).isEmpty()) null else Triple(
                base.offsetMapping.originalToTransformed(match.range.first),
                base.offsetMapping.originalToTransformed(match.range.first + href.length),
                href,
            )
        }.toList()
        if (links.isEmpty()) return base

        val forward = IntArray(base.text.length + 1)
        val backward = mutableListOf(0)
        val transformed = buildAnnotatedString {
            fun appendOriginal(start: Int, end: Int) {
                for (index in start until end) {
                    forward[index] = length + index - start
                    backward += index + 1
                }
                append(base.text.subSequence(start, end))
                forward[end] = length
            }
            var cursor = 0
            for ((start, end, href) in links) {
                appendOriginal(cursor, start)
                val iconStart = length
                append(LINK_ICON_PREFIX)
                repeat(LINK_ICON_PREFIX.length) { backward += start }
                addStringAnnotation(COMPOSER_LINK_ICON, href, iconStart, iconStart + 1)
                // 一个 em 空格为图标预留宽度，连接符让图标与网址首字保持在同一行。
                addStyle(SpanStyle(fontSize = 19.sp), iconStart, length)
                val linkStart = length
                appendOriginal(start, end)
                addStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium), linkStart, length)
                cursor = end
            }
            appendOriginal(cursor, base.text.length)
        }
        return TransformedText(transformed, object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                forward[base.offsetMapping.originalToTransformed(offset)]

            override fun transformedToOriginal(offset: Int): Int =
                base.offsetMapping.transformedToOriginal(backward[offset])
        })
    }
}

/**
 * 保留原生输入、选区和输入法行为，仅在编辑时显示网站图标及链接颜色。
 * 将滚动放在文字与图标共同的容器上，长草稿滚动时两者使用相同坐标，不会发生错位。
 */
@Composable
internal fun LinkStyledTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    cursorBrush: Brush = SolidColor(MaterialTheme.colorScheme.primary),
    maxLines: Int = 5,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    decorationBox: @Composable (@Composable () -> Unit) -> Unit = { it() },
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val linkColor = if (dark) Color(0xFFACAEDC) else Color(0xFF515BB0)
    val transformation = remember(visualTransformation, linkColor) {
        ComposerLinkVisualTransformation(visualTransformation, linkColor)
    }
    val transformed = remember(value.annotatedString, transformation) { transformation.filter(value.annotatedString) }
    val icons = transformed.text.getStringAnnotations(COMPOSER_LINK_ICON, 0, transformed.text.length)
        .map { annotation ->
            val icon = linkIcon(annotation.item)
            Triple(annotation.start, painterResource(icon.resource), icon.multicolor)
        }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scroll = rememberScrollState()
    val measurer = rememberTextMeasurer()
    val lineHeight = measurer.measure("Hg", style = textStyle).size.height
    val maxHeight = with(LocalDensity.current) { (lineHeight * maxLines).toDp() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        readOnly = readOnly,
        textStyle = textStyle,
        cursorBrush = cursorBrush,
        visualTransformation = transformation,
        onTextLayout = { layout = it },
        decorationBox = { innerTextField ->
            decorationBox {
                Box(Modifier.heightIn(max = maxHeight).verticalScroll(scroll)) {
                    Box(Modifier.drawWithContent {
                        drawContent()
                        val current = layout ?: return@drawWithContent
                        // 输入变更到下一次布局之间，不使用旧坐标绘制新链接。
                        if (current.layoutInput.text.text != transformed.text.text) return@drawWithContent
                        for ((offset, painter, multicolor) in icons) {
                            val bounds = current.getBoundingBox(offset)
                            val side = bounds.width * 16f / 19f
                            translate(bounds.left, bounds.center.y - side / 2f) {
                                with(painter) {
                                    draw(Size(side, side), colorFilter = if (multicolor) null else ColorFilter.tint(linkColor))
                                }
                            }
                        }
                    }) { innerTextField() }
                }
            }
        },
    )
}

/** String 草稿入口保留原生选区及输入法组合状态，与聊天的 TextFieldValue 入口复用同一实现。 */
@Composable
internal fun LinkStyledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    cursorBrush: Brush = SolidColor(MaterialTheme.colorScheme.primary),
    maxLines: Int = 5,
    decorationBox: @Composable (@Composable () -> Unit) -> Unit = { it() },
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    LinkStyledTextField(
        value = fieldValue.copy(text = value),
        onValueChange = { next ->
            fieldValue = next
            if (next.text != value) onValueChange(next.text)
        },
        modifier = modifier,
        readOnly = readOnly,
        textStyle = textStyle,
        cursorBrush = cursorBrush,
        maxLines = maxLines,
        decorationBox = decorationBox,
    )
}
