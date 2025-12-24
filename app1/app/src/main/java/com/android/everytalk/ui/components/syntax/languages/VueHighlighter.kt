package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * Vue 模板语法高亮器
 * 
 * 支持：
 * - 标准 HTML 标签和属性
 * - Vue 指令（v-if, v-for, v-model, v-show, v-bind, v-on 等）
 * - Vue 简写（:prop, @event, #slot）
 * - Vue 修饰符（.prevent, .stop, .once, .sync 等）
 * - Vue 插值表达式（{{ expression }}）
 * - 嵌入式 <style> 和 <script> 块
 */
object VueHighlighter : LanguageHighlighter {
    
    // 预编译的正则表达式
    private val commentPattern = Pattern.compile("<!--[\\s\\S]*?-->")
    private val doctypePattern = Pattern.compile("<!DOCTYPE[^>]*>", Pattern.CASE_INSENSITIVE)
    
    // Vue 插值表达式
    private val interpolationPattern = Pattern.compile("\\{\\{([^}]+)\\}\\}")
    
    // 标签解析
    private val tagStartPattern = Pattern.compile("</?([a-zA-Z][a-zA-Z0-9-:]*)")
    private val tagEndPattern = Pattern.compile("/?>")
    
    // 嵌入内容正则
    private val styleBlockPattern = Pattern.compile("(<style[^>]*>)([\\s\\S]*?)(</style>)", Pattern.CASE_INSENSITIVE)
    private val scriptBlockPattern = Pattern.compile("(<script[^>]*>)([\\s\\S]*?)(</script>)", Pattern.CASE_INSENSITIVE)
    
    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)
        
        // 1. 优先处理嵌入的 CSS 和 JS（<script setup> 和 <style scoped>）
        processEmbeddedBlocks(code, styleBlockPattern, CssHighlighter, tokens, processed)
        processEmbeddedBlocks(code, scriptBlockPattern, JavaScriptHighlighter, tokens, processed)
        
        // 2. 处理 Vue 插值表达式 {{ }}
        processInterpolations(code, tokens, processed)
        
        // 3. 注释
        findMatches(commentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 4. DOCTYPE
        findMatches(doctypePattern, code, TokenType.TAG, tokens, processed)
        
        // 5. 解析 HTML 标签和 Vue 属性
        parseTemplateContent(code, tokens, processed)
        
        return tokens.sortedBy { it.start }
    }
    
    /**
     * 处理 Vue 插值表达式 {{ expression }}
     */
    private fun processInterpolations(
        code: String,
        tokens: MutableList<Token>,
        processed: BooleanArray
    ) {
        val matcher = interpolationPattern.matcher(code)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            
            // 检查是否已处理
            if (processed[start]) continue
            
            // {{ 开始符号 - 使用 PUNCTUATION
            tokens.add(Token(TokenType.PUNCTUATION, start, start + 2, "{{"))
            
            // 表达式内容 - 使用 VARIABLE（红色，与变量一致）
            val exprStart = start + 2
            val exprEnd = end - 2
            if (exprStart < exprEnd) {
                val expression = code.substring(exprStart, exprEnd).trim()
                // 只有非空内容才添加 token
                if (expression.isNotEmpty()) {
                    // 找到实际内容的位置（跳过前导空格）
                    val trimmedStart = code.indexOf(expression, exprStart)
                    tokens.add(Token(TokenType.VARIABLE, trimmedStart, trimmedStart + expression.length, expression))
                }
            }
            
            // }} 结束符号 - 使用 PUNCTUATION
            tokens.add(Token(TokenType.PUNCTUATION, end - 2, end, "}}"))
            
            // 标记为已处理
            for (i in start until end) {
                processed[i] = true
            }
        }
    }
    
    /**
     * 解析模板内容（标签和属性）
     */
    private fun parseTemplateContent(
        code: String,
        tokens: MutableList<Token>,
        processed: BooleanArray
    ) {
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
                    val bracketLen = if (i + 1 < code.length && code[i + 1] == '/') 2 else 1
                    tokens.add(Token(TokenType.PUNCTUATION, tagStart, tagStart + bracketLen, code.substring(tagStart, tagStart + bracketLen)))
                    
                    // 标记标签名
                    tokens.add(Token(TokenType.TAG, tagStart + bracketLen, tagEnd, tagName))
                    
                    for (j in tagStart until tagEnd) processed[j] = true
                    i = tagEnd
                    
                    // 解析属性直到 >
                    i = parseAttributes(code, i, tokens, processed)
                    continue
                }
            }
            i++
        }
    }
    
    /**
     * 解析标签内的属性
     */
    private fun parseAttributes(
        code: String,
        startPos: Int,
        tokens: MutableList<Token>,
        processed: BooleanArray
    ): Int {
        var i = startPos
        
        while (i < code.length) {
            // 跳过空白
            while (i < code.length && code[i].isWhitespace()) {
                processed[i] = true
                i++
            }
            if (i >= code.length) break
            
            // 检查是否是标签结束
            val endMatcher = tagEndPattern.matcher(code)
            if (endMatcher.find(i) && endMatcher.start() == i) {
                val endTagStart = endMatcher.start()
                val endTagEnd = endMatcher.end()
                tokens.add(Token(TokenType.PUNCTUATION, endTagStart, endTagEnd, endMatcher.group()))
                for (j in endTagStart until endTagEnd) processed[j] = true
                return endTagEnd
            }
            
            // 尝试解析属性
            val attrResult = parseAttribute(code, i, tokens, processed)
            if (attrResult > i) {
                i = attrResult
            } else {
                // 无法识别的字符，跳过
                i++
            }
        }
        
        return i
    }
    
    /**
     * 解析单个属性
     * 支持：
     * - 普通属性：class="foo"
     * - Vue 绑定：:prop="value" 或 v-bind:prop="value"
     * - Vue 事件：@event="handler" 或 v-on:event="handler"
     * - Vue 指令：v-if="condition" v-for="item in list"
     * - Vue 修饰符：@click.prevent.stop="handler"
     * - Vue slot：#default="{ item }"
     */
    private fun parseAttribute(
        code: String,
        startPos: Int,
        tokens: MutableList<Token>,
        processed: BooleanArray
    ): Int {
        var i = startPos
        val char = code[i]
        
        // 属性名开始字符检测
        if (!isAttributeStartChar(char)) {
            return i
        }
        
        // 确定属性类型并读取属性名
        val attrNameStart = i
        var attrNameEnd = i
        var isVueDirective = false
        var isVueBinding = false
        var isVueEvent = false
        var isVueSlot = false
        
        when {
            // Vue 事件简写 @event
            char == '@' -> {
                isVueEvent = true
                attrNameEnd = readAttributeNameWithModifiers(code, i + 1)
            }
            // Vue 绑定简写 :prop
            char == ':' -> {
                isVueBinding = true
                attrNameEnd = readAttributeNameWithModifiers(code, i + 1)
            }
            // Vue slot 简写 #slot
            char == '#' -> {
                isVueSlot = true
                attrNameEnd = readAttributeName(code, i + 1)
            }
            // v- 开头的指令
            char == 'v' && i + 1 < code.length && code[i + 1] == '-' -> {
                isVueDirective = true
                attrNameEnd = readAttributeNameWithModifiers(code, i)
            }
            // 普通属性
            else -> {
                attrNameEnd = readAttributeName(code, i)
            }
        }
        
        if (attrNameEnd <= attrNameStart) {
            return i
        }
        
        val attrName = code.substring(attrNameStart, attrNameEnd)
        
        // 根据属性类型选择 Token 类型
        val tokenType = when {
            isVueDirective || isVueBinding || isVueEvent || isVueSlot -> TokenType.KEYWORD  // 紫色
            else -> TokenType.ATTRIBUTE  // 橙色
        }
        
        tokens.add(Token(tokenType, attrNameStart, attrNameEnd, attrName))
        for (j in attrNameStart until attrNameEnd) processed[j] = true
        i = attrNameEnd
        
        // 跳过空白
        while (i < code.length && code[i].isWhitespace()) {
            processed[i] = true
            i++
        }
        
        // 检查是否有 = 和值
        if (i < code.length && code[i] == '=') {
            tokens.add(Token(TokenType.OPERATOR, i, i + 1, "="))
            processed[i] = true
            i++
            
            // 跳过空白
            while (i < code.length && code[i].isWhitespace()) {
                processed[i] = true
                i++
            }
            
            // 解析属性值
            if (i < code.length) {
                i = parseAttributeValue(code, i, isVueDirective || isVueBinding || isVueEvent || isVueSlot, tokens, processed)
            }
        }
        
        return i
    }
    
    /**
     * 检查是否是属性名开始字符
     */
    private fun isAttributeStartChar(char: Char): Boolean {
        return char.isLetter() || char == '@' || char == ':' || char == '#' || char == 'v'
    }
    
    /**
     * 读取属性名（不含修饰符）
     */
    private fun readAttributeName(code: String, startPos: Int): Int {
        var i = startPos
        while (i < code.length) {
            val c = code[i]
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == ':') {
                i++
            } else {
                break
            }
        }
        return i
    }
    
    /**
     * 读取属性名（含 Vue 修饰符 .xxx）
     */
    private fun readAttributeNameWithModifiers(code: String, startPos: Int): Int {
        var i = startPos
        while (i < code.length) {
            val c = code[i]
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == ':' || c == '.') {
                i++
            } else {
                break
            }
        }
        return i
    }
    
    /**
     * 解析属性值
     * 
     * @param isVueExpression 是否是 Vue 表达式（动态绑定的值）
     */
    private fun parseAttributeValue(
        code: String,
        startPos: Int,
        isVueExpression: Boolean,
        tokens: MutableList<Token>,
        processed: BooleanArray
    ): Int {
        val firstChar = code[startPos]
        var valEnd: Int
        
        if (firstChar == '"' || firstChar == '\'') {
            // 引号包裹的值
            valEnd = code.indexOf(firstChar, startPos + 1)
            if (valEnd != -1) {
                valEnd += 1
                
                if (isVueExpression) {
                    // Vue 表达式：分开处理引号和内容
                    // 开始引号
                    tokens.add(Token(TokenType.PUNCTUATION, startPos, startPos + 1, firstChar.toString()))
                    processed[startPos] = true
                    
                    // 表达式内容（使用 VARIABLE 类型，红色）
                    val contentStart = startPos + 1
                    val contentEnd = valEnd - 1
                    if (contentStart < contentEnd) {
                        val content = code.substring(contentStart, contentEnd)
                        tokens.add(Token(TokenType.VARIABLE, contentStart, contentEnd, content))
                        for (j in contentStart until contentEnd) processed[j] = true
                    }
                    
                    // 结束引号
                    tokens.add(Token(TokenType.PUNCTUATION, valEnd - 1, valEnd, firstChar.toString()))
                    processed[valEnd - 1] = true
                } else {
                    // 普通属性值（绿色）
                    tokens.add(Token(TokenType.ATTRIBUTE_VALUE, startPos, valEnd, code.substring(startPos, valEnd)))
                    for (j in startPos until valEnd) processed[j] = true
                }
                
                return valEnd
            } else {
                // 未闭合的引号
                return startPos + 1
            }
        } else {
            // 无引号的值
            valEnd = startPos
            while (valEnd < code.length && !code[valEnd].isWhitespace() && code[valEnd] != '>') {
                valEnd++
            }
            
            if (valEnd > startPos) {
                val tokenType = if (isVueExpression) TokenType.VARIABLE else TokenType.ATTRIBUTE_VALUE
                tokens.add(Token(tokenType, startPos, valEnd, code.substring(startPos, valEnd)))
                for (j in startPos until valEnd) processed[j] = true
            }
            
            return valEnd
        }
    }
    
    /**
     * 处理嵌入的 CSS/JS 代码块
     */
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
            
            // 处理开始标签（如 <style scoped> 或 <script setup>）
            val startTag = matcher.group(1)
            val startTagStart = fullStart
            val startTagEnd = fullStart + startTag.length
            
            // 解析开始标签中的属性
            val tagContent = startTag.substring(1, startTag.length - 1) // 去掉 < 和 >
            val tagNameEnd = tagContent.indexOfFirst { it.isWhitespace() || it == '>' }
            val tagName = if (tagNameEnd > 0) tagContent.substring(0, tagNameEnd) else tagContent.trimEnd('>')
            
            // < 符号
            tokens.add(Token(TokenType.PUNCTUATION, startTagStart, startTagStart + 1, "<"))
            // 标签名
            tokens.add(Token(TokenType.TAG, startTagStart + 1, startTagStart + 1 + tagName.length, tagName))
            // 简化处理：将剩余部分作为属性
            if (tagNameEnd > 0 && tagNameEnd + 1 < tagContent.length) {
                val attrPart = tagContent.substring(tagNameEnd).trimEnd('>')
                if (attrPart.isNotBlank()) {
                    val attrStart = startTagStart + 1 + tagNameEnd
                    tokens.add(Token(TokenType.ATTRIBUTE, attrStart, attrStart + attrPart.trim().length, attrPart.trim()))
                }
            }
            // > 符号
            tokens.add(Token(TokenType.PUNCTUATION, startTagEnd - 1, startTagEnd, ">"))
            
            for (i in startTagStart until startTagEnd) processed[i] = true
            
            // 处理嵌入内容
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
            
            // 处理结束标签（如 </style> 或 </script>）
            val endTag = matcher.group(3)
            val endTagStart = matcher.start(3)
            // </ 符号
            tokens.add(Token(TokenType.PUNCTUATION, endTagStart, endTagStart + 2, "</"))
            // 标签名
            val endTagName = endTag.substring(2, endTag.length - 1)
            tokens.add(Token(TokenType.TAG, endTagStart + 2, endTagStart + 2 + endTagName.length, endTagName))
            // > 符号
            tokens.add(Token(TokenType.PUNCTUATION, fullEnd - 1, fullEnd, ">"))
            
            for (i in endTagStart until fullEnd) processed[i] = true
        }
    }
    
    /**
     * 查找并添加匹配的 Token
     */
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