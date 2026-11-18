package com.android.everytalk.ui.components.markdown

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.appendAutoLink
import com.mikepenz.markdown.annotator.appendMarkdownLink
import com.mikepenz.markdown.annotator.appendMarkdownReference
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpanPainter
import com.mikepenz.markdown.compose.extendedspans.SpanDrawInstructions
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

internal const val EXTERNAL_LINK_SUFFIX = "↗"

internal fun isExternalMarkdownLink(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

/**
 * 继续交给组件库生成链接、引用与内部格式，只给外部网页追加同目标的箭头。
 * 不间断空格让箭头尽量跟随末字；脚注、图片、公式和未解析的引用保持原样。
 */
internal fun AnnotatedString.Builder.appendStyledMarkdownLink(
    content: String,
    node: ASTNode,
    settings: AnnotatorSettings,
): Boolean {
    when (node.type) {
        MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.AUTOLINK,
        GFMTokenTypes.GFM_AUTOLINK, MarkdownElementTypes.SHORT_REFERENCE_LINK,
        MarkdownElementTypes.FULL_REFERENCE_LINK -> Unit
        else -> return false
    }
    // GFM 可能在行内代码下仍生成自动链接节点；这里只输出原字面文本。
    if (node.hasMarkdownLinkLogoExcludedAncestor()) {
        append(node.getUnescapedTextInNode(content))
        return true
    }
    val rendered = buildAnnotatedString {
        when (node.type) {
            MarkdownElementTypes.INLINE_LINK -> appendMarkdownLink(content, node, settings)
            MarkdownElementTypes.AUTOLINK, GFMTokenTypes.GFM_AUTOLINK -> appendAutoLink(content, node, settings)
            MarkdownElementTypes.SHORT_REFERENCE_LINK,
            MarkdownElementTypes.FULL_REFERENCE_LINK -> appendMarkdownReference(content, node, settings)
            else -> return false
        }
    }
    append(rendered)
    val link = rendered.getLinkAnnotations(0, rendered.length).singleOrNull()?.item as? LinkAnnotation.Url
    if (link != null && isExternalMarkdownLink(link.url)) {
        withLink(link) { append(EXTERNAL_LINK_SUFFIX) }
    }
    return true
}

/**
 * 布局完成后计算可见链接每一行的范围，绘制时只画圆点，不重新解析或测量正文。
 * 选区路径在换行处会覆盖行尾空白，不能作为点线范围。
 * 改为合并每行可见字符的边界，排除行首尾空白、箭头和省略部分；双向文字分开绘制。
 */
internal fun markdownLinkUnderlineBounds(layout: TextLayoutResult): List<Rect> = buildList {
    val text = layout.layoutInput.text
    text.getLinkAnnotations(0, text.length).forEach { range ->
        val link = range.item as? LinkAnnotation.Url ?: return@forEach
        val external = isExternalMarkdownLink(link.url)
        if (!external && !link.url.startsWith(MESSAGE_FILE_SCHEME)) return@forEach
        val end = if (external && text.text.regionMatches(range.end - EXTERNAL_LINK_SUFFIX.length, EXTERNAL_LINK_SUFFIX, 0, EXTERNAL_LINK_SUFFIX.length)) {
            range.end - EXTERNAL_LINK_SUFFIX.length
        } else {
            range.end
        }
        if (range.start >= end) return@forEach
        val firstLine = layout.getLineForOffset(range.start)
        val lastLine = layout.getLineForOffset(end - 1)
        for (line in firstLine..lastLine) {
            if (line >= layout.lineCount || layout.getLineTop(line) >= layout.size.height) break
            var start = maxOf(range.start, layout.getLineStart(line))
            var lineEnd = minOf(end, layout.getLineEnd(line, visibleEnd = true))
            while (start < lineEnd && text[start].isWhitespace()) start++
            while (lineEnd > start && text[lineEnd - 1].isWhitespace()) lineEnd--
            if (start < lineEnd) {
                val boxes = (start until lineEnd).map { layout.getBoundingBox(it) }
                    .filter { it.width > 0f }.sortedBy { it.left }
                var left = 0f
                var right = 0f
                boxes.forEachIndexed { index, box ->
                    if (index == 0 || box.left > right + 0.5f) {
                        if (index > 0) {
                            val glyphBottom = boxes[index - 1].bottom
                            add(Rect(left, layout.getLineBaseline(line), right, glyphBottom))
                        }
                        left = box.left
                        right = box.right
                    } else right = maxOf(right, box.right)
                }
                if (boxes.isNotEmpty()) {
                    // 使用实际字形的底部，而不是整行行框底部，避免中英文混排时点线漂移。
                    val glyphBottom = boxes.maxOf { it.bottom }
                    add(Rect(left, layout.getLineBaseline(line), right, glyphBottom))
                }
            }
        }
    }
}

internal object MarkdownDottedLinkPainter : ExtendedSpanPainter() {
    override fun decorate(span: SpanStyle, start: Int, end: Int, text: AnnotatedString, builder: AnnotatedString.Builder): SpanStyle = span

    override fun decorate(linkAnnotation: LinkAnnotation, start: Int, end: Int, text: AnnotatedString, builder: AnnotatedString.Builder): LinkAnnotation = linkAnnotation

    override fun drawInstructionsFor(layoutResult: TextLayoutResult, color: Color?): SpanDrawInstructions {
        val bounds = markdownLinkUnderlineBounds(layoutResult)
        return SpanDrawInstructions {
            val radius = 1.dp.toPx()
            val step = 3.8.dp.toPx()
            val dotColor = (color ?: Color.Gray).copy(alpha = 0.45f)
            bounds.forEach { line ->
                // 点线跟随当前行真实字形底部，并留出稳定的视觉间距。
                val y = line.bottom + 1.5.dp.toPx()
                var x = line.left + radius
                while (x + radius <= line.right) {
                    drawCircle(dotColor, radius, Offset(x, y))
                    x += step
                }
            }
        }
    }
}
