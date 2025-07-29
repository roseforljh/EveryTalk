package com.example.everytalk.test

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.example.everytalk.util.CodeHighlighter

fun testCodeHighlighter() {
    val testCode = """
        // 这是注释
        fun main() {
            val message = "Hello, World!"
            val number = 42
            println(message)
        }
    """.trimIndent()
    
    val result = CodeHighlighter.highlightToAnnotatedString(testCode, "kotlin")
    
    println("原始代码:")
    println(testCode)
    println("\n高亮结果:")
    println("文本: ${result.text}")
    println("样式数量: ${result.spanStyles.size}")
    
    result.spanStyles.forEach { spanStyle ->
        val start = spanStyle.start
        val end = spanStyle.end
        val text = result.text.substring(start, end)
        val color = spanStyle.item.color
        println("文本片段: '$text' -> 颜色: $color (位置: $start-$end)")
    }
}