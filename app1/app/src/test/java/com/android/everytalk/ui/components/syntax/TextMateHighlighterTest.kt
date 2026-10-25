package com.android.everytalk.ui.components.syntax

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMateHighlighterTest {
    private fun highlight(code: String, language: String, dark: Boolean = false): AnnotatedString =
        checkNotNull(TextMateHighlighter.highlightOrNull(code, language, if (dark) SyntaxHighlightTheme.Dark else SyntaxHighlightTheme.Light))

    /** 取最后覆盖的颜色，与 Compose 后添加样式覆盖前面样式的规则一致。 */
    private fun colorAt(result: AnnotatedString, text: String): Color {
        val offset = result.text.indexOf(text)
        check(offset >= 0)
        return result.spanStyles.last { offset >= it.start && offset < it.end }.item.color
    }

    @Test
    fun `all bundled languages highlight offline and preserve source exactly`() {
        val samples = mapOf(
            "html" to "<div class=\"card\">中文</div>",
            "css" to ".card { color: red; }",
            "js" to "const greeting = `Hello \${name}`;",
            "jsx" to "const Card = () => <div title=\"你好\">{name}</div>;",
            "ts" to "function greet(name: string): string { return name; }",
            "tsx" to "const Card = (props: {name: string}) => <div>{props.name}</div>;",
            "json" to "{\"name\": \"中文😀\", \"enabled\": true, \"count\": 42}",
            "kotlin" to "fun greet(name: String) = \"Hello, \$name\"",
        )
        samples.forEach { (language, code) ->
            listOf(false, true).forEach { dark ->
                val result = highlight(code, language, dark)
                assertEquals(language, code, result.text)
                assertTrue(language, result.spanStyles.size > 2)
                assertTrue(result.spanStyles.all { it.start >= 0 && it.end <= code.length })
            }
        }
        val exact = "\r\n  const text = \"中文😀\";  \r\n\t// 注释\r\n\r\n"
        assertEquals(exact, highlight(exact, "js").text)
        assertEquals("a\rb\n", highlight("a\rb\n", "js").text)
    }

    @Test
    fun `quotes containing comment markers do not become comments`() {
        val result = highlight("const url = \"https://example.com\"; // comment", "js")
        assertEquals(colorAt(result, "https"), colorAt(result, "//example"))
        assertNotEquals(colorAt(result, "https"), colorAt(result, "// comment"))
        assertNotEquals(colorAt(result, "https"), colorAt(result, "const"))
    }

    @Test
    fun `html loads embedded javascript and css grammar`() {
        val result = highlight("<style>.card { color: red; }</style>\n<script>const value = \"hello\";</script>", "html")
        assertNotEquals(colorAt(result, "const"), colorAt(result, "hello"))
        val css = highlight(".card { color: red; }", "css")
        assertEquals(colorAt(css, "color"), colorAt(result, "color"))
        assertEquals(colorAt(css, "red"), colorAt(result, "red"))
        assertNotEquals(colorAt(result, "style"), colorAt(result, "color"))
    }

    @Test
    fun `multiline state and themes are applied without leaking between calls`() {
        val code = "/* first\nsecond */\nconst value = 42;"
        val light = highlight(code, "js")
        assertEquals(colorAt(light, "first"), colorAt(light, "second"))
        assertNotEquals(colorAt(light, "second"), colorAt(light, "const"))
        assertNotEquals(colorAt(light, "const"), colorAt(highlight(code, "js", true), "const"))
        assertNotEquals(colorAt(highlight("/* unfinished", "js"), "unfinished"), colorAt(highlight("const value = 1;", "js"), "const"))
    }

    @Test
    fun `aliases use textmate and unsupported or oversized inputs keep legacy fallback`() {
        listOf(" TSX ", "htm", "nodejs", "jsonc", "kt", "kts").forEach {
            assertNotNull(TextMateHighlighter.highlightOrNull("sample", it, SyntaxHighlightTheme.Light))
        }
        assertNull(TextMateHighlighter.highlightOrNull("select * from table;", "sql", SyntaxHighlightTheme.Light))
        assertNull(TextMateHighlighter.highlightOrNull("x".repeat(2_001), "js", SyntaxHighlightTheme.Light))
        assertEquals("SELECT value", SyntaxHighlighter.highlight("SELECT value", "sql", SyntaxHighlightTheme.Light).text)
        assertTrue(SyntaxHighlighter.highlight("SELECT value", "sql", SyntaxHighlightTheme.Light).spanStyles.size > 1)
        assertEquals("plain", SyntaxHighlighter.highlight("plain", "unknown", SyntaxHighlightTheme.Light).text)
    }

    @Test
    fun `shared parser safely handles concurrent code blocks`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 16).map { index -> Callable {
                val code = "const value$index = \"text$index\";"
                assertEquals(code, highlight(code, "js", index % 2 == 0).text)
            } }
            executor.invokeAll(jobs).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }
}
