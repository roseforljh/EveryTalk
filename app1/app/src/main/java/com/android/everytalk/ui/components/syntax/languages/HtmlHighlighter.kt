package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * HTML 语法高亮器
 * 
 * 支持：
 * - 基础 HTML 结构（标签、属性、注释、DOCTYPE）
 * - 嵌入式 CSS (<style>...</style>)
 * - 嵌入式 JavaScript (<script>...</script>)
 */
object HtmlHighlighter : LanguageHighlighter {
    
    // 预编译的正则表达式
    private val commentPattern = Pattern.compile("<!--[\\s\\S]*?-->")
    private val doctypePattern = Pattern.compile("<!DOCTYPE[^>]*>", Pattern.CASE_INSENSITIVE)
    
    // 标签解析（更精确的控制）
    private val tagStartPattern = Pattern.compile("</?([a-zA-Z][a-zA-Z0-9-:]*)")
    // 支持常规属性、Vue/Angular绑定 (@click, :src, #slot, *ngIf, [prop], (event))
    // 修复正则：转义 [ 字符
    private val attributePattern = Pattern.compile("\\s+([@:#\\[(*]?[a-zA-Z][a-zA-Z0-9-.:]*[\\])]?)\\s*(?==|/|>)")
    private val attributeValuePattern = Pattern.compile("=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)")
    private val tagEndPattern = Pattern.compile("/?>")
    
