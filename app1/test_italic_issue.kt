import com.example.everytalk.ui.components.preprocessMarkdownForCustomCodeStyle

fun main() {
    // 测试用户报告的问题文本
    val problemText = """
        这是一个测试文本，包含*斜体文字*应该被正确处理。
        
        另一个测试：*这里也应该是斜体*但可能没有被正确处理。
        
        混合测试：**粗体文字**和*斜体文字*在同一行。
        
        边界测试：文本开头*斜体*和结尾*斜体*。
    """.trimIndent()
    
    println("原始文本：")
    println(problemText)
    println("\n===================\n")
    
    // 测试预处理函数
    val processedLight = preprocessMarkdownForCustomCodeStyle(problemText, false)
    println("浅色主题处理结果：")
    println(processedLight)
    println("\n===================\n")
    
    val processedDark = preprocessMarkdownForCustomCodeStyle(problemText, true)
    println("深色主题处理结果：")
    println(processedDark)
    
    // 测试斜体正则表达式
    val italicRegex = "(?<!\\*)\\*([^*\n]+?)\\*(?!\\*)".toRegex()
    println("\n===================\n")
    println("斜体正则表达式匹配结果：")
    italicRegex.findAll(problemText).forEach { match ->
        println("匹配到: '${match.value}' -> 内容: '${match.groupValues[1]}'")
    }
}