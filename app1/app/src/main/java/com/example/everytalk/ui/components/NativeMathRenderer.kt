package com.example.everytalk.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import ru.noties.jlatexmath.JLatexMathDrawable
import ru.noties.jlatexmath.JLatexMathView
import java.util.concurrent.ConcurrentHashMap

/**
 * 高性能原生数学公式渲染器
 * 三层架构：Unicode快速转换 -> JLatexMath渲染 -> 智能缓存
 */
object NativeMathRenderer {
    private const val TAG = "NativeMathRenderer"
    
    // 渲染状态
    enum class RenderState {
        PENDING, RENDERING, COMPLETED, FAILED
    }
    
    // 缓存管理
    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val renderStateCache = ConcurrentHashMap<String, RenderState>()
    private const val MAX_CACHE_SIZE = 200
    
    // 渲染作用域
    private val renderScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    /**
     * Layer 1: Unicode符号快速转换
     * 适用于简单的数学符号，性能最佳
     */
    private val unicodeReplacements = mapOf(
        // 希腊字母
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\epsilon" to "ε", "\\varepsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ",
        "\\vartheta" to "ϑ", "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ", "\\mu" to "μ",
        "\\nu" to "ν", "\\xi" to "ξ", "\\pi" to "π", "\\varpi" to "ϖ", "\\rho" to "ρ",
        "\\varrho" to "ϱ", "\\sigma" to "σ", "\\varsigma" to "ς", "\\tau" to "τ", "\\upsilon" to "υ",
        "\\phi" to "φ", "\\varphi" to "ϕ", "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
        
        // 大写希腊字母
        "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ",
        "\\Xi" to "Ξ", "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Upsilon" to "Υ",
        "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω",
        
        // 数学符号
        "\\infty" to "∞", "\\partial" to "∂", "\\nabla" to "∇",
        "\\pm" to "±", "\\mp" to "∓", "\\times" to "×", "\\div" to "÷",
        "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥", 
        "\\neq" to "≠", "\\ne" to "≠", "\\approx" to "≈", "\\sim" to "∼",
        "\\equiv" to "≡", "\\propto" to "∝", "\\in" to "∈", "\\notin" to "∉",
        "\\subset" to "⊂", "\\supset" to "⊃", "\\subseteq" to "⊆", "\\supseteq" to "⊇",
        "\\cup" to "∪", "\\cap" to "∩", "\\emptyset" to "∅", "\\varnothing" to "∅",
        
        // 积分和求和
        "\\int" to "∫", "\\iint" to "∬", "\\iiint" to "∭", "\\oint" to "∮",
        "\\sum" to "Σ", "\\prod" to "∏", "\\coprod" to "∐",
        "\\bigcup" to "⋃", "\\bigcap" to "⋂",
        
        // 箭头
        "\\leftarrow" to "←", "\\gets" to "←", "\\rightarrow" to "→", "\\to" to "→",
        "\\leftrightarrow" to "↔", "\\Leftarrow" to "⇐", "\\Rightarrow" to "⇒", 
        "\\Leftrightarrow" to "⇔", "\\uparrow" to "↑", "\\downarrow" to "↓", 
        "\\updownarrow" to "↕", "\\nearrow" to "↗", "\\searrow" to "↘",
        "\\swarrow" to "↙", "\\nwarrow" to "↖",
        
        // 省略号
        "\\ldots" to "…", "\\cdots" to "⋯", "\\vdots" to "⋮", "\\ddots" to "⋱",
        "\\dots" to "…",
        
        // 根号（简单情况）
        "\\sqrt{2}" to "√2", "\\sqrt{3}" to "√3", "\\sqrt{x}" to "√x",
        "\\sqrt{a}" to "√a", "\\sqrt{b}" to "√b", "\\sqrt{n}" to "√n",
        
        // 常用函数
        "\\sin" to "sin", "\\cos" to "cos", "\\tan" to "tan",
        "\\cot" to "cot", "\\sec" to "sec", "\\csc" to "csc",
        "\\log" to "log", "\\ln" to "ln", "\\exp" to "exp",
        "\\lim" to "lim", "\\max" to "max", "\\min" to "min",
        "\\sup" to "sup", "\\inf" to "inf",
        
        // 特殊数字和常数
        "\\ell" to "ℓ", "\\hbar" to "ℏ", "\\wp" to "℘",
        "\\Re" to "ℜ", "\\Im" to "ℑ", "\\aleph" to "ℵ",
        
        // 逻辑符号
        "\\land" to "∧", "\\lor" to "∨", "\\lnot" to "¬", "\\neg" to "¬",
        "\\forall" to "∀", "\\exists" to "∃", "\\nexists" to "∄",
        
        // 其他常用符号
        "\\angle" to "∠", "\\perp" to "⊥", "\\parallel" to "∥",
        "\\diamond" to "◊", "\\Box" to "□", "\\triangle" to "△",
        "\\star" to "⋆", "\\ast" to "∗", "\\bullet" to "•",
        "\\circ" to "∘", "\\oplus" to "⊕", "\\ominus" to "⊖",
        "\\otimes" to "⊗", "\\oslash" to "⊘"
    )
    
