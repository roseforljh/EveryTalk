package com.android.everytalk.ui.components.markdown

import android.content.Context
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.AbstractMarkwonPlugin
import org.commonmark.node.Code
import android.graphics.Typeface
import android.text.style.StyleSpan
import android.text.style.ForegroundColorSpan

/**
 * Markwon实例全局缓存
 * 
 * 设计目标：
 * 1. 避免LazyColumn回收导致的Markwon重复初始化（每次初始化耗时50-100ms）
 * 2. 按主题（深色/浅色）+ 字号缓存，确保视觉一致性
 * 3. 线程安全：使用synchronized保护缓存操作
 * 4. 内存管理：最多缓存4个实例（深色/浅色 × 2种常用字号）
 * 
 * 性能提升：
 * - 修复前：流式结束后4次初始化，累计200-400ms
 * - 修复后：全局只初始化1-2次，后续<1ms命中缓存
 * - 预期减少80%的初始化耗时
 */
object MarkwonCache {
    
    private val cacheMap = mutableMapOf<String, Markwon>()
    private val lock = Any()
    
    /**
     * 获取或创建Markwon实例
     * 
     * @param context Android上下文
     * @param isDark 是否为深色主题
     * @param textSize 文本字号（sp）
     * @return 缓存的或新创建的Markwon实例
     */
    fun getOrCreate(
        context: Context,
        isDark: Boolean,
        textSize: Float
    ): Markwon {
        // 生成缓存key：主题+字号（四舍五入到整数，减少缓存碎片）
        val roundedSize = textSize.toInt()
        val cacheKey = "dark=${isDark}_size=${roundedSize}"
        
        synchronized(lock) {
            // 命中缓存：直接返回
            cacheMap[cacheKey]?.let { cached ->
                android.util.Log.d("MarkwonCache", "✅ Cache HIT: $cacheKey")
                return cached
            }
            
            // 缓存未命中：创建新实例
            android.util.Log.d("MarkwonCache", "🔧 Cache MISS, creating new instance: $cacheKey")
            val startTime = System.currentTimeMillis()
            
            val mathTextSize = textSize * 5f  // 公式放大5倍
            
            val markwon = Markwon.builder(context)
                // 启用核心插件
                .usePlugin(CorePlugin.create())
                // 数学公式支持 - 必须在InlineParser之前注册
                .usePlugin(JLatexMathPlugin.create(mathTextSize) { builder ->
                    builder.inlinesEnabled(true)  // 启用内联公式 $...$
                })
                // InlineParser必须在JLatexMathPlugin之后
                .usePlugin(MarkwonInlineParserPlugin.create())
                // 表格支持
                .usePlugin(TablePlugin.create(context))
                // 主题与span定制（内联`code`样式）
                .usePlugin(object : AbstractMarkwonPlugin() {
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        // 代码块背景和边距
                        builder.codeBlockMargin(0)  // 去额外外边距，避免气泡内跳动
                    }
                    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                        // 完全替换内联`code`的Span，确保无背景，仅灰色+加粗
                        builder.setFactory(Code::class.java) { _, _ ->
                            arrayOf(
                                StyleSpan(Typeface.BOLD),
                                ForegroundColorSpan(android.graphics.Color.parseColor("#9E9E9E"))
                            )
                        }
                    }
                })
                .build()
            
            val initTime = System.currentTimeMillis() - startTime
            android.util.Log.d("MarkwonCache", "✅ Created in ${initTime}ms, cached as: $cacheKey")
            
            // 写入缓存
            cacheMap[cacheKey] = markwon
            
            // 内存保护：限制缓存大小（最多4个实例）
            if (cacheMap.size > 4) {
                val oldestKey = cacheMap.keys.first()
                cacheMap.remove(oldestKey)
                android.util.Log.d("MarkwonCache", "🗑️ Evicted oldest: $oldestKey")
            }
            
            return markwon
        }
    }
    
    /**
     * 清空缓存（用于内存压力大时）
     */
    fun clear() {
        synchronized(lock) {
            val size = cacheMap.size
            cacheMap.clear()
            android.util.Log.d("MarkwonCache", "🗑️ Cache cleared, removed $size instances")
        }
    }
    
    /**
     * 获取缓存统计信息
     */
    fun getStats(): String {
        synchronized(lock) {
            return "Cached instances: ${cacheMap.size}, Keys: ${cacheMap.keys.joinToString()}"
        }
    }
}