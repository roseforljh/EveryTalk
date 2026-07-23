package com.android.everytalk.ui.components.syntax

import androidx.compose.ui.text.AnnotatedString
import java.security.MessageDigest
import java.util.Locale

/**
 * 高亮结果缓存
 * 
 * 使用 LRU 策略缓存高亮结果，避免重复解析
 */
object HighlightCache {
    internal const val MAX_HIGHLIGHT_CODE_CHARS = 20_000
    internal const val MAX_STREAMING_HIGHLIGHT_CODE_CHARS = 500
    internal const val MAX_CACHE_ENTRIES = 64
    internal const val MAX_CACHE_WEIGHT = 256_000

    private val cache = LinkedHashMap<String, AnnotatedString>(MAX_CACHE_ENTRIES, 0.75f, true)
    private var cacheWeight = 0

    // 缓存版本号 - 更新高亮规则时递增此值以使旧缓存失效
    private const val CACHE_VERSION = 6

    fun shouldHighlight(code: String, isStreaming: Boolean): Boolean {
        val limit = if (isStreaming) MAX_STREAMING_HIGHLIGHT_CODE_CHARS else MAX_HIGHLIGHT_CODE_CHARS
        return code.length <= limit
    }

    fun highlight(
        code: String,
        language: String?,
        isDark: Boolean,
        theme: SyntaxHighlightTheme,
    ): AnnotatedString {
        val key = generateKey(code, language, isDark)
        get(key)?.let { return it }
        return SyntaxHighlighter.highlight(code, language, theme).also { put(key, it) }
    }

    private fun generateKey(code: String, language: String?, isDark: Boolean): String {
        val langKey = language?.trim()?.lowercase(Locale.ROOT) ?: "plain"
        val themeKey = if (isDark) "dark" else "light"
        return "v${CACHE_VERSION}:$langKey:$themeKey:${md5(code)}"
    }

    @Synchronized
    private fun get(key: String): AnnotatedString? = cache[key]

    @Synchronized
    private fun put(key: String, result: AnnotatedString) {
        cache.put(key, result)?.let { previous ->
            cacheWeight -= estimateWeight(key, previous)
        }
        cacheWeight += estimateWeight(key, result)
        trimToLimits()
    }

    @Synchronized
    internal fun clear() {
        cache.clear()
        cacheWeight = 0
    }

    @Synchronized
    internal fun size(): Int = cache.size

    private fun trimToLimits() {
        val iterator = cache.entries.iterator()
        while ((cache.size > MAX_CACHE_ENTRIES || cacheWeight > MAX_CACHE_WEIGHT) && iterator.hasNext()) {
            val eldest = iterator.next()
            cacheWeight -= estimateWeight(eldest.key, eldest.value)
            iterator.remove()
        }
    }

    private fun estimateWeight(key: String, value: AnnotatedString): Int {
        return key.length + value.length +
            (value.spanStyles.size + value.paragraphStyles.size) * 32
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
