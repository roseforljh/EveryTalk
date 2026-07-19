package com.android.everytalk.statecontroller

import android.util.Log
import com.android.everytalk.ui.components.streaming.IncrementalParseCache
import com.android.everytalk.ui.components.streaming.StreamingRenderState
import com.android.everytalk.ui.components.streaming.buildStreamingRenderState
import com.android.everytalk.ui.components.streaming.buildStreamingRenderStateIncremental
import com.android.everytalk.util.debug.PerformanceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * StreamingMessageStateManager
 *
 * Manages real-time streaming state for messages during streaming output.
 * This component provides efficient state observation for UI components,
 * allowing them to observe only the streaming content changes without
 * triggering recomposition of the entire message list.
 *
 * Key Features:
 * - Maintains separate StateFlow for each streaming message
 * - Adaptive flush intervals based on content length
 * - Debounce mechanism to batch high-frequency updates
 * - Automatic cleanup when streaming completes
 * - Fence-aware and markdown-aware flush suppression to avoid unstable partial renders
 *
 * @see ViewModelStateHolder
 * @see StreamingBuffer
 */
class StreamingMessageStateManager {

    // Map of message ID to its streaming content StateFlow
    private val streamingStates = ConcurrentHashMap<String, MutableStateFlow<String>>()

    // Map of message ID to its structured render state (blocks/tokens lifecycle)
    private val streamingRenderStates = ConcurrentHashMap<String, MutableStateFlow<StreamingRenderState>>()

    // Track which messages are currently streaming
    private val activeStreamingMessages = ConcurrentHashMap.newKeySet<String>()

