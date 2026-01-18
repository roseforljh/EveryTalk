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
import io.noties.markwon.core.CoreProps
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.ext.tables.TablePlugin
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
        // v9: reordered plugins - JLatexMathPlugin before MarkwonInlineParserPlugin for proper inline math
        val cacheKey = "v9_dark=${isDark}_size=${roundedSize}"
        
        synchronized(lock) {
            cacheMap[cacheKey]?.let { return it }
            
            val density = context.resources.displayMetrics.density
            val mathTextSize = textSize * density
            
            val markwon = Markwon.builder(context)
                .usePlugin(CorePlugin.create())
                .usePlugin(ImagesPlugin.create { plugin ->
                     plugin.addSchemeHandler(io.noties.markwon.image.data.DataUriSchemeHandler.create())
                })
                .usePlugin(TablePlugin.create(context))
                // JLatexMathPlugin 必须在 MarkwonInlineParserPlugin 之前，
                // 这样它才能正确配置 InlineParserPlugin 来处理 $$...$$ 语法
                .usePlugin(JLatexMathPlugin.create(mathTextSize) { builder ->
                    // 启用内联数学公式支持
                    builder.inlinesEnabled(true)
                })
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(object : AbstractMarkwonPlugin() {
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        builder.codeBlockMargin(0)
                        
                        // 缩小无序/有序列表前面的点和圈（当前 Markwon 版本仅支持像素 Int 宽度）
                        val smallBulletPx = (4f * density).toInt()   // 比默认更小的圆点
                        builder.bulletWidth(smallBulletPx)

                        // 移除 headingBreakHeight(0) 恢复默认标题样式
                        // 重新添加 headingBreakHeight(0) 以消除气泡顶部的额外空白
                        // 标题间的间距由预处理中的换行符控制
                        builder.headingBreakHeight(0)
                    }
                    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                        // 自定义标题样式：使用纯文本样式模拟，彻底消除 HeadingSpan 可能带来的顶部间距
                        // 解决 "#越多，顶部空间越大" 的问题
                        builder.setFactory(org.commonmark.node.Heading::class.java) { _, props ->
                            val level = CoreProps.HEADING_LEVEL.get(props) ?: 1
                            val spans = mutableListOf<Any>()
                            
                            // 加粗
                            spans.add(StyleSpan(Typeface.BOLD))
                            
                            // 字号调整 (参考 Markwon 默认比例)
                            val size = when (level) {
                                1 -> 2.0f
                                2 -> 1.5f
                                3 -> 1.17f
                                4 -> 1.0f
                                5 -> 0.83f
                                6 -> 0.67f
                                else -> 1.0f
                            }
                            spans.add(RelativeSizeSpan(size))
                            
                            spans.toTypedArray()
                        }

                        // 自定义代码块样式
                        builder.setFactory(Code::class.java) { _, _ ->
                            // 根据主题动态设置代码文字颜色
                            val codeColor = if (isDark) {
                                android.graphics.Color.parseColor("#E0E0E0") // 暗色模式：浅灰
                            } else {
                                android.graphics.Color.parseColor("#24292f") // 亮色模式：深灰 (GitHub 风格)
                            }
                            
                            arrayOf<Any>(
                                TypefaceSpan("monospace"),
                                RelativeSizeSpan(0.85f),
                                ForegroundColorSpan(codeColor)
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