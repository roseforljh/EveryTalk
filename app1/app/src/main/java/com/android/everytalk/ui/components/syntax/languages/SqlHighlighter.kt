package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * SQL 语法高亮器（增强版）
 *
 * 支持：
 * - SQL 关键字（DML, DDL, DCL）
 * - 数据类型
 * - 内置函数（聚合函数、字符串函数、日期函数等）
 * - 表名和列名
 * - 字符串和数字
 * - 注释（单行 -- 和多行 /* */）
 * - 变量和参数（@var, :param, $1）
 */
object SqlHighlighter : LanguageHighlighter {
    
    // DML 关键字
    private val dmlKeywords = setOf(
        "select", "insert", "update", "delete", "merge", "replace",
        "from", "where", "and", "or", "not", "in", "like", "between",
        "is", "null", "true", "false", "exists", "case", "when", "then", "else", "end",
        "order", "by", "asc", "desc", "nulls", "first", "last",
        "group", "having", "limit", "offset", "fetch", "next", "rows", "only",
        "union", "intersect", "except", "all", "distinct",
        "join", "inner", "left", "right", "full", "outer", "cross", "natural", "on", "using",
        "into", "values", "set", "as", "with", "recursive"
    )
    
    // DDL 关键字
    private val ddlKeywords = setOf(
        "create", "alter", "drop", "truncate", "rename", "comment",
        "table", "view", "index", "sequence", "trigger", "procedure", "function",
        "database", "schema", "tablespace", "type", "domain", "role", "user",
        "primary", "key", "foreign", "references", "unique", "check", "constraint",
        "default", "auto_increment", "identity", "serial", "generated", "always",
        "cascade", "restrict", "no", "action", "deferrable", "initially", "deferred", "immediate",
        "add", "column", "modify", "change", "if", "temporary", "temp", "unlogged",
        "partition", "partitioned", "range", "list", "hash"
    )
    
    // DCL 关键字
    private val dclKeywords = setOf(
        "grant", "revoke", "deny", "commit", "rollback", "savepoint", "begin", "transaction",
        "start", "work", "isolation", "level", "read", "write", "committed", "uncommitted",
        "repeatable", "serializable", "lock", "unlock", "share", "exclusive", "nowait"
    )
    
    // 数据类型
    private val dataTypes = setOf(
        // 数值类型
        "int", "integer", "tinyint", "smallint", "mediumint", "bigint",
        "decimal", "numeric", "float", "double", "real", "precision",
        "bit", "bool", "boolean", "serial", "bigserial", "smallserial",
        // 字符串类型
        "char", "varchar", "text", "tinytext", "mediumtext", "longtext",
        "nchar", "nvarchar", "ntext", "character", "varying",
        "binary", "varbinary", "blob", "tinyblob", "mediumblob", "longblob",
        "bytea", "clob", "nclob",
        // 日期时间类型
        "date", "time", "datetime", "timestamp", "timestamptz", "timetz",
        "year", "interval",
        // 其他类型
        "json", "jsonb", "xml", "uuid", "array", "enum", "set",
        "point", "line", "polygon", "geometry", "geography",
        "inet", "cidr", "macaddr", "money", "hstore"
    )
    
    // 聚合函数
    private val aggregateFunctions = setOf(
        "count", "sum", "avg", "min", "max",
        "group_concat", "string_agg", "array_agg", "json_agg", "jsonb_agg",
        "first_value", "last_value", "nth_value",
        "row_number", "rank", "dense_rank", "ntile", "lag", "lead",
        "cume_dist", "percent_rank", "percentile_cont", "percentile_disc"
    )
    
    // 字符串函数
    private val stringFunctions = setOf(
        "concat", "concat_ws", "substring", "substr", "left", "right",
        "length", "char_length", "octet_length", "bit_length",
        "upper", "lower", "initcap", "trim", "ltrim", "rtrim", "btrim",
        "lpad", "rpad", "repeat", "reverse", "replace", "translate",
        "position", "locate", "instr", "strpos", "charindex",
        "split_part", "regexp_replace", "regexp_matches", "regexp_split_to_array",
        "ascii", "chr", "format", "quote_literal", "quote_ident"
    )
    
