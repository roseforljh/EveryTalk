package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * Dockerfile 语法高亮器（增强版）
 *
 * 支持：
 * - Dockerfile 指令（FROM, RUN, COPY 等）
 * - 变量和环境变量
 * - 字符串
 * - 注释
 * - Shell 命令
 * - 镜像名称和标签
 * - 端口号
 * - 路径
 */
object DockerfileHighlighter : LanguageHighlighter {
    
    private val instructions = setOf(
        "FROM", "MAINTAINER", "RUN", "CMD", "LABEL", "EXPOSE", "ENV", "ADD", "COPY",
        "ENTRYPOINT", "VOLUME", "USER", "WORKDIR", "ARG", "ONBUILD", "STOPSIGNAL",
        "HEALTHCHECK", "SHELL", "CROSS_BUILD"
    )
    
    // Shell 常用命令
    private val shellCommands = setOf(
        "apt-get", "apt", "yum", "dnf", "apk", "pip", "pip3", "npm", "yarn", "pnpm",
        "curl", "wget", "git", "make", "cmake", "gcc", "g++",
        "chmod", "chown", "mkdir", "rm", "cp", "mv", "ln", "cat", "echo", "touch",
        "tar", "unzip", "gzip", "gunzip",
        "cd", "ls", "pwd", "export", "source", "set", "unset",
        "sed", "awk", "grep", "find", "xargs",
        "install", "update", "upgrade", "clean", "autoremove",
        "useradd", "groupadd", "adduser", "addgroup"
    )

    // 预编译正则表达式
    private val commentPattern = Pattern.compile("#.*")
    
    // 指令（行首）
    private val instructionPattern = Pattern.compile("^\\s*([A-Z]+)(?=\\s|$)", Pattern.MULTILINE)
    
    // FROM 后的镜像名和标签（使用非捕获组替代 lookbehind）
    private val imagePattern = Pattern.compile("(?:FROM\\s+)([a-zA-Z0-9._/-]+)(?::([a-zA-Z0-9._-]+))?(?:\\s+AS\\s+([a-zA-Z0-9_-]+))?", Pattern.CASE_INSENSITIVE)
    
    // AS 别名
    private val asPattern = Pattern.compile("\\bAS\\s+([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE)
    
    // 字符串
    private val doubleStringPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val singleStringPattern = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'")
    
    // 变量
    private val variablePattern = Pattern.compile("\\$\\{[^}]+\\}")
    private val simpleVarPattern = Pattern.compile("\\$[a-zA-Z_][a-zA-Z0-9_]*")
    
    // ARG/ENV 定义的变量名（使用非捕获组替代 lookbehind）
    private val envDefPattern = Pattern.compile("(?:ARG|ENV)\\s+([a-zA-Z_][a-zA-Z0-9_]*)(?==|\\s|$)")
    
    // 端口号
    private val portPattern = Pattern.compile("\\b\\d{1,5}(?:/(?:tcp|udp))?\\b")
    
    // 路径
    private val pathPattern = Pattern.compile("(?:^|\\s)(/[a-zA-Z0-9_./-]+|\\./[a-zA-Z0-9_./-]*)")
    
    // Shell 命令
    private val shellCmdPattern = Pattern.compile("\\b([a-zA-Z][a-zA-Z0-9_-]*)\\b")
    
    // 命令选项 (-x, --option)
    private val optionPattern = Pattern.compile("\\s(-{1,2}[a-zA-Z][a-zA-Z0-9_-]*)(?=\\s|=|$)")
    
    // 运算符
    private val operatorPattern = Pattern.compile("&&|\\|\\||\\||;|\\\\$")
    
    // 等号
    private val equalPattern = Pattern.compile("=")
    
    // 括号
    private val bracketPattern = Pattern.compile("[\\[\\]{}()]")
    
    // JSON 数组语法 (用于 CMD, ENTRYPOINT)
    private val jsonArrayPattern = Pattern.compile("\\[\\s*\"[^\"]*\"(?:\\s*,\\s*\"[^\"]*\")*\\s*\\]")

    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)

        // 1. 注释（最高优先级）
        findMatches(commentPattern, code, TokenType.COMMENT, tokens, processed)

        // 2. Dockerfile 指令
        val instrMatcher = instructionPattern.matcher(code)
        while (instrMatcher.find()) {
            val start = instrMatcher.start(1)
            val end = instrMatcher.end(1)
            if (!processed[start]) {
                val word = instrMatcher.group(1)
                if (instructions.contains(word.uppercase())) {
                    tokens.add(Token(TokenType.KEYWORD, start, end, word))
                    for (i in start until end) processed[i] = true
                }
            }
        }

