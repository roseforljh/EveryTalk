package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

object ShellHighlighter : LanguageHighlighter {
    private val keywords = setOf(
        "if", "then", "else", "elif", "fi", "case", "esac", "for", "select", "while", "until",
        "do", "done", "in", "function", "time", "coproc", "return", "exit", "export", "local",
        "declare", "typeset", "readonly", "alias", "unalias", "set", "unset", "echo", "printf",
        "cd", "pwd", "ls", "grep", "cat", "chmod", "chown", "mkdir", "rm", "cp", "mv", "touch",
        "sudo", "apt", "yum", "brew", "git", "docker", "kubectl", "systemctl", "journalctl"
    )

    private val commentPattern = Pattern.compile("#.*")
    private val stringPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'")
    private val variablePattern = Pattern.compile("\\$[a-zA-Z_][a-zA-Z0-9_]*|\\$\\{[^}]+\\}")
    private val identifierPattern = Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_-]*\\b")
    private val operatorPattern = Pattern.compile("&&|\\|\\||\\|\\||>>|<<|[=&!|<>]+")

    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)

        findMatches(commentPattern, code, TokenType.COMMENT, tokens, processed)
        findMatches(stringPattern, code, TokenType.STRING, tokens, processed)
        findMatches(variablePattern, code, TokenType.VARIABLE, tokens, processed)

        val identMatcher = identifierPattern.matcher(code)
        while (identMatcher.find()) {
            val start = identMatcher.start()
            val end = identMatcher.end()
            if (!processed[start]) {
                val word = identMatcher.group()
                if (keywords.contains(word)) {
                    tokens.add(Token(TokenType.KEYWORD, start, end, word))
                    for (i in start until end) processed[i] = true
                }
            }
        }

        findMatches(operatorPattern, code, TokenType.OPERATOR, tokens, processed)
        findMatches(Pattern.compile("[{}\\[\\]();]"), code, TokenType.PUNCTUATION, tokens, processed)

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