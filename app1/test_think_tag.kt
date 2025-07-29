import com.example.everytalk.ui.screens.BubbleMain.Main.extractThinkingContent
import com.example.everytalk.ui.screens.BubbleMain.Main.parseMarkdownSegments
import com.example.everytalk.ui.screens.BubbleMain.Main.TextSegment

fun main() {
    // 测试包含<think>标签的内容
    val testInput = """<think>
这是一个思考过程，我需要分析用户的问题。
用户问的是关于AI的问题，我应该给出详细的回答。
</think>

你好！我是一个AI助手，很高兴为你服务。

这是正常的回复内容，应该显示在主要内容区域。"""

    println("原始输入:")
    println(testInput)
    println("\n" + "=".repeat(50))
    
    // 测试提取思考内容
    val thinkingContent = extractThinkingContent(testInput)
    println("\n提取的思考内容:")
    println(thinkingContent ?: "未找到思考内容")
    
    // 测试解析段落
    val segments = parseMarkdownSegments(testInput)
    println("\n解析的段落:")
    segments.forEachIndexed { index, segment ->
        when (segment) {
            is TextSegment.ThinkingContent -> {
                println("[$index] 思考内容: ${segment.content}")
            }
            is TextSegment.Normal -> {
                println("[$index] 普通文本: ${segment.text}")
            }
            else -> {
                println("[$index] 其他类型: $segment")
            }
        }
    }
}