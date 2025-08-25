/**
 * 测试当前正则表达式是否能匹配图片中的代码块格式
 */

fun main() {
    // 当前使用的正则表达式
    val codeBlockRegex = "```([a-zA-Z0-9+#-]*)`?\\s*\\n([\\s\\S]*?)\\n```".toRegex()
    
    // 测试各种可能的代码块格式
    val testCases = listOf(
        // 标准格式
        "```kotlin\nfun main() {\n    println(\"Hello\")\n}\n```",
        
        // 后端修复后的格式（多一个反引号）
        "```kotlin`\nfun main() {\n    println(\"Hello\")\n}\n```",
        
        // 无语言标识符
        "```\nfun main() {\n    println(\"Hello\")\n}\n```",
        
        // 无语言标识符 + 多反引号
        "```\`\nfun main() {\n    println(\"Hello\")\n}\n```",
        
        // 图片中可能的格式（无换行开始）
        "```kotlin\nfun main() { println(\"Hello\") }```",
        
        // 图片中可能的格式（带额外空格）
        "``` kotlin \nfun main() {\n    println(\"Hello\")\n}\n```",
        
        // 可能的内联格式
        "```kotlin fun main() { println(\"Hello\") } ```",
        
        // 实际从图片中观察到的格式（模拟）
        "```\nPowershell\n# 设置WARP为Desktop模式\n```",
        
        "```powershell\n# 设置WARP为Desktop模式\nwarp-cli set-mode warp\n```"
    )
    
    println("=== 测试代码块正则表达式匹配 ===")
    println("当前正则表达式: $codeBlockRegex")
    println()
    
    testCases.forEachIndexed { index, testCase ->
        println("测试用例 ${index + 1}:")
        println("输入: ${testCase.replace("\n", "\\n")}")
        
        val matches = codeBlockRegex.findAll(testCase).toList()
        if (matches.isNotEmpty()) {
            matches.forEach { match ->
                println("✓ 匹配成功!")
                println("  语言: '${match.groupValues[1]}'")
                println("  代码: '${match.groupValues[2]}'")
            }
        } else {
            println("✗ 匹配失败!")
        }
        println()
    }
    
    // 测试改进的正则表达式
    println("=== 测试改进的正则表达式 ===")
    
    // 更宽松的正则表达式，支持更多格式
    val improvedRegex = "```\\s*([a-zA-Z0-9+#-]*)`?\\s*\\n?([\\s\\S]*?)\\n?```".toRegex()
    println("改进的正则表达式: $improvedRegex")
    println()
    
    testCases.forEachIndexed { index, testCase ->
        println("测试用例 ${index + 1}:")
        val matches = improvedRegex.findAll(testCase).toList()
        if (matches.isNotEmpty()) {
            matches.forEach { match ->
                println("✓ 改进版匹配成功!")
                println("  语言: '${match.groupValues[1]}'")
                println("  代码: '${match.groupValues[2]}'")
            }
        } else {
            println("✗ 改进版匹配失败!")
        }
        println()
    }
}