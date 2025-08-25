// 测试代码块解析的简单脚本
fun main() {
    // 模拟后端修复后的代码块格式
    val backendOutput = """这是一些文本
```bash`
# Debian/Ubuntu 举例 
curl -fsSL https://pkg.cloudclient.com/install.deb.sh
```
更多文本内容"""
    
    // 使用修复后的正则表达式
    val codeBlockRegex = "```([a-zA-Z0-9+#-]*)`?\\s*\\n([\\s\\S]*?)\\n```".toRegex()
    
    println("测试后端输出格式解析:")
    println("原始内容: $backendOutput")
    println()
    
    val matches = codeBlockRegex.findAll(backendOutput)
    matches.forEach { match ->
        val language = match.groupValues[1].trim()
        val codeContent = match.groupValues[2].trim()
        println("找到代码块:")
        println("语言: '$language'")
        println("代码内容: '$codeContent'")
        println("完整匹配: '${match.value}'")
        println()
    }
    
    // 测试原始格式（没有额外反引号）
    val normalOutput = """这是一些文本
```bash
# 正常格式的代码块
echo "Hello World"
```
更多文本内容"""
    
    println("测试正常格式解析:")
    println("原始内容: $normalOutput")
    println()
    
    val normalMatches = codeBlockRegex.findAll(normalOutput)
    normalMatches.forEach { match ->
        val language = match.groupValues[1].trim()
        val codeContent = match.groupValues[2].trim()
        println("找到代码块:")
        println("语言: '$language'")
        println("代码内容: '$codeContent'")
        println("完整匹配: '${match.value}'")
        println()
    }
}