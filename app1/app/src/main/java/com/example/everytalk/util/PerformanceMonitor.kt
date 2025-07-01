package com.example.everytalk.util

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object PerformanceMonitor {
    val metrics = ConcurrentHashMap<String, Long>()

    inline fun <T> measure(name: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val duration = System.currentTimeMillis() - start
            metrics[name] = duration
            if (duration > 100) { // Log if operation takes more than 100ms
                Log.w("Performance", "$name took ${duration}ms")
            }
        }
    }

    @Suppress("unused")
    fun getMetrics(): Map<String, Long> {
        return metrics.toMap()
    }

    @Suppress("unused")
    fun reset() {
        metrics.clear()
    }
}