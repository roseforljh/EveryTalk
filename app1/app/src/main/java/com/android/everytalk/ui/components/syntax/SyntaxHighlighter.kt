package com.android.everytalk.ui.components.syntax

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.android.everytalk.ui.components.syntax.languages.*

/**
 * Token 数据类
 * 
 * @param type Token 类型
 * @param start 起始位置（包含）
 * @param end 结束位置（不包含）
 * @param text Token 文本内容
 */
data class Token(
    val type: TokenType,
    val start: Int,
    val end: Int,
    val text: String
)

/**
 * 语言高亮器接口
 */
interface LanguageHighlighter {
    /**
     * 对代码进行词法分析，返回 Token 列表
     * 
     * @param code 源代码
     * @return Token 列表，按位置排序
     */
    fun tokenize(code: String): List<Token>
}

/**
 * 语法高亮器
 * 
 * 核心功能：
 * 1. 根据语言选择对应的高亮器
 * 2. 对代码进行词法分析
 * 3. 生成带颜色的 AnnotatedString
 */
object SyntaxHighlighter {
    
    // 语言高亮器映射（惰性初始化）
    private val highlighters: Map<String, LanguageHighlighter> by lazy {
        mapOf(
            // HTML
            "html" to HtmlHighlighter,
            "htm" to HtmlHighlighter,
            
            // XML/SVG (专用高亮器)
            "xml" to XmlHighlighter,
            "svg" to XmlHighlighter,
            "xsl" to XmlHighlighter,
            "xslt" to XmlHighlighter,
            "xsd" to XmlHighlighter,
            "plist" to XmlHighlighter,
            "xaml" to XmlHighlighter,
            
            // Vue (专用高亮器)
            "vue" to VueHighlighter,
            
            // CSS
            "css" to CssHighlighter,
            "scss" to CssHighlighter,
            "sass" to CssHighlighter,
            "less" to CssHighlighter,
            
            // JavaScript
            "javascript" to JavaScriptHighlighter,
            "js" to JavaScriptHighlighter,
            "jsx" to JavaScriptHighlighter,
            "typescript" to JavaScriptHighlighter,
            "ts" to JavaScriptHighlighter,
            "tsx" to JavaScriptHighlighter,
            "node" to JavaScriptHighlighter,
            "nodejs" to JavaScriptHighlighter,
            
            // Python
            "python" to PythonHighlighter,
            "py" to PythonHighlighter,
            
            // Kotlin
            "kotlin" to KotlinHighlighter,
            "kt" to KotlinHighlighter,
            "kts" to KotlinHighlighter,
            
            // JSON
            "json" to JsonHighlighter,
            "jsonc" to JsonHighlighter,

            // Java
            "java" to JavaHighlighter,

            // C/C++
            "c" to CppHighlighter,
            "cpp" to CppHighlighter,
            "c++" to CppHighlighter,
            "h" to CppHighlighter,
            "hpp" to CppHighlighter,

            // Go
            "go" to GoHighlighter,
            "golang" to GoHighlighter,

            // Rust
            "rust" to RustHighlighter,
            "rs" to RustHighlighter,

            // SQL 和数据库语言
            "sql" to SqlHighlighter,
            "mysql" to SqlHighlighter,
            "postgresql" to SqlHighlighter,
            "postgres" to SqlHighlighter,
            "pgsql" to SqlHighlighter,
            "plpgsql" to SqlHighlighter,
            "sqlite" to SqlHighlighter,
            "sqlite3" to SqlHighlighter,
            "oracle" to SqlHighlighter,
            "plsql" to SqlHighlighter,
            "pl/sql" to SqlHighlighter,
            "tsql" to SqlHighlighter,
            "t-sql" to SqlHighlighter,
            "mssql" to SqlHighlighter,
            "sqlserver" to SqlHighlighter,
            "mariadb" to SqlHighlighter,
            "hive" to SqlHighlighter,
            "hiveql" to SqlHighlighter,
            "spark" to SqlHighlighter,
            "sparksql" to SqlHighlighter,
            "presto" to SqlHighlighter,
            "prestosql" to SqlHighlighter,
            "trino" to SqlHighlighter,
            "bigquery" to SqlHighlighter,
            "redshift" to SqlHighlighter,
            "snowflake" to SqlHighlighter,
            "clickhouse" to SqlHighlighter,
            "cassandra" to SqlHighlighter,
            "cql" to SqlHighlighter,

            // Shell
            "bash" to ShellHighlighter,
            "sh" to ShellHighlighter,
            "shell" to ShellHighlighter,
            "zsh" to ShellHighlighter,

            // YAML
            "yaml" to YamlHighlighter,
            "yml" to YamlHighlighter,

            // Dockerfile
            "dockerfile" to DockerfileHighlighter,
            "docker" to DockerfileHighlighter,

            // Swift
            "swift" to SwiftHighlighter,

            // Ruby
            "ruby" to RubyHighlighter,
            "rb" to RubyHighlighter,

            // PHP
            "php" to PhpHighlighter,

            // C#
            "csharp" to CSharpHighlighter,
            "cs" to CSharpHighlighter,
            "c#" to CSharpHighlighter,

            // Scala
            "scala" to ScalaHighlighter,
            "sc" to ScalaHighlighter,

            // Lua
            "lua" to LuaHighlighter,

            // R
            "r" to RHighlighter,
            "rscript" to RHighlighter,

            // Markdown
            "markdown" to MarkdownHighlighter,
            "md" to MarkdownHighlighter
        )
    }
    
    /**
     * 对代码进行语法高亮
     * 
     * @param code 源代码
     * @param language 编程语言（可为 null）
     * @param theme 语法高亮主题
     * @return 带颜色的 AnnotatedString
     */
    fun highlight(
        code: String,
        language: String?,
        theme: SyntaxHighlightTheme
    ): AnnotatedString {
        // 空代码直接返回
        if (code.isEmpty()) {
            return AnnotatedString("")
        }
        
        // 获取语言高亮器
        val normalizedLang = language?.trim()?.lowercase()
        val highlighter = normalizedLang?.let { highlighters[it] }
        
        // 如果没有对应的高亮器，返回纯色文本
        if (highlighter == null) {
            return buildAnnotatedString {
                append(code)
                addStyle(
                    SpanStyle(color = theme.plain),
                    0,
                    code.length
                )
            }
        }
        
        // 执行词法分析
        val tokens = try {
            highlighter.tokenize(code)
        } catch (e: Exception) {
            // 词法分析失败，返回纯色文本
            return buildAnnotatedString {
                append(code)
                addStyle(
                    SpanStyle(color = theme.plain),
                    0,
                    code.length
                )
            }
        }
        
        // 构建 AnnotatedString
        return buildAnnotatedString {
            append(code)
            
            // 先设置基础颜色
            addStyle(
                SpanStyle(color = theme.plain),
                0,
                code.length
            )
            
            // 应用 Token 颜色
            for (token in tokens) {
                if (token.start >= 0 && token.end <= code.length && token.start < token.end) {
                    addStyle(
                        SpanStyle(color = theme.getColor(token.type)),
                        token.start,
                        token.end
                    )
                }
            }
        }
    }
    
    /**
     * 检查是否支持该语言的高亮
     */
    fun isSupported(language: String?): Boolean {
        val normalizedLang = language?.trim()?.lowercase() ?: return false
        return highlighters.containsKey(normalizedLang)
    }
    
    /**
     * 获取所有支持的语言
     */
    fun getSupportedLanguages(): Set<String> = highlighters.keys
}