package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * Markdown 语法高亮器
 * 
 * 支持：
 * - 标题（# ## ### 等）
 * - 强调（**粗体** *斜体* ~~删除线~~）
 * - 代码（`行内代码` 和 ```代码块```）
 * - 链接和图片
 * - 列表（有序和无序）
 * - 引用
 * - 水平线
 * - 表格
 */
object MarkdownHighlighter : LanguageHighlighter {
    
    // 预编译正则表达式
    // 代码块（优先级最高，防止内部内容被其他规则匹配）
    private val fencedCodeBlockPattern = Pattern.compile("```[a-zA-Z]*\\n[\\s\\S]*?```|~~~[a-zA-Z]*\\n[\\s\\S]*?~~~")
    private val indentedCodeBlockPattern = Pattern.compile("(?:^|\\n)(?: {4}|\\t).*(?:\\n(?: {4}|\\t).*)*")
    private val inlineCodePattern = Pattern.compile("`[^`\\n]+`")
    
    // 标题
    private val atxHeadingPattern = Pattern.compile("^#{1,6}\\s+.+$", Pattern.MULTILINE)
    private val setextHeading1Pattern = Pattern.compile("^.+\\n=+$", Pattern.MULTILINE)
    private val setextHeading2Pattern = Pattern.compile("^.+\\n-+$", Pattern.MULTILINE)
    
    // 强调
    private val boldPattern = Pattern.compile("\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__")
    private val italicPattern = Pattern.compile("(?<!\\*)\\*[^*\\n]+\\*(?!\\*)|(?<!_)_[^_\\n]+_(?!_)")
    private val strikethroughPattern = Pattern.compile("~~[^~\\n]+~~")
    
    // 链接和图片
    private val imagePattern = Pattern.compile("!\\[[^\\]]*\\]\\([^)]+\\)")
    private val linkPattern = Pattern.compile("\\[[^\\]]*\\]\\([^)]+\\)")
    private val referenceLinkPattern = Pattern.compile("\\[[^\\]]*\\]\\[[^\\]]*\\]")
    private val linkDefinitionPattern = Pattern.compile("^\\[[^\\]]+\\]:\\s+\\S+.*$", Pattern.MULTILINE)
    private val autoLinkPattern = Pattern.compile("<https?://[^>]+>|<[^@>]+@[^>]+>")
    
    // 列表
    private val unorderedListPattern = Pattern.compile("^\\s*[*+-]\\s+", Pattern.MULTILINE)
    private val orderedListPattern = Pattern.compile("^\\s*\\d+\\.\\s+", Pattern.MULTILINE)
    
    // 引用
    private val blockquotePattern = Pattern.compile("^>\\s?.*$", Pattern.MULTILINE)
    
    // 水平线
    private val horizontalRulePattern = Pattern.compile("^(?:---+|\\*\\*\\*+|___+)\\s*$", Pattern.MULTILINE)
    
    // 表格
    private val tableDelimiterPattern = Pattern.compile("^\\|?(?:\\s*:?-+:?\\s*\\|)+\\s*:?-+:?\\s*\\|?$", Pattern.MULTILINE)
    private val tableCellPattern = Pattern.compile("\\|")
    
    // HTML 标签
    private val htmlTagPattern = Pattern.compile("</?[a-zA-Z][a-zA-Z0-9]*(?:\\s+[^>]*)?>")
    private val htmlCommentPattern = Pattern.compile("<!--[\\s\\S]*?-->")
    
    // 任务列表
    private val taskListPattern = Pattern.compile("^\\s*[*+-]\\s+\\[[ xX]\\]\\s+", Pattern.MULTILINE)
    
    // 脚注
    private val footnotePattern = Pattern.compile("\\[\\^[^\\]]+\\]")
    
    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)
        
        // 1. 围栏代码块（最高优先级）
        findMatches(fencedCodeBlockPattern, code, TokenType.STRING, tokens, processed)
        
        // 2. 缩进代码块
        findMatches(indentedCodeBlockPattern, code, TokenType.STRING, tokens, processed)
        
        // 3. 行内代码
        findMatches(inlineCodePattern, code, TokenType.STRING, tokens, processed)
        
        // 4. HTML 注释
        findMatches(htmlCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 5. HTML 标签
        findMatches(htmlTagPattern, code, TokenType.TAG, tokens, processed)
        
        // 6. 标题（ATX 风格）
        findMatches(atxHeadingPattern, code, TokenType.KEYWORD, tokens, processed)
        
        // 7. 标题（Setext 风格）
        findMatches(setextHeading1Pattern, code, TokenType.KEYWORD, tokens, processed)
        findMatches(setextHeading2Pattern, code, TokenType.KEYWORD, tokens, processed)
        
        // 8. 水平线
        findMatches(horizontalRulePattern, code, TokenType.PUNCTUATION, tokens, processed)
        
        // 9. 引用
        findMatches(blockquotePattern, code, TokenType.COMMENT, tokens, processed)
        
        // 10. 图片
        findMatches(imagePattern, code, TokenType.FUNCTION, tokens, processed)
        
        // 11. 链接
        findMatches(linkPattern, code, TokenType.FUNCTION, tokens, processed)
        findMatches(referenceLinkPattern, code, TokenType.FUNCTION, tokens, processed)
        findMatches(linkDefinitionPattern, code, TokenType.FUNCTION, tokens, processed)
        findMatches(autoLinkPattern, code, TokenType.FUNCTION, tokens, processed)
        
        // 12. 脚注
        findMatches(footnotePattern, code, TokenType.ANNOTATION, tokens, processed)
        
        // 13. 任务列表
        findMatches(taskListPattern, code, TokenType.KEYWORD, tokens, processed)
        
        // 14. 有序列表
        findMatches(orderedListPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 15. 无序列表
        findMatches(unorderedListPattern, code, TokenType.PUNCTUATION, tokens, processed)
        
        // 16. 表格分隔符
        findMatches(tableDelimiterPattern, code, TokenType.PUNCTUATION, tokens, processed)
        
        // 17. 粗体
        findMatches(boldPattern, code, TokenType.KEYWORD, tokens, processed)
        
        // 18. 斜体
        findMatches(italicPattern, code, TokenType.VARIABLE, tokens, processed)
        
        // 19. 删除线
        findMatches(strikethroughPattern, code, TokenType.COMMENT, tokens, processed)
        
        return tokens.sortedBy { it.start }
    }
    
    private fun findMatches(
        pattern: Pattern,
        code: String,
        tokenType: TokenType,
        tokens: MutableList<Token>,
        processed: BooleanArray
    ) {
        val matcher = pattern.matcher(code)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            if (!processed[start]) {
                tokens.add(Token(tokenType, start, end, matcher.group()))
                for (i in start until end) processed[i] = true
            }
        }
    }
}