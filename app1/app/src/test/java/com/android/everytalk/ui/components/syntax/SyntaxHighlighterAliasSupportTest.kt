package com.android.everytalk.ui.components.syntax

import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxHighlighterAliasSupportTest {

    @Test
    fun `cmd and powershell aliases should be supported`() {
        assertTrue(SyntaxHighlighter.isSupported("cmd"))
        assertTrue(SyntaxHighlighter.isSupported("bat"))
        assertTrue(SyntaxHighlighter.isSupported("batch"))
        assertTrue(SyntaxHighlighter.isSupported("powershell"))
        assertTrue(SyntaxHighlighter.isSupported("ps1"))
        assertTrue(SyntaxHighlighter.isSupported("pwsh"))
    }

    @Test
    fun `existing shell aliases should stay supported`() {
        assertTrue(SyntaxHighlighter.isSupported("bash"))
        assertTrue(SyntaxHighlighter.isSupported("sh"))
        assertTrue(SyntaxHighlighter.isSupported("shell"))
        assertTrue(SyntaxHighlighter.isSupported("zsh"))
    }
}
