fun main() {
    val testInput = """<think>
We are given no specific problem in the user's message. The user just said "hi". Since there is no mathematical problem to solve, I should respond with a greeting. However, the instruction says to format mathematical expressions with KaTeX. But since there are none, I can just write a normal response.

But note: the assistant is expected to be focused on math problems. So I can remind the user that I'm here to help with math.

Let's respond appropriately.
</think>
Hello! 👋 It looks like your message just says "hi." If you have a math problem, concept, or equation you'd like help with, feel free to share it!

For example:"""

    println("原始输入:")
    println(testInput)
    println("\n" + "=".repeat(50))
    
    // 测试正则表达式
    val thinkingPatterns = listOf(
        "<think>[\\s\\S]*?</think>".toRegex(),
        "<thinking>[\\s\\S]*?</thinking>".toRegex()
    )
    
    for ((index, pattern) in thinkingPatterns.withIndex()) {
        println("测试正则表达式 ${index + 1}: ${pattern.pattern}")
        val match = pattern.find(testInput)
        if (match != null) {
            println("匹配成功!")
            println("匹配内容: ${match.value}")
            val content = match.value.removePrefix("<think>").removeSuffix("</think>").trim()
            println("提取的思考内容: $content")
        } else {
            println("匹配失败!")
        }
        println()
    }
    
    // 测试移除思考内容后的结果
    var contentWithoutThinking = testInput
    for (pattern in thinkingPatterns) {
        contentWithoutThinking = pattern.replace(contentWithoutThinking, "")
    }
    
    println("移除思考内容后:")
    println(contentWithoutThinking.trim())
}