    /**
     * 判断LaTeX是否可以用Unicode快速转换
     */
    private fun canUseUnicodeConversion(latex: String): Boolean {
        // 检查是否只包含简单的替换规则
        val cleanLatex = latex.trim()
        
        // 排除复杂结构
        if (cleanLatex.contains("frac") || cleanLatex.contains("{") || 
            cleanLatex.contains("_") || cleanLatex.contains("^") ||
            cleanLatex.contains("begin") || cleanLatex.contains("end")) {
            return false
        }
        
        // 检查是否所有命令都在Unicode替换表中
        val commands = Regex("""\\[a-zA-Z]+""").findAll(cleanLatex).map { it.value }.toSet()
        return commands.all { it in unicodeReplacements }
    }
    
    /**
     * Layer 1: Unicode快速转换
     */
    private fun convertToUnicode(latex: String): String {
        var result = latex
        unicodeReplacements.forEach { (command, symbol) ->
            result = result.replace(command, symbol)
        }
        return result
    }
    
    /**
     * Layer 2: JLatexMath复杂渲染
     */
    private suspend fun renderWithJLatexMath(
        latex: String, 
        textColor: Int, 
        textSize: Float,
        isInline: Boolean
    ): Bitmap? = withContext(Dispatchers.Default) {
        try {
            Log.d(TAG, "🎯 Rendering with JLatexMath: $latex")
            
            // 适中的字体大小，平衡清晰度和美观性
            val scaledTextSize = if (isInline) textSize * 1.8f else textSize * 2.2f
            
            // 创建JLatexMath drawable
            val drawable = JLatexMathDrawable.builder(latex)
                .textSize(scaledTextSize)
                .color(textColor)
                .align(JLatexMathDrawable.ALIGN_LEFT)
                .padding(6, 6, 6, 6) // 适中的内边距
                .build()
            
            // 计算尺寸
            val width = drawable.intrinsicWidth
            val height = drawable.intrinsicHeight
            
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "Invalid dimensions: ${width}x${height}")
                return@withContext null
            }
            
            // 创建高质量bitmap
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            // 设置背景为透明
            canvas.drawColor(Color.TRANSPARENT)
            
