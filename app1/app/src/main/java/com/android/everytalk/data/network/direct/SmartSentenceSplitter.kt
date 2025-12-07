package com.android.everytalk.data.network.direct

import android.util.Log

/**
 * 智能句子分割器
 * 
 * 用于语音模式中的 LLM 输出分割，以提前触发 TTS，减少首次语音输出延迟。
 * 
 * 移植自 Python 版本：ET-Backend-code/eztalk_proxy/services/smart_splitter.py
 * 
 * 分割策略优先级：
 * 1. 首句快速触发：对第一个片段使用更激进的策略，遇到任意标点即分割
 * 2. 强制分割点：句末标点（。！？.!?）、换行
 * 3. 推荐分割点：逗号、分号等 + 满足最小长度
 * 4. 紧急分割点：超过最大长度时强制在任意标点处分割
 * 5. 兜底分割：超过绝对最大长度时在空格/字符边界分割
 */
class SmartSentenceSplitter(
    private val minLength: Int = 8,
    private val preferredLength: Int = 20,
    private val maxLength: Int = 50,
    private val absoluteMax: Int = 80,
    // 首句快速触发配置
    private val firstSegmentMinLength: Int = 2,      // 首句最小长度（更激进）
    private val firstSegmentMaxWait: Int = 15,       // 首句最大等待长度
    private val enableImmediateTriggers: Boolean = true,  // 启用立即触发模式
    private val immediateTriggers: Set<String> = DEFAULT_IMMEDIATE_TRIGGERS
) {
    companion object {
        private const val TAG = "SmartSentenceSplitter"
        
        // 常见开场白，遇到即立即触发（无需等待更多文本）
        val DEFAULT_IMMEDIATE_TRIGGERS = setOf(
            // 中文开场白 - 肯定回应
            "好的，", "好的。", "好的!", "好，", "好!",
            "嗯，", "嗯。", "嗯!", "嗯嗯，", "嗯嗯。",
            "当然，", "当然。", "当然!", "当然可以，", "当然可以。", "当然可以!",
            "是的，", "是的。", "是的!", "对的，", "对的。", "对!",
            "可以，", "可以。", "可以!", "没问题，", "没问题。", "没问题!",
            "好啊，", "好啊。", "好啊!", "行，", "行。", "行!",
            "明白，", "明白。", "明白!", "了解，", "了解。", "了解!",
            "收到，", "收到。", "收到!",
            "好呀，", "好呀。", "好呀!",
            "哦，", "哦。", "哦!", "噢，", "噢。",
            "啊，", "啊。", "呀，", "呀。",
            // 中文开场白 - 思考/过渡
            "这个，", "这个。", "那个，", "那个。",
            "其实，", "其实。", "实际上，", "实际上。",
            "首先，", "首先。", "然后，", "然后。",
            "关于这个问题，", "关于这个，",
            "让我想想，", "让我看看，",
            "我觉得，", "我认为，", "我想，",
            // 中文开场白 - 打招呼
            "你好，", "你好。", "你好!",
            "哈喽，", "哈喽。", "嗨，", "嗨。",
            "早上好，", "下午好，", "晚上好，",
            // 英文开场白
            "OK，", "OK。", "OK!", "Sure，", "Sure。", "Sure!",
            "Yes，", "Yes。", "Yes!", "Okay，", "Okay。", "Okay!",
            "Of course，", "Of course。", "Of course!",
            "Certainly，", "Certainly。", "Certainly!",
            "Absolutely，", "Absolutely。", "Absolutely!",
            "Well，", "Well。", "So，", "So。",
            "Actually，", "Actually。",
            "I think，", "I think。",
            "Let me，", "Let's，",
            "Hello，", "Hello。", "Hello!",
            "Hi，", "Hi。", "Hi!"
        )
        
        // 强分割点（句末标点）
        private val STRONG_ENDINGS = Regex("[。！？.!?\\n]")
        
        // 弱分割点（逗号、分号等）
        private val WEAK_ENDINGS = Regex("[，,；;：:、]")
        
        // 任意标点
        private val ANY_PUNCTUATION = Regex("[，,；;：:、。！？.!?\\n]")
        
        // 小数/时间保护
        private val DECIMAL_PATTERN = Regex("\\d+[.,]$")
        private val TIME_PATTERN = Regex("[\\d]+[:：]$")
        
        // 未闭合的引号/书名号
        private val UNCLOSED_BOOK_QUOTE = Regex("[《「『【][^》」』】]*$")
        private val UNCLOSED_CHINESE_QUOTE = Regex("“[^”]*$")
        private val UNCLOSED_ENGLISH_QUOTE = Regex("\"[^\"]*$")
        private val UNCLOSED_SINGLE_QUOTE = Regex("'[^']*$")
        
        // 不应断开的短语前缀（尾部匹配）
        private val NO_BREAK_PREFIXES = listOf(
            "请稍", "稀等", "让我", "我来",
            "比如", "例如", "也就", "就是",
            "首先", "其次", "最后", "然后",
            "一方", "另一", "此外", "另外",
            "因为", "所以", "但是", "而且",
            "如果", "那么", "否则"
        )
    }
    
    /**
     * 分割结果
     */
    data class SplitResult(
        val segments: List<String>,    // 可以发送 TTS 的片段
        val remainder: String          // 剩余未完成的 buffer
    )
    
    // 首句状态标记
    private var firstSegmentEmitted = false
    
    /**
     * 重置分割器状态（新对话开始时调用）
     */
    fun reset() {
        firstSegmentEmitted = false
        Log.d(TAG, "SmartSentenceSplitter reset")
    }
    
    /**
     * 检查是否匹配立即触发的开场白
     * 
     * @param text 待检查的文本
     * @return 匹配的分割点位置，未匹配返回 null
     */
    private fun checkImmediateTrigger(text: String): Int? {
        if (!enableImmediateTriggers) return null
        
        for (trigger in immediateTriggers) {
            if (text.startsWith(trigger)) {
                Log.i(TAG, "[FirstSegment] 立即触发匹配: '$trigger'")
                return trigger.length
            }
        }
        
        return null
    }
    
    /**
     * 为首句寻找快速分割点（更激进的策略）
     * 
     * 策略：
     * 1. 检查立即触发的开场白
     * 2. 在任意标点处分割（只要达到最小长度）
     * 3. 超过最大等待长度时强制分割
     * 
     * @return 分割位置，或 null（不分割）
     */
    private fun findFirstSegmentSplitPoint(text: String): Int? {
        val length = text.length
        
        // 1. 检查立即触发
        val triggerPos = checkImmediateTrigger(text)
        if (triggerPos != null) {
            return triggerPos
        }
        
        // 2. 检查是否处于保护模式
        if (isProtected(text)) {
            // 首句保护模式下，如果超过最大等待长度，忽略保护
            if (length < firstSegmentMaxWait) {
                return null
            }
        }
        
        // 3. 寻找任意标点分割点（首句使用更小的最小长度）
        val matches = ANY_PUNCTUATION.findAll(text)
        for (match in matches) {
            val pos = match.range.last + 1
            if (pos >= firstSegmentMinLength) {
                if (!isPositionProtected(text, match.range.first)) {
                    Log.i(TAG, "[FirstSegment] 快速分割点: pos=$pos, char='${text[match.range.first]}'")
                    return pos
                }
            }
        }
        
        // 4. 超过最大等待长度，强制分割
        if (length >= firstSegmentMaxWait) {
            // 尝试在空格处分割
            val spacePos = text.lastIndexOf(' ', firstSegmentMaxWait - 1)
            if (spacePos >= firstSegmentMinLength) {
                Log.i(TAG, "[FirstSegment] 强制分割（空格）: pos=${spacePos + 1}")
                return spacePos + 1
            }
            // 硬切
            Log.i(TAG, "[FirstSegment] 强制分割（硬切）: pos=$firstSegmentMaxWait")
            return firstSegmentMaxWait
        }
        
        return null
    }
    
    /**
     * 分割 buffer，返回可发送的片段和剩余内容
     * 
     * @param buffer 待分割的文本
     * @return SplitResult 包含可发送片段列表和剩余内容
     */
    fun split(buffer: String): SplitResult {
        val segments = mutableListOf<String>()
        var remaining = buffer
        
        while (remaining.isNotEmpty()) {
            // 首句使用快速分割策略
            if (!firstSegmentEmitted) {
                val splitPoint = findFirstSegmentSplitPoint(remaining)
                if (splitPoint != null && splitPoint > 0) {
                    val segment = remaining.substring(0, splitPoint).trim()
                    remaining = remaining.substring(splitPoint).trimStart()
                    if (segment.isNotEmpty()) {
                        segments.add(segment)
                        firstSegmentEmitted = true
                        Log.i(TAG, "[FirstSegment] ✓ 首句输出 (${segment.length} chars): '$segment'")
                    }
                    continue
                } else {
                    // 首句尚未准备好
                    break
                }
            }
            
            // 后续片段使用常规策略
            val splitPoint = findBestSplitPoint(remaining)
            
            if (splitPoint == null || splitPoint <= 0) {
                // 无法分割或保护模式激活，等待更多内容
                break
            }
            
            // 提取片段
            val segment = remaining.substring(0, splitPoint).trim()
            remaining = remaining.substring(splitPoint).trimStart()
            
            if (segment.isNotEmpty()) {
                segments.add(segment)
                Log.d(TAG, "Extracted segment (${segment.length} chars): '${segment.take(30)}...'")
            }
        }
        
        return SplitResult(segments = segments, remainder = remaining)
    }
    
    /**
     * 寻找最佳分割点
     * 
     * @return 分割位置（包含标点），null（不分割），或 0（在保护区内）
     */
    private fun findBestSplitPoint(text: String): Int? {
        val length = text.length
        
        // 1. 检查是否处于保护模式
        if (isProtected(text)) {
            Log.d(TAG, "Buffer is protected, waiting for more content")
            return 0
        }
        
        // 2. 寻找强制分割点（句末标点）
        var strongMatch: Int? = null
        for (match in STRONG_ENDINGS.findAll(text)) {
            val pos = match.range.last + 1
            // 检查分割后的长度是否合理
            if (pos >= minLength) {
                // 检查该位置是否在保护区内
                if (!isPositionProtected(text, match.range.first)) {
                    strongMatch = pos
                    break  // 找到第一个有效的强分割点
                }
            }
        }
        
        if (strongMatch != null) {
            Log.d(TAG, "Found strong split point at $strongMatch")
            return strongMatch
        }
        
        // 3. 如果长度足够，寻找推荐分割点（逗号、分号等）
        if (length >= minLength) {
            // 优先在理想长度附近分割
            var bestWeak: Int? = null
            var bestDistance = Int.MAX_VALUE
            
            for (match in WEAK_ENDINGS.findAll(text)) {
                val pos = match.range.last + 1
                if (pos >= minLength) {
                    if (!isPositionProtected(text, match.range.first)) {
                        val distance = kotlin.math.abs(pos - preferredLength)
                        if (distance < bestDistance) {
                            bestDistance = distance
                            bestWeak = pos
                        }
                    }
                }
            }
            
            // 如果找到合适的弱分割点，且长度超过理想长度，执行分割
            if (bestWeak != null && length >= preferredLength) {
                Log.d(TAG, "Found weak split point at $bestWeak (preferred=$preferredLength)")
                return bestWeak
            }
        }
        
        // 4. 超长保护：超过最大长度时，在任意标点处分割
        if (length >= maxLength) {
            for (match in ANY_PUNCTUATION.findAll(text)) {
                val pos = match.range.last + 1
                if (pos >= minLength) {
                    Log.d(TAG, "Forced split at $pos due to maxLength=$maxLength")
                    return pos
                }
            }
        }
        
        // 5. 绝对最大保护：在空格或字符边界强制分割
        if (length >= absoluteMax) {
            // 优先在空格处分割
            val spacePos = text.lastIndexOf(' ', absoluteMax - 1)
            if (spacePos >= minLength) {
                Log.d(TAG, "Emergency split at space position ${spacePos + 1}")
                return spacePos + 1
            }
            // 否则在绝对最大位置硬切
            Log.d(TAG, "Hard cut at absoluteMax=$absoluteMax")
            return absoluteMax
        }
        
        // 不分割，继续等待
        return null
    }
    
    /**
     * 检查 buffer 尾部是否处于保护模式
     */
    private fun isProtected(text: String): Boolean {
        // 检查尾部模式
        if (DECIMAL_PATTERN.containsMatchIn(text)) return true
        if (TIME_PATTERN.containsMatchIn(text)) return true
        if (UNCLOSED_BOOK_QUOTE.containsMatchIn(text)) return true
        if (UNCLOSED_CHINESE_QUOTE.containsMatchIn(text)) return true
        if (UNCLOSED_ENGLISH_QUOTE.containsMatchIn(text)) return true
        if (UNCLOSED_SINGLE_QUOTE.containsMatchIn(text)) return true
        
        // 检查尾部是否匹配不应断开的短语前缀
        for (prefix in NO_BREAK_PREFIXES) {
            if (text.endsWith(prefix)) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * 检查特定位置的标点是否在保护区内
     */
    private fun isPositionProtected(text: String, pos: Int): Boolean {
        if (pos <= 0 || pos >= text.length) return false
        
        val before = if (pos > 0) text[pos - 1] else ' '
        val after = if (pos + 1 < text.length) text[pos + 1] else ' '
        
        // 小数点保护：前后都是数字
        if (before.isDigit() && after.isDigit()) {
            return true
        }
        
        // 千分位逗号保护
        val char = text[pos]
        if (char == ',' && before.isDigit() && after.isDigit()) {
            return true
        }
        
        return false
    }
}