        // 3. 镜像名和标签 (FROM image:tag AS name)
        val imageMatcher = imagePattern.matcher(code)
        while (imageMatcher.find()) {
            // 镜像名
            val imageStart = imageMatcher.start(1)
            val imageEnd = imageMatcher.end(1)
            if (!processed[imageStart]) {
                tokens.add(Token(TokenType.CLASS_NAME, imageStart, imageEnd, imageMatcher.group(1)))
                for (i in imageStart until imageEnd) processed[i] = true
            }
            // 标签
            if (imageMatcher.group(2) != null) {
                val tagStart = imageMatcher.start(2)
                val tagEnd = imageMatcher.end(2)
                if (!processed[tagStart]) {
                    tokens.add(Token(TokenType.STRING, tagStart, tagEnd, imageMatcher.group(2)))
                    for (i in tagStart until tagEnd) processed[i] = true
                }
            }
            // AS 别名
            if (imageMatcher.group(3) != null) {
                val aliasStart = imageMatcher.start(3)
                val aliasEnd = imageMatcher.end(3)
                if (!processed[aliasStart]) {
                    tokens.add(Token(TokenType.VARIABLE, aliasStart, aliasEnd, imageMatcher.group(3)))
                    for (i in aliasStart until aliasEnd) processed[i] = true
                }
            }
        }

        // 4. AS 关键字
        val asMatcher = asPattern.matcher(code)
        while (asMatcher.find()) {
            val start = asMatcher.start()
            val end = asMatcher.start(1) // 只高亮 AS 部分
            val asEnd = start + 2
            if (!processed[start]) {
                tokens.add(Token(TokenType.KEYWORD, start, asEnd, "AS"))
                for (i in start until asEnd) processed[i] = true
            }
        }

        // 5. JSON 数组语法
        findMatches(jsonArrayPattern, code, TokenType.STRING, tokens, processed)

        // 6. 双引号字符串
        findMatches(doubleStringPattern, code, TokenType.STRING, tokens, processed)

        // 7. 单引号字符串
        findMatches(singleStringPattern, code, TokenType.STRING, tokens, processed)

        // 8. 环境变量定义的变量名
        val envDefMatcher = envDefPattern.matcher(code)
        while (envDefMatcher.find()) {
            val start = envDefMatcher.start(1)
            val end = envDefMatcher.end(1)
            if (!processed[start]) {
                tokens.add(Token(TokenType.PROPERTY, start, end, envDefMatcher.group(1)))
                for (i in start until end) processed[i] = true
            }
        }

        // 9. 变量引用 ${VAR}
        findMatches(variablePattern, code, TokenType.VARIABLE, tokens, processed)

        // 10. 简单变量 $VAR
        findMatches(simpleVarPattern, code, TokenType.VARIABLE, tokens, processed)

        // 11. 端口号
        findMatches(portPattern, code, TokenType.NUMBER, tokens, processed)

        // 12. 路径
        val pathMatcher = pathPattern.matcher(code)
        while (pathMatcher.find()) {
            val start = pathMatcher.start(1)
            val end = pathMatcher.end(1)
            if (!processed[start]) {
                tokens.add(Token(TokenType.STRING, start, end, pathMatcher.group(1)))
                for (i in start until end) processed[i] = true
            }
        }

        // 13. 命令选项 (--option, -o)
        val optionMatcher = optionPattern.matcher(code)
        while (optionMatcher.find()) {
            val start = optionMatcher.start(1)
            val end = optionMatcher.end(1)
            if (!processed[start]) {
                tokens.add(Token(TokenType.ATTRIBUTE, start, end, optionMatcher.group(1)))
                for (i in start until end) processed[i] = true
            }
        }

        // 14. Shell 命令
        val cmdMatcher = shellCmdPattern.matcher(code)
        while (cmdMatcher.find()) {
            val start = cmdMatcher.start(1)
            val end = cmdMatcher.end(1)
            if (!processed[start]) {
                val cmd = cmdMatcher.group(1)
                if (shellCommands.contains(cmd)) {
                    tokens.add(Token(TokenType.FUNCTION, start, end, cmd))
                    for (i in start until end) processed[i] = true
                }
            }
        }

        // 15. 运算符 (&&, ||, |, ;, \)
        findMatches(operatorPattern, code, TokenType.OPERATOR, tokens, processed)

        // 16. 等号
        findMatches(equalPattern, code, TokenType.OPERATOR, tokens, processed)

        // 17. 括号
        findMatches(bracketPattern, code, TokenType.PUNCTUATION, tokens, processed)

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