package com.example.everytalk.ui.components

// 统一的基础 Markdown 规范化（字形 -> 标题/列表/表格容错）
fun normalizeBasicMarkdown(text: String): String {
    if (text.isEmpty()) return text
    var t = normalizeMarkdownGlyphs(text)
    t = normalizeHeadingSpacing(t)
    t = normalizeListSpacing(t)
    t = normalizeTableSpacing(t) // 🎯 新增：表格格式化
    t = normalizeDetachedBulletPoints(t) // 🔧 新增：处理分离式列表项目符号
    return t
}

/**
 * 标题容错：
 * 1) 行内出现的 ##... -> 强制换行到行首
 * 2) 行首 #{1..6} 后若未跟空格则补空格（###标题 -> ### 标题）
 * 3) 强化：确保标题前后都有空行分隔
 */
private fun normalizeHeadingSpacing(md: String): String {
    if (md.isEmpty()) return md
    var text = md
    
    // 将"行内标题"移到新的一行（避免被当作普通文本）
    val newlineBefore = Regex("(?m)([^\\n])\\s*(#{1,6})(?=\\S)")
    text = text.replace(newlineBefore, "$1\n\n$2")
    
    // 标题后补空格（行首 #... 与后续字符之间补空格）
    val spaceAfter = Regex("(?m)^(#{1,6})([^#\\s])")
    text = text.replace(spaceAfter, "$1 $2")
    
    // 🎯 新增：确保标题前后都有空行（除非在文档开头/结尾）
    val headingWithSpacing = Regex("(?m)^(#{1,6}\\s+.*)$")
    text = text.replace(headingWithSpacing) { match ->
        val heading = match.value
        "\n$heading\n"
    }.replace("^\n+".toRegex(), "").replace("\n+$".toRegex(), "")
    
    return text
}

// 在非代码围栏内规范化列表前缀：
// - 将开头的 *, -, + 后若无空格补空格（排除以 ** 开头的粗体场景）
// - 有序列表的 "1." 或 "1)" 后补空格
// - 将常见的项目符号（• · ・ ﹒ ∙ 以及全角＊﹡）规范为标准 Markdown 列表
private fun normalizeListSpacing(md: String): String {
    if (md.isEmpty()) return md
    val lines = md.split("\n").toMutableList()
    var insideFence = false
    for (i in lines.indices) {
        var line = lines[i]
        if (line.contains("```")) {
            val count = "```".toRegex().findAll(line).count()
            if (count % 2 == 1) insideFence = !insideFence
            lines[i] = line
            continue
        }
        if (!insideFence) {
            // 全角星号转半角并作为列表处理
            line = line.replace(Regex("^(\\s*)[＊﹡]([^\\s])"), "$1* $2")
            // • · ・ ﹒ ∙ 作为项目符号
            line = line.replace(Regex("^(\\s*)[•·・﹒∙]([^\\s])"), "$1- $2")
            // 🔧 修复：处理单独的星号作为列表项目符号
            line = line.replace(Regex("^(\\s*)\\*\\s*$"), "$1- ")
            // 无序列表符号后补空格（避免 ** 触发）
            line = line.replace(Regex("^(\\s*)([*+\\-])(?![ *+\\-])(\\S)"), "$1$2 $3")
            // 有序列表（1. 或 1)）后补空格
            line = line.replace(Regex("^(\\s*)(\\d+)([.)])(\\S)"), "$1$2$3 $4")
            lines[i] = line
        }
    }
    return lines.joinToString("\n")
}

/**
 * 🎯 新增：表格格式化 - 确保表格能正确识别和渲染
 */
private fun normalizeTableSpacing(md: String): String {
    if (md.isEmpty()) return md
    val lines = md.split("\n").toMutableList()
    var insideFence = false
    
    for (i in lines.indices) {
        var line = lines[i]
        
        // 跳过代码围栏内的内容
        if (line.contains("```")) {
            val count = "```".toRegex().findAll(line).count()
            if (count % 2 == 1) insideFence = !insideFence
            continue
        }
        
        if (!insideFence && line.contains("|")) {
            // 规范化表格分隔符
            line = line.replace("｜", "|") // 全角竖线
                      .replace("│", "|") // 框线字符
                      .replace("┃", "|") // 粗框线字符
            
            // 确保表格行前后有适当的空格
            if (line.trim().startsWith("|") && line.trim().endsWith("|")) {
                // 这是一个标准的表格行
                lines[i] = line
            } else if (line.contains("|")) {
                // 包含竖线但格式不标准，尝试修复
                val trimmed = line.trim()
                if (!trimmed.startsWith("|")) {
                    line = "| $trimmed"
                }
                if (!trimmed.endsWith("|")) {
                    line = "$line |"
                }
                lines[i] = line
            }
        }
    }
    
    return lines.joinToString("\n")
}

/**
 * 规范化常见 Markdown 符号（最小化处理）：将全角星号替换为半角，
 * 以便 **加粗** / *斜体* 在 Compose MarkdownText 中正确识别。
 * 不处理反引号与代码块围栏。
 */
internal fun normalizeMarkdownGlyphs(text: String): String {
    if (text.isEmpty()) return text
    return text
        // 去除常见不可见字符，避免打断 **bold** / *italic*
        .replace("\u200B", "") // ZERO WIDTH SPACE
        .replace("\u200C", "") // ZERO WIDTH NON-JOINER
        .replace("\u200D", "") // ZERO WIDTH JOINER
        .replace("\uFEFF", "") // ZERO WIDTH NO-BREAK SPACE (BOM)
        // 统一星号
        .replace('＊', '*')  // 全角星号 -> 半角
        .replace('﹡', '*')  // 小型星号 -> 半角
}

/**
 * 🔧 新增：处理分离式列表项目符号
 * 将单独一行的 * 与下一行的内容合并成标准的Markdown列表项
 */
private fun normalizeDetachedBulletPoints(md: String): String {
    if (md.isEmpty()) return md
    
    val lines = md.split("\n").toMutableList()
    val result = mutableListOf<String>()
    var i = 0
    
    while (i < lines.size) {
        val currentLine = lines[i].trim()
        
        // 检查是否为单独的项目符号
        if (currentLine == "*" || currentLine == "-" || currentLine == "+") {
            // 查找下一个非空行作为列表内容
            var nextContentIndex = i + 1
            while (nextContentIndex < lines.size && lines[nextContentIndex].trim().isEmpty()) {
                nextContentIndex++
            }
            
            if (nextContentIndex < lines.size) {
                val nextContent = lines[nextContentIndex].trim()
                if (nextContent.isNotEmpty()) {
                    // 合并为标准的Markdown列表项
                    result.add("- $nextContent")
                    // 跳过已处理的行
                    i = nextContentIndex + 1
                    continue
                }
            }
        }
        
        // 不是项目符号或找不到对应内容，保持原样
        result.add(lines[i])
        i++
    }
    
    return result.joinToString("\n")
}