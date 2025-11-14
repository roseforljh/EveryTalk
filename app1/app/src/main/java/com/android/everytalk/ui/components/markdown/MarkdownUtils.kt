package com.android.everytalk.ui.components.markdown

/**
 * 简化的 Markdown 工具函数（搬迁版）
 * 原文件位置：ui/components/MarkdownUtils.kt
 * 说明：为实现模块化与分类管理，已迁移到 markdown/ 目录。
 */

// 直接返回原始文本
fun normalizeMarkdownGlyphs(text: String): String = text

// 直接返回原始文本
fun normalizeBasicMarkdown(text: String): String = text

// 预编译的 Markdown 正则，避免在热点路径反复创建 Regex 对象
private val CODE_BLOCK_BACKTICK_REGEX = Regex("```[\\w]*\\n([\\s\\S]*?)```")
private val CODE_BLOCK_TILDE_REGEX = Regex("~~~[\\w]*\\n([\\s\\S]*?)~~~")
private val INLINE_CODE_REGEX = Regex("`([^`]+)`")
private val HEADING_REGEX = Regex("^#{1,6}\\s+", RegexOption.MULTILINE)
private val BOLD_ITALIC_ASTERISK_REGEX = Regex("\\*\\*\\*([^*]+)\\*\\*\\*")
private val BOLD_ITALIC_UNDERSCORE_REGEX = Regex("___([^_]+)___")
private val BOLD_ASTERISK_REGEX = Regex("\\*\\*([^*]+)\\*\\*")
private val BOLD_UNDERSCORE_REGEX = Regex("__([^_]+)__")
private val ITALIC_ASTERISK_REGEX = Regex("\\*([^*]+)\\*")
private val ITALIC_UNDERSCORE_REGEX = Regex("_([^_]+)_")
private val STRAY_DOUBLE_ASTERISK_REGEX = Regex("\\*\\*")
private val STRAY_ASTERISK_REGEX = Regex("\\*")
private val STRAY_DOUBLE_UNDERSCORE_REGEX = Regex("__")
private val STRAY_UNDERSCORE_REGEX = Regex("_")
private val STRIKETHROUGH_REGEX = Regex("~~([^~]+)~~")
private val LINK_REGEX = Regex("\\[([^\\]]+)\\]\\([^)]+\\)")
private val IMAGE_REGEX = Regex("!\\[([^\\]]*)\\]\\([^)]+\\)")
private val BLOCKQUOTE_REGEX = Regex("^>\\s+", RegexOption.MULTILINE)
private val UNORDERED_LIST_REGEX = Regex("^[\\s]*[-*+]\\s+", RegexOption.MULTILINE)
private val ORDERED_LIST_REGEX = Regex("^[\\s]*\\d+\\.\\s+", RegexOption.MULTILINE)
private val HORIZONTAL_RULE_REGEX = Regex("^[-*_]{3,}$", RegexOption.MULTILINE)
private val TABLE_SEPARATOR_REGEX = Regex("^\\|?[\\s]*:?-+:?[\\s]*\\|.*$", RegexOption.MULTILINE)
private val TABLE_ROW_REGEX = Regex("^\\|(.*)\\|$", RegexOption.MULTILINE)
private val EXCESSIVE_EMPTY_LINES_REGEX = Regex("\\n{3,}")

/**
 * 将 Markdown 文本转换为纯文本，移除所有 Markdown 语法标记
 * 用于在文本选择对话框中显示可选择的纯文本内容
 */
fun markdownToPlainText(markdown: String): String {
    if (markdown.isEmpty()) return markdown

    var text = markdown

    // 1. 移除代码块（保留内容）
    text = text.replace(CODE_BLOCK_BACKTICK_REGEX, "$1")
    text = text.replace(CODE_BLOCK_TILDE_REGEX, "$1")

    // 2. 移除内联代码反引号
    text = text.replace(INLINE_CODE_REGEX, "$1")

    // 3. 移除标题标记
    text = text.replace(HEADING_REGEX, "")

    // 4. 移除加粗和斜体
    text = text.replace(BOLD_ITALIC_ASTERISK_REGEX, "$1") // 粗斜体
    text = text.replace(BOLD_ITALIC_UNDERSCORE_REGEX, "$1") // 粗斜体
    text = text.replace(BOLD_ASTERISK_REGEX, "$1") // 加粗
    text = text.replace(BOLD_UNDERSCORE_REGEX, "$1") // 加粗
    text = text.replace(ITALIC_ASTERISK_REGEX, "$1") // 斜体
    text = text.replace(ITALIC_UNDERSCORE_REGEX, "$1") // 斜体

    // 4.1 移除孤立的 ** 和 * 标记（不成对的）
    text = text.replace(STRAY_DOUBLE_ASTERISK_REGEX, "") // 移除所有剩余的 **
    text = text.replace(STRAY_ASTERISK_REGEX, "") // 移除所有剩余的 *
    text = text.replace(STRAY_DOUBLE_UNDERSCORE_REGEX, "") // 移除所有剩余的 __
    text = text.replace(STRAY_UNDERSCORE_REGEX, "") // 移除所有剩余的 _

    // 5. 移除删除线
    text = text.replace(STRIKETHROUGH_REGEX, "$1")

    // 6. 移除链接，保留链接文本
    text = text.replace(LINK_REGEX, "$1")

    // 7. 移除图片标记
    text = text.replace(IMAGE_REGEX, "$1")

    // 8. 移除引用标记
    text = text.replace(BLOCKQUOTE_REGEX, "")

    // 9. 移除无序列表标记
    text = text.replace(UNORDERED_LIST_REGEX, "")

    // 10. 移除有序列表标记
    text = text.replace(ORDERED_LIST_REGEX, "")

    // 11. 移除水平分隔线
    text = text.replace(HORIZONTAL_RULE_REGEX, "")

    // 12. 移除表格分隔符行
    text = text.replace(TABLE_SEPARATOR_REGEX, "")

    // 13. 简化表格行（移除管道符，保留内容）
    text = text.replace(TABLE_ROW_REGEX, "$1")
    text = text.replace('|', ' ')

    // 14. 清理多余的空行（超过2个连续空行的压缩为2个）
    text = text.replace(EXCESSIVE_EMPTY_LINES_REGEX, "\n\n")

    // 15. 清理首尾空白
    text = text.trim()

    return text
}
