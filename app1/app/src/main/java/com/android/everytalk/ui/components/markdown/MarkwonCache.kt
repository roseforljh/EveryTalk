package com.android.everytalk.ui.components.markdown

import android.content.Context
import io.noties.markwon.Markwon
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.AsyncDrawable
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
        textSize: Float,
        imageClickListener: ((String) -> Unit)? = null
    ): Markwon {
        // 生成缓存key：主题+字号（四舍五入到整数，减少缓存碎片）
        // val roundedSize = textSize.toInt() // 移除重复声明
        // 但为了性能，我们通常希望监听器是全局统一处理或者通过tag传递。
        // 这里我们采取一种策略：Markwon实例本身是通用的，点击事件通过 movementMethod 动态处理，
        // 或者我们在 configureTheme 中配置 ImagePlugin。
        // 由于 Markwon 的 ImagePlugin 配置是在构建时确定的，如果我们需要动态的点击处理，
        // 最好是不把 listener 绑死在缓存实例上，而是利用 LinkMovementMethod 或者自定义 Span。
        //
        // 实际上，Markwon 的 ImagesPlugin 默认没有点击事件。要支持点击，我们需要自定义 ImagePlugin
        // 或者使用 LinkPlugin 配合。
        //
        // 考虑到缓存的复用性，我们将 imageClickListener 作为一个非缓存因素。
        // 如果传入了 listener，我们可能需要每次都 build，或者使用一个能够动态分发的机制。
        //
        // 💡 最佳实践：Markwon 实例缓存通用配置。对于点击事件，我们在 MarkdownRenderer 中
        // 通过 LinkMovementMethod 或者 setOnTouchListener 来处理，或者使用 Markwon 的 configuration。
        //
        // 但是 Markwon 的 AsyncDrawable 是通过 ImageSpan 渲染的，ImageSpan 本身不处理点击。
        // 我们需要配置 ImagesPlugin 来支持点击。
        //
        // 让我们修改策略：
        // 我们在 Markwon 构建时配置一个全局的或者通用的 ImageDestinationProcessor，或者
        // 使用 LinkPlugin。
        //
        // 简单方案：不缓存带 Listener 的实例，或者假定 Listener 是通过外部单例/事件总线分发的。
        //
        // 为了不破坏现有的缓存逻辑，且考虑到 imageClickListener 在 Compose 中可能会变化（闭包），
        // 我们这里暂时不把 listener 放入 getOrCreate 的缓存 key 中，而是：
        // 1. 如果需要点击图片，我们可能无法复用这个静态缓存，或者
        // 2. 我们让 Markwon 实例支持点击，但点击的具体行为由外部决定（例如通过 tag）。
        //
        // 实际上，Markwon 提供了 `LinkSpan`。我们可以把图片包裹在 Link 中。
        // 但如果是纯图片，我们希望它也能点击。
        //
        // 让我们尝试使用一个自定义的 ImagePlugin 配置，它允许点击。
        // 由于 MarkwonCache 是单例对象，很难传入随 UI 变化的 listener。
        //
        // 💡 解决方案：
        // 我们保留 getOrCreate 的签名不变（或者增加参数但不影响缓存key，这有风险）。
        // 更好的方式是：在 MarkdownRenderer 中，不依赖 MarkwonCache 的缓存来处理点击，
        // 或者让 MarkwonCache 支持一个“无缓存模式”或者“带点击回调的构建”。
        //
        // 鉴于目前 MarkwonCache 主要是为了性能，我们不想因为点击事件放弃缓存。
        // 我们可以利用 Markwon 的 `MarkwonConfiguration`。
        //
        // 另一种思路：在 MarkdownRenderer 中，当检测到 text 中有图片时，
        // 使用一个不缓存的 Markwon 实例，或者使用一个“图片可点击”的专用缓存。
        //
        // 让我们修改 getOrCreate，增加 `useImageClick: Boolean` 参数。
        // 如果 useImageClick 为 true，我们生成一个支持点击的 Markwon 实例。
        // 点击的具体动作，可以通过 AsyncDrawable 的回调或者 LinkResolver。
        //
        // Markwon 并没有直接的 setOnImageClickListener。
        // 通常做法是：
        // .usePlugin(object : AbstractMarkwonPlugin() {
        //     override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
        //         val origin = builder.getFactory(Image::class.java)
        //         builder.setFactory(Image::class.java) { configuration, props ->
        //             val span = origin?.getSpans(configuration, props)
        //             // 这里很难插入点击事件，因为 AsyncDrawable 只是 Drawable。
        //         }
        //     }
        // })
        //
        // 查阅 Markwon 文档/经验：
        // 可以通过 LinkResolver 来处理，把图片当做链接。
        // 或者，使用 `LinkifyPlugin` 自动链接化？不对。
        //
        // 最稳妥的方式：
        // 使用 LinkPlugin，并确保图片被包裹在 Link 中？不，这需要修改 Markdown 内容。
        //
        // 让我们看看 ImagesPlugin 是否有配置。
        // ImagesPlugin.create { plugin -> ... }
        // 并没有直接的 onClick。
        //
        // 替代方案：
        // 我们可以在 MarkdownRenderer 中，在 setText 之后，
        // 遍历 Spannable，找到 ImageSpan，然后替换为 ClickableSpan。
        // 这样我们就可以复用 Markwon 实例（它只负责生成 Spannable），
        // 而点击逻辑在 View 层处理。
        //
        // ✅ 决定：不修改 MarkwonCache 的构建逻辑（保持缓存），
        // 而是在 MarkdownRenderer 中，拿到 Spanned 后，
        // 查找 ImageSpan 并包裹 ClickableSpan。
        
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
                // 图片支持 - 用于渲染 data:image/png;base64,...
                .usePlugin(ImagesPlugin.create { plugin ->
                     plugin.addSchemeHandler(io.noties.markwon.image.data.DataUriSchemeHandler.create())
                })
                // 数学公式支持 - 必须在InlineParser之前注册
                .usePlugin(JLatexMathPlugin.create(mathTextSize) { builder ->
                    builder.inlinesEnabled(true)  // 启用内联公式 $...$
                })
                // InlineParser必须在JLatexMathPlugin之后
                .usePlugin(MarkwonInlineParserPlugin.create())
                // 表格支持：已移除，改用 Compose TableRenderer 统一渲染
                // .usePlugin(TablePlugin.create(context))
                // 主题与span定制（内联`code`样式）
                .usePlugin(object : AbstractMarkwonPlugin() {
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        // 代码块背景和边距
                        builder.codeBlockMargin(0)  // 去额外外边距，避免气泡内跳动
                    }
                    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                        // 完全替换内联`code`的Span，确保无背景，仅灰色+加粗
                        builder.setFactory(Code::class.java) { _, _ ->
                            arrayOf<Any>(
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