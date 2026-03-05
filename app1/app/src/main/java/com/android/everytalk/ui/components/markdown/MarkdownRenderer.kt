package com.android.everytalk.ui.components.markdown

import android.util.TypedValue
import android.os.SystemClock
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.Gravity
import android.widget.TextView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.AbstractMarkwonPlugin
import org.commonmark.node.Code
import android.graphics.Typeface
import android.text.style.StyleSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ClickableSpan
import android.view.View
import android.text.Spannable
import android.text.SpannableStringBuilder
import io.noties.markwon.image.AsyncDrawable // 浣跨敤 AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.MathStreamingPolicy
import com.android.everytalk.ui.components.markdown.MarkdownSpansCache

private val MULTIPLE_SPACES_REGEX = Regex(" {2,}")
private val ENUM_ITEM_REGEX = Regex("(?<!\\n)\\s+([A-D])[\\.)]\\s")
private val WINDOWS_PATH_REGEX = Regex("^[A-Za-z]:\\\\")

// 棰勭紪璇?preprocessAiMarkdown 涓殑姝ｅ垯锛岄伩鍏嶆瘡甯ч噸澶嶇紪璇?
private val BASE64_IMAGE_PATTERN = Regex(
    "(\\![\\[^\\]]*\\]\\()\\s*(<?)(:?data:image\\/[^)>]+)(>?)\\s*(\\))",
    setOf(RegexOption.DOT_MATCHES_ALL)
)
private val FULL_WIDTH_PAREN_BOLD_REGEX = Regex("""（\*\*""")
private val QUOTED_BOLD_PATTERN =
    Regex("""\*\*["“”'‘’「」『』](.+?)["“”'‘’「」『』]\*\*""")
private val HEADER_SPACE_REGEX = Regex("(?<=^|\\n)(#{1,6})(?=[^#\\s])")
private val LONG_HEADER_REGEX = Regex("^(#{1,6})(?=\\s.{50,})", RegexOption.MULTILINE)
private val MULTILINE_BLOCK_DOLLAR_PATTERN = Regex(
    "\\[double dollar]\\s*\\n([\\s\\S]*?)\\n\\s*\\[double dollar]",
    RegexOption.MULTILINE
)
private val BLOCK_PLACEHOLDER_PATTERN = Regex("(?m)^[ \\t]*\\[double dollar][ \\t]*$")
private val SPORTS_SCORE_PATTERN = Regex("^\\d{1,3}\\s*[:：]\\s*\\d{1,3}$")
private val PURE_BLOCK_DOLLAR_MATH_REGEX = Regex("^\\s*\\$\\$[\\s\\S]*\\$\\$\\s*$")
private val PURE_BLOCK_BRACKET_MATH_REGEX = Regex("^\\s*\\\\\\[[\\s\\S]*\\\\\\]\\s*$")
private val INLINE_DOUBLE_DOLLAR_MATH_REGEX = Regex("\\$\\$(?!\\$)([^\\n]+?)\\$\\$(?!\\$)")
private val FIRST_BLOCK_MATH_TOKEN_REGEX = Regex("\\$\\$(?!\\$)[\\s\\S]+?\\$\\$(?!\\$)")
private val plainLatexReplacements = listOf(
    "\\Longleftrightarrow" to "⇔",
    "\\Leftrightarrow" to "⇔",
    "\\Longrightarrow" to "⟹",
    "\\Rightarrow" to "⇒",
    "\\rightarrow" to "→",
    "\\leftarrow" to "←",
    "\\implies" to "⇒",
    "\\iff" to "⇔",
    "\\geq" to "≥",
    "\\ge" to "≥",
    "\\leq" to "≤",
    "\\le" to "≤",
    "\\neq" to "≠",
    "\\times" to "×",
    "\\cdot" to "·",
)

private fun isSupportedImageSource(raw: String?): Boolean {
    if (raw.isNullOrBlank()) return false
    val s = raw.trim()
    if (s.startsWith("http://") || s.startsWith("https://")) return true
    if (s.startsWith("data:image", ignoreCase = true)) return true
    if (s.startsWith("content://") || s.startsWith("file://")) return true
    if (s.startsWith("/")) return true
    if (WINDOWS_PATH_REGEX.containsMatchIn(s)) return true
    return false
}

private data class MarkdownRenderSignature(
    val processed: String, // 棰勫鐞嗗悗鐨勬枃鏈紝鍖呭惈杞箟閫昏緫鐨勭粨鏋?
    val isDark: Boolean,
    val textSizeSp: Float
)

private data class MarkdownRenderViewState(
    val signature: MarkdownRenderSignature,
    val processed: String,
    val pureMathBlockMessage: Boolean
)

private data class MarkdownRenderSession(
    val contentKey: String,
    val isStreaming: Boolean,
    val hasMath: Boolean,
    val isDark: Boolean,
    val textSizeSp: Float,
    val preprocessMs: Long,
    val parseMs: Long,
    val renderMs: Long,
    val totalMs: Long,
    val cacheHit: Boolean,
    val errorCode: String?
)

private fun classifyMarkdownRenderError(error: Throwable): String = when (error) {
    is OutOfMemoryError -> "oom"
    is IllegalArgumentException -> "illegal_argument"
    is IllegalStateException -> "illegal_state"
    is IndexOutOfBoundsException -> "index_out_of_bounds"
    else -> "unknown"
}

private fun logMarkdownRenderSession(session: MarkdownRenderSession) {
    if (!com.android.everytalk.config.PerformanceConfig.ENABLE_PERFORMANCE_LOGGING) return
    android.util.Log.d(
        "MarkdownRenderer",
        "session key=${session.contentKey.ifBlank { "<none>" }} stream=${session.isStreaming} " +
            "math=${session.hasMath} dark=${session.isDark} size=${session.textSizeSp} " +
            "pre=${session.preprocessMs}ms parse=${session.parseMs}ms render=${session.renderMs}ms " +
            "total=${session.totalMs}ms cacheHit=${session.cacheHit} err=${session.errorCode ?: "none"}"
    )
}

