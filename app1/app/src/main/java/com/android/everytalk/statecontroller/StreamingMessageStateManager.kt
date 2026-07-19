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
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
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
    private val bufferLocks = Array(LOCK_STRIPE_COUNT) { Any() }
    private val renderLocks = Array(LOCK_STRIPE_COUNT) { Any() }
    private val lifecycleLock = ReentrantReadWriteLock()

    private val DEBOUNCE_MS = 60L
    private val MAX_BUFFER_BEFORE_FORCE = 1024
    private val SHORT_CONTENT_THRESHOLD = 200
    
    // 短内容快速响应：首次 flush 不走 debounce
    private val FIRST_FLUSH_DEBOUNCE_MS = 8L
    private val lastFlushTime = ConcurrentHashMap<String, Long>()
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
        val initialContent = synchronized(bufferLockFor(messageId)) {
            activeStreamingMessages.add(messageId)
            finalizedMessages.remove(messageId)
            contentVersions[messageId] = 0L
            val stateFlow = streamingStates.getOrPut(messageId) {
                MutableStateFlow("")
            }
            pendingBuffers.remove(messageId)
            pendingJobs.remove(messageId)?.cancel()
            hasFirstFlushed.remove(messageId)
            lastFlushTime.remove(messageId)
            contentLengthTracker.remove(messageId)
            stateFlow.value
        }
        synchronized(renderLockFor(messageId)) {
            incrementalParseCaches.remove(messageId)
            if (contentVersions[messageId] != 0L) return@synchronized
            val initialState = buildStreamingRenderState(
                messageId = messageId,
                content = initialContent,
                isStreaming = true,
                isComplete = false,
                contentVersion = 0L,
            )
            lifecycleLock.read {
                if (contentVersions[messageId] != 0L || !streamingStates.containsKey(messageId)) return@read
                streamingRenderStates[messageId] = MutableStateFlow(initialState)
            }
        }
        Log.d("StreamingMessageStateManager", "Started streaming for message: $messageId")
    }

    fun appendText(messageId: String, text: String) {
        if (text.isEmpty()) return

        val shouldForceFlush = synchronized(bufferLockFor(messageId)) {
            if (streamingStates[messageId] == null || finalizedMessages.contains(messageId)) {
                Log.w(
                    "StreamingMessageStateManager",
                    "Attempted to append to inactive streaming state: $messageId"
                )
                return
            }

            val buf = pendingBuffers.getOrPut(messageId) { StringBuilder() }
            buf.append(text)

            if (buf.length >= MAX_BUFFER_BEFORE_FORCE) {
                true
            } else {
                // 首次 flush 使用极短延迟，确保短回复快速显示
                val isFirstFlush = !hasFirstFlushed.contains(messageId)
                val effectiveDebounce = if (isFirstFlush) FIRST_FLUSH_DEBOUNCE_MS else DEBOUNCE_MS

                pendingJobs.remove(messageId)?.cancel()
                pendingJobs[messageId] = scope.launch {
                    delay(effectiveDebounce)
                    flushNow(messageId)
                }
                false
            }
        }

        if (shouldForceFlush) flushNow(messageId)
    }

    private fun flushNow(messageId: String) {
        var renderedContent: String? = null
        var renderedVersion = 0L
        var deltaLength = 0
        var previousLength = 0
        var adaptiveInterval = 0L

        synchronized(bufferLockFor(messageId)) {
            val stateFlow = streamingStates[messageId] ?: return
            val buf = pendingBuffers[messageId] ?: return
            if (buf.isEmpty()) return

            val previousContent = stateFlow.value
            val pendingText = buf.toString()
            val combined = previousContent + pendingText
            if (shouldDelayFlush(previousContent, combined, pendingText.length)) {
                return
            }

            previousLength = previousContent.length
            contentLengthTracker[messageId] = previousLength
            adaptiveInterval = resolveStreamingRenderFlushIntervalMs(previousLength)

            val now = System.currentTimeMillis()
            val lastTime = lastFlushTime[messageId] ?: 0L
            if ((now - lastTime) < adaptiveInterval && pendingText.length < MAX_BUFFER_BEFORE_FORCE) {
                val remainingDelay = (adaptiveInterval - (now - lastTime)).coerceAtLeast(1L)
                pendingJobs[messageId] = scope.launch {
                    delay(remainingDelay)
                    flushNow(messageId)
                }
                return
            }

            buf.setLength(0)
            stateFlow.value = combined
            hasFirstFlushed.add(messageId)
            lastFlushTime[messageId] = now

            deltaLength = pendingText.length
            renderedContent = combined
            renderedVersion = nextContentVersion(messageId)
        }

        val content = renderedContent ?: return
        if (PerformanceMonitor.enabled && (deltaLength >= 50 || previousLength > 3000)) {
            Log.d(
                "StreamingMessageStateManager",
                "Flush: len=$previousLength, delta=$deltaLength, interval=${adaptiveInterval}ms"
            )
        }
        PerformanceMonitor.recordStateFlowFlush(messageId, deltaLength, content.length)
        updateRenderState(messageId, content, isComplete = false, contentVersion = renderedVersion)
    }

    fun updateContent(messageId: String, content: String) {
        val contentVersion = synchronized(bufferLockFor(messageId)) {
            val stateFlow = streamingStates[messageId]
            pendingBuffers.remove(messageId)
            pendingJobs.remove(messageId)?.cancel()
            if (stateFlow != null) {
                stateFlow.value = content
            } else {
                Log.w(
                    "StreamingMessageStateManager",
                    "Creating new state for message: $messageId, len=${content.length}"
                )
                streamingStates[messageId] = MutableStateFlow(content)
            }
            nextContentVersion(messageId)
        }
        updateRenderState(messageId, content, isComplete = false, contentVersion = contentVersion)
    }

    fun finishStreaming(messageId: String): String {
        return finalizeMessage(messageId)
    }

    fun finalizeMessage(messageId: String): String {
        var finalContent: String
        var contentVersion = 0L
        var finalDeltaLength = 0

        synchronized(bufferLockFor(messageId)) {
            if (!finalizedMessages.add(messageId)) {
                return streamingStates[messageId]?.value ?: ""
            }

            pendingJobs.remove(messageId)?.cancel()

            val stateFlow = streamingStates[messageId]
            val buf = pendingBuffers.remove(messageId)
            if (stateFlow != null && buf != null && buf.isNotEmpty()) {
                val delta = buf.toString()
                stateFlow.value = stateFlow.value + delta
                finalDeltaLength = delta.length
            }

            activeStreamingMessages.remove(messageId)
            finalContent = stateFlow?.value.orEmpty()
            contentVersion = nextContentVersion(messageId)
        }

        if (PerformanceMonitor.enabled && finalDeltaLength > 0) {
            Log.d(
                "StreamingMessageStateManager",
                "Final flush for $messageId: deltaLen=$finalDeltaLength, totalLen=${finalContent.length}"
            )
        }

        updateRenderState(messageId, finalContent, isComplete = true, contentVersion = contentVersion)
        Log.d(
            "StreamingMessageStateManager",
            "Finished streaming for message: $messageId, final length: ${finalContent.length}"
        )
        return finalContent
    }

    fun clearStreamingState(messageId: String) {
        synchronized(bufferLockFor(messageId)) {
            lifecycleLock.write {
                activeStreamingMessages.remove(messageId)
                finalizedMessages.remove(messageId)
                pendingJobs.remove(messageId)?.cancel()
                pendingBuffers.remove(messageId)
                hasFirstFlushed.remove(messageId)
                lastFlushTime.remove(messageId)
                contentLengthTracker.remove(messageId)
                contentVersions.remove(messageId)
                streamingStates.remove(messageId)
                incrementalParseCaches.remove(messageId)
            }
        }
        // 不删除 streamingRenderStates：UI 侧仍在 collectAsState 订阅该 Flow，
        // 如果此时 remove，UI 会收到一个全新的空 RenderState，导致一帧内容空白和高度坍塌。
        // 保留最终态让 UI 自然过渡到 message.text；下次 startStreaming 时会覆盖。
        Log.d("StreamingMessageStateManager", "Cleared streaming state for message: $messageId (renderState retained)")
    }

    fun clearAll() {
        val count = streamingStates.size
        withAllLocks(bufferLocks) {
            lifecycleLock.write {
                activeStreamingMessages.clear()
                pendingJobs.values.forEach { it.cancel() }
                pendingJobs.clear()
                pendingBuffers.clear()
                hasFirstFlushed.clear()
                finalizedMessages.clear()
                lastFlushTime.clear()
                contentLengthTracker.clear()
                incrementalParseCaches.clear()
                contentVersions.clear()
                streamingStates.clear()
                streamingRenderStates.clear()
            }
        }
        Log.d("StreamingMessageStateManager", "Cleared all streaming states (count: $count)")
    }

    fun isStreaming(messageId: String): Boolean {
        return activeStreamingMessages.contains(messageId)
    }

    fun getCurrentContent(messageId: String): String {
        return synchronized(bufferLockFor(messageId)) {
            val base = streamingStates[messageId]?.value ?: ""
            val buf = pendingBuffers[messageId]?.toString().orEmpty()
            base + buf
        }
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
        val pendingLengths = pendingBuffers.keys.associateWith { messageId ->
            synchronized(bufferLockFor(messageId)) {
                min(pendingBuffers[messageId]?.length ?: 0, 256)
            }
        }
        return mapOf(
            "activeStreamingCount" to activeStreamingMessages.size,
            "totalStatesCount" to streamingStates.size,
            "activeMessageIds" to activeStreamingMessages.toList(),
            "pendingBuffers" to pendingLengths
        )
    }

    fun cleanup() {
        Log.d("StreamingMessageStateManager", "🧹 Cleaning up StreamingMessageStateManager")

        withAllLocks(bufferLocks) {
            lifecycleLock.write {
                pendingJobs.values.forEach { it.cancel() }
                pendingJobs.clear()
                pendingBuffers.clear()
                hasFirstFlushed.clear()
                finalizedMessages.clear()
                lastFlushTime.clear()
                contentLengthTracker.clear()
                incrementalParseCaches.clear()
                contentVersions.clear()
                activeStreamingMessages.clear()
                streamingStates.clear()
                streamingRenderStates.clear()
            }
        }

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

    private fun updateRenderState(
        messageId: String,
        content: String,
        isComplete: Boolean,
        contentVersion: Long,
    ) {
        synchronized(renderLockFor(messageId)) {
            // 只允许当前版本发布，避免旧解析任务或已清理生命周期覆盖新结果。
            if (contentVersions[messageId] != contentVersion || !streamingStates.containsKey(messageId)) return

            val isCurrentlyStreaming = activeStreamingMessages.contains(messageId) && !isComplete
            val cache = incrementalParseCaches[messageId]
            val previousContent = streamingRenderStates[messageId]?.value?.content

            val state: StreamingRenderState
            val nextCache: IncrementalParseCache?
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
                nextCache = newCache
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
                    nextCache = IncrementalParseCache(
                        committedBlocks = committed,
                        committedEndOffset = committedEnd,
                        lastContentLength = content.length,
                        lastBlockIndex = committed.size,
                    )
                } else {
                    nextCache = null
                }
            }

            lifecycleLock.read {
                if (contentVersions[messageId] != contentVersion || !streamingStates.containsKey(messageId)) {
                    return@read
                }
                if (nextCache != null) {
                    incrementalParseCaches[messageId] = nextCache
                } else {
                    incrementalParseCaches.remove(messageId)
                }
                streamingRenderStates.getOrPut(messageId) { MutableStateFlow(state) }.value = state
            }
        }
    }

    private fun nextContentVersion(messageId: String): Long {
        return contentVersions.merge(messageId, 1L) { current, _ -> current + 1L } ?: 1L
    }

    private fun bufferLockFor(messageId: String): Any =
        bufferLocks[(messageId.hashCode() and Int.MAX_VALUE) % bufferLocks.size]

    private fun renderLockFor(messageId: String): Any =
        renderLocks[(messageId.hashCode() and Int.MAX_VALUE) % renderLocks.size]

    private fun <T> withAllLocks(locks: Array<Any>, index: Int = 0, block: () -> T): T {
        if (index == locks.size) return block()
        return synchronized(locks[index]) {
            withAllLocks(locks, index + 1, block)
        }
    }

    private companion object {
        const val LOCK_STRIPE_COUNT = 32
    }
}

internal fun resolveStreamingRenderFlushIntervalMs(contentLength: Int): Long = when {
    contentLength < 200 -> 80L
    contentLength < 500 -> 120L
    contentLength < 2_000 -> 150L
    contentLength < 5_000 -> 180L
    else -> 220L
}
