fun main() {
    val testInput = """<think>
We are given no specific problem in the user's message. The user just said "hi". Since there is no mathematical problem to solve, I should respond with a greeting. However, the instruction says to format mathematical expressions with KaTeX. But since there are none, I can just write a normal response.

But note: the assistant is expected to be focused on math problems. So I can remind the user that I'm here to help with math.

Let's respond appropriately.
</think>
Hello! 👋 It looks like your message just says "hi." If you have a math problem, concept, or equation you'd like help with, feel free to share it!

For example:"""

    println("Original input:")
    println(testInput)
    println("\n" + "=".repeat(50) + "\n")
    
    // Test extractThinkingContent
    val thinkingPatterns = listOf(
        "<think>[\\s\\S]*?<\\/think>".toRegex(),
        "<thinking>[\\s\\S]*?<\\/thinking>".toRegex(),
        "\\*\\*思考过程\\*\\*[\\s\\S]*?(?=\\n\\n|\\*\\*|$)".toRegex(),
        "思考：[\\s\\S]*?(?=\\n\\n|$)".toRegex()
    )
    
    for ((index, pattern) in thinkingPatterns.withIndex()) {
        val match = pattern.find(testInput)
        if (match != null) {
            println("Pattern $index matched:")
            println("Match: ${match.value}")
            val content = match.value
            val extracted = when {
                content.startsWith("<think>") -> content.removePrefix("<think>").removeSuffix("</think>").trim()
                content.startsWith("<thinking>") -> content.removePrefix("<thinking>").removeSuffix("</thinking>").trim()
                content.startsWith("**思考过程**") -> content.removePrefix("**思考过程**").trim()
                content.startsWith("思考：") -> content.removePrefix("思考：").trim()
                else -> content.trim()
            }
            println("Extracted: $extracted")
            break
        }
    }
    
    // Test content removal
    var contentWithoutThinking = testInput
    for (pattern in thinkingPatterns) {
        contentWithoutThinking = pattern.replace(contentWithoutThinking, "")
    }
    
    println("\nContent after removing thinking tags:")
    println(contentWithoutThinking)
}