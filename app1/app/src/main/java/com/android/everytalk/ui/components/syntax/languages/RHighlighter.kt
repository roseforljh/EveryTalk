package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * R 语言语法高亮器
 * 
 * 支持：
 * - R 关键字和控制流
 * - 字符串（单引号、双引号）
 * - 注释
 * - 数字（整数、浮点数、复数、科学计数法）
 * - 特殊值（NA, NULL, TRUE, FALSE, Inf, NaN）
 */
object RHighlighter : LanguageHighlighter {
    
    private val keywords = setOf(
        // 控制流
        "if", "else", "for", "while", "repeat", "in",
        "next", "break", "return",
        // 定义
        "function", "local",
        // 其他
        "library", "require", "source", "setwd", "getwd"
    )
    
    private val builtinConstants = setOf(
        "TRUE", "FALSE", "T", "F",
        "NA", "NA_integer_", "NA_real_", "NA_complex_", "NA_character_",
        "NULL", "Inf", "NaN"
    )
    
    private val builtinFunctions = setOf(
        // 基础函数
        "c", "list", "vector", "matrix", "array", "data.frame", "factor",
        "length", "nrow", "ncol", "dim", "names", "class", "typeof", "mode",
        "print", "cat", "paste", "paste0", "sprintf", "format",
        "sum", "mean", "median", "sd", "var", "min", "max", "range",
        "abs", "sqrt", "exp", "log", "log10", "log2", "sin", "cos", "tan",
        "floor", "ceiling", "round", "trunc",
        "seq", "rep", "rev", "sort", "order", "unique", "duplicated",
        "head", "tail", "subset", "which", "any", "all",
        "is.na", "is.null", "is.numeric", "is.character", "is.logical",
        "as.numeric", "as.character", "as.logical", "as.integer", "as.factor",
        "apply", "lapply", "sapply", "mapply", "tapply",
        "read.csv", "read.table", "write.csv", "write.table",
        "plot", "hist", "barplot", "boxplot", "pie", "lines", "points",
        "lm", "glm", "t.test", "chisq.test", "cor", "cov"
    )
    
    // 预编译正则表达式
    private val commentPattern = Pattern.compile("#.*")
    private val doubleStringPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val singleStringPattern = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'")
    private val rawStringPattern = Pattern.compile("r\"\\([^)]*\\)\"|r'\\([^)]*\\)'", Pattern.CASE_INSENSITIVE)
    private val complexNumberPattern = Pattern.compile("\\b\\d+(\\.\\d+)?[iI]\\b")
    private val hexNumberPattern = Pattern.compile("\\b0x[0-9a-fA-F]+[lL]?\\b")
    private val numberPattern = Pattern.compile("\\b\\d+\\.?\\d*(?:[eE][+-]?\\d+)?[lL]?\\b")
    private val identifierPattern = Pattern.compile("\\b[a-zA-Z_.][a-zA-Z0-9_.]*\\b")
    private val functionCallPattern = Pattern.compile("\\b([a-zA-Z_.][a-zA-Z0-9_.]*)\\s*(?=\\()")
    private val operatorPattern = Pattern.compile("<-|<<-|->|->>|%%|%/%|%\\*%|%in%|%o%|%x%|\\|\\||&&|<=|>=|==|!=|\\$|@|::|:::|[+\\-*/%^<>=!&|~:]")
    private val punctuationPattern = Pattern.compile("[{}\\[\\]();,]")
    
    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)
        
        // 1. 注释
        findMatches(commentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 2. 原始字符串 (R 4.0+)
        findMatches(rawStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 3. 双引号字符串
        findMatches(doubleStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 4. 单引号字符串
        findMatches(singleStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 5. 复数
        findMatches(complexNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 6. 十六进制数字
        findMatches(hexNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 7. 普通数字
        findMatches(numberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 8. 函数调用
        val funcMatcher = functionCallPattern.matcher(code)
        while (funcMatcher.find()) {
            val start = funcMatcher.start(1)
            val end = funcMatcher.end(1)
            if (!processed[start]) {
                val funcName = funcMatcher.group(1)
                if (!keywords.contains(funcName) && !builtinConstants.contains(funcName)) {
                    tokens.add(Token(TokenType.FUNCTION, start, end, funcName))
                    for (i in start until end) processed[i] = true
                }
            }
        }
        
        // 9. 标识符和关键字
        val identMatcher = identifierPattern.matcher(code)
        while (identMatcher.find()) {
            val start = identMatcher.start()
            val end = identMatcher.end()
            if (!processed[start]) {
                val word = identMatcher.group()
                val tokenType = when {
                    keywords.contains(word) -> TokenType.KEYWORD
                    builtinConstants.contains(word) -> when (word) {
                        "TRUE", "FALSE", "T", "F" -> TokenType.BOOLEAN
                        "NULL", "NA", "NA_integer_", "NA_real_", "NA_complex_", "NA_character_" -> TokenType.NULL
                        "Inf", "NaN" -> TokenType.NUMBER
                        else -> TokenType.KEYWORD
                    }
                    builtinFunctions.contains(word) -> TokenType.FUNCTION
                    else -> TokenType.VARIABLE
                }
                tokens.add(Token(tokenType, start, end, word))
                for (i in start until end) processed[i] = true
            }
        }
        
        // 10. 运算符
        findMatches(operatorPattern, code, TokenType.OPERATOR, tokens, processed)
        
        // 11. 标点符号
        findMatches(punctuationPattern, code, TokenType.PUNCTUATION, tokens, processed)
        
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