    // 日期函数
    private val dateFunctions = setOf(
        "now", "current_timestamp", "current_date", "current_time",
        "getdate", "sysdate", "systimestamp", "localtimestamp",
        "date_add", "date_sub", "dateadd", "datediff", "timestampdiff",
        "extract", "date_part", "date_trunc", "to_date", "to_timestamp",
        "year", "month", "day", "hour", "minute", "second",
        "dayofweek", "dayofmonth", "dayofyear", "weekofyear", "week",
        "age", "make_date", "make_time", "make_timestamp"
    )
    
    // 数学函数
    private val mathFunctions = setOf(
        "abs", "ceil", "ceiling", "floor", "round", "trunc", "truncate",
        "mod", "power", "pow", "sqrt", "exp", "ln", "log", "log10", "log2",
        "sin", "cos", "tan", "asin", "acos", "atan", "atan2",
        "sign", "rand", "random", "greatest", "least", "coalesce", "nullif", "ifnull", "nvl", "nvl2"
    )
    
    // 类型转换函数
    private val conversionFunctions = setOf(
        "cast", "convert", "try_cast", "try_convert",
        "to_char", "to_number", "to_date", "to_timestamp",
        "decode", "encode", "md5", "sha1", "sha256", "sha512"
    )
    
    // 所有内置函数
    private val builtinFunctions = aggregateFunctions + stringFunctions + dateFunctions + mathFunctions + conversionFunctions
    
    // 所有关键字
    private val allKeywords = dmlKeywords + ddlKeywords + dclKeywords

    // 预编译正则表达式
    private val multiLineCommentPattern = Pattern.compile("/\\*[\\s\\S]*?\\*/")
    private val singleLineCommentPattern = Pattern.compile("--.*")
    
