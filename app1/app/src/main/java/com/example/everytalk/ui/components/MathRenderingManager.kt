package com.example.everytalk.ui.components

import android.util.Log
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.BufferOverflow
import java.util.concurrent.ConcurrentHashMap

/**
 * 数学公式渲染状态管理器
 * 负责协调WebView数学公式的异步渲染，避免同时创建多个WebView导致性能问题
 */
object MathRenderingManager {
    private const val TAG = "MathRenderingManager"
    
    // 渲染状态
    enum class RenderState {
        PENDING,     // 等待渲染
        RENDERING,   // 正在渲染
        COMPLETED,   // 渲染完成
        FAILED       // 渲染失败
    }
    
    // 渲染任务信息
    data class RenderTask(
        val messageId: String,
        val mathId: String,
        val latex: String,
        val inline: Boolean,
        val priority: Int = 0  // 优先级，数字越小优先级越高
    )
    
    // 渲染状态存储
    private val renderStates = ConcurrentHashMap<String, MutableStateFlow<RenderState>>()
    
    // 渲染队列
    private val renderQueue = MutableSharedFlow<RenderTask>(
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    // 当前正在渲染的任务数量
    private val _activeRenderCount = MutableStateFlow(0)
    val activeRenderCount = _activeRenderCount.asStateFlow()
    
    // 最大并发渲染数量
    private const val MAX_CONCURRENT_RENDERS = 1
    
    // 渲染作用域
    private val renderingScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    
    init {
        // 启动渲染队列处理器
        startRenderingProcessor()
    }
    
    /**
     * 获取数学公式的渲染状态
     */
    @Composable
    fun getRenderState(mathId: String): State<RenderState> {
        val stateFlow = remember(mathId) {
            renderStates.getOrPut(mathId) { MutableStateFlow(RenderState.PENDING) }
        }
        return stateFlow.collectAsState()
    }
    
    /**
     * 提交数学公式渲染任务
     */
    fun submitRenderTask(
        messageId: String,
        mathId: String,
        latex: String,
        inline: Boolean,
        priority: Int = 0
    ) {
        Log.d(TAG, "🎯 提交渲染任务: messageId=$messageId, mathId=$mathId, latex='${latex.take(20)}...', inline=$inline, priority=$priority")
        
        val task = RenderTask(messageId, mathId, latex, inline, priority)
        
        // 设置为等待状态
        renderStates.getOrPut(mathId) { MutableStateFlow(RenderState.PENDING) }
            .value = RenderState.PENDING
        
        // 提交到队列
        renderQueue.tryEmit(task)
    }
    
    /**
     * 批量提交消息中的所有数学公式渲染任务
     */
    fun submitMessageMathTasks(messageId: String, mathBlocks: List<Pair<String, String>>) {
        Log.d(TAG, "🎯 批量提交消息 $messageId 的 ${mathBlocks.size} 个数学公式")
        
        mathBlocks.forEachIndexed { index, (mathId, latex) ->
            val inline = !latex.contains("\\begin") && !latex.contains("\\displaystyle")
            submitRenderTask(
                messageId = messageId,
                mathId = mathId,
                latex = latex,
                inline = inline,
                priority = index  // 按顺序渲染
            )
        }
    }
    
    /**
     * 标记渲染开始
     */
    fun markRenderingStarted(mathId: String) {
        Log.d(TAG, "🎯 开始渲染: $mathId")
        renderStates[mathId]?.value = RenderState.RENDERING
        _activeRenderCount.value = _activeRenderCount.value + 1
    }
    
    /**
     * 标记渲染完成
     */
    fun markRenderingCompleted(mathId: String) {
        Log.d(TAG, "✅ 渲染完成: $mathId")
        renderStates[mathId]?.value = RenderState.COMPLETED
        _activeRenderCount.value = maxOf(0, _activeRenderCount.value - 1)
    }
    
    /**
     * 标记渲染失败
     */
    fun markRenderingFailed(mathId: String) {
        Log.e(TAG, "❌ 渲染失败: $mathId")
        renderStates[mathId]?.value = RenderState.FAILED
        _activeRenderCount.value = maxOf(0, _activeRenderCount.value - 1)
    }
    
    /**
     * 清理消息的渲染状态
     */
    fun clearMessageStates(messageId: String) {
        Log.d(TAG, "🧹 清理消息渲染状态: $messageId")
        val keysToRemove = renderStates.keys.filter { it.startsWith("${messageId}_") }
        keysToRemove.forEach { key ->
            renderStates.remove(key)
        }
    }
    
    /**
     * 检测消息是否包含数学公式
     */
    fun hasRenderableMath(messageText: String): Boolean {
        return messageText.contains('$') || 
               messageText.contains("\\begin") || 
               messageText.contains("\\end") ||
               messageText.contains("\\frac") ||
               messageText.contains("\\sqrt") ||
               messageText.contains("\\sum") ||
               messageText.contains("\\int")
    }
    
    /**
     * 启动渲染队列处理器
     */
    private fun startRenderingProcessor() {
        renderingScope.launch {
            renderQueue
                .buffer(capacity = 100)
                .collect { task ->
                    // 等待直到有空闲的渲染槽位
                    while (_activeRenderCount.value >= MAX_CONCURRENT_RENDERS) {
                        delay(50)
                    }
                    
                    Log.d(TAG, "🚀 开始处理渲染任务: ${task.mathId}")
                    
                    // 标记开始渲染
                    markRenderingStarted(task.mathId)
                    
                    try {
                        // 模拟渲染延迟（实际渲染会在LatexMath组件中进行）
                        delay(100)
                        
                        // 这里不直接执行渲染，而是由LatexMath组件监听状态变化后执行
                        // 渲染完成的标记会由LatexMath组件调用
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "渲染任务异常: ${task.mathId}", e)
                        markRenderingFailed(task.mathId)
                    }
                }
        }
    }
    
    /**
     * 重置所有状态（用于切换会话时）
     */
    fun resetAllStates() {
        Log.d(TAG, "🔄 重置所有渲染状态")
        renderStates.clear()
        _activeRenderCount.value = 0
    }
}

/**
 * 异步会话加载管理器
 */
object ConversationLoadManager {
    private const val TAG = "ConversationLoadManager"
    
    /**
     * 检查会话是否包含数学公式
     */
    fun conversationHasMath(messages: List<com.example.everytalk.data.DataClass.Message>): Boolean {
        return messages.any { message ->
            MathRenderingManager.hasRenderableMath(message.text)
        }
    }
    
    /**
     * 预检测会话是否包含数学公式，用于优化页面过渡
     */
    fun preCheckConversationMath(messages: List<com.example.everytalk.data.DataClass.Message>): Boolean {
        return messages.any { message ->
            MathRenderingManager.hasRenderableMath(message.text)
        }
    }
    
    /**
     * 异步加载包含数学公式的会话（优化版本）
     */
    suspend fun loadConversationAsyncOptimized(
        messages: List<com.example.everytalk.data.DataClass.Message>,
        hasMathPreChecked: Boolean,
        onConversationReady: () -> Unit,
        onPageTransitionComplete: () -> Unit,
        onMathRenderingStart: () -> Unit
    ) {
        Log.d(TAG, "🚀 开始优化异步加载会话，消息数量: ${messages.size}, 预检数学公式: $hasMathPreChecked")
        
        // Step 1: 立即进入会话，显示基础内容
        withContext(Dispatchers.Main.immediate) {
            onConversationReady()
        }
        
        // Step 2: 等待页面过渡完成
        withContext(Dispatchers.Main.immediate) {
            onPageTransitionComplete()
        }
        
        // Step 3: 如果预检发现有数学公式，进行优化处理
        if (hasMathPreChecked) {
            // 较短的延迟，因为已经预先知道有数学公式
            delay(150) // 150ms延迟，优化流畅度
            
            Log.d(TAG, "页面过渡完成，开始渲染预检的数学公式")
            
            // Step 4: 开始数学公式渲染流程
            withContext(Dispatchers.Main.immediate) {
                onMathRenderingStart()
            }
            
            // Step 5: 提交渲染任务（可以并行处理）
            messages.forEach { message ->
                if (MathRenderingManager.hasRenderableMath(message.text)) {
                    val mathBlocks = extractMathBlocks(message.text, message.id)
                    if (mathBlocks.isNotEmpty()) {
                        MathRenderingManager.submitMessageMathTasks(message.id, mathBlocks)
                    }
                }
            }
        }
        
        Log.d(TAG, "✅ 优化会话异步加载完成")
    }
    
    /**
     * 从消息文本中提取数学公式块
     */
    fun extractMathBlocks(text: String, messageId: String): List<Pair<String, String>> {
        val mathBlocks = mutableListOf<Pair<String, String>>()
        var index = 0
        
        // 提取 $...$ 行内数学公式
        val inlineMathPattern = Regex("""\$([^$]+)\$""")
        inlineMathPattern.findAll(text).forEach { match ->
            val mathId = "${messageId}_inline_${index++}"
            val latex = match.groupValues[1].trim()
            if (latex.isNotBlank()) {
                mathBlocks.add(mathId to latex)
            }
        }
        
        // 提取 $$...$$ 块级数学公式
        val blockMathPattern = Regex("""\$\$([^$]+)\$\$""")
        blockMathPattern.findAll(text).forEach { match ->
            val mathId = "${messageId}_block_${index++}"
            val latex = match.groupValues[1].trim()
            if (latex.isNotBlank()) {
                mathBlocks.add(mathId to latex)
            }
        }
        
        // 提取 \begin...\end 环境
        val envPattern = Regex("""\\begin\{([^}]+)\}(.*?)\\end\{\1\}""", RegexOption.DOT_MATCHES_ALL)
        envPattern.findAll(text).forEach { match ->
            val mathId = "${messageId}_env_${index++}"
            val latex = match.value.trim()
            if (latex.isNotBlank()) {
                mathBlocks.add(mathId to latex)
            }
        }
        
        Log.d(TAG, "从消息 $messageId 提取到 ${mathBlocks.size} 个数学公式块")
        return mathBlocks
    }
}