private fun hasUnclosedFence(text: String): Boolean {
    var count = 0
    var index = 0
    while (true) {
        val pos = text.indexOf("```", index)
        if (pos < 0) break
        count++
        index = pos + 3
    }
    return (count % 2) == 1
}

private fun findSafeStreamingSplitIndex(previousProcessed: String, currentProcessed: String): Int {
    if (previousProcessed.isEmpty()) return -1
    if (!currentProcessed.startsWith(previousProcessed)) return -1
    if (currentProcessed.length <= previousProcessed.length) return -1

    val nearestParagraphBoundary = currentProcessed.lastIndexOf("\n\n", previousProcessed.length)
    if (nearestParagraphBoundary <= 0) return -1

    val splitIndex = nearestParagraphBoundary + 2
    val head = currentProcessed.substring(0, splitIndex)
    if (hasUnclosedFence(head)) return -1
    if (MathStreamingPolicy.hasUnclosedMathDelimiter(head)) return -1
    return splitIndex
}

/**
 * 娴佸紡杈撳嚭鏃讹細浠呰浆涔夋湭闂悎鐨勬暟瀛﹀叕寮忔爣璁般€?
 * 宸查棴鍚堢殑鍏紡锛堝 $$E=mc^2$$锛変繚鐣欏師鏍凤紝鍏佽 JLatexMath 绔嬪嵆娓叉煋銆?
 * 鏈棴鍚堢殑鍏紡锛堝 $$\int_0^1 f(x)锛夌殑璧峰鏍囪琚浆涔夛紝
 * 闃叉 JLatexMath 灏濊瘯瑙ｆ瀽娈嬬己鍏紡瀵艰嚧甯冨眬璺冲彉銆?
 */
private fun escapeUnclosedMathForStreaming(input: String): String {
    return MathStreamingPolicy.escapeUnclosedMathDelimiters(input)
}

private fun buildMathSafeFallbackMarkdown(input: String): String {
    return MathStreamingPolicy.escapeAllMathDelimiters(input)
}

private fun decodeCommonHtmlEntities(input: String): String {
    if (!input.contains('&')) return input
    var s = input
    repeat(3) {
        val decoded = s
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&#160;", " ", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&le;", "≤", ignoreCase = true)
            .replace("&ge;", "≥", ignoreCase = true)
        if (decoded == s) return s
        s = decoded
    }
    return s
}

private fun normalizePlainLatexCommandsOutsideMath(input: String): String {
    if (!input.contains('\\')) return input

    val out = StringBuilder(input.length + 8)
    var inInlineCode = false
    var inInlineMath = false
    var inBlockMath = false
    var i = 0

    while (i < input.length) {
        val ch = input[i]
        val escaped = i > 0 && input[i - 1] == '\\'

        if (ch == '`' && !escaped) {
            inInlineCode = !inInlineCode
            out.append(ch)
            i++
            continue
        }

        if (!inInlineCode) {
            if (!inInlineMath && i + 1 < input.length && input[i] == '$' && input[i + 1] == '$') {
                inBlockMath = !inBlockMath
                out.append("$$")
                i += 2
                continue
            }

            if (!inBlockMath && ch == '$' && !escaped) {
                inInlineMath = !inInlineMath
                out.append(ch)
                i++
                continue
            }
        }

        if (!inInlineCode && !inInlineMath && !inBlockMath && ch == '\\') {
            val match = plainLatexReplacements.firstOrNull { (key, _) ->
                input.regionMatches(i, key, 0, key.length)
            }
            if (match != null) {
                out.append(match.second)
                i += match.first.length
                continue
            }
        }

        out.append(ch)
        i++
    }

    return out.toString()
}