    // 字符串
    private val singleQuoteStringPattern = Pattern.compile("'(?:[^'\\\\]|\\\\.|'')*'")
    private val doubleQuoteStringPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.|\"\")*\"")
    private val dollarStringPattern = Pattern.compile("\\$\\$[\\s\\S]*?\\$\\$|\\$[a-zA-Z_][a-zA-Z0-9_]*\\$[\\s\\S]*?\\$[a-zA-Z_][a-zA-Z0-9_]*\\$")
    
    // 标识符
    private val backtickIdentifierPattern = Pattern.compile("`[^`]*`")
    private val bracketIdentifierPattern = Pattern.compile("\\[[^\\]]*\\]")
    
    // 变量和参数
    private val variablePattern = Pattern.compile("@[a-zA-Z_][a-zA-Z0-9_]*|@@[a-zA-Z_][a-zA-Z0-9_]*")
    private val namedParamPattern = Pattern.compile(":[a-zA-Z_][a-zA-Z0-9_]*")
    private val positionalParamPattern = Pattern.compile("\\$\\d+")
    private val placeholderPattern = Pattern.compile("\\?")
    
    // 数字
    private val hexNumberPattern = Pattern.compile("\\b0x[0-9a-fA-F]+\\b")
    private val binaryNumberPattern = Pattern.compile("\\b0b[01]+\\b")
    private val numberPattern = Pattern.compile("\\b\\d+\\.?\\d*(?:[eE][+-]?\\d+)?\\b")
    
    // 标识符
    private val identifierPattern = Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b")
    
    // 函数调用
    private val functionCallPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?=\\()")
    
    // 运算符
    private val operatorPattern = Pattern.compile("::|\\.\\.|<>|!=|<=|>=|\\|\\||&&|->|->>|#>|#>>|@>|<@|\\?\\||\\?&|[+\\-*/%=<>|&^~]")
    
    // 标点
    private val punctuationPattern = Pattern.compile("[(),.;]")

    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)

        // 1. 多行注释
        findMatches(multiLineCommentPattern, code, TokenType.COMMENT, tokens, processed)

        // 2. 单行注释
        findMatches(singleLineCommentPattern, code, TokenType.COMMENT, tokens, processed)

        // 3. Dollar 引号字符串 (PostgreSQL)
        findMatches(dollarStringPattern, code, TokenType.STRING, tokens, processed)

        // 4. 单引号字符串
        findMatches(singleQuoteStringPattern, code, TokenType.STRING, tokens, processed)

        // 5. 双引号标识符
        findMatches(doubleQuoteStringPattern, code, TokenType.VARIABLE, tokens, processed)

        // 6. 反引号标识符 (MySQL)
        findMatches(backtickIdentifierPattern, code, TokenType.VARIABLE, tokens, processed)

        // 7. 方括号标识符 (SQL Server)
        findMatches(bracketIdentifierPattern, code, TokenType.VARIABLE, tokens, processed)

        // 8. 变量 (@var, @@var)
        findMatches(variablePattern, code, TokenType.VARIABLE, tokens, processed)

        // 9. 命名参数 (:param)
        findMatches(namedParamPattern, code, TokenType.PARAMETER, tokens, processed)

        // 10. 位置参数 ($1, $2)
        findMatches(positionalParamPattern, code, TokenType.PARAMETER, tokens, processed)

        // 11. 占位符 (?)
        findMatches(placeholderPattern, code, TokenType.PARAMETER, tokens, processed)

        // 12. 十六进制数字
        findMatches(hexNumberPattern, code, TokenType.NUMBER, tokens, processed)

        // 13. 二进制数字
        findMatches(binaryNumberPattern, code, TokenType.NUMBER, tokens, processed)

        // 14. 普通数字
        findMatches(numberPattern, code, TokenType.NUMBER, tokens, processed)

        // 15. 函数调用
        val funcMatcher = functionCallPattern.matcher(code)
        while (funcMatcher.find()) {
            val start = funcMatcher.start(1)
            val end = funcMatcher.end(1)
            if (!processed[start]) {
                val funcName = funcMatcher.group(1).lowercase()
                val tokenType = when {
                    builtinFunctions.contains(funcName) -> TokenType.FUNCTION
                    allKeywords.contains(funcName) -> TokenType.KEYWORD
                    else -> TokenType.FUNCTION
                }
                tokens.add(Token(tokenType, start, end, funcMatcher.group(1)))
                for (i in start until end) processed[i] = true
            }
        }

        // 16. 标识符和关键字
        val identMatcher = identifierPattern.matcher(code)
        while (identMatcher.find()) {
            val start = identMatcher.start()
            val end = identMatcher.end()
            if (!processed[start]) {
                val word = identMatcher.group()
                val wordLower = word.lowercase()
                
                val tokenType = when {
                    // 关键字
                    allKeywords.contains(wordLower) -> when (wordLower) {
                        "null" -> TokenType.NULL
                        "true", "false" -> TokenType.BOOLEAN
                        else -> TokenType.KEYWORD
                    }
                    // 数据类型
                    dataTypes.contains(wordLower) -> TokenType.CLASS_NAME
                    // 内置函数（非函数调用形式）
                    builtinFunctions.contains(wordLower) -> TokenType.FUNCTION
                    // 普通标识符（表名、列名等）
                    else -> TokenType.PROPERTY
                }
                
                tokens.add(Token(tokenType, start, end, word))
                for (i in start until end) processed[i] = true
            }
        }

        // 17. 运算符
        findMatches(operatorPattern, code, TokenType.OPERATOR, tokens, processed)

        // 18. 标点符号
        findMatches(punctuationPattern, code, TokenType.PUNCTUATION, tokens, processed)

        return tokens.sortedBy { it.start }
    }

    private fun findMatches(pattern: Pattern, code: String, type: TokenType, tokens: MutableList<Token>, processed: BooleanArray) {
        val matcher = pattern.matcher(code)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            if (!processed[start]) {
                tokens.add(Token(type, start, end, matcher.group()))
                for (i in start until end) processed[i] = true
            }
        }
    }
}