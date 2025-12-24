package com.android.everytalk.ui.components.syntax

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * 语法高亮主题配色
 * 
 * 参考 One Dark Pro 主题设计
 */
@Immutable
data class SyntaxHighlightTheme(
    // 通用
    val keyword: Color,           // 关键字
    val string: Color,            // 字符串
    val number: Color,            // 数字
    val comment: Color,           // 注释
    val operator: Color,          // 运算符
    val punctuation: Color,       // 标点
    
    // 标识符
    val function: Color,          // 函数名
    val className: Color,         // 类名
    val variable: Color,          // 变量
    val parameter: Color,         // 参数
    val property: Color,          // 属性
    
    // HTML
    val tag: Color,               // HTML 标签
    val attribute: Color,         // 属性名
    val attributeValue: Color,    // 属性值
    
    // CSS
    val selector: Color,          // 选择器
    val cssProperty: Color,       // CSS 属性
    val cssValue: Color,          // CSS 值
    val cssUnit: Color,           // 单位
    val cssColor: Color,          // 颜色值
    
    // Python
    val decorator: Color,         // 装饰器
    val fstring: Color,           // f-string
    val docstring: Color,         // 文档字符串
    
    // Kotlin
    val annotation: Color,        // 注解
    val stringTemplate: Color,    // 字符串模板
    
    // 特殊
    val plain: Color,             // 普通文本
    val error: Color,             // 错误
    val boolean: Color,           // 布尔值
    val nullValue: Color          // null
) {
    /**
     * 根据 Token 类型获取对应颜色
     */
    fun getColor(tokenType: TokenType): Color = when (tokenType) {
        TokenType.KEYWORD -> keyword
        TokenType.STRING -> string
        TokenType.NUMBER -> number
        TokenType.COMMENT -> comment
        TokenType.OPERATOR -> operator
        TokenType.PUNCTUATION -> punctuation
        TokenType.FUNCTION -> function
        TokenType.CLASS_NAME -> className
        TokenType.VARIABLE -> variable
        TokenType.PARAMETER -> parameter
        TokenType.PROPERTY -> property
        TokenType.TAG -> tag
        TokenType.ATTRIBUTE -> attribute
        TokenType.ATTRIBUTE_VALUE -> attributeValue
        TokenType.SELECTOR -> selector
        TokenType.CSS_PROPERTY -> cssProperty
        TokenType.CSS_VALUE -> cssValue
        TokenType.CSS_UNIT -> cssUnit
        TokenType.CSS_COLOR -> cssColor
        TokenType.DECORATOR -> decorator
        TokenType.FSTRING -> fstring
        TokenType.DOCSTRING -> docstring
        TokenType.ANNOTATION -> annotation
        TokenType.STRING_TEMPLATE -> stringTemplate
        TokenType.PLAIN -> plain
        TokenType.ERROR -> error
        TokenType.BOOLEAN -> boolean
        TokenType.NULL -> nullValue
    }
    
    companion object {
        /**
         * 暗色主题 - 参考 One Dark Pro
         */
        val Dark = SyntaxHighlightTheme(
            // 通用
            keyword = Color(0xFFC678DD),           // 紫色 - if, for, class
            string = Color(0xFF98C379),            // 绿色 - "hello"
            number = Color(0xFFD19A66),            // 橙色 - 123, 3.14
            comment = Color(0xFF5C6370),           // 灰色 - // comment
            operator = Color(0xFF56B6C2),          // 青色 - =, +, -
            punctuation = Color(0xFFABB2BF),       // 浅灰 - {, }, [, ]
            
            // 标识符
            function = Color(0xFF61AFEF),          // 蓝色 - functionName()
            className = Color(0xFFE5C07B),         // 黄色 - ClassName
            variable = Color(0xFFE06C75),          // 红色 - variable
            parameter = Color(0xFFD19A66),         // 橙色 - param
            property = Color(0xFFE06C75),          // 红色 - obj.property
            
            // HTML
            tag = Color(0xFFE06C75),               // 红色 - <div>
            attribute = Color(0xFFD19A66),         // 橙色 - class=
            attributeValue = Color(0xFF98C379),    // 绿色 - "value"
            
            // CSS
            selector = Color(0xFFE06C75),          // 红色 - .class, #id
            cssProperty = Color(0xFFD19A66),       // 橙色 - display:
            cssValue = Color(0xFFABB2BF),          // 浅灰 - flex
            cssUnit = Color(0xFF98C379),           // 绿色 - 20px
            cssColor = Color(0xFF56B6C2),          // 青色 - #fff
            
            // Python
            decorator = Color(0xFFE5C07B),         // 黄色 - @property
            fstring = Color(0xFF98C379),           // 绿色 - f"..."
            docstring = Color(0xFF5C6370),         // 灰色 - """..."""
            
            // Kotlin
            annotation = Color(0xFFE5C07B),        // 黄色 - @Composable
            stringTemplate = Color(0xFF56B6C2),    // 青色 - $variable
            
            // 特殊
            plain = Color(0xFFABB2BF),             // 浅灰 - 普通文本
            error = Color(0xFFFF6B6B),             // 亮红 - 错误
            boolean = Color(0xFFD19A66),           // 橙色 - true, false
            nullValue = Color(0xFFD19A66)          // 橙色 - null
        )
        
        /**
         * 亮色主题 - 参考 One Light
         */
        val Light = SyntaxHighlightTheme(
            // 通用
            keyword = Color(0xFFA626A4),           // 紫色 - if, for, class
            string = Color(0xFF50A14F),            // 绿色 - "hello"
            number = Color(0xFF986801),            // 棕色 - 123, 3.14
            comment = Color(0xFFA0A1A7),           // 灰色 - // comment
            operator = Color(0xFF0184BC),          // 青色 - =, +, -
            punctuation = Color(0xFF383A42),       // 深灰 - {, }, [, ]
            
            // 标识符
            function = Color(0xFF4078F2),          // 蓝色 - functionName()
            className = Color(0xFFC18401),         // 黄棕 - ClassName
            variable = Color(0xFFE45649),          // 红色 - variable
            parameter = Color(0xFF986801),         // 棕色 - param
            property = Color(0xFFE45649),          // 红色 - obj.property
            
            // HTML
            tag = Color(0xFFE45649),               // 红色 - <div>
            attribute = Color(0xFF986801),         // 棕色 - class=
            attributeValue = Color(0xFF50A14F),    // 绿色 - "value"
            
            // CSS
            selector = Color(0xFFE45649),          // 红色 - .class, #id
            cssProperty = Color(0xFF986801),       // 棕色 - display:
            cssValue = Color(0xFF383A42),          // 深灰 - flex
            cssUnit = Color(0xFF50A14F),           // 绿色 - 20px
            cssColor = Color(0xFF0184BC),          // 青色 - #fff
            
            // Python
            decorator = Color(0xFFC18401),         // 黄棕 - @property
            fstring = Color(0xFF50A14F),           // 绿色 - f"..."
            docstring = Color(0xFFA0A1A7),         // 灰色 - """..."""
            
            // Kotlin
            annotation = Color(0xFFC18401),        // 黄棕 - @Composable
            stringTemplate = Color(0xFF0184BC),    // 青色 - $variable
            
            // 特殊
            plain = Color(0xFF383A42),             // 深灰 - 普通文本
            error = Color(0xFFE45649),             // 红色 - 错误
            boolean = Color(0xFF986801),           // 棕色 - true, false
            nullValue = Color(0xFF986801)          // 棕色 - null
        )
    }
}

/**
 * 根据系统主题获取语法高亮配色
 */
@Composable
fun rememberSyntaxTheme(): SyntaxHighlightTheme {
    val isDark = isSystemInDarkTheme()
    return remember(isDark) {
        if (isDark) SyntaxHighlightTheme.Dark else SyntaxHighlightTheme.Light
    }
}