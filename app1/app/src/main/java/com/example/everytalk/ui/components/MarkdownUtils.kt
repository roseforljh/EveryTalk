package com.example.everytalk.ui.components

// 统一的基础 Markdown 规范化（字形 -> 标题/列表容错）
fun normalizeBasicMarkdown(text: String): String {
    if (text.isEmpty()) return text
    var t = normalizeMarkdownGlyphs(text)
    t = normalizeHeadingSpacing(t)
    t = normalizeListSpacing(t)
    return t
}

/**
 * 标题容错：
 * 1) 行内出现的 ##... -> 强制换行到行首
 * 2) 行首 #{1..6} 后若未跟空格则补空格（###标题 -> ### 标题）
 */
private fun normalizeHeadingSpacing(md: String): String {
    if (md.isEmpty()) return md
    var text = md
    // 将“行内标题”移到新的一行（避免被当作普通文本）
    val newlineBefore = Regex("(?m)([^\\n])\\s*(#{1,6})(?=\\S)")
    text = text.replace(newlineBefore, "$1\n$2")
    // 标题后补空格（行首 #... 与后续字符之间补空格）
    val spaceAfter = Regex("(?m)^(#{1,6})([^#\\s])")
    text = text.replace(spaceAfter, "$1 $2")
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