    // Track messages that have already been finalized to keep finish idempotent
    private val finalizedMessages = ConcurrentHashMap.newKeySet<String>()

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisorJob)
    private val pendingBuffers: MutableMap<String, StringBuilder> = ConcurrentHashMap()
    private val pendingJobs: MutableMap<String, Job> = ConcurrentHashMap()

    private val DEBOUNCE_MS = 60L
    private val MAX_BUFFER_BEFORE_FORCE = 1024
    
    // 短内容快速响应：首次 flush 不走 debounce
    private val FIRST_FLUSH_DEBOUNCE_MS = 8L
    // 短内容（< 200 字符）使用更短的 flush 间隔
    private val SHORT_CONTENT_FLUSH_INTERVAL_MS = 30L
    private val SHORT_CONTENT_THRESHOLD = 200

    private val lastFlushTime = ConcurrentHashMap<String, Long>()
    private val MIN_FLUSH_INTERVAL_MS = 90L
    // 记录每个消息是否已经执行过首次 flush
    private val hasFirstFlushed = ConcurrentHashMap.newKeySet<String>()

    private val contentLengthTracker = ConcurrentHashMap<String, Int>()
    private val incrementalParseCaches = ConcurrentHashMap<String, IncrementalParseCache>()
    private val contentVersions = ConcurrentHashMap<String, Long>()

    fun getOrCreateStreamingState(messageId: String): StateFlow<String> {
        return streamingStates.getOrPut(messageId) {
            MutableStateFlow("")
        }.asStateFlow()
    }

    fun getOrCreateRenderState(messageId: String): StateFlow<StreamingRenderState> {
        return streamingRenderStates.getOrPut(messageId) {
            MutableStateFlow(
                buildStreamingRenderState(
                    messageId = messageId,
                    content = "",
                    isStreaming = activeStreamingMessages.contains(messageId),
                    isComplete = false,
                    contentVersion = contentVersions[messageId] ?: 0L,
                )
            )
        }.asStateFlow()
    }

    fun startStreaming(messageId: String) {
        activeStreamingMessages.add(messageId)
        finalizedMessages.remove(messageId)
        contentVersions[messageId] = 0L
        streamingStates.getOrPut(messageId) {
            MutableStateFlow("")
        }
        streamingRenderStates[messageId] = MutableStateFlow(
            buildStreamingRenderState(
                messageId = messageId,
                content = streamingStates[messageId]?.value.orEmpty(),
                isStreaming = true,
                isComplete = false,
                contentVersion = 0L,
            )
        )
        pendingBuffers.remove(messageId)
        pendingJobs.remove(messageId)?.cancel()
        hasFirstFlushed.remove(messageId)
        incrementalParseCaches.remove(messageId)
        Log.d("StreamingMessageStateManager", "Started streaming for message: $messageId")
    }

    fun appendText(messageId: String, text: String) {
        if (text.isEmpty()) return

        val stateFlow = streamingStates[messageId]
        if (stateFlow == null) {
            Log.w(
                "StreamingMessageStateManager",
                "Attempted to append to non-existent streaming state: $messageId"
            )
            return
        }

        val buf = pendingBuffers.getOrPut(messageId) { StringBuilder() }
        buf.append(text)

        if (buf.length >= MAX_BUFFER_BEFORE_FORCE) {
            flushNow(messageId)
            return
        }

        // 首次 flush 使用极短延迟，确保短回复快速显示
        val isFirstFlush = !hasFirstFlushed.contains(messageId)
        val effectiveDebounce = if (isFirstFlush) FIRST_FLUSH_DEBOUNCE_MS else DEBOUNCE_MS

        pendingJobs.remove(messageId)?.cancel()
        pendingJobs[messageId] = scope.launch {
            delay(effectiveDebounce)
            flushNow(messageId)
        }
    }

    private fun flushNow(messageId: String) {
        val stateFlow = streamingStates[messageId] ?: return
        val buf = pendingBuffers[messageId] ?: return
        if (buf.isEmpty()) return

        val combined = stateFlow.value + buf.toString()
        if (shouldDelayFlush(stateFlow.value, combined, buf.length)) {
            return
        }

        val currentLength = stateFlow.value.length
        contentLengthTracker[messageId] = currentLength

        val adaptiveInterval = when {
            currentLength < SHORT_CONTENT_THRESHOLD -> SHORT_CONTENT_FLUSH_INTERVAL_MS
            currentLength < 500 -> MIN_FLUSH_INTERVAL_MS
            currentLength < 2000 -> MIN_FLUSH_INTERVAL_MS + 30L
            currentLength < 5000 -> MIN_FLUSH_INTERVAL_MS + 60L
            else -> MIN_FLUSH_INTERVAL_MS + 100L
        }

        val now = System.currentTimeMillis()
        val lastTime = lastFlushTime[messageId] ?: 0L
        if ((now - lastTime) < adaptiveInterval && buf.length < MAX_BUFFER_BEFORE_FORCE) {
            return
        }

        val delta = buf.toString()
        buf.setLength(0)

        if (delta.length >= 50 || currentLength > 3000) {
            Log.d(
                "StreamingMessageStateManager",
                "Flush: len=$currentLength, delta=${delta.length}, interval=${adaptiveInterval}ms"
            )
        }
        PerformanceMonitor.recordStateFlowFlush(messageId, delta.length, currentLength + delta.length)

        stateFlow.value = stateFlow.value + delta
        hasFirstFlushed.add(messageId)
        updateRenderState(messageId, stateFlow.value, isComplete = false)
        lastFlushTime[messageId] = now
    }

    fun updateContent(messageId: String, content: String) {
        val stateFlow = streamingStates[messageId]
        if (stateFlow != null) {
            pendingBuffers.remove(messageId)
            pendingJobs.remove(messageId)?.cancel()
            stateFlow.value = content
            updateRenderState(messageId, content, isComplete = false)
        } else {
            Log.w(
                "StreamingMessageStateManager",
                "Creating new state for message: $messageId, len=${content.length}"
            )
            streamingStates[messageId] = MutableStateFlow(content)
            val contentVersion = nextContentVersion(messageId)
            streamingRenderStates[messageId] = MutableStateFlow(
                buildStreamingRenderState(
                    messageId = messageId,
                    content = content,
                    isStreaming = activeStreamingMessages.contains(messageId),
                    isComplete = false,
                    contentVersion = contentVersion,
                )
            )
        }
    }

    fun finishStreaming(messageId: String): String {
        return finalizeMessage(messageId)
    }

    fun finalizeMessage(messageId: String): String {
        if (!finalizedMessages.add(messageId)) {
            return streamingStates[messageId]?.value ?: ""
        }

        pendingJobs.remove(messageId)?.cancel()

        val stateFlow = streamingStates[messageId]
        val buf = pendingBuffers[messageId]

        if (stateFlow != null && buf != null && buf.isNotEmpty()) {
            val delta = buf.toString()
            stateFlow.value = stateFlow.value + delta
            updateRenderState(messageId, stateFlow.value, isComplete = false)
            buf.setLength(0)

            Log.d(
                "StreamingMessageStateManager",
                "Final flush for $messageId: deltaLen=${delta.length}, totalLen=${stateFlow.value.length}"
            )
        }

        activeStreamingMessages.remove(messageId)
        val finalContent = streamingStates[messageId]?.value ?: ""
        updateRenderState(messageId, finalContent, isComplete = true)
        Log.d(
            "StreamingMessageStateManager",
            "Finished streaming for message: $messageId, final length: ${finalContent.length}"
        )
        return finalContent
    }

    fun clearStreamingState(messageId: String) {
        activeStreamingMessages.remove(messageId)
        pendingJobs.remove(messageId)?.cancel()
        pendingBuffers.remove(messageId)
        hasFirstFlushed.remove(messageId)
        incrementalParseCaches.remove(messageId)
        contentVersions.remove(messageId)
        streamingStates.remove(messageId)
        // 不删除 streamingRenderStates：UI 侧仍在 collectAsState 订阅该 Flow，
        // 如果此时 remove，UI 会收到一个全新的空 RenderState，导致一帧内容空白和高度坍塌。
        // 保留最终态让 UI 自然过渡到 message.text；下次 startStreaming 时会覆盖。
        Log.d("StreamingMessageStateManager", "Cleared streaming state for message: $messageId (renderState retained)")
    }

    fun clearAll() {
        val count = streamingStates.size
        activeStreamingMessages.clear()
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
        pendingBuffers.clear()
        hasFirstFlushed.clear()
        incrementalParseCaches.clear()
        contentVersions.clear()
        streamingStates.clear()
        streamingRenderStates.clear()
        Log.d("StreamingMessageStateManager", "Cleared all streaming states (count: $count)")
    }

    fun isStreaming(messageId: String): Boolean {
        return activeStreamingMessages.contains(messageId)
    }

    fun getCurrentContent(messageId: String): String {
        val base = streamingStates[messageId]?.value ?: ""
        val buf = pendingBuffers[messageId]?.toString().orEmpty()
        return base + buf
    }

    fun getCurrentRenderState(messageId: String): StreamingRenderState {
        val current = streamingRenderStates[messageId]?.value
        if (current != null) return current
        return buildStreamingRenderState(
            messageId = messageId,
            content = getCurrentContent(messageId),
            isStreaming = isStreaming(messageId),
            isComplete = !isStreaming(messageId),
            contentVersion = contentVersions[messageId] ?: 0L,
        )
    }

    fun getActiveStreamingCount(): Int {
        return activeStreamingMessages.size
    }

    fun getStats(): Map<String, Any> {
        return mapOf(
            "activeStreamingCount" to activeStreamingMessages.size,
            "totalStatesCount" to streamingStates.size,
            "activeMessageIds" to activeStreamingMessages.toList(),
            "pendingBuffers" to pendingBuffers.mapValues { min(it.value.length, 256) }
        )
    }

    fun cleanup() {
        Log.d("StreamingMessageStateManager", "🧹 Cleaning up StreamingMessageStateManager")

        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()

        pendingBuffers.clear()
        hasFirstFlushed.clear()
        incrementalParseCaches.clear()
        contentVersions.clear()
        activeStreamingMessages.clear()
        streamingStates.clear()
        streamingRenderStates.clear()

        scope.cancel()

        Log.d("StreamingMessageStateManager", "✅ StreamingMessageStateManager cleanup complete")
    }

    private fun shouldDelayFlush(previous: String, current: String, pendingLength: Int): Boolean {
        if (pendingLength >= MAX_BUFFER_BEFORE_FORCE) return false
        // 短内容不做 markdown 延迟，优先保证响应速度
        if (current.length < SHORT_CONTENT_THRESHOLD) return false
        if (endsWithDangerousMarkdownPrefix(current)) return true
        if (hasUnclosedInlineMarkers(current)) return true
        if (hasUnstableTrailingTable(previous, current)) return true
        return false
    }

    private fun endsWithDangerousMarkdownPrefix(text: String): Boolean {
        val line = text.substringAfterLast('\n')
        val trimmedEnd = line.trimEnd()
        val trimmed = trimmedEnd.trimStart()
        if (trimmed == "#" || trimmed == "##" || trimmed == "###" || trimmed == "####" || trimmed == "#####" || trimmed == "######") {
            return true
        }
        if (trimmed == "-" || trimmed == "*" || trimmed == "+" || trimmed == ">") {
            return true
        }
        if (Regex("^\\d+\\.$").matches(trimmed)) {
            return true
        }
        val isBareBacktickFence = trimmedEnd == "```" || trimmedEnd.startsWith("```") && !trimmedEnd.contains(' ')
        val isBareTildeFence = trimmedEnd == "~~~" || trimmedEnd.startsWith("~~~") && !trimmedEnd.contains(' ')
        if (isBareBacktickFence || isBareTildeFence) {
            return true
        }
        if (trimmedEnd.endsWith("|") && trimmedEnd.count { it == '|' } >= 2) {
            return true
        }
        return false
    }

    private fun hasUnclosedInlineMarkers(text: String): Boolean {
        val line = text.substringAfterLast('\n')
        val doubleStarCount = Regex("(?<!\\\\)\\*\\*").findAll(line).count()
        if ((doubleStarCount % 2) == 1) return true

        val backtickCount = Regex("(?<!\\\\)`").findAll(line).count()
        if ((backtickCount % 2) == 1) return true

        return false
    }

    private fun hasUnstableTrailingTable(previous: String, current: String): Boolean {
        val currentLines = current.lines()
        if (currentLines.size < 2) return false
        val tail = currentLines.takeLast(3)
        val tableLikeCount = tail.count { it.contains('|') }
        if (tableLikeCount == 0) return false

        val headerStarted = tail.firstOrNull()?.contains('|') == true
        val hasSeparator = tail.any { Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)+\\|?\\s*$").matches(it) }
        if (!hasSeparator && headerStarted && current.length > previous.length) {
            return true
        }
        return false
    }

    private fun updateRenderState(messageId: String, content: String, isComplete: Boolean) {
        val isCurrentlyStreaming = activeStreamingMessages.contains(messageId) && !isComplete
        val cache = incrementalParseCaches[messageId]
        val previousContent = streamingRenderStates[messageId]?.value?.content
        val contentVersion = nextContentVersion(messageId)

        val state: StreamingRenderState
        if (
            cache != null &&
            previousContent != null &&
            content.startsWith(previousContent) &&
            content.length >= cache.committedEndOffset &&
            isCurrentlyStreaming
        ) {
            val (newState, newCache) = buildStreamingRenderStateIncremental(
                messageId = messageId,
                content = content,
                isStreaming = true,
                isComplete = false,
                cache = cache,
                contentVersion = contentVersion,
            )
            state = newState
            incrementalParseCaches[messageId] = newCache
        } else {
            state = buildStreamingRenderState(
                messageId = messageId,
                content = content,
                isStreaming = isCurrentlyStreaming,
                isComplete = isComplete,
                contentVersion = contentVersion,
            )
            if (isCurrentlyStreaming) {
                val blocks = state.blocks
                val committed = if (blocks.size > 1) blocks.dropLast(1) else emptyList()
                val committedEnd = committed.lastOrNull()?.endExclusive ?: 0
                incrementalParseCaches[messageId] = IncrementalParseCache(
                    committedBlocks = committed,
                    committedEndOffset = committedEnd,
                    lastContentLength = content.length,
                    lastBlockIndex = committed.size,
                )
            } else {
                incrementalParseCaches.remove(messageId)
            }
        }

        streamingRenderStates.getOrPut(messageId) { MutableStateFlow(state) }.value = state
    }

    private fun nextContentVersion(messageId: String): Long {
        return contentVersions.merge(messageId, 1L) { current, _ -> current + 1L } ?: 1L
    }
}
