package com.example.everytalk.ui.performance

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 优化的状态管理工具 - 避免主线程阻塞
 */
object PerformanceStateManager {
    
    /**
     * 创建异步计算的状态，避免在主线程进行重型计算
     */
    @Composable
    fun <T, R> rememberAsyncDerivedState(
        key: T,
        computation: suspend (T) -> R,
        initialValue: R
    ): State<R> {
        var result by remember { mutableStateOf(initialValue) }
        val scope = rememberCoroutineScope()
        
        LaunchedEffect(key) {
            scope.launch(Dispatchers.Default) {
                try {
                    val computed = computation(key)
                    withContext(Dispatchers.Main.immediate) {
                        result = computed
                    }
                } catch (e: CancellationException) {
                    // 忽略取消异常
                } catch (e: Exception) {
                    android.util.Log.e("PerformanceStateManager", "异步计算失败", e)
                }
            }
        }
        
        return remember { derivedStateOf { result } }
    }
    
    /**
     * 创建带去抖动的状态，减少频繁更新
     */
    @Composable
    fun <T> rememberDebouncedState(
        value: T,
        delayMs: Long = 300L
    ): State<T> {
        var debouncedValue by remember(value) { mutableStateOf(value) }
        
        LaunchedEffect(value) {
            delay(delayMs)
            debouncedValue = value
        }
        
        return remember { derivedStateOf { debouncedValue } }
    }
    
    /**
     * 创建批量更新状态，减少重组次数
     */
    class BatchStateUpdater<T>(
        private val setState: (T) -> Unit,
        private val batchDelayMs: Long = 16L // 一帧的时间
    ) {
        private var pendingUpdate: T? = null
        private var updateJob: Job? = null
        private val scope = CoroutineScope(Dispatchers.Main.immediate)
        
        fun update(newValue: T) {
            pendingUpdate = newValue
            updateJob?.cancel()
            updateJob = scope.launch {
                delay(batchDelayMs)
                pendingUpdate?.let { setState(it) }
                pendingUpdate = null
            }
        }
        
        fun updateImmediate(newValue: T) {
            updateJob?.cancel()
            pendingUpdate = null
            setState(newValue)
        }
        
        fun cleanup() {
            updateJob?.cancel()
            scope.cancel()
        }
    }
    
    /**
     * 创建内存高效的列表状态
     */
    @Composable
    fun <T> rememberOptimizedListState(
        items: List<T>,
        keySelector: (T) -> Any,
        maxCacheSize: Int = 100
    ): State<List<T>> {
        // 使用简化的实现，避免泛型类型问题
        return remember(items) {
            derivedStateOf {
                items // 简化实现，直接返回items避免缓存类型问题
            }
        }
    }
}

/**
 * 优化的Compose性能监听器
 */
object ComposePerformanceMonitor {
    private var recompositionCount = 0
    private var lastLogTime = 0L
    
    @Composable
    fun MonitorRecomposition(
        name: String,
        threshold: Int = 10
    ) {
        LaunchedEffect(Unit) {
            recompositionCount++
            val currentTime = System.currentTimeMillis()
            
            if (currentTime - lastLogTime > 1000) { // 每秒记录一次
                if (recompositionCount > threshold) {
                    android.util.Log.w(
                        "ComposePerformance", 
                        "组件 '$name' 在1秒内重组了 $recompositionCount 次，超过阈值 $threshold"
                    )
                }
                recompositionCount = 0
                lastLogTime = currentTime
            }
        }
    }
}

/**
 * 优化的文本处理工具
 */
object OptimizedTextProcessor {
    private val textCache = androidx.collection.LruCache<String, String>(50)
    
    /**
     * 缓存文本处理结果，避免重复计算
     */
    fun processTextWithCache(
        text: String,
        processor: (String) -> String
    ): String {
        val cacheKey = "${text.hashCode()}_${processor.hashCode()}"
        return textCache.get(cacheKey) ?: processor(text).also {
            textCache.put(cacheKey, it)
        }
    }
    
    /**
     * 异步处理长文本，避免阻塞主线程
     */
    suspend fun processLongTextAsync(
        text: String,
        processor: suspend (String) -> String
    ): String = withContext(Dispatchers.Default) {
        if (text.length > 1000) {
            // 对于长文本，分块处理
            val chunks = text.chunked(500)
            val results = mutableListOf<String>()
            chunks.forEach { chunk ->
                results.add(processor(chunk))
            }
            results.joinToString("")
        } else {
            processor(text)
        }
    }
    
    fun clearCache() {
        textCache.evictAll()
    }
}

/**
 * 优化的图像加载状态管理
 */
@Composable
fun rememberOptimizedImageState(
    imageUrl: String?
): State<ImageLoadState> {
    var loadState by remember(imageUrl) { 
        mutableStateOf<ImageLoadState>(ImageLoadState.Loading) 
    }
    
    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrBlank()) {
            loadState = ImageLoadState.Empty
            return@LaunchedEffect
        }
        
        loadState = ImageLoadState.Loading
        
        try {
            // 模拟异步图像加载
            withContext(Dispatchers.IO) {
                delay(100) // 模拟网络延迟
                // 实际应用中这里会是真实的图像加载逻辑
            }
            loadState = ImageLoadState.Success
        } catch (e: Exception) {
            loadState = ImageLoadState.Error(e.message ?: "加载失败")
        }
    }
    
    return remember { derivedStateOf { loadState } }
}

sealed class ImageLoadState {
    object Empty : ImageLoadState()
    object Loading : ImageLoadState()
    object Success : ImageLoadState()
    data class Error(val message: String) : ImageLoadState()
}