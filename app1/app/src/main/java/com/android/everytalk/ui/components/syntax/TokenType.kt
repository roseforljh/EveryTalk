package com.android.everytalk.ui.components.syntax

/**
 * 代码语法 Token 类型枚举
 * 用于标识代码中不同语法元素的类型
 */
enum class TokenType {
    // ==================== 通用类型 ====================
    /** 关键字: if, for, class, function 等 */
    KEYWORD,
    
    /** 字符串: "...", '...' */
    STRING,
    
    /** 数字: 123, 3.14, 0xFF */
    NUMBER,
    
    /** 注释: // ..., /* ... */ */
    COMMENT,
    
    /** 运算符: +, -, *, /, =, == */
    OPERATOR,
    
    /** 标点: {, }, [, ], (, ), ;, , */
    PUNCTUATION,
    
    // ==================== 标识符相关 ====================
    /** 函数名 */
    FUNCTION,
    
    /** 类名 */
    CLASS_NAME,
    
    /** 变量名 */
    VARIABLE,
    
    /** 参数 */
    PARAMETER,
    
    /** 属性/成员 */
    PROPERTY,
    
    // ==================== HTML 专用 ====================
    /** HTML 标签: <div>, </span> */
    TAG,
    
    /** HTML 属性名: class, id, style */
    ATTRIBUTE,
    
    /** HTML 属性值 */
    ATTRIBUTE_VALUE,
    
    // ==================== CSS 专用 ====================
    /** CSS 选择器: .class, #id, element */
    SELECTOR,
    
    /** CSS 属性名: color, margin, display */
    CSS_PROPERTY,
    
    /** CSS 属性值 */
    CSS_VALUE,
    
    /** CSS 单位: px, em, %, rem */
    CSS_UNIT,
    
    /** 颜色值: #fff, rgb(), rgba() */
    CSS_COLOR,
    
    // ==================== Python 专用 ====================
    /** 装饰器: @property */
    DECORATOR,
    
    /** f-string: f"..." */
    FSTRING,
    
    /** 文档字符串: """...""" */
    DOCSTRING,
    
    // ==================== Kotlin 专用 ====================
    /** 注解: @Composable */
    ANNOTATION,
    
    /** 字符串模板: $variable, ${expression} */
    STRING_TEMPLATE,
    
    // ==================== 特殊类型 ====================
    /** 普通文本（无高亮） */
    PLAIN,
    
    /** 语法错误 */
    ERROR,
    
    /** 布尔值: true, false */
    BOOLEAN,
    
    /** null/None/nil */
    NULL
}