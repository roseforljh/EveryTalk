package com.android.everytalk.util

import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * 改进版内容去重器：
 * - 使用 SHA-256 哈希避免 hashCode 碰撞
 * - LRU 缓存防止内存无限增长
 * - 过期清理，避免长时间占用
 *
 * 典型用法：
 * val dedup = ImprovedContentDeduplicator(maxCacheSize = 1000, expirationMs = 60_000)
 * if (dedup.checkAndAdd(chunk)) {
 *     // 这是新内容，执行显示/拼接
 * }
 */
class ImprovedContentDeduplicator(
    private val maxCacheSize: Int = 1000,
    private val expirationMs: Long = 60_000
) {

    data class Entry(
        val timestamp: Long
    )

    /**
     * LRU 实现：当 size 超过 maxCacheSize 时自动移除最旧条目
     */
    private inner class LruMap<K, V>(
        private val capacity: Int
    ) : LinkedHashMap<K, V>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > capacity
        }
    }

    private val map = LruMap<String, Entry>(maxCacheSize)

    /**
     * 计算 SHA-256
     */
    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 检查是否为新内容，并写入缓存
     * 返回 true 表示是新内容（未见过或已过期），false 表示重复内容
     */
    @Synchronized
    fun checkAndAdd(content: String): Boolean {
        if (content.isBlank()) return false
        val now = System.currentTimeMillis()
        cleanup(now)

        val key = sha256(content)
        val existing = map[key]
        return if (existing == null) {
            map[key] = Entry(timestamp = now)
            true
        } else {
            // 已存在，但如果过期则视为新内容
            val expired = now - existing.timestamp > expirationMs
            if (expired) {
                map[key] = Entry(timestamp = now)
                true
            } else {
                false
            }
        }
    }

    /**
     * 去重：新内容则返回原文，否则返回 null
     */
    fun deduplicate(content: String): String? {
        return if (checkAndAdd(content)) content else null
    }

    /**
     * 清理过期条目
     */
    @Synchronized
    fun cleanup(now: Long = System.currentTimeMillis()) {
        if (map.isEmpty()) return
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.timestamp > expirationMs) {
                it.remove()
            }
        }
    }

    /**
     * 清空缓存
     */
    @Synchronized
    fun clear() {
        map.clear()
    }

    @Synchronized
    fun stats(): Stats = Stats(size = map.size, capacity = maxCacheSize)

    data class Stats(val size: Int, val capacity: Int)
}