package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.TokenType
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellHighlighterTest {

    @Test
    fun `powershell pipeline command should highlight command parameters properties and strings`() {
        val code = "Get-PhysicalDisk | Select-Object FriendlyName, MediaType, Model, SerialNumber, Size"

        val tokens = ShellHighlighter.tokenize(code)
        println(tokens.joinToString(separator = " | ") { "${it.text}:${it.type}" })

        assertTrue(tokens.any { it.text == "Get-PhysicalDisk" && it.type == TokenType.FUNCTION })
        assertTrue(tokens.any { it.text == "Select-Object" && it.type == TokenType.FUNCTION })
        assertTrue(tokens.any { it.text == "|" && it.type == TokenType.OPERATOR })
        assertTrue(tokens.count { it.type == TokenType.PROPERTY } >= 5)
    }

    @Test
    fun `cmd builtins and diskpart subcommands should be highlighted`() {
        val code = "wmic diskdrive get model,caption,size\ndiskpart\nlist disk\nselect disk 0\ndetail disk"

        val tokens = ShellHighlighter.tokenize(code)
        println(tokens.joinToString(separator = " | ") { "${it.text}:${it.type}" })

        assertTrue(tokens.any { it.text == "wmic" && it.type == TokenType.FUNCTION })
        assertTrue(tokens.any { it.text == "diskpart" && it.type == TokenType.FUNCTION })
        assertTrue(tokens.any { it.text == "list" && it.type == TokenType.FUNCTION })
        assertTrue(tokens.any { it.text == "select" && it.type == TokenType.FUNCTION })
        assertTrue(tokens.any { it.text == "detail" && it.type == TokenType.FUNCTION })
        assertTrue(tokens.any { it.text == "0" && it.type == TokenType.NUMBER })
    }
}