private fun normalizeComparisonEntities(input: String): String {
    if (!input.contains('&')) return input
    return input
        .replace("&amp;gt;", ">", ignoreCase = true)
        .replace("&amp;lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
}

private fun normalizeAccidentalIndentedNonCode(input: String): String {
    if (!input.contains("    ") && !input.contains('\t')) return input

    val codeLineRegex = Regex(
        "^\\s*(fun\\b|class\\b|val\\b|var\\b|import\\b|public\\b|private\\b|return\\b|if\\b|else\\b|for\\b|while\\b|switch\\b|case\\b|try\\b|catch\\b|def\\b|from\\b|const\\b|let\\b|function\\b|#include\\b|SELECT\\b|INSERT\\b|UPDATE\\b|DELETE\\b|CREATE\\b|ALTER\\b|DROP\\b)"
    )

    return input.lines().joinToString("\n") { line ->
        val hasIndent = line.startsWith("    ") || line.startsWith("\t")
        if (!hasIndent) return@joinToString line

        val body = line.removePrefix("    ").removePrefix("\t")
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return@joinToString body

        val hasMathSignals = trimmed.contains('$') ||
            trimmed.contains("\\frac") ||
            trimmed.contains("\\sum") ||
            trimmed.contains("\\int") ||
            trimmed.contains("\\sqrt") ||
            trimmed.contains("\\begin") ||
            trimmed.contains("\\end")
        val hasCjk = trimmed.any { it.code in 0x4E00..0x9FFF }
        val strongCodeTokens = listOf("{", "}", ";", "=>", "->", "::")
            .sumOf { token -> Regex(Regex.escape(token)).findAll(trimmed).count() }
        val isCodeLike = codeLineRegex.containsMatchIn(trimmed) || strongCodeTokens >= 3

        if ((hasMathSignals || hasCjk) && !isCodeLike) body else line
    }
}

private fun convertSingleDollarMathToDouble(input: String): String {
    if (!input.contains('$')) return input

    fun findClosingSingleDollar(fromIndex: Int): Int {
        var i = fromIndex
        while (i < input.length) {
            val ch = input[i]
            if (ch == '\n') return -1
            val escaped = i > 0 && input[i - 1] == '\\'
            if (ch == '$' && !escaped) {
                val isDouble = i + 1 < input.length && input[i + 1] == '$'
                if (!isDouble) return i
                i += 2
                continue
            }
            i++
        }
        return -1
    }

    val out = StringBuilder(input.length + 16)
    var inInlineCode = false
    var i = 0

    while (i < input.length) {
        val ch = input[i]
        val escaped = i > 0 && input[i - 1] == '\\'
        if (ch == '`' && !escaped) {
            inInlineCode = !inInlineCode
            out.append(ch)
            i++
            continue
        }
        if (inInlineCode) {
            out.append(ch)
            i++
            continue
        }

        if (i + 1 < input.length && input[i] == '$' && input[i + 1] == '$') {
            out.append("$$")
            i += 2
            continue
        }

        if (ch == '$' && !escaped) {
            val close = findClosingSingleDollar(i + 1)
            if (close > i) {
                val rawContent = input.substring(i + 1, close)
                val normalizedContent = rawContent.trim()
                if (SPORTS_SCORE_PATTERN.matches(normalizedContent)) {
                    out.append(rawContent)
                } else {
                    out.append("$$").append(rawContent).append("$$")
                }
                i = close + 1
                continue
            }
        }

        out.append(ch)
        i++
    }

    return out.toString()
}

private fun shouldPromoteInlineMathToBlock(mathBody: String): Boolean {
    val body = mathBody.trim()
    if (body.isEmpty()) return false
    if (SPORTS_SCORE_PATTERN.matches(body)) return false

    val hasComplexToken = body.contains("\\frac") ||
        body.contains("\\sum") ||
        body.contains("\\int") ||
        body.contains("\\prod") ||
        body.contains("\\lim") ||
        body.contains("\\begin") ||
        body.contains("\\left") ||
        body.contains("\\right") ||
        body.contains("\\matrix") ||
        body.contains("\\cases")

    return body.length >= 36 || hasComplexToken
}

private fun promoteLongInlineMathToBlock(input: String): String {
    if (!input.contains("$$")) return input

    val lines = input.lines()
    val rebuilt = mutableListOf<String>()

    lines.forEach { originalLine ->
        var line = originalLine
        val matches = INLINE_DOUBLE_DOLLAR_MATH_REGEX.findAll(line).toList()
        if (matches.isEmpty()) {
            rebuilt.add(line)
            return@forEach
        }

        var promoted = false
        matches.asReversed().forEach { match ->
            val mathToken = match.value
            val mathBody = match.groupValues.getOrElse(1) { "" }
            if (!shouldPromoteInlineMathToBlock(mathBody)) return@forEach

            val prefix = line.substring(0, match.range.first)
            val suffix = line.substring(match.range.last + 1)
            val hasTextAround = prefix.trim().isNotEmpty() || suffix.trim().isNotEmpty()
            if (!hasTextAround) return@forEach

            val replacement = "\n${mathToken}\n"
            line = line.replaceRange(match.range, replacement)
            promoted = true
        }

        if (promoted) {
            rebuilt.addAll(line.split('\n').map { it.trimEnd() })
        } else {
            rebuilt.add(line)
        }
    }

    return rebuilt.joinToString("\n")
}

private fun separateTextAndLongBlockMathLines(input: String): String {
    if (!input.contains("$$")) return input

    val rebuilt = mutableListOf<String>()
    input.lines().forEach { rawLine ->
        val line = rawLine.trimEnd()
        val match = FIRST_BLOCK_MATH_TOKEN_REGEX.find(line)
        if (match == null) {
            rebuilt.add(line)
            return@forEach
        }

        val prefix = line.substring(0, match.range.first).trim()
        val mathToken = match.value.trim()
        val suffix = line.substring(match.range.last + 1).trim()

        val mathBody = mathToken.removePrefix("$$").removeSuffix("$$").trim()
        val hasTextAround = prefix.isNotEmpty() || suffix.isNotEmpty()
        val hasCjkAround = (prefix + suffix).any { it.code in 0x4E00..0x9FFF }
        val shouldSplit = hasTextAround &&
            hasCjkAround &&
            shouldPromoteInlineMathToBlock(mathBody)

        if (!shouldSplit) {
            rebuilt.add(line)
            return@forEach
        }

        if (prefix.isNotEmpty()) rebuilt.add(prefix)
        rebuilt.add(mathToken)
        if (suffix.isNotEmpty()) rebuilt.add(suffix)
    }

    return rebuilt.joinToString("\n")
}

private fun escapeCurrencyOutsideMath(input: String): String {
    if (!input.contains('$')) return input

    fun hasClosingSingleDollar(start: Int): Boolean {
        var j = start
        while (j < input.length && input[j] != '\n') {
            val c = input[j]
            val isEscaped = j > 0 && input[j - 1] == '\\'
            if (c == '$' && !isEscaped) {
                val isDouble = j + 1 < input.length && input[j + 1] == '$'
                if (isDouble) {
                    j += 2
                    continue
                }
                return true
            }
            j++
        }
        return false
    }

    fun hasClosingDoubleDollar(start: Int): Boolean {
        var j = start
        while (j + 1 < input.length && input[j] != '\n') {
            val isEscaped = j > 0 && input[j - 1] == '\\'
            if (input[j] == '$' && input[j + 1] == '$' && !isEscaped) {
                return true
            }
            j++
        }
        return false
    }

    val out = StringBuilder(input.length + 16)
    var inInlineCode = false
    var inInlineMath = false
    var inBlockMath = false
    var i = 0

    while (i < input.length) {
        val ch = input[i]

        val isEscaped = i > 0 && input[i - 1] == '\\'
        if (ch == '`' && !isEscaped) {
            inInlineCode = !inInlineCode
            out.append(ch)
            i++
            continue
        }
        if (inInlineCode) {
            out.append(ch)
            i++
            continue
        }

        if (!inInlineMath && !inBlockMath && i + 3 < input.length && input.startsWith("$$$", i) && input[i + 3].isDigit()) {
            out.append("\\$")
            i += 3
            continue
        }

        if (!inInlineMath && i + 1 < input.length && input.startsWith("$$", i)) {
            if (!inBlockMath && i + 2 < input.length && input[i + 2].isDigit()) {
                if (hasClosingDoubleDollar(i + 2)) {
                    inBlockMath = true
                    out.append("$$")
                    i += 2
                    continue
                } else {
                    out.append("\\$")
                    i += 2
                    continue
                }
            }
            inBlockMath = !inBlockMath
            out.append("$$")
            i += 2
            continue
        }

        if (ch == '$' && !inBlockMath) {
            if (!inInlineMath && i + 1 < input.length && input[i + 1].isDigit()) {
                if (hasClosingSingleDollar(i + 1)) {
                    inInlineMath = true
                    out.append('$')
                    i++
                    continue
                } else {
                    out.append("\\$")
                    i++
                    continue
                }
            }
            inInlineMath = !inInlineMath
            out.append(ch)
            i++
            continue
        }

        out.append(ch)
        i++
    }

    return out.toString()
}

/**
 * 棰勫鐞?Markdown 鏂囨湰锛堢畝鍖栫増锛?
 *
 * 鐢变簬浠ｇ爜鍧楀拰琛ㄦ牸宸茬敱 ContentParser 鎻愬彇锛屾鍑芥暟鍙渶澶勭悊绾枃鏈唴瀹广€?
 */
internal fun preprocessAiMarkdown(input: String, isStreaming: Boolean = false): String {
    if (input.isBlank()) return input
    if (PURE_BLOCK_DOLLAR_MATH_REGEX.matches(input) || PURE_BLOCK_BRACKET_MATH_REGEX.matches(input)) {
        return input.trim()
    }

    var s = decodeCommonHtmlEntities(input)

    // 1. HTML Escape
    s = s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    // 2. Base64 Image: 绉婚櫎 Base64 涓殑绌虹櫧
    if (s.contains("data:image/")) {
        val base64ImagePattern = Regex("(\\!\\[[^\\]]*\\]\\()\\s*(<?)(data:image\\/[^)>]+)(>?)\\s*(\\))", setOf(RegexOption.DOT_MATCHES_ALL))
        s = s.replace(base64ImagePattern) { mr ->
            val prefix = mr.groupValues[1]
            val openAngle = mr.groupValues[2]
            val data = mr.groupValues[3].filter { !it.isWhitespace() }
            val closeAngle = mr.groupValues[4]
            val suffix = mr.groupValues[5]
            prefix + openAngle + data + closeAngle + suffix
        }
    }

    // 4. Special Spaces
    s = s.replace("&nbsp;", " ")
        .replace("\u00A0", " ")
        .replace("\u3000", " ")

    // 5. Full-width Paren Bold Fix
    s = s.replace(FULL_WIDTH_PAREN_BOLD_REGEX, "**(")

    // 5.1 Fix: **"xxx"** 绛夊紩鍙峰寘瑁瑰姞绮楁棤娉曟覆鏌撶殑闂
    s = s.replace(QUOTED_BOLD_PATTERN) { mr ->
        val inner = mr.groupValues[1]
        "\"**${inner}**\""
    }

    // 6. Headers: 纭繚 # 鍚庢湁绌烘牸
    s = s.replace(HEADER_SPACE_REGEX, "$1 ")
    s = s.replace(LONG_HEADER_REGEX) { mr ->
        "\\" + mr.groupValues[1]
    }

    // 6.5 防误判缩进代码块：把数学/中文说明的 4 空格缩进恢复为普通文本
    s = normalizeAccidentalIndentedNonCode(s)

    // 7. Hard Break Enforcement
    val lines = s.lines()
    val lastIndex = lines.size - 1
    s = lines.mapIndexed { index, line ->
        val trimmedLine = line.trimStart()
        val isHeadingLine = trimmedLine.startsWith("# ") ||
                trimmedLine.startsWith("## ") ||
                trimmedLine.startsWith("### ") ||
                trimmedLine.startsWith("#### ") ||
                trimmedLine.startsWith("##### ") ||
                trimmedLine.startsWith("###### ")

        val isEmptyLine = line.isBlank()
        val isTableLine = line.contains("|")
        val hasUnbalancedBold = line.split("**").size % 2 == 0
        val trimmedEnd = line.trimEnd()
        val endsWithMathDelimiter = trimmedEnd.endsWith("$$") || trimmedEnd.endsWith("$")
        // 鏃犺鏄惁娴佸紡娓叉煋锛屾湁鏈棴鍚堢殑 ** 閮借烦杩囩‖鎹㈣锛岄伩鍏嶇牬鍧忓姞绮楄娉?
        val shouldSkipHardBreak = isTableLine || hasUnbalancedBold || endsWithMathDelimiter

        when {
            index == lastIndex -> line
            isEmptyLine -> "$line\n"
            isHeadingLine -> "$line\n"
            shouldSkipHardBreak -> "$line\n"
            line.endsWith("  ") -> "$line\n"
            else -> "$line  \n"
        }
    }.joinToString("")

    // 8. 鍧楃骇 [double dollar] 鍗犱綅绗﹁浆鎹紙浠呭鐞嗙嫭绔嬭/璺ㄨ鍧楋級
    if (s.contains("[double dollar]")) {
        s = s.replace(MULTILINE_BLOCK_DOLLAR_PATTERN) { matchResult ->
            val inner = matchResult.groupValues[1].trim()
            if (inner.isEmpty()) "" else "\$\$${inner}\$\$"
        }

        s = BLOCK_PLACEHOLDER_PATTERN.replace(s) { "\$\$" }
    }

    // 8.5 琛屽唴鏁板鍒嗛殧绗﹀崟鍚戣鑼冨寲锛堣烦杩囦唬鐮佸洿鏍?琛屽唴浠ｇ爜锛?
    s = MathDelimiterNormalizer.normalize(s)

    // 非数学上下文中的裸 LaTeX 指令降级为可读符号，避免显示 \implies 这类原始命令
    s = normalizePlainLatexCommandsOutsideMath(s)

    // 8.8 Currency: 先在数学转换前保护货币符号，避免被后续 `$...$` 识别误吞
    s = escapeCurrencyOutsideMath(s)

    // 9. Inline Math:
    // - JLatexMathPlugin(4.6.2) 行内仅识别 `$$...$$`，因此将 `$...$` 统一提升为 `$$...$$`
    // - 使用状态机避免误匹配 `$$...$$`、货币、以及代码片段中的 `$`
    s = convertSingleDollarMathToDouble(s)

    // 9.5/9.6 关闭“行内公式自动升级块级”策略，避免渲染尺寸突变。
    // 按 Gemini 风格保持分隔符语义稳定：行内留在行内，块级仅在模型明确输出块级时渲染为块级。

    // 最后一步仅在流式阶段执行：
    // 1) safe-points 开启时，统一转义全部数学分隔符，避免流式阶段触发数学引擎重排/报错导致闪烁。
    // 2) 否则保留原策略，只转义未闭合数学标记。
    if (isStreaming) {
        if (com.android.everytalk.config.PerformanceConfig.MATH_STREAMING_RENDER_SAFEPOINTS) {
            s = MathStreamingPolicy.escapeAllMathDelimiters(s)
            android.util.Log.d(
                "MathStreamThrottle",
                "strategy=streaming_math_plaintext action=escape-all len=${s.length}"
            )
        } else {
            s = escapeUnclosedMathForStreaming(s)
        }
    }

    // 收口：确保比较符实体不会作为字面量显示
    s = normalizeComparisonEntities(s)

    return s.trimStart('\n')
}

@Composable
fun MarkdownRenderer(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    onLongPress: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null,
    sender: Sender = Sender.AI,
    contentKey: String = "",
    disableVerticalPadding: Boolean = false, // 鏂板鍙傛暟锛氬厑璁哥鐢ㄥ瀭鐩磒adding
    enablePureMathHorizontalScroll: Boolean = true
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    
    val baseTextSizeSp = if (style.fontSize.value > 0f) style.fontSize.value else 16f
    // 缁熶竴姘旀场鍐呮枃鏈瓧鍙凤細鐢ㄦ埛涓?AI 涓€鏍凤紝鏁翠綋鐣ュ皬浜庝箣鍓嶇殑 AI 鏀惧ぇ鏁堟灉
    val textSizeSp = baseTextSizeSp * 1.05f
    val markwon = remember(isDark, textSizeSp) {
        MarkwonCache.getOrCreate(
            context = context,
            isDark = isDark,
            textSize = textSizeSp
        )
    }

    val finalColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }

    val processedMarkdown = remember(markdown, isStreaming) {
        preprocessAiMarkdown(markdown, isStreaming)
    }
    val pureMathBlockLayout = remember(processedMarkdown) {
        val normalized = processedMarkdown.trim()
        PURE_BLOCK_DOLLAR_MATH_REGEX.matches(normalized) ||
            PURE_BLOCK_BRACKET_MATH_REGEX.matches(normalized)
    }

    // 
    val baseViewModifier = if (sender == Sender.User) {
        modifier.wrapContentWidth()
    } else {
        modifier
    }
    val viewModifier = if (pureMathBlockLayout && enablePureMathHorizontalScroll) {
        baseViewModifier
            .horizontalScroll(rememberScrollState())
    } else {
        baseViewModifier
    }
    
    AndroidView(
        modifier = viewModifier,
        factory = {
            TextView(it).apply {
                // 缁熶竴鏂囨湰鏍峰紡锛堝瓧鍙凤級
                val baseSp = if (style.fontSize.value > 0f) style.fontSize.value else 16f
                // 鐢ㄦ埛涓?AI 浣跨敤鐩稿悓瀛楀彿锛屾暣浣撶暐灏忎簬涔嬪墠鐨?AI 鏀惧ぇ鏁堟灉
                val sp = baseSp * 1.05f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                setTextColor(finalColor.toArgb())
                // 绋冲畾鍩虹嚎锛屽噺灏戣烦鍔?
                // setIncludeFontPadding(false) // 瀵艰嚧鏁板鍏紡鍨傜洿琚埅鏂紝蹇呴』寮€鍚?
                setIncludeFontPadding(true)
                
                // TextView鍐呴儴padding - 鐢ㄦ埛姘旀场浣跨敤鐩哥瓑鐨勪笂涓媝adding瀹炵幇鍨傜洿灞呬腑
                if (sender == Sender.User) {
                    // 鐢ㄦ埛姘旀场锛氫娇鐢ㄧ浉绛夌殑涓婁笅padding锛屽噺灏忔按骞硃adding
                    val horizontalPaddingPx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        1f,  // 鍑忓皬姘村钩padding
                        resources.displayMetrics
                    ).toInt()
                    val verticalPaddingPx = if (disableVerticalPadding) 0 else TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        4f,  // 澧炲姞鍨傜洿padding浠ュ疄鐜拌瑙夊眳涓?
                        resources.displayMetrics
                    ).toInt()
                    setPadding(horizontalPaddingPx, verticalPaddingPx, horizontalPaddingPx, verticalPaddingPx)
                } else {
                    // AI姘旀场
                    // 澧炲姞 padding 浠ラ槻姝㈡暟瀛﹀叕寮忥紙鐗瑰埆鏄枩浣?绉垎绗﹀彿锛夊湪杈圭紭琚埅鏂?
                    // 16dp 搴旇瓒冲瀹圭撼澶ч儴鍒嗘孩鍑虹殑瀛楀舰
                    val paddingPx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        1f,
                        resources.displayMetrics
                    ).toInt()
                    
                    val verticalPaddingPx = if (disableVerticalPadding) 0 else paddingPx
                    
                    setPadding(paddingPx, verticalPaddingPx, paddingPx, verticalPaddingPx)
                }
                
                // 琛岄棿璺?- 鐢ㄦ埛涓?AI 鍖哄垎璁剧疆
                // 鐢ㄦ埛淇濇寔鐣ョ揣鍑戯紝AI 閫傚害鍔犲ぇ涓婁笅琛岃窛绂?
                // 鏍规嵁鍙嶉"绋嶅井鍑忔枃鏈箣闂寸殑璺濈"锛屽皢 AI 琛岄棿璺濅粠 6f 璋冩暣涓?5f
                val lineSpacingDp = if (sender == Sender.User) 2f else 5f
                setLineSpacing(
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        lineSpacingDp,
                        resources.displayMetrics
                    ),
                    1.0f
                )
                
                // 瀛楃闂磋窛 - 缁熶竴鍑忓皬宸﹀彸闂磋窛锛屼娇 AI 姘旀场鍐呮枃鏈洿绱у噾锛屽悓鏃朵繚鎸佺敤鎴蜂笌 AI 涓€鑷?
                letterSpacing = 0.02f
                
                // 璁剧疆灞呬腑瀵归綈 - 瀵瑰琛屾枃鏈湁鏁?
                // gravity = Gravity.CENTER_VERTICAL // 绉婚櫎鍨傜洿灞呬腑锛岄伩鍏嶉暱鏂?鍥剧墖鏄剧ず寮傚父
                
                // 绂佺敤鏂囨湰閫夋嫨浣嗕繚鐣欓暱鎸夊姛鑳?
                setTextIsSelectable(false)
                highlightColor = android.graphics.Color.TRANSPARENT
                
                isFocusable = false
                isFocusableInTouchMode = false
                
                // 缁熶竴澶勭悊瑙︽懜浜嬩欢锛氬浘鐗囩偣鍑?+ 闀挎寜鍧愭爣鎹曡幏
                if (onImageClick != null || onLongPress != null) {
                    movementMethod = null // 绂佺敤 LinkMovementMethod锛屽畬鍏ㄦ墜鍔ㄦ帴绠?
                    linksClickable = false
                    isClickable = true
                    isLongClickable = true
                    
                    var lastTouchRawX = 0f
                    var lastTouchRawY = 0f

                    setOnTouchListener { v, event ->
                        val tvLocal = v as TextView
                        val isPureMathBlock = (tvLocal.tag as? MarkdownRenderViewState)?.pureMathBlockMessage == true

                        if (isPureMathBlock) {
                            if (event.action == MotionEvent.ACTION_DOWN) {
                                lastTouchRawX = event.rawX
                                lastTouchRawY = event.rawY
                            }
                            // 纯块数学的横向滚动由 Compose 外层容器处理，这里不拦截触摸链
                            return@setOnTouchListener false
                        }

                        if (event.action == MotionEvent.ACTION_DOWN) {
                            lastTouchRawX = event.rawX
                            lastTouchRawY = event.rawY
                        }
                        
                        // 浠呭湪 ACTION_UP 鏃舵娴嬪浘鐗囩偣鍑?
                        if (onImageClick != null && event.action == MotionEvent.ACTION_UP) {
                            val text = tvLocal.text
                            if (text is android.text.Spannable) {
                                var x = event.x.toInt()
                                var y = event.y.toInt()

                                x -= tvLocal.totalPaddingLeft
                                y -= tvLocal.totalPaddingTop
                                x += tvLocal.scrollX
                                y += tvLocal.scrollY

                                val layout = tvLocal.layout
                                if (layout != null) {
                                    val line = layout.getLineForVertical(y)
                                
                                    // 鍑犱綍鍛戒腑娴嬭瘯锛氱洿鎺ユ鏌ヨЕ鎽哥偣鏄惁鍦?ImageSpan 鐨?bounds 鍐?
                                    val lineStart = layout.getLineStart(line)
                                    val lineEnd = layout.getLineEnd(line)
                                
                                    val imageSpans = text.getSpans(lineStart, lineEnd, AsyncDrawableSpan::class.java)
                                    for (imageSpan in imageSpans) {
                                        val spanStart = text.getSpanStart(imageSpan)
                                        val xStart = layout.getPrimaryHorizontal(spanStart)
                                        val drawable = imageSpan.drawable
                                        val bounds = drawable.bounds
                                        val width = bounds.width()

                                        val touchSlop = 20
                                        if (x >= (xStart - touchSlop) && x <= (xStart + width + touchSlop)) {
                                            val sourceRaw = drawable.destination
                                            val source = sourceRaw?.trim().orEmpty()
                                            if (isSupportedImageSource(source)) {
                                                onImageClick(source)
                                                return@setOnTouchListener true
                                            }
                                        }
                                    }

                                    val standardImageSpans = text.getSpans(lineStart, lineEnd, android.text.style.ImageSpan::class.java)
                                    for (imageSpan in standardImageSpans) {
                                        val spanStart = text.getSpanStart(imageSpan)
                                        val xStart = layout.getPrimaryHorizontal(spanStart)
                                        val drawable = imageSpan.drawable
                                        val width = drawable.bounds.width()

                                        if (x >= xStart && x <= (xStart + width)) {
                                            val sourceRaw = imageSpan.source
                                            val source = sourceRaw?.trim().orEmpty()
                                            if (isSupportedImageSource(source)) {
                                                onImageClick(source)
                                                return@setOnTouchListener true
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // 杩斿洖 false锛岃 View 缁х画澶勭悊闀挎寜绛夊叾浠栦簨浠?
                        false
                    }

                    if (onLongPress != null) {
                        setOnLongClickListener {
                            onLongPress.invoke(androidx.compose.ui.geometry.Offset(lastTouchRawX, lastTouchRawY))
                            true
                        }
                    } else {
                        setOnLongClickListener(null)
                    }
                } else {
                    movementMethod = null
                    linksClickable = false
                    setOnTouchListener(null)
                    isClickable = false
                    setOnLongClickListener(null)
                }
            }
        },
        update = { tv ->
            val updateStartNs = SystemClock.elapsedRealtimeNanos()
            val preprocessStartNs = updateStartNs
            val processed = processedMarkdown
            val preprocessMs = (SystemClock.elapsedRealtimeNanos() - preprocessStartNs) / 1_000_000L
            val normalizedProcessed = processed.trim()
            val pureMathBlockMessage =
                PURE_BLOCK_DOLLAR_MATH_REGEX.matches(normalizedProcessed) ||
                    PURE_BLOCK_BRACKET_MATH_REGEX.matches(normalizedProcessed)
            val signature = MarkdownRenderSignature(
                processed = processed,
                isDark = isDark,
                textSizeSp = textSizeSp
            )

            val previousState = tv.tag as? MarkdownRenderViewState
            if (previousState?.signature == signature) {
                return@AndroidView
            }
            tv.tag = MarkdownRenderViewState(
                signature = signature,
                processed = processed,
                pureMathBlockMessage = pureMathBlockMessage
            )

            // 纯数学块：内容保持单行（不自动折行），由 Compose 外层承载横向滚动
            tv.setHorizontallyScrolling(pureMathBlockMessage)
            tv.isHorizontalScrollBarEnabled = false
            tv.overScrollMode = View.OVER_SCROLL_NEVER
            tv.movementMethod = null

            // 缂撳瓨浼樺寲锛氬皾璇曚粠缂撳瓨鑾峰彇 Spanned 瀵硅薄
            // 娴佸紡鏈熼棿锛氫笂娓?TableAwareText 鍙宸茬ǔ瀹氱殑 part 鎻愪緵 contentKey
            // 鏈€鍚庝竴涓紙鎸佺画鍙樺寲鐨勶級part 鐨?contentKey 涓虹┖锛屼笉缂撳瓨
            val sp = if (style.fontSize.value > 0f) style.fontSize.value else 16f
            val cacheKey = if (contentKey.isNotBlank()) {
                MarkdownSpansCache.generateKey(contentKey + "_v42", isDark, sp)
            } else ""

            val cachedSpanned = if (cacheKey.isNotBlank()) MarkdownSpansCache.get(cacheKey) else null
            var parseMs = 0L
            var renderMs = 0L
            var cacheHit = false
            var errorCode: String? = null
            val previousProcessed = previousState?.processed.orEmpty()
            val segmentSplitIndex = if (isStreaming) {
                findSafeStreamingSplitIndex(previousProcessed, processed)
            } else {
                -1
            }

            try {
                if (cachedSpanned != null) {
                    cacheHit = true
                    tv.text = cachedSpanned
                    if (com.android.everytalk.config.PerformanceConfig.ENABLE_PERFORMANCE_LOGGING) {
                        android.util.Log.d("MarkdownRenderer", "Spans Cache HIT: $cacheKey")
                    }
                } else if (segmentSplitIndex > 0) {
                    val headText = processed.substring(0, segmentSplitIndex)
                    val tailText = processed.substring(segmentSplitIndex)

                    val headKey = if (contentKey.isNotBlank()) {
                        MarkdownSpansCache.generateKey(
                            "${contentKey}_seg_head_${headText.hashCode()}_v42",
                            isDark,
                            sp
                        )
                    } else ""

                    val tailKey = if (contentKey.isNotBlank()) {
                        MarkdownSpansCache.generateKey(
                            "${contentKey}_seg_tail_${tailText.hashCode()}_v42",
                            isDark,
                            sp
                        )
                    } else ""

                    val parseStartNs = SystemClock.elapsedRealtimeNanos()
                    val headSpanned = if (headKey.isNotBlank()) {
                        MarkdownSpansCache.get(headKey) ?: run {
                            val node = markwon.parse(headText)
                            val spanned = markwon.render(node)
                            MarkdownSpansCache.put(headKey, spanned)
                            spanned
                        }
                    } else {
                        markwon.render(markwon.parse(headText))
                    }

                    val tailSpanned = if (tailKey.isNotBlank()) {
                        MarkdownSpansCache.get(tailKey) ?: run {
                            val node = markwon.parse(tailText)
                            val spanned = markwon.render(node)
                            MarkdownSpansCache.put(tailKey, spanned)
                            spanned
                        }
                    } else {
                        markwon.render(markwon.parse(tailText))
                    }

                    parseMs = (SystemClock.elapsedRealtimeNanos() - parseStartNs) / 1_000_000L
                    renderMs = 0L

                    val merged = SpannableStringBuilder().apply {
                        append(headSpanned)
                        append(tailSpanned)
                    }
                    tv.text = merged
                    if (com.android.everytalk.config.PerformanceConfig.ENABLE_PERFORMANCE_LOGGING) {
                        android.util.Log.d(
                            "MarkdownRenderer",
                            "Spans Segment MISS split=$segmentSplitIndex"
                        )
                    }
                } else {
                    if (processed.contains("$")) {
                        android.util.Log.d("MarkdownRenderer", "妫€娴嬪埌鏁板鍏紡鏍囪: ${processed.take(100)}")
                    }

                    val parseStartNs = SystemClock.elapsedRealtimeNanos()
                    val node = markwon.parse(processed)
                    parseMs = (SystemClock.elapsedRealtimeNanos() - parseStartNs) / 1_000_000L

                    val renderStartNs = SystemClock.elapsedRealtimeNanos()
                    val spanned = markwon.render(node)
                    renderMs = (SystemClock.elapsedRealtimeNanos() - renderStartNs) / 1_000_000L

                    if (cacheKey.isNotBlank()) {
                        MarkdownSpansCache.put(cacheKey, spanned)
                        if (com.android.everytalk.config.PerformanceConfig.ENABLE_PERFORMANCE_LOGGING) {
                            android.util.Log.d("MarkdownRenderer", "Spans Cache MISS, cached: $cacheKey")
                        }
                    }

                    markwon.setParsedMarkdown(tv, spanned)
                }
            } catch (error: Throwable) {
                errorCode = classifyMarkdownRenderError(error)
                android.util.Log.e(
                    "MarkdownRenderer",
                    "render failed, try math-safe fallback first, code=$errorCode",
                    error
                )
                val fallbackMarkdown = buildMathSafeFallbackMarkdown(processed)
                val fallbackApplied = fallbackMarkdown != processed
                if (fallbackApplied) {
                    try {
                        val fallbackNode = markwon.parse(fallbackMarkdown)
                        val fallbackSpanned = markwon.render(fallbackNode)
                        markwon.setParsedMarkdown(tv, fallbackSpanned)
                    } catch (fallbackError: Throwable) {
                        android.util.Log.e(
                            "MarkdownRenderer",
                            "math-safe fallback render failed, fallback to plain text",
                            fallbackError
                        )
                        tv.text = processed
                    }
                } else {
                    tv.text = processed
                }
            }

            val totalMs = (SystemClock.elapsedRealtimeNanos() - updateStartNs) / 1_000_000L
            logMarkdownRenderSession(
                MarkdownRenderSession(
                    contentKey = contentKey,
                    isStreaming = isStreaming,
                    hasMath = processed.contains('$') || processed.contains("\\["),
                    isDark = isDark,
                    textSizeSp = textSizeSp,
                    preprocessMs = preprocessMs,
                    parseMs = parseMs,
                    renderMs = renderMs,
                    totalMs = totalMs,
                    cacheHit = cacheHit,
                    errorCode = errorCode
                )
            )

            // 澶勭悊鍥剧墖鐐瑰嚮浜嬩欢锛堝吋瀹?AsyncDrawableSpan 涓?ImageSpan锛?
            if (onImageClick != null) {
                val text = tv.text
                if (text is Spannable) {
                    val asyncSpans = text.getSpans(0, text.length, AsyncDrawableSpan::class.java)
                    asyncSpans.forEach { span ->
                        val start = text.getSpanStart(span)
                        val end = text.getSpanEnd(span)
                        val drawable = span.drawable
                        val source: String? = drawable.destination
                        val finalSource = source?.trim().orEmpty()
                        if (!isSupportedImageSource(finalSource)) return@forEach

                        text.getSpans(start, end, ClickableSpan::class.java).forEach { text.removeSpan(it) }

                        android.util.Log.d("MarkdownRenderer", "Attach ClickableSpan on AsyncDrawableSpan: range=[$start,$end), src.len=${finalSource.length}")
                        text.setSpan(object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                android.util.Log.d("MarkdownRenderer", "onImageClick triggered (AsyncDrawableSpan)")
                                onImageClick(finalSource)
                            }
                            override fun updateDrawState(ds: android.text.TextPaint) {
                                ds.isUnderlineText = false
                            }
                        }, start, end, Spannable.SPAN_EXCLUSIVE_INCLUSIVE)
                    }
                    // 鑻?asyncSpans 涓虹┖锛屾墦鍗颁竴娆℃棩蹇楀府鍔╁畾浣?
                    if (asyncSpans.isEmpty()) {
                        android.util.Log.d("MarkdownRenderer", "No AsyncDrawableSpan found; will fallback to ImageSpan")
                    }

                    val imageSpans = text.getSpans(0, text.length, android.text.style.ImageSpan::class.java)
                    imageSpans.forEach { imageSpan ->
                        val start = text.getSpanStart(imageSpan)
                        val end = text.getSpanEnd(imageSpan)
                        val source: String = imageSpan.source ?: ""
                        val finalSource = source.trim()
                        if (!isSupportedImageSource(finalSource)) return@forEach

                        text.getSpans(start, end, ClickableSpan::class.java).forEach { text.removeSpan(it) }

                        android.util.Log.d("MarkdownRenderer", "Attach ClickableSpan on ImageSpan: range=[$start,$end), src.len=${finalSource.length}")
                        text.setSpan(object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                android.util.Log.d("MarkdownRenderer", "onImageClick triggered (ImageSpan)")
                                onImageClick(finalSource)
                            }
                            override fun updateDrawState(ds: android.text.TextPaint) {
                                ds.isUnderlineText = false
                            }
                        }, start, end, Spannable.SPAN_EXCLUSIVE_INCLUSIVE)
                    }
                }
            }

            // 鏇存柊闀挎寜鐩戝惉鍣?- 绉婚櫎锛屾敼鐢?Compose 灞傜粺涓€澶勭悊
            // if (onLongPress != null) {
            //    tv.setOnLongClickListener {
            //        onLongPress.invoke()
            //        true
            //    }
            // } else {
            //    tv.setOnLongClickListener(null)
            // }
        }
    )
}



