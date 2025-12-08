package com.android.everytalk.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

/**
 * 简单的速率限制器，用于确保操作之间的最小间隔。
 * 用于限制 API 请求频率，防止触发限流错误。
 */
import java.util.concurrent.atomic.AtomicLong

class RateLimiter(
    private val minIntervalMillis: Long
) {
    private val mutex = Mutex()
    private var lastActionTime = 0L
    
    // 动态退避时间
    private val backoffMillis = AtomicLong(0)

    /**
     * 获取许可，必要时挂起以保持最小间隔。
     *
     * 该方法是线程安全的，并且会按顺序排队请求。
     */
    suspend fun acquire() {
        // 即使 minIntervalMillis 为 0，如果有 backoff 也需要等待
        if (minIntervalMillis <= 0 && backoffMillis.get() <= 0) return

        mutex.withLock {
            val currentBackoff = backoffMillis.get()
            val effectiveInterval = max(minIntervalMillis, currentBackoff)
            
            val now = System.currentTimeMillis()
            val timeSinceLast = now - lastActionTime
            val waitTime = max(0L, effectiveInterval - timeSinceLast)

            if (waitTime > 0) {
                delay(waitTime)
            }
            
            // 如果存在退避，每次成功获取许可后尝试减少退避时间（线性恢复）
            if (currentBackoff > 0) {
                val newBackoff = max(0L, currentBackoff - 1000) // 每次成功减少 1s
                backoffMillis.set(newBackoff)
            }
            
            lastActionTime = System.currentTimeMillis()
        }
    }
    
    /**
     * 报告发生了限流错误，触发退避机制
     */
    fun reportRateLimitError() {
        // 每次错误增加 2秒 退避，最高 10秒
        backoffMillis.updateAndGet { current ->
            (current + 2000).coerceAtMost(10000)
        }
    }
}