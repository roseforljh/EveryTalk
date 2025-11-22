package com.android.everytalk.ui.components.markdown

import android.text.Spanned
import android.util.LruCache

/**
 * Cache for pre-rendered Markdown Spanned objects.
 * This avoids re-parsing Markdown when scrolling back and forth in LazyColumn.
 */
object MarkdownSpansCache {
    // Cache size: 100 items to cover visible items + buffer for smooth scrolling
    private const val CACHE_SIZE = 100

    private val cache = object : LruCache<String, Spanned>(CACHE_SIZE) {
        override fun sizeOf(key: String, value: Spanned): Int {
            return 1 // Count by item
        }
    }

    fun get(key: String): Spanned? {
        return cache.get(key)
    }

    fun put(key: String, value: Spanned) {
        cache.put(key, value)
    }

    fun clear() {
        cache.evictAll()
    }

    /**
     * Remove entries related to a specific content key (message ID)
     * This is needed when a message is edited to force re-rendering
     */
    fun remove(contentKey: String) {
        // Since keys are composite (id_isDark_size), we need to iterate and remove matching keys
        // LruCache doesn't support iteration over keys easily or removing by pattern.
        // However, for a specific message edit, we can try to remove the most likely variants
        // or just clear the cache if needed. Given the cache size is small (100),
        // iterating a snapshot of keys would be ideal but LruCache doesn't expose keys.
        
        // Workaround: Since we can't easily find all keys starting with contentKey,
        // and editing is a rare user action, we can accept that we might not clear
        // all variants (e.g. different text sizes).
        // But wait, we can construct the keys if we know the current theme and text size.
        // Since we don't have that context here, and LruCache is limited,
        // maybe we should just evict all? It's safe and simple.
        // Or we can expose a method to remove by exact key if the caller knows it.
        
        // Better approach for now: Evict all. Editing is rare enough that rebuilding
        // the cache for visible items (max ~10) is negligible.
        cache.evictAll()
    }

    fun generateKey(contentKey: String, isDark: Boolean, textSize: Float): String {
        // Round text size to avoid float precision issues in key
        val sizeInt = (textSize * 10).toInt()
        return "${contentKey}_${isDark}_${sizeInt}"
    }
}