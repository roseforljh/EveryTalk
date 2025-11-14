package com.android.everytalk.util.messageprocessor

import android.util.Log

/**
 * 智能验证并处理 content_final 事件，避免盲目替换导致的内容丢失或结构破坏。
 *
 * 使用场景：
 * - 当后端发送 content_final 时，先通过 shouldReplaceCurrent 判断是否应整体替换；
 * - 如果不满足整体替换条件，可调用 mergeContent 做保守合并，尽量保持现有连续性（尤其是代码块/表格）。
 */
object ContentFinalValidator {

    private const val TAG = "ContentFinalValidator"
    
    // 预编译的正则表达式，避免重复编译
    private val CODE_FENCE_REGEX = Regex("```")
    private val MATH_FENCE_REGEX = Regex("\\$\\$")
    private val EXCESSIVE_NEWLINES_REGEX = Regex("\n{3,}")
    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * 判断是否应该用最终文本整体替换当前累积文本。
     *
     * 规则优先级（从高到低）：
     * 1) 当前为空 → 替换
     * 2) 最终文本为空 → 不替换
     * 3) 最终文本明显更短（小于当前的50%）→ 不替换（可能后端异常截断）
     * 4) 前缀一致且最终文本更长（严格前缀扩展）→ 替换（最安全）
     * 5) 长度相近（±15%）且前缀高相似（前100字符相等）→ 替换（多为清理版）
     * 6) 检测格式化改进（换行清理、空白优化）→ 倾向替换
     * 7) 代码围栏/反引号数量：若最终文本的围栏闭合性更好 → 倾向替换
     * 8) 默认保守：不替换
     */
    fun shouldReplaceCurrent(currentContent: String, finalContent: String): Boolean {
        if (currentContent.isEmpty()) {
            Log.d(TAG, "Current is empty, will replace with final.")
            return true
        }
        if (finalContent.isEmpty()) {
            Log.w(TAG, "Final content is empty, keep current.")
            return false
        }

        // 过短保护：避免非预期回退
        if (finalContent.length < currentContent.length * 0.5) {
            Log.w(TAG, "Final content is significantly shorter (${finalContent.length} < ${currentContent.length * 0.5}), keep current.")
            return false
        }

        // 绝对安全：严格前缀扩展
        if (finalContent.length > currentContent.length && finalContent.startsWith(currentContent)) {
            Log.d(TAG, "Final is a strict prefix extension; replace.")
            return true
        }

        // 近似清理：长度接近且前缀高相似（放宽到 ±15%，以支持换行清理）
        val prefixLen = minOf(100, currentContent.length, finalContent.length)
        val currentPrefix = currentContent.take(prefixLen)
        val finalPrefix = finalContent.take(prefixLen)
        val lengthClose = finalContent.length in (currentContent.length * 0.85).toInt()..(currentContent.length * 1.15).toInt()
        if (lengthClose && currentPrefix == finalPrefix) {
            Log.d(TAG, "Length close (±15%) and prefix match; treat as cleaned version; replace.")
            return true
        }

        // 🎯 新增：检测格式化改进（换行清理、空白优化）
        val formattingImproved = detectFormattingImprovement(currentContent, finalContent)
        if (formattingImproved) {
            Log.d(TAG, "Detected formatting improvement (e.g., newline cleanup); replace.")
            return true
        }

        // 代码块/反引号围栏闭合性评估：闭合更好→更可信
        val currentFenceScore = fenceClosureScore(currentContent)
        val finalFenceScore = fenceClosureScore(finalContent)
        if (finalFenceScore > currentFenceScore) {
            Log.d(TAG, "Final has better fence closure (current=$currentFenceScore, final=$finalFenceScore); replace.")
            return true
        }

        Log.d(TAG, "Conservative decision: keep current.")
        return false
    }

    /**
     * 无法整体替换时的保守合并策略：
     * - 若最终文本是当前文本的“清理扩展”（包含当前前缀的较长版本）→ 用最终文本
     * - 若最终文本以当前开头（追加了尾部）→ 用最终文本
     * - 否则保留当前，避免闪烁/倒退
     */
    fun mergeContent(currentContent: String, finalContent: String): String {
        if (finalContent.length > currentContent.length &&
            finalContent.startsWith(currentContent.take(minOf(50, currentContent.length)))) {
            Log.d(TAG, "Final appears to extend/clean current; merge by using final.")
            return finalContent
        }

        if (finalContent.startsWith(currentContent)) {
            Log.d(TAG, "Final strictly starts with current; merge by using final.")
            return finalContent
        }

        Log.d(TAG, "Conservative merge: keep current to avoid visual reset.")
        return currentContent
    }

    /**
     * 简单的围栏闭合评分：
     * - 统计 ``` 的偶数配对闭合情况，配对越多分数越高；
     * - 统计成对 $$ 的闭合情况；
     * - 返回综合分数用于比较（越高表示闭合性越好）。
     */
    private fun fenceClosureScore(text: String): Int {
        if (text.isEmpty()) return 0
        var score = 0

        // ``` 围栏计数与配对
        val tripleBacktickCount = CODE_FENCE_REGEX.findAll(text).count()
        if (tripleBacktickCount >= 2) {
            score += (tripleBacktickCount / 2)
        }
        // $$ 围栏计数与配对
        val doubleDollarCount = MATH_FENCE_REGEX.findAll(text).count()
        if (doubleDollarCount >= 2) {
            score += (doubleDollarCount / 2)
        }
        return score
    }

    /**
     * 检测格式化改进：判断最终文本是否比当前文本有更好的格式
     * 
     * 检测指标：
     * 1. 换行符显著减少（清理了多余换行）
     * 2. 行尾空白减少
     * 3. 内容相似度高（去除空白后的文本接近）
     * 
     * 返回 true 表示最终文本包含格式化改进
     */
    private fun detectFormattingImprovement(currentContent: String, finalContent: String): Boolean {
        // 计算换行符数量
        val currentNewlines = currentContent.count { it == '\n' }
        val finalNewlines = finalContent.count { it == '\n' }
        
        // 计算连续换行符数量（多余的换行），复用预编译正则
        val currentExcessiveNewlines = EXCESSIVE_NEWLINES_REGEX.findAll(currentContent).count()
        val finalExcessiveNewlines = EXCESSIVE_NEWLINES_REGEX.findAll(finalContent).count()
        
        // 计算行尾空白总数
        val currentTrailingSpaces = currentContent.lines().sumOf { line ->
            line.length - line.trimEnd().length
        }
        val finalTrailingSpaces = finalContent.lines().sumOf { line ->
            line.length - line.trimEnd().length
        }
        
        // 计算去除所有空白后的内容相似度
        val currentNormalized = currentContent.replace(WHITESPACE_REGEX, " ").trim()
        val finalNormalized = finalContent.replace(WHITESPACE_REGEX, " ").trim()
        val contentSimilar = currentNormalized.length > 0 &&
            finalNormalized.length in (currentNormalized.length * 0.9).toInt()..(currentNormalized.length * 1.1).toInt()
        
        // 判断是否有格式化改进
        val hasNewlineCleanup = (currentExcessiveNewlines > 0 && finalExcessiveNewlines < currentExcessiveNewlines) ||
            (currentNewlines > finalNewlines * 1.2 && finalNewlines > 0)
        val hasTrailingSpaceCleanup = currentTrailingSpaces > finalTrailingSpaces * 1.5
        
        val hasImprovement = contentSimilar && (hasNewlineCleanup || hasTrailingSpaceCleanup)
        
        if (hasImprovement) {
            Log.d(TAG, "Formatting improvement detected:")
            Log.d(TAG, "  Newlines: $currentNewlines -> $finalNewlines")
            Log.d(TAG, "  Excessive newlines: $currentExcessiveNewlines -> $finalExcessiveNewlines")
            Log.d(TAG, "  Trailing spaces: $currentTrailingSpaces -> $finalTrailingSpaces")
            Log.d(TAG, "  Content similar: $contentSimilar")
        }
        
        return hasImprovement
    }
}