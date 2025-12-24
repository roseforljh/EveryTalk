package com.android.everytalk.ui.components.syntax

import androidx.compose.ui.text.AnnotatedString
import java.security.MessageDigest

/**
 * 高亮结果缓存
 * 
 * 使用 LRU 策略缓存高亮结果，避免重复解析
 */
object HighlightCache {
    
    private const val MAX_CACHE_SIZE = 100
    
    // LRU 缓存实现
    private val cache = object : LinkedHashMap<String, AnnotatedString>(
        MAX_CACHE_SIZE,
        0.75f,
        true // accessOrder = true 实现 LRU
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnnotatedString>): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }
    
    /**
     * 生成缓存键
     * 
     * @param code 源代码
     * @param language 编程语言
     * @param isDark 是否暗色主题
     * @return 缓存键
     */
    // 缓存版本号 - 更新高亮规则时递增此值以使旧缓存失效
    private const val CACHE_VERSION = 6
    
    fun generateKey(code: String, language: String?, isDark: Boolean): String {
        // 对于短代码，直接使用原文
        // 对于长代码，使用 MD5 哈希
        val codeKey = if (code.length <= 100) {
            code
        } else {
            md5(code)
        }
        
        val langKey = language?.trim()?.lowercase() ?: "plain"
        val themeKey = if (isDark) "dark" else "light"
        
        return "v${CACHE_VERSION}:$langKey:$themeKey:$codeKey"
    }
    
    /**
     * 从缓存获取高亮结果
     */
    @Synchronized
    fun get(key: String): AnnotatedString? {
        return cache[key]
    }
    
    /**
     * 将高亮结果存入缓存
     */
    @Synchronized
    fun put(key: String, result: AnnotatedString) {
        cache[key] = result
    }
    
    /**
     * 清空缓存
     * 主题切换时调用
     */
    @Synchronized
    fun clear() {
        cache.clear()
    }
    
    /**
     * 获取当前缓存大小
     */
    @Synchronized
    fun size(): Int = cache.size
    
    /**
     * 计算 MD5 哈希
     */
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}