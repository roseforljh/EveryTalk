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
    val pattern = "<think>[\\s\\S]*?</think>".toRegex()
    
    println("测试正则表达式: ${pattern.pattern}")
    val match = pattern.find(testInput)
    if (match != null) {
        println("匹配成功!")
        println("匹配内容: ${match.value}")
        val content = match.value.removePrefix("<think>").removeSuffix("</think>").trim()
        println("提取的思考内容: $content")
        
        // 测试移除思考内容后的结果
        val contentWithoutThinking = pattern.replace(testInput, "")
        println("\n移除思考内容后:")
        println(contentWithoutThinking.trim())
    } else {
        println("匹配失败!")
    }
}