    // 嵌入内容正则
    private val styleBlockPattern = Pattern.compile("(<style[^>]*>)([\\s\\S]*?)(</style>)", Pattern.CASE_INSENSITIVE)
    private val scriptBlockPattern = Pattern.compile("(<script[^>]*>)([\\s\\S]*?)(</script>)", Pattern.CASE_INSENSITIVE)
    
    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)
        
        // 1. 优先处理嵌入的 CSS 和 JS
        // 这必须在处理普通标签之前完成，以防止内容被误判为 HTML
        processEmbeddedBlocks(code, styleBlockPattern, CssHighlighter, tokens, processed)
        processEmbeddedBlocks(code, scriptBlockPattern, JavaScriptHighlighter, tokens, processed)
        
        // 2. 注释
        findMatches(commentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 3. DOCTYPE
        findMatches(doctypePattern, code, TokenType.TAG, tokens, processed)
        
        // 4. 解析 HTML 标签
        // 我们手动遍历寻找 <，因为需要处理属性
        var i = 0
        while (i < code.length) {
            if (processed[i]) {
                i++
                continue
            }
            
            if (code[i] == '<') {
                // 尝试匹配标签开始
                val matcher = tagStartPattern.matcher(code)
                if (matcher.find(i) && matcher.start() == i) {
                    val tagStart = matcher.start()
                    val tagEnd = matcher.end()
                    val tagName = matcher.group(1)
                    
                    // 标记 < 或 </
                    val bracketLen = if (code[i + 1] == '/') 2 else 1
                    tokens.add(Token(TokenType.PUNCTUATION, tagStart, tagStart + bracketLen, code.substring(tagStart, tagStart + bracketLen)))
                    
                    // 标记标签名
                    tokens.add(Token(TokenType.TAG, tagStart + bracketLen, tagEnd, tagName))
                    
                    for (j in tagStart until tagEnd) processed[j] = true
                    i = tagEnd
                    
                    // 解析属性直到 >
                    while (i < code.length) {
                        // 跳过空白
                        while (i < code.length && code[i].isWhitespace()) i++
                        if (i >= code.length) break
                        
                        // 检查是否是标签结束
                        val endMatcher = tagEndPattern.matcher(code)
                        if (endMatcher.find(i) && endMatcher.start() == i) {
                            val endTagStart = endMatcher.start()
                            val endTagEnd = endMatcher.end()
                            tokens.add(Token(TokenType.PUNCTUATION, endTagStart, endTagEnd, endMatcher.group()))
                            for (j in endTagStart until endTagEnd) processed[j] = true
                            i = endTagEnd
                            break
                        }
                        
                        // 匹配属性名
                        val attrMatcher = attributePattern.matcher(code)
                        if (attrMatcher.find(i) && attrMatcher.start() == i) { // 也就是从当前位置开始的空白+属性名
                            val attrStart = attrMatcher.start(1) // 组1是属性名
                            val attrEnd = attrMatcher.end(1)
                            val attrName = attrMatcher.group(1)
                            
                            // 区分普通属性和 Vue/Angular 指令
                            val tokenType = if (attrName.startsWith("v-") ||
                                                attrName.startsWith("@") ||
                                                attrName.startsWith(":") ||
                                                attrName.startsWith("#") ||
                                                attrName.startsWith("*")) {
                                TokenType.KEYWORD // 指令显示为紫色（关键字颜色），或者 ANNOTATION（黄色）
                            } else {
                                TokenType.ATTRIBUTE // 普通属性显示为橙色
                            }
                            
                            tokens.add(Token(tokenType, attrStart, attrEnd, attrName))
                            for (j in attrMatcher.start() until attrEnd) processed[j] = true // 标记包括前导空白
                            i = attrEnd
                            
                            // 跳过可能的空白
                            while (i < code.length && code[i].isWhitespace()) i++
                            
                            // 匹配 = 和值
                            if (i < code.length && code[i] == '=') {
                                tokens.add(Token(TokenType.OPERATOR, i, i + 1, "="))
                                processed[i] = true
                                i++
                                
                                while (i < code.length && code[i].isWhitespace()) i++
                                
                                // 属性值
                                if (i < code.length) {
                                    val valStart = i
                                    val firstChar = code[i]
                                    var valEnd = i
                                    
                                    if (firstChar == '"' || firstChar == '\'') {
                                        // 引号包裹的值
                                        valEnd = code.indexOf(firstChar, i + 1)
                                        if (valEnd != -1) {
                                            valEnd += 1
                                            tokens.add(Token(TokenType.ATTRIBUTE_VALUE, valStart, valEnd, code.substring(valStart, valEnd)))
                                            for (j in valStart until valEnd) processed[j] = true
                                            i = valEnd
                                        } else {
                                            // 未闭合，处理到末尾或非法字符
                                            i++ 
                                        }
                                    } else {
                                        // 无引号值，读取直到空白或 >
                                        while (valEnd < code.length && !code[valEnd].isWhitespace() && code[valEnd] != '>') {
                                            valEnd++
                                        }
                                        tokens.add(Token(TokenType.ATTRIBUTE_VALUE, valStart, valEnd, code.substring(valStart, valEnd)))
                                        for (j in valStart until valEnd) processed[j] = true
                                        i = valEnd
                                    }
                                }
                            }
                        } else {
                            // 无法识别的内容，跳过一个字符防止死循环
                            i++
                        }
                    }
                    continue
                }
            }
            i++
        }
        
        return tokens.sortedBy { it.start }
    }
    
    private fun processEmbeddedBlocks(
        code: String, 
        pattern: Pattern, 
        highlighter: LanguageHighlighter, 
        tokens: MutableList<Token>, 
        processed: BooleanArray
    ) {
        val matcher = pattern.matcher(code)
        while (matcher.find()) {
            val fullStart = matcher.start()
            val fullEnd = matcher.end()
            
            if (processed[fullStart]) continue
            
            // 处理开始标签 <style...>
            val startTag = matcher.group(1)
            val startTagEnd = fullStart + startTag.length
            // 简单的将开始标签作为 TAG 处理（这里不细分属性了，简单处理）
            tokens.add(Token(TokenType.TAG, fullStart, startTagEnd, startTag))
            for (i in fullStart until startTagEnd) processed[i] = true
            
            // 处理内容
            val content = matcher.group(2)
            val contentStart = matcher.start(2)
            if (content.isNotBlank()) {
                val embeddedTokens = highlighter.tokenize(content)
                for (token in embeddedTokens) {
                    tokens.add(Token(
                        token.type,
                        contentStart + token.start,
                        contentStart + token.end,
                        token.text
                    ))
                }
                for (i in contentStart until (contentStart + content.length)) processed[i] = true
            }
            
            // 处理结束标签 </style>
            val endTag = matcher.group(3)
            val endTagStart = matcher.start(3)
            tokens.add(Token(TokenType.TAG, endTagStart, fullEnd, endTag))
            for (i in endTagStart until fullEnd) processed[i] = true
        }
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
            
            if (processed[start]) continue
            
            tokens.add(Token(tokenType, start, end, matcher.group()))
            for (i in start until end) {
                processed[i] = true
            }
        }
    }
}