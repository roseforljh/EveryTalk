package com.android.everytalk.ui.components.markdown

import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j
import io.noties.prism4j.Prism4j.Grammar

/**
 * 简单的 GrammarLocator 实现
 * 使用 Prism4j 的默认语言支持
 * 
 * 注意：由于 Prism4j 2.0.0 的语言定义类需要通过 bundler 生成，
 * 而 bundler 与当前 Kotlin 版本存在兼容性问题，
 * 因此这里返回 null，让 Markwon 使用默认的代码块样式（无语法高亮）
 */
class SimpleGrammarLocator : GrammarLocator {
    
    override fun grammar(prism4j: Prism4j, language: String): Grammar? {
        // 暂时返回 null，使用默认代码块样式
        // 未来可以在 Prism4j bundler 兼容性问题解决后添加语法支持
        android.util.Log.d("SimpleGrammarLocator", "请求语言: $language (当前使用默认样式)")
        return null
    }
    
    override fun languages(): MutableSet<String> {
        // 返回空集合，表示当前不支持任何语言的语法高亮
        return mutableSetOf()
    }
}