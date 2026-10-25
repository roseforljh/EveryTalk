package com.android.everytalk.ui.components.syntax

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.textmate.grammar.raw.GrammarReader
import dev.textmate.grammar.tokenize.StateStack
import dev.textmate.registry.GrammarSource
import dev.textmate.registry.Registry
import dev.textmate.theme.FontStyle
import dev.textmate.theme.ResolvedStyle
import dev.textmate.theme.Theme
import dev.textmate.theme.ThemeReader
import java.util.Locale
import androidx.compose.ui.text.font.FontStyle as ComposeFontStyle

/**
 * 将离线 TextMate 语法转换成现有组件使用的 AnnotatedString，不执行代码也不请求网络。
 * 语法与主题按需读取；未覆盖的语言、加载失败和超长单行返回 null，由公共入口使用旧高亮器。
 */
internal object TextMateHighlighter {
    private val grammarFiles = mapOf(
        "text.html.basic" to "html", "source.css" to "css", "source.js" to "javascript",
        "source.js.jsx" to "jsx", "source.ts" to "typescript", "source.tsx" to "tsx",
        "source.json" to "json", "source.kotlin" to "kotlin",
    )
    private val languageScopes = mapOf(
        "html" to "text.html.basic", "htm" to "text.html.basic", "css" to "source.css",
        "javascript" to "source.js", "js" to "source.js", "node" to "source.js", "nodejs" to "source.js",
        "jsx" to "source.js.jsx", "typescript" to "source.ts", "ts" to "source.ts", "tsx" to "source.tsx",
        "json" to "source.json", "jsonc" to "source.json",
        "kotlin" to "source.kotlin", "kt" to "source.kotlin", "kts" to "source.kotlin",
    )
    private val linePattern = Regex("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)")
    private val registry by lazy {
        Registry(GrammarSource { scope ->
            grammarFiles[scope]?.let { name ->
                resource("grammars/$name.json").use(GrammarReader::readGrammar)
            }
        })
    }
    private val lightTheme by lazy { readTheme("github-light-default") }
    private val darkTheme by lazy { readTheme("github-dark-default") }

    /**
     * 只改变颜色及字形，保留所有空白、CRLF、尾部换行和 UTF-16 索引，保证显示与复制的原文一致。
     * TextMate 的 Registry/Grammar 有可变状态，整次解析必须串行；UI 在后台线程调用此函数。
     */
    @Synchronized
    fun highlightOrNull(code: String, language: String?, theme: SyntaxHighlightTheme): AnnotatedString? {
        val scope = languageScopes[language?.trim()?.lowercase(Locale.ROOT)] ?: return null
        // 自定义配色继续走原实现；长单行不交给复杂语法，避免压缩文件拖慢聊天界面。
        if (theme != SyntaxHighlightTheme.Light && theme != SyntaxHighlightTheme.Dark) return null
        if (code.length > HighlightCache.MAX_HIGHLIGHT_CODE_CHARS || code.lineSequence().any { it.length > 2_000 }) return null

        return try {
            val grammar = registry.loadGrammar(scope) ?: return null
            val colors = if (theme == SyntaxHighlightTheme.Dark) darkTheme else lightTheme
            val builder = AnnotatedString.Builder(code)
            builder.addStyle(colors.defaultStyle.toSpanStyle(), 0, code.length)
            var state: StateStack? = null
            val styles = mutableMapOf<List<String>, SpanStyle>()
            // ponytail: 共用一个语法缓存并串行解析；沿用现有 2 万字符上限，不引入线程池。
            for (match in linePattern.findAll(code)) {
                if (match.value.isEmpty()) continue
                val line = match.value.trimEnd('\r', '\n')
                val result = grammar.tokenizeLine(line, state)
                state = result.ruleStack
                for (token in result.tokens) {
                    val start = token.startIndex.coerceIn(0, line.length)
                    val end = token.endIndex.coerceIn(0, line.length)
                    if (start >= end) continue
                    val style = styles.getOrPut(token.scopes) { colors.match(token.scopes).toSpanStyle() }
                    builder.addStyle(style, match.range.first + start, match.range.first + end)
                }
            }
            builder.toAnnotatedString()
        } catch (error: Exception) {
            // 资源或第三方解析器失败时保留旧高亮，不能让一条消息影响整个聊天列表。
            java.util.logging.Logger.getLogger("TextMateHighlighter").log(
                java.util.logging.Level.WARNING, "TextMate 高亮失败，已回退到原有规则", error,
            )
            null
        }
    }

    private fun resource(path: String): java.io.InputStream =
        checkNotNull(TextMateHighlighter::class.java.getResourceAsStream("/textmate/$path")) { "缺少高亮资源：$path" }

    private fun readTheme(name: String): Theme = resource("themes/$name.json").use(ThemeReader::readTheme)

    /** 映射主题字体属性，不覆盖卡片背景、字号或布局。 */
    private fun ResolvedStyle.toSpanStyle(): SpanStyle {
        val decorations = buildList {
            if (FontStyle.UNDERLINE in fontStyle) add(TextDecoration.Underline)
            if (FontStyle.STRIKETHROUGH in fontStyle) add(TextDecoration.LineThrough)
        }
        return SpanStyle(
            color = Color(foreground),
            fontWeight = if (FontStyle.BOLD in fontStyle) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (FontStyle.ITALIC in fontStyle) ComposeFontStyle.Italic else ComposeFontStyle.Normal,
            textDecoration = if (decorations.isEmpty()) TextDecoration.None else TextDecoration.combine(decorations),
        )
    }
}
