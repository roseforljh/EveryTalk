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

/**
 * 将 Markdown 文本转换为纯文本，移除所有 Markdown 语法标记
 * 用于在文本选择对话框中显示可选择的纯文本内容
 */
fun markdownToPlainText(markdown: String): String {
    if (markdown.isEmpty()) return markdown
    
    var text = markdown
    
    // 1. 移除代码块（保留内容）
    text = text.replace(Regex("```[\\w]*\\n([\\s\\S]*?)```"), "$1")
    text = text.replace(Regex("~~~[\\w]*\\n([\\s\\S]*?)~~~"), "$1")
    
    // 2. 移除内联代码反引号
    text = text.replace(Regex("`([^`]+)`"), "$1")
    
    // 3. 移除标题标记
    text = text.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
    
    // 4. 移除加粗和斜体
    text = text.replace(Regex("\\*\\*\\*([^*]+)\\*\\*\\*"), "$1") // 粗斜体
    text = text.replace(Regex("___([^_]+)___"), "$1") // 粗斜体
    text = text.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1") // 加粗
    text = text.replace(Regex("__([^_]+)__"), "$1") // 加粗
    text = text.replace(Regex("\\*([^*]+)\\*"), "$1") // 斜体
    text = text.replace(Regex("_([^_]+)_"), "$1") // 斜体
    
    // 5. 移除删除线
    text = text.replace(Regex("~~([^~]+)~~"), "$1")
    
    // 6. 移除链接，保留链接文本
    text = text.replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
    
    // 7. 移除图片标记
    text = text.replace(Regex("!\\[([^\\]]*)\\]\\([^)]+\\)"), "$1")
    
    // 8. 移除引用标记
    text = text.replace(Regex("^>\\s+", RegexOption.MULTILINE), "")
    
    // 9. 移除无序列表标记
    text = text.replace(Regex("^[\\s]*[-*+]\\s+", RegexOption.MULTILINE), "")
    
    // 10. 移除有序列表标记
    text = text.replace(Regex("^[\\s]*\\d+\\.\\s+", RegexOption.MULTILINE), "")
    
    // 11. 移除水平分隔线
    text = text.replace(Regex("^[-*_]{3,}$", RegexOption.MULTILINE), "")
    
    // 12. 移除表格分隔符行
    text = text.replace(Regex("^\\|?[\\s]*:?-+:?[\\s]*\\|.*$", RegexOption.MULTILINE), "")
    
    // 13. 简化表格行（移除管道符，保留内容）
    text = text.replace(Regex("^\\|(.*)\\|$", RegexOption.MULTILINE), "$1")
    text = text.replace(Regex("\\|"), " ")
    
    // 14. 清理多余的空行（超过2个连续空行的压缩为2个）
    text = text.replace(Regex("\\n{3,}"), "\n\n")
    
    // 15. 清理首尾空白
    text = text.trim()
    
    return text
}
