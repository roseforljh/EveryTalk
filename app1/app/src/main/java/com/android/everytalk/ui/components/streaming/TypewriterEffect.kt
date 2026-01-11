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
    
    LaunchedEffect(isStreaming) {
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
        val newLen = min(currentLen + count, targetLen)
        
        if (newLen > currentLen) {
            _displayedText = _targetText.substring(0, newLen)
        }
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
