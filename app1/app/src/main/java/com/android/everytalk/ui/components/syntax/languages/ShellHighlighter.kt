package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

object ShellHighlighter : LanguageHighlighter {
    private val keywords = setOf(
        "if", "then", "else", "elif", "fi", "case", "esac", "for", "while", "until",
        "do", "done", "in", "function", "time", "coproc", "return", "exit", "export", "local",
        "declare", "typeset", "readonly", "alias", "unalias", "set", "unset", "echo", "printf",
        "cd", "pwd", "ls", "grep", "cat", "chmod", "chown", "mkdir", "rm", "cp", "mv", "touch",
        "sudo", "apt", "yum", "brew", "git", "docker", "kubectl", "systemctl", "journalctl",
        "list", "select", "detail", "format", "clean", "assign", "remove", "get"
    )

    private val commandKeywords = setOf(
        "wmic", "diskpart", "msinfo32", "Get-PhysicalDisk", "Select-Object", "Get-Disk", "Get-Volume",
        "Get-CimInstance", "Format-Table", "Where-Object", "Sort-Object", "Measure-Object"
    )

    private val propertyKeywords = setOf(
        "FriendlyName", "MediaType", "Model", "SerialNumber", "Size", "caption", "model", "size",
        "InterfaceType", "HealthStatus", "OperationalStatus", "CanPool", "DeviceId", "Status"
    )

    private val booleanKeywords = setOf("true", "false")
    private val nullKeywords = setOf("null", "nil", "none")

    private val commentPattern = Pattern.compile("#.*")
    private val stringPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'")
    private val variablePattern = Pattern.compile("\\$[a-zA-Z_][a-zA-Z0-9_]*|\\$\\{[^}]+\\}")
    private val numberPattern = Pattern.compile("\\b(?:0x[0-9a-fA-F]+|\\d+(?:\\.\\d+)?)\\b")
    private val commandPattern = Pattern.compile("(?m)(^|(?<=[;|&()\\r\\n]))\\s*([A-Za-z][A-Za-z0-9.-]*-[A-Za-z0-9.-]+|[A-Za-z][A-Za-z0-9._-]*)")
    private val identifierPattern = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9._-]*\\b")
    private val operatorPattern = Pattern.compile("&&|\\|\\||>>|<<|\\||[=&!<>]+")
    private val punctuationPattern = Pattern.compile("[{}\\[\\]();,]")

    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)

        findMatches(commentPattern, code, TokenType.COMMENT, tokens, processed)
        findMatches(stringPattern, code, TokenType.STRING, tokens, processed)
        findMatches(variablePattern, code, TokenType.VARIABLE, tokens, processed)
        findMatches(numberPattern, code, TokenType.NUMBER, tokens, processed)
        findCommandMatches(code, tokens, processed)

        val identMatcher = identifierPattern.matcher(code)
        while (identMatcher.find()) {
            val start = identMatcher.start()
            val end = identMatcher.end()
            if (processed[start]) continue

            val word = identMatcher.groupText()
            when {
                commandKeywords.contains(word) -> addToken(TokenType.FUNCTION, start, end, word, tokens, processed)
                propertyKeywords.contains(word) -> addToken(TokenType.PROPERTY, start, end, word, tokens, processed)
                booleanKeywords.contains(word.lowercase()) -> addToken(TokenType.BOOLEAN, start, end, word, tokens, processed)
                nullKeywords.contains(word.lowercase()) -> addToken(TokenType.NULL, start, end, word, tokens, processed)
                keywords.contains(word.lowercase()) -> addToken(TokenType.KEYWORD, start, end, word, tokens, processed)
            }
        }

        findMatches(operatorPattern, code, TokenType.OPERATOR, tokens, processed)
        findMatches(punctuationPattern, code, TokenType.PUNCTUATION, tokens, processed)

        return tokens.sortedBy { it.start }
    }

    private fun findCommandMatches(code: String, tokens: MutableList<Token>, processed: BooleanArray) {
        val matcher = commandPattern.matcher(code)
        while (matcher.find()) {
            val start = matcher.start(2)
            val end = matcher.end(2)
            if (!processed[start]) {
                addToken(TokenType.FUNCTION, start, end, matcher.groupText(2), tokens, processed)
            }
        }
    }

    private fun findMatches(
        pattern: Pattern,
        code: String,
        type: TokenType,
        tokens: MutableList<Token>,
        processed: BooleanArray
    ) {
        val matcher = pattern.matcher(code)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            if (!processed[start]) {
                addToken(type, start, end, matcher.groupText(), tokens, processed)
            }
        }
    }

    private fun addToken(
        type: TokenType,
        start: Int,
        end: Int,
        text: String,
        tokens: MutableList<Token>,
        processed: BooleanArray
    ) {
        tokens.add(Token(type, start, end, text))
        for (i in start until end) {
            processed[i] = true
        }
    }
}
