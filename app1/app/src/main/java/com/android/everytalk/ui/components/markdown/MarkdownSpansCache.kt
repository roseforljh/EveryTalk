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

    fun generateKey(contentKey: String, isDark: Boolean, textSize: Float): String {
        // Round text size to avoid float precision issues in key
        val sizeInt = (textSize * 10).toInt()
        return "${contentKey}_${isDark}_${sizeInt}"
    }
}