package com.example.everytalk.util

import android.util.Log

/**
 * 内容去重工具类
 * 用于检测和过滤重复的AI输出内容
 * 
 * 解决AI流式输出中的重复内容问题
 */
object ContentDeduplicator {
    private const val TAG = "ContentDeduplicator"
    private val recentContents = mutableListOf<String>()
    private const val MAX_HISTORY_SIZE = 20
    
    /**
     * 检测是否为重复内容
     * 
     * @param content 待检测的内容
     * @return true表示内容重复，应该跳过
     */
    fun isDuplicate(content: String): Boolean {
        if (content.isBlank()) return false
        
        val normalized = normalizeContent(content)
        
        // 检查是否在最近的内容中出现过
        val isDup = recentContents.any { recent ->
            val recentNormalized = normalizeContent(recent)
            // 完全相同或高度相似
            normalized == recentNormalized || 
            calculateSimilarity(normalized, recentNormalized) > 0.85
        }
        
        if (!isDup) {
            // 添加到历史记录
            recentContents.add(content)
            // 保持历史记录大小
            if (recentContents.size > MAX_HISTORY_SIZE) {
                recentContents.removeAt(0)
            }
        } else {
            Log.w(TAG, "Duplicate content detected (length=${content.length}): ${content.take(50)}...")
        }
        
        return isDup
    }
    
    /**
     * 归一化内容用于比较
     * 移除多余的空白字符，统一大小写
     */
    private fun normalizeContent(content: String): String {
        return content
            .trim()
            .replace(Regex("\\s+"), " ")  // 统一空白字符
            .lowercase()
    }
    
    /**
     * 计算两个字符串的相似度 (简单版本)
     * 
     * @return 0.0-1.0之间的相似度值
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        
        // 如果较短字符串完全包含在较长字符串中，认为高度相似
        if (longer.contains(shorter)) {
            return shorter.length.toDouble() / longer.length
        }
        
        // 计算共同字符数
        val matchingChars = shorter.toSet().intersect(longer.toSet()).size
        return matchingChars.toDouble() / longer.length
    }
    
    /**
     * 清除历史记录
     * 在开始新对话时调用
     */
    fun clear() {
        recentContents.clear()
        Log.d(TAG, "Content history cleared")
    }
    
    /**
     * 获取当前历史记录大小（用于调试）
     */
    fun getHistorySize(): Int = recentContents.size
}