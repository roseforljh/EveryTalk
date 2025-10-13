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
     * 计算两个字符串的相似度（改进版 - 使用 Levenshtein 距离）
     *
     * @return 0.0-1.0之间的相似度值
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        
        // 长度差异过大，直接认为不相似
        if (longer.length > shorter.length * 2) {
            return 0.0
        }
        
        // 如果较短字符串完全包含在较长字符串中，认为高度相似
        if (longer.contains(shorter)) {
            return shorter.length.toDouble() / longer.length
        }
        
        // 使用 Levenshtein 距离计算编辑距离
        val distance = levenshteinDistance(s1, s2)
        val maxLength = maxOf(s1.length, s2.length)
        
        // 相似度 = 1 - (编辑距离 / 最大长度)
        return 1.0 - (distance.toDouble() / maxLength)
    }
    
    /**
     * 计算 Levenshtein 距离（编辑距离）
     * 表示将一个字符串转换为另一个字符串所需的最少编辑操作数
     *
     * @return 编辑距离
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        
        // 优化：如果其中一个为空，距离就是另一个的长度
        if (len1 == 0) return len2
        if (len2 == 0) return len1
        
        // 动态规划矩阵（使用一维数组优化空间）
        var prevRow = IntArray(len2 + 1) { it }
        
        for (i in 1..len1) {
            val currRow = IntArray(len2 + 1)
            currRow[0] = i
            
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                currRow[j] = minOf(
                    currRow[j - 1] + 1,      // 插入
                    prevRow[j] + 1,          // 删除
                    prevRow[j - 1] + cost    // 替换
                )
            }
            
            prevRow = currRow
        }
        
        return prevRow[len2]
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