package com.android.everytalk.ui.components.markdown

import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j
import io.noties.prism4j.annotations.PrismBundle

/**
 * Prism4j Grammar Locator
 * 用于定位和加载语法定义
 * 
 * @PrismBundle 注解会在编译时生成语法定义代码
 * 支持的语言包括：java, kotlin, python, javascript, json, xml, sql, bash 等
 */
@PrismBundle(
    include = [
        "java", "kotlin", "python", "javascript",
        "json", "xml", "html", "css", "sql", "bash", "c", "cpp",
        "csharp", "go", "php", "ruby", "yaml",
        "markdown"
    ],
    grammarLocatorClassName = ".Prism4jGrammarLocator"
)
class GrammarLocatorDef : GrammarLocator {
    
    private val locator: GrammarLocator by lazy {
        try {
            // 尝试加载生成的 Prism4jGrammarLocator
            val clazz = Class.forName("com.android.everytalk.ui.components.markdown.Prism4jGrammarLocator")
            clazz.getDeclaredConstructor().newInstance() as GrammarLocator
        } catch (e: Exception) {
            android.util.Log.e("GrammarLocatorDef", "无法加载 Prism4jGrammarLocator: ${e.message}", e)
            // 返回空实现作为后备
            object : GrammarLocator {
                override fun grammar(prism4j: Prism4j, language: String): Prism4j.Grammar? {
                    android.util.Log.w("GrammarLocatorDef", "使用空 GrammarLocator，语言: $language")
                    return null
                }
                override fun languages(): MutableSet<String> = mutableSetOf()
            }
        }
    }
    
    override fun grammar(prism4j: Prism4j, language: String): Prism4j.Grammar? {
        return locator.grammar(prism4j, language)
    }
    
    override fun languages(): MutableSet<String> {
        return locator.languages()
    }
}