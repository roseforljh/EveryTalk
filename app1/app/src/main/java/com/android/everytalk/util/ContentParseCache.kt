package com.android.everytalk.util

import android.util.LruCache
import com.android.everytalk.ui.components.ContentPart

/**
 * 全局内容解析缓存（LRU）
 * - 目的：避免 LazyColumn 回收后重新进入视口触发重复解析，降低重组卡顿
 * - Key 建议：消息ID（messageId）或稳定 contentKey
 * - 容量：按条目数量（非字节），默认 64，可按需要调整
 */
object ContentParseCache {
    private const val DEFAULT_SIZE = 64

    // 以条目数作为容量，LRU 自动淘汰最久未使用的解析结果
    private val cache = object : LruCache<String, List<ContentPart>>(DEFAULT_SIZE) {
        override fun sizeOf(key: String, value: List<ContentPart>): Int {
            // 按条目计数，每条缓存视为1个单位
            return 1
        }
    }

    @Synchronized
    fun get(key: String?): List<ContentPart>? {
        if (key.isNullOrBlank()) return null
        return cache.get(key)
    }

    @Synchronized
    fun put(key: String?, value: List<ContentPart>) {
        if (key.isNullOrBlank()) return
        cache.put(key, value)
    }

    @Synchronized
    fun remove(key: String?) {
        if (key.isNullOrBlank()) return
        cache.remove(key)
    }

    @Synchronized
    fun clear() {
        cache.evictAll()
    }

    @Synchronized
    fun size(): Int = cache.size()

    @Synchronized
    fun maxSize(): Int = DEFAULT_SIZE
}