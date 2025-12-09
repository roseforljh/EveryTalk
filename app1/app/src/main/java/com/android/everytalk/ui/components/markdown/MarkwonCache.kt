package com.android.everytalk.ui.components.markdown

import android.content.Context
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.RelativeSizeSpan
import android.text.style.AbsoluteSizeSpan
import android.graphics.Typeface
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import org.commonmark.node.Code

/**
 * Markwon实例全局缓存
 */
object MarkwonCache {
    
    private val cacheMap = mutableMapOf<String, Markwon>()
    private val lock = Any()
    
    /**
     * 获取或创建Markwon实例
     */
    fun getOrCreate(
        context: Context,
        isDark: Boolean,
        textSize: Float,
        imageClickListener: ((String) -> Unit)? = null
    ): Markwon {
        val roundedSize = textSize.toInt()
        // v4: use default heading style from library, invalidate old cache
        val cacheKey = "v4_dark=${isDark}_size=${roundedSize}"
        
        synchronized(lock) {
            cacheMap[cacheKey]?.let { return it }
            
            val density = context.resources.displayMetrics.density
            val mathTextSize = textSize * density
            
            val markwon = Markwon.builder(context)
                .usePlugin(CorePlugin.create())
                .usePlugin(ImagesPlugin.create { plugin ->
                     plugin.addSchemeHandler(io.noties.markwon.image.data.DataUriSchemeHandler.create())
                })
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(JLatexMathPlugin.create(mathTextSize) { builder ->
                    // Enable inlines to support standard inline math if needed,
                    // though we mostly rely on $$ block fallback from MarkdownRenderer
                    builder.inlinesEnabled(true)
                })
                .usePlugin(object : AbstractMarkwonPlugin() {
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        builder.codeBlockMargin(0)
                        
                        // 缩小无序/有序列表前面的点和圈（当前 Markwon 版本仅支持像素 Int 宽度）
                        val smallBulletPx = (4f * density).toInt()   // 比默认更小的圆点
                        builder.bulletWidth(smallBulletPx)

                        // 移除 headingBreakHeight(0) 恢复默认标题样式
                    }
                    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                        // 标题样式完全交给 Markwon 默认实现，
                        // 这里只自定义代码块样式
                        builder.setFactory(Code::class.java) { _, _ ->
                            arrayOf<Any>(
                                TypefaceSpan("monospace"),
                                RelativeSizeSpan(0.85f),
                                ForegroundColorSpan(android.graphics.Color.parseColor("#9E9E9E"))
                            )
                        }
                    }
                })
                .build()
            
            if (cacheMap.size > 4) {
                cacheMap.remove(cacheMap.keys.first())
            }
            cacheMap[cacheKey] = markwon
            return markwon
        }
    }
    
    fun clear() {
        synchronized(lock) { cacheMap.clear() }
    }
    
    fun getStats(): String {
        synchronized(lock) {
            return "Cached instances: ${cacheMap.size}, Keys: ${cacheMap.keys.joinToString()}"
        }
    }
}