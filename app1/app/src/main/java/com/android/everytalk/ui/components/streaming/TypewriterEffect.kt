package com.android.everytalk.ui.components.streaming

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min

/**
 * Typewriter effect hook inspired by Cherry Studio's useSmoothStream.
 * Uses dynamic speed adjustment: releases more chars per frame when queue grows.
 */
@Composable
fun rememberTypewriterState(
    targetText: String,
    isStreaming: Boolean,
    charsPerFrame: Int = 3,
    frameDelayMs: Long = 16L,
    maxCharsPerFrame: Int = 50,
    catchUpDivisor: Int = 5
): TypewriterState {
    val state = remember { TypewriterState() }
    
    LaunchedEffect(targetText) {
        state.updateTarget(targetText)
    }
    
    LaunchedEffect(isStreaming, frameDelayMs) {
        if (!isStreaming) {
            state.flushAll()
            return@LaunchedEffect
        }
        
        while (isActive && isStreaming) {
            val queueLength = state.pendingLength
            
            if (queueLength > 0) {
                // Dynamic speed: queue.length / divisor (Cherry Studio pattern)
                val dynamicChars = max(charsPerFrame, queueLength / catchUpDivisor)
                val charsToRelease = min(dynamicChars, maxCharsPerFrame)
                state.releaseChars(charsToRelease)
            }
            
            delay(frameDelayMs)
        }
    }
    
    LaunchedEffect(isStreaming) {
        if (!isStreaming) {
            state.flushAll()
        }
    }
    
    return state
}

@Stable
class TypewriterState {
    private var _targetText by mutableStateOf("")
    private var _displayedText by mutableStateOf("")
    
    val displayedText: String get() = _displayedText
    val pendingLength: Int get() = _targetText.length - _displayedText.length
    val hasPending: Boolean get() = pendingLength > 0
    
    fun updateTarget(newTarget: String) {
        if (newTarget.length > _targetText.length) {
            _targetText = newTarget
        } else if (newTarget != _targetText && newTarget.isNotEmpty()) {
            _targetText = newTarget
            _displayedText = ""
        }
    }
    
    fun releaseChars(count: Int) {
        val targetLen = _targetText.length
        val currentLen = _displayedText.length
        var newLen = min(currentLen + count, targetLen)
        
        // 数学公式边界保护：不在 $$ 中间断开
        // 扫描 displayedText 末尾到 newLen 之间，检查是否切入了未闭合的 $$ 块
        if (newLen > currentLen && newLen < targetLen) {
            newLen = adjustForMathBoundary(currentLen, newLen, targetLen)
        }
        
        if (newLen > currentLen) {
            _displayedText = _targetText.substring(0, newLen)
        }
    }
    
    /**
     * 调整释放位置，避免在 $$ 数学公式中间断开。
     * 策略：
     * - 如果新释放的文本跨越了一个 $$ 开始标记，检查闭合 $$ 是否在 targetText 中
     * - 如果闭合 $$ 已可用且距离不太远（≤500字符），一次性释放整个公式
     * - 如果闭合 $$ 不可用或太远，截断到 $$ 开始标记之前
     */
    private fun adjustForMathBoundary(currentLen: Int, proposedLen: Int, targetLen: Int): Int {
        // 检查从 currentLen 到 proposedLen 之间是否有 $$ 标记
        val searchStart = max(0, currentLen - 1) // 回退1个字符，防止上一帧刚好停在第一个$
        val segment = _targetText.substring(searchStart, min(proposedLen + 1, targetLen))
        
        var idx = 0
        while (idx < segment.length - 1) {
            if (segment[idx] == '$' && segment[idx + 1] == '$') {
                val absolutePos = searchStart + idx
                
                // 这个 $$ 是开启还是闭合？检查它之前是否有未闭合的 $$
                if (isUnclosedMathAt(absolutePos)) {
                    // 这是闭合标记，包含它
                    val closeEnd = absolutePos + 2
                    if (closeEnd <= proposedLen) {
                        idx += 2
                        continue
                    }
                    // proposedLen 在闭合 $$ 中间，扩展到包含它
                    return min(closeEnd, targetLen)
                } else {
                    // 这是开启标记，寻找闭合
                    val closePos = _targetText.indexOf("$$", absolutePos + 2)
                    if (closePos >= 0 && closePos + 2 <= targetLen && closePos - absolutePos <= 500) {
                        // 闭合标记已可用且不太远，一次性释放整个公式
                        return closePos + 2
                    } else {
                        // 闭合标记不可用或太远，截断到 $$ 之前
                        return if (absolutePos > currentLen) absolutePos else proposedLen
                    }
                }
            }
            idx++
        }
        
        return proposedLen
    }
    
    /**
     * 检查 targetText 中 pos 位置的 $$ 是否是闭合标记（即之前有未闭合的 $$）
     */
    private fun isUnclosedMathAt(pos: Int): Boolean {
        var count = 0
        var i = 0
        val text = _targetText
        while (i < pos) {
            if (i + 1 < text.length && text[i] == '$' && text[i + 1] == '$') {
                count++
                i += 2
            } else {
                i++
            }
        }
        // 奇数个 $$ 表示 pos 处的 $$ 是闭合标记
        return count % 2 == 1
    }
    
    fun flushAll() {
        _displayedText = _targetText
    }
    
    fun reset() {
        _targetText = ""
        _displayedText = ""
    }
}

@Composable
fun rememberTypewriterText(
    targetText: String,
    isStreaming: Boolean,
    charsPerFrame: Int = 3,
    frameDelayMs: Long = 16L
): String {
    val state = rememberTypewriterState(
        targetText = targetText,
        isStreaming = isStreaming,
        charsPerFrame = charsPerFrame,
        frameDelayMs = frameDelayMs
    )
    return state.displayedText
}
