package com.android.everytalk.ui.components.markdown

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.annotation.ColorInt
import io.noties.prism4j.Prism4j
import io.noties.prism4j.annotations.PrismBundle

/**
 * 自定义语法高亮主题
 * 支持深色和浅色模式
 */
abstract class SyntaxHighlightTheme : io.noties.markwon.syntax.Prism4jTheme {
    
    protected abstract fun getColor(type: String): Int
    
    override fun apply(
        language: String,
        syntax: Prism4j.Syntax,
        builder: SpannableStringBuilder,
        start: Int,
        end: Int
    ) {
        val color = getColor(syntax.type())
        if (color != 0) {
            builder.setSpan(
                ForegroundColorSpan(color),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
    
    companion object {
        /**
         * 创建深色主题
         */
        fun createDark(): SyntaxHighlightTheme = DarkTheme()
        
        /**
         * 创建浅色主题
         */
        fun createLight(): SyntaxHighlightTheme = LightTheme()
    }
}

/**
 * 深色主题 - 类似 VS Code Dark+ 主题
 */
private class DarkTheme : SyntaxHighlightTheme() {
    
    @ColorInt
    override fun background(): Int = Color.parseColor("#1E1E1E")
    
    @ColorInt
    override fun textColor(): Int = Color.parseColor("#D4D4D4")
    
    override fun getColor(type: String): Int = when (type) {
        // 关键字 - 蓝色
        "keyword" -> Color.parseColor("#569CD6")
        
        // 字符串 - 橙色
        "string" -> Color.parseColor("#CE9178")
        
        // 注释 - 绿色
        "comment" -> Color.parseColor("#6A9955")
        
        // 函数名 - 黄色
        "function" -> Color.parseColor("#DCDCAA")
        
        // 类名 - 青色
        "class-name" -> Color.parseColor("#4EC9B0")
        
        // 数字 - 浅绿色
        "number" -> Color.parseColor("#B5CEA8")
        
        // 操作符 - 白色
        "operator" -> Color.parseColor("#D4D4D4")
        
        // 标点符号 - 白色
        "punctuation" -> Color.parseColor("#D4D4D4")
        
        // 变量 - 浅蓝色
        "variable" -> Color.parseColor("#9CDCFE")
        
        // 常量 - 浅蓝色
        "constant" -> Color.parseColor("#4FC1FF")
        
        // 布尔值 - 蓝色
        "boolean" -> Color.parseColor("#569CD6")
        
        // 属性 - 浅蓝色
        "property" -> Color.parseColor("#9CDCFE")
        
        // 标签 - 青色
        "tag" -> Color.parseColor("#4EC9B0")
        
        // 属性名 - 浅蓝色
        "attr-name" -> Color.parseColor("#9CDCFE")
        
        // 属性值 - 橙色
        "attr-value" -> Color.parseColor("#CE9178")
        
        // 正则表达式 - 红色
        "regex" -> Color.parseColor("#D16969")
        
        // 重要 - 粉色
        "important" -> Color.parseColor("#C586C0")
        
        // 内置 - 青色
        "builtin" -> Color.parseColor("#4EC9B0")
        
        // 注解 - 黄色
        "annotation" -> Color.parseColor("#DCDCAA")
        
        else -> 0 // 使用默认颜色
    }
}

/**
 * 浅色主题 - 类似 VS Code Light+ 主题
 */
private class LightTheme : SyntaxHighlightTheme() {
    
    @ColorInt
    override fun background(): Int = Color.parseColor("#FFFFFF")
    
    @ColorInt
    override fun textColor(): Int = Color.parseColor("#000000")
    
    override fun getColor(type: String): Int = when (type) {
        // 关键字 - 蓝色
        "keyword" -> Color.parseColor("#0000FF")
        
        // 字符串 - 红色
        "string" -> Color.parseColor("#A31515")
        
        // 注释 - 绿色
        "comment" -> Color.parseColor("#008000")
        
        // 函数名 - 棕色
        "function" -> Color.parseColor("#795E26")
        
        // 类名 - 青色
        "class-name" -> Color.parseColor("#267F99")
        
        // 数字 - 深绿色
        "number" -> Color.parseColor("#098658")
        
        // 操作符 - 黑色
        "operator" -> Color.parseColor("#000000")
        
        // 标点符号 - 黑色
        "punctuation" -> Color.parseColor("#000000")
        
        // 变量 - 深蓝色
        "variable" -> Color.parseColor("#001080")
        
        // 常量 - 深蓝色
        "constant" -> Color.parseColor("#0070C1")
        
        // 布尔值 - 蓝色
        "boolean" -> Color.parseColor("#0000FF")
        
        // 属性 - 深蓝色
        "property" -> Color.parseColor("#001080")
        
        // 标签 - 深红色
        "tag" -> Color.parseColor("#800000")
        
        // 属性名 - 红色
        "attr-name" -> Color.parseColor("#FF0000")
        
        // 属性值 - 蓝色
        "attr-value" -> Color.parseColor("#0000FF")
        
        // 正则表达式 - 深红色
        "regex" -> Color.parseColor("#811F3F")
        
        // 重要 - 紫色
        "important" -> Color.parseColor("#AF00DB")
        
        // 内置 - 青色
        "builtin" -> Color.parseColor("#267F99")
        
        // 注解 - 灰色
        "annotation" -> Color.parseColor("#808080")
        
        else -> 0 // 使用默认颜色
    }
}