            // 设置drawable边界并绘制
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            
            Log.d(TAG, "✅ JLatexMath render success: ${width}x${height}")
            bitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ JLatexMath render failed: ${e.message}", e)
            // 回退到Unicode渲染
            try {
                renderUnicodeText(convertToUnicode(latex), textColor, textSize * 2.0f)
            } catch (fallbackException: Exception) {
                Log.e(TAG, "❌ Fallback render also failed: ${fallbackException.message}")
                null
            }
        }
    }
    
    /**
     * 生成缓存键
     */
    private fun getCacheKey(latex: String, textColor: Int, textSize: Float, isInline: Boolean): String {
        return "${latex.hashCode()}_${textColor}_${textSize.toInt()}_$isInline"
    }
    
    /**
     * 清理缓存（LRU策略）
     */
    private fun cleanupCache() {
        if (bitmapCache.size > MAX_CACHE_SIZE) {
            // 更安全的清理策略：只移除最旧的条目
            val keysToRemove = bitmapCache.keys.take(bitmapCache.size / 3) // 只移除1/3
            keysToRemove.forEach { key ->
                renderStateCache.remove(key)
                bitmapCache[key]?.let { bitmap ->
                    if (!bitmap.isRecycled) {
                        try {
                            bitmap.recycle()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to recycle bitmap: ${e.message}")
                        }
                    }
                }
                bitmapCache.remove(key)
            }
            Log.d(TAG, "🧹 Cache cleaned safely, removed ${keysToRemove.size} items")
        }
    }
    
    /**
     * 主要渲染接口
     */
    suspend fun renderMath(
        latex: String,
        textColor: Int,
        textSize: Float,
        isInline: Boolean
    ): Bitmap? {
        val cacheKey = getCacheKey(latex, textColor, textSize, isInline)
        
        // 检查缓存
        bitmapCache[cacheKey]?.let { cachedBitmap ->
            Log.d(TAG, "📦 Cache hit for: $latex")
            return cachedBitmap
        }
        
        // 设置渲染状态
        renderStateCache[cacheKey] = RenderState.RENDERING
        
        try {
            val bitmap = if (canUseUnicodeConversion(latex)) {
                // Layer 1: Unicode快速转换
                Log.d(TAG, "⚡ Using Unicode conversion for: $latex")
                renderUnicodeText(convertToUnicode(latex), textColor, textSize)
            } else {
                // Layer 2: JLatexMath渲染
                renderWithJLatexMath(latex, textColor, textSize, isInline)
            }
            
            bitmap?.let {
                // 缓存结果
                cleanupCache()
                bitmapCache[cacheKey] = it
                renderStateCache[cacheKey] = RenderState.COMPLETED
                Log.d(TAG, "✅ Math rendered and cached: $latex")
            } ?: run {
                renderStateCache[cacheKey] = RenderState.FAILED
                Log.e(TAG, "❌ Math render failed: $latex")
            }
            
            return bitmap
            
        } catch (e: Exception) {
            renderStateCache[cacheKey] = RenderState.FAILED
            Log.e(TAG, "❌ Math render exception: ${e.message}", e)
            return null
        }
    }
    
    /**
     * 渲染Unicode文本为Bitmap（用于Layer 1）
     */
    private fun renderUnicodeText(text: String, textColor: Int, textSize: Float): Bitmap {
        val paint = Paint().apply {
            color = textColor
            this.textSize = textSize * 1.6f // 适中的字体增大
            typeface = Typeface.DEFAULT
            isAntiAlias = true
            isSubpixelText = true
            textAlign = Paint.Align.LEFT
        }
        
        // 计算文本尺寸
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        
        val padding = 6 // 适中的内边距
        val width = bounds.width() + padding * 2
        val height = bounds.height() + padding * 2
        
        // 创建bitmap并绘制
        val bitmap = Bitmap.createBitmap(
            maxOf(width, 28), // 适中的最小宽度
            maxOf(height, 28), // 适中的最小高度
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        
        // 绘制文本，左对齐
        val x = padding.toFloat()
        val y = height / 2f - (paint.descent() + paint.ascent()) / 2f
        
        canvas.drawText(text, x, y, paint)
        
        return bitmap
    }
    
    /**
     * 清理所有缓存
     */
    fun clearCache() {
        // 更安全的清理：先标记为无效，延迟回收
        val keysToRemove = bitmapCache.keys.toList()
        keysToRemove.forEach { key ->
            renderStateCache.remove(key)
        }
        // 延迟清理bitmap，避免正在使用的被回收
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(1000) // 延迟1秒
            keysToRemove.forEach { key ->
                bitmapCache[key]?.let { bitmap ->
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
                bitmapCache.remove(key)
            }
        }
        Log.d(TAG, "🧹 Cache clearing initiated safely")
    }
    
    /**
     * 获取渲染状态
     */
    fun getRenderState(latex: String, textColor: Int, textSize: Float, isInline: Boolean): RenderState {
        val cacheKey = getCacheKey(latex, textColor, textSize, isInline)
        return renderStateCache[cacheKey] ?: RenderState.PENDING
    }
}

/**
 * Compose组件：高性能数学公式显示
 */
@Composable
fun NativeMathText(
    latex: String,
    isInline: Boolean = false,
    modifier: Modifier = Modifier
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    // 适中的字体大小，平衡可见性和美观性
    val textSize = if (isInline) 20.sp.value else 22.sp.value
    var renderState by remember { mutableStateOf(NativeMathRenderer.RenderState.PENDING) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val density = LocalDensity.current
    
    // 监听应用生命周期，防止使用已回收的bitmap
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var shouldRerender by remember { mutableStateOf(false) }
    
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    // 应用恢复时，检查bitmap是否还有效
                    bitmap?.let { bmp ->
                        if (bmp.isRecycled) {
                            bitmap = null
                            shouldRerender = true
                            Log.d("NativeMathText", "Detected recycled bitmap, will re-render")
                        }
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { 
            lifecycleOwner.lifecycle.removeObserver(observer) 
        }
    }
    
    // 异步渲染
    LaunchedEffect(latex, textColor, textSize, isInline, shouldRerender) {
        if (shouldRerender) {
            shouldRerender = false
        }
        
        renderState = NativeMathRenderer.RenderState.RENDERING
        
        // 适中的字体倍数
        val result = NativeMathRenderer.renderMath(latex, textColor, textSize * 1.5f, isInline)
        
        bitmap = result
        renderState = if (result != null) {
            NativeMathRenderer.RenderState.COMPLETED
        } else {
            NativeMathRenderer.RenderState.FAILED
        }
    }
    
    // 根据渲染状态显示内容
    when (renderState) {
        NativeMathRenderer.RenderState.PENDING, 
        NativeMathRenderer.RenderState.RENDERING -> {
            Text(
                text = "⌛",
                fontSize = if (isInline) 18.sp else 20.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = modifier
            )
        }
        
        NativeMathRenderer.RenderState.COMPLETED -> {
            bitmap?.let { bmp ->
                // 检查bitmap是否有效
                if (bmp.isRecycled) {
                    // bitmap已被回收，触发重新渲染
                    LaunchedEffect(Unit) {
                        shouldRerender = true
                    }
                    Text(
                        text = "🔄",
                        fontSize = if (isInline) 18.sp else 20.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = modifier
                    )
                } else {
                    if (isInline) {
                        // 行内公式使用AndroidView确保基线对齐
                        AndroidView(
                            factory = { context ->
                                ImageView(context).apply {
                                    scaleType = ImageView.ScaleType.FIT_START
                                    adjustViewBounds = true
                                }
                            },
                            update = { imageView ->
                                if (!bmp.isRecycled) {
                                    imageView.setImageBitmap(bmp)
                                } else {
                                    Log.w("NativeMathText", "Bitmap is recycled, will re-render")
                                    shouldRerender = true
                                }
                            },
                            modifier = modifier.wrapContentSize()
                        )
                    } else {
                        // 块级公式使用Image组件
                        Box(
                            modifier = modifier.fillMaxWidth().wrapContentHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            // 检查bitmap是否有效再使用
                            if (!bmp.isRecycled) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Math: $latex",
                                    modifier = Modifier.wrapContentSize(),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                // bitmap已被回收，触发重新渲染
                                LaunchedEffect(Unit) {
                                    shouldRerender = true
                                }
                                Text(
                                    text = "🔄",
                                    fontSize = 20.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            } ?: run {
                Text(
                    text = latex,
                    fontSize = if (isInline) 18.sp else 20.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = modifier
                )
            }
        }
        
        NativeMathRenderer.RenderState.FAILED -> {
            Text(
                text = latex.replace("\\", "").replace("{", "").replace("}", ""),
                fontSize = if (isInline) 18.sp else 20.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                modifier = modifier
            )
        }
    }
}