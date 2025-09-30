package com.example.everytalk.ui.components

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis

/**
 * 内存泄漏防护系统 - 主动监控和清理WebView内存
 * 
 * 防护策略：
 * 1. 实时内存监控：跟踪WebView内存使用情况
 * 2. 自动垃圾回收：智能触发GC和内存清理
 * 3. 泄漏检测：识别潜在的内存泄漏点
 * 4. 紧急保护：内存压力过大时的应急措施
 */
object MemoryLeakGuard {
    
    private var isInitialized = false
    private var monitoringJob: Job? = null
    private val memoryStats = MemoryStats()
    private val leakDetector = LeakDetector()
    
    // 内存阈值（字节）
    private const val MEMORY_WARNING_THRESHOLD = 50 * 1024 * 1024L // 50MB
    private const val MEMORY_CRITICAL_THRESHOLD = 80 * 1024 * 1024L // 80MB
    private const val MEMORY_EMERGENCY_THRESHOLD = 120 * 1024 * 1024L // 120MB
    
    /**
     * 初始化内存防护系统
     */
    fun initialize(application: Application) {
        if (isInitialized) return
        
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                startMonitoring()
            }
            
            override fun onStop(owner: LifecycleOwner) {
                performEmergencyCleanup()
            }
            
            override fun onDestroy(owner: LifecycleOwner) {
                stopMonitoring()
            }
        })
        
        isInitialized = true
    }
    
    /**
     * 开始内存监控
     */
    private fun startMonitoring() {
        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val currentMemory = getCurrentMemoryUsage()
                    memoryStats.record(currentMemory)
                    
                    when {
                        currentMemory > MEMORY_EMERGENCY_THRESHOLD -> {
                            handleEmergencyMemoryPressure()
                        }
                        currentMemory > MEMORY_CRITICAL_THRESHOLD -> {
                            handleCriticalMemoryPressure()
                        }
                        currentMemory > MEMORY_WARNING_THRESHOLD -> {
                            handleMemoryWarning()
                        }
                    }
                    
                    // 检测内存泄漏
                    leakDetector.checkForLeaks()
                    
                } catch (e: Exception) {
                    android.util.Log.w("MemoryLeakGuard", "监控过程中出现异常", e)
                }
                
                delay(5000) // 每5秒检查一次
            }
        }
    }
    
    /**
     * 停止内存监控
     */
    private fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
    }
    
    /**
     * 获取当前内存使用量
     */
    private fun getCurrentMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
    
    /**
     * 处理内存警告
     */
    private suspend fun handleMemoryWarning() {
        withContext(Dispatchers.Main) {
            // 内存警告时不清理缓存，只记录日志
            android.util.Log.i("MemoryLeakGuard", "内存警告 - 但保留渲染缓存避免界面闪烁")
        }
    }
    
    /**
     * 处理严重内存压力
     */
    private suspend fun handleCriticalMemoryPressure() {
        withContext(Dispatchers.Main) {
            // 严重内存压力时也先不清理缓存
            System.gc()
            
            android.util.Log.w("MemoryLeakGuard", "严重内存压力 - 已触发GC但保留渲染缓存")
        }
    }
    
    /**
     * 处理紧急内存压力
     */
    private suspend fun handleEmergencyMemoryPressure() {
        withContext(Dispatchers.Main) {
            // 只有在真正紧急的情况下才清理缓存
            NativeMathRenderer.clearCache()
            
            // 强制多次垃圾回收
            repeat(3) {
                System.gc()
                System.runFinalization()
                delay(100)
            }
            
            android.util.Log.e("MemoryLeakGuard", "紧急内存压力 - 已清理渲染缓存")
        }
    }
    
    /**
     * 执行紧急清理（外部调用）
     */
    fun performEmergencyCleanup() {
        CoroutineScope(Dispatchers.Main).launch {
            handleEmergencyMemoryPressure()
        }
    }
    
    /**
     * 获取内存统计信息
     */
    fun getMemoryStats(): MemoryStatsSnapshot {
        return memoryStats.getSnapshot()
    }
}

/**
 * 内存统计类
 */
private class MemoryStats {
    private val measurements = mutableListOf<MemoryMeasurement>()
    private val maxMeasurements = 100
    
    fun record(memoryUsage: Long) {
        synchronized(measurements) {
            measurements.add(MemoryMeasurement(System.currentTimeMillis(), memoryUsage))
            
            // 保持最近100次测量
            if (measurements.size > maxMeasurements) {
                measurements.removeAt(0)
            }
        }
    }
    
    fun getSnapshot(): MemoryStatsSnapshot {
        synchronized(measurements) {
            if (measurements.isEmpty()) {
                return MemoryStatsSnapshot(0, 0, 0, 0)
            }
            
            val current = measurements.last().memoryUsage
            val max = measurements.maxOfOrNull { it.memoryUsage } ?: 0
            val min = measurements.minOfOrNull { it.memoryUsage } ?: 0
            val average = measurements.map { it.memoryUsage }.average().toLong()
            
            return MemoryStatsSnapshot(current, max, min, average)
        }
    }
}

/**
 * 泄漏检测器
 */
private class LeakDetector {
    private val webViewCount = AtomicLong(0)
    private var lastCheck = System.currentTimeMillis()
    private val suspiciousGrowthThreshold = 5 // 连续5次检查都在增长
    private var consecutiveGrowth = 0
    
    private var lastMemory = 0L
    
    fun checkForLeaks() {
        // 简化处理，原生渲染器内存管理更简单
        val currentMemory = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        if (lastMemory > 0 && currentMemory > lastMemory * 1.5) {
            android.util.Log.w("MemoryLeakGuard", 
                "检测到内存使用增长：当前 ${currentMemory / 1024 / 1024}MB")
            
            // 触发清理
            NativeMathRenderer.clearCache()
        }
        
        lastMemory = currentMemory
        lastCheck = System.currentTimeMillis()
    }
}

/**
 * 内存统计快照
 */
data class MemoryStatsSnapshot(
    val currentMemory: Long,
    val maxMemory: Long,
    val minMemory: Long,
    val averageMemory: Long
) {
    fun formatMemory(bytes: Long): String {
        val mb = bytes / (1024 * 1024)
        return "${mb}MB"
    }
    
    override fun toString(): String {
        return "Memory(current=${formatMemory(currentMemory)}, max=${formatMemory(maxMemory)}, " +
                "min=${formatMemory(minMemory)}, avg=${formatMemory(averageMemory)})"
    }
}

/**
 * 内存测量数据点
 */
private data class MemoryMeasurement(
    val timestamp: Long,
    val memoryUsage: Long
)

/**
 * Compose集成 - 内存监控Hook
 */
@Composable
fun MemoryMonitorEffect() {
    val context = LocalContext.current
    
    LaunchedEffect(Unit) {
        if (context.applicationContext is Application) {
            MemoryLeakGuard.initialize(context.applicationContext as Application)
        }
    }
    
    // 定期输出内存统计（仅在Debug模式）
    LaunchedEffect(Unit) {
        if (android.util.Log.isLoggable("MemoryLeakGuard", android.util.Log.DEBUG)) {
            while (true) {
                delay(30000) // 每30秒
                val stats = MemoryLeakGuard.getMemoryStats()
                android.util.Log.d("MemoryLeakGuard", "内存统计: $stats")
            }
        }
    }
}