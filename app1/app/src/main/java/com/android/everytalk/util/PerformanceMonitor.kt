package com.android.everytalk.util

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * PerformanceMonitor - Unified stream logging and metrics aggregation
 *
 * Goals:
 * - Centralize logging with sampling to reduce log volume
 * - Aggregate per-message/session metrics
 * - Emit single-line summary at Finish/Abort for fast triage
 *
 * Usage (non-intrusive):
 * - Call recordEvent() on stream event receipt
 * - Call recordBufferFlush() from StreamingBuffer.performFlush()
 * - Call recordStateFlowFlush() from StreamingMessageStateManager.flushNow()
 * - Call onFinish()/onAbort() from ApiHandler Finish/Error/Cancel
 */
object PerformanceMonitor {

    // Config (can be made dynamic via PerformanceConfig later)
    @Volatile var enabled: Boolean = true
    @Volatile var tag: String = "STREAM"
    @Volatile var firstNStraightLogs: Int = 5       // first N items are logged verbosely
    @Volatile var everyMSampled: Int = 10           // then sample every Mth item
    @Volatile var deltaLenThreshold: Int = 50       // force-log if delta length >= threshold
    @Volatile var interimSummaryIntervalMs: Long = 0L // 0 = disabled

    private data class Counters(
        var total: Int = 0,
        var content: Int = 0,
        var text: Int = 0,
        var reasoning: Int = 0,
        var contentFinal: Int = 0,
        var error: Int = 0,
        var finish: Int = 0,
    )

    private data class FlushStats(
        var count: Int = 0,
        var totalChars: Long = 0,
        var maxChars: Int = 0,
        var lastTs: Long = 0,
        var avgIntervalMs: Long = 0,
    )

    private data class SessionStats(
        val messageId: String,
        var mode: String = "text",
        val startTs: Long = System.currentTimeMillis(),
        var endTs: Long = 0L,
        var backend: String? = null,
        var model: String? = null,
        val events: Counters = Counters(),
        val buffer: FlushStats = FlushStats(),
        val stateFlow: FlushStats = FlushStats(),
        var textTotalChars: Long = 0,
        var reasoningTotalChars: Long = 0,
        var retriesTotal: Int = 0,
        var retriesTimeout: Int = 0,
        var retriesConnect: Int = 0,
        var retries429: Int = 0,
        var usedMemoryMB: Int = 0,
        var maxMemoryMB: Int = 0,
        var lastInterimEmit: Long = 0L,
    )

    private val sessions = ConcurrentHashMap<String, SessionStats>()

    private fun stats(messageId: String): SessionStats {
        return sessions.getOrPut(messageId) { SessionStats(messageId) }
    }

    fun setContext(messageId: String, mode: String? = null, backend: String? = null, model: String? = null) {
        if (!enabled) return
        val s = stats(messageId)
        mode?.let { s.mode = it }
        backend?.let { s.backend = it }
        model?.let { s.model = it }
    }

    fun recordEvent(messageId: String, eventType: String, deltaLen: Int = 0) {
        if (!enabled) return
        val s = stats(messageId)
        s.events.total++
        when (eventType) {
            "Content" -> {
                s.events.content++
                if (deltaLen > 0) s.textTotalChars += deltaLen
            }
            "Text" -> {
                s.events.text++
                if (deltaLen > 0) s.textTotalChars += deltaLen
            }
            "Reasoning" -> {
                s.events.reasoning++
                if (deltaLen > 0) s.reasoningTotalChars += deltaLen
            }
            "ContentFinal" -> s.events.contentFinal++
            "Error" -> s.events.error++
            "Finish", "StreamEnd" -> s.events.finish++
        }

        maybeEmitInterim(messageId)
        maybeLogSampled(messageId, "EVENT", mapOf(
            "type" to eventType,
            "deltaLen" to deltaLen.toString(),
            "events.total" to s.events.total.toString()
        ), sampleIndex = s.events.total, force = deltaLen >= deltaLenThreshold)
    }

    fun recordBufferFlush(messageId: String, incrementalLen: Int, totalLen: Int) {
        if (!enabled) return
        val s = stats(messageId)
        val b = s.buffer
        b.count++
        b.totalChars += incrementalLen
        b.maxChars = max(b.maxChars, incrementalLen)
        val now = System.currentTimeMillis()
        if (b.lastTs != 0L) {
            // simple incremental average
            val interval = now - b.lastTs
            val n = b.count.toLong()
            b.avgIntervalMs = ((b.avgIntervalMs * (n - 1) + interval) / n)
        }
        b.lastTs = now

        maybeEmitInterim(messageId)
        maybeLogSampled(messageId, "BUFFER_FLUSH", mapOf(
            "delta" to incrementalLen.toString(),
            "total" to totalLen.toString(),
            "count" to b.count.toString(),
            "avgIntervalMs" to b.avgIntervalMs.toString()
        ), sampleIndex = b.count, force = incrementalLen >= deltaLenThreshold)
    }

    fun recordStateFlowFlush(messageId: String, deltaLen: Int, currentLen: Int) {
        if (!enabled) return
        val s = stats(messageId)
        val f = s.stateFlow
        f.count++
        f.totalChars += deltaLen
        f.maxChars = max(f.maxChars, deltaLen)
        val now = System.currentTimeMillis()
        if (f.lastTs != 0L) {
            val interval = now - f.lastTs
            val n = f.count.toLong()
            f.avgIntervalMs = ((f.avgIntervalMs * (n - 1) + interval) / n)
        }
        f.lastTs = now

        maybeEmitInterim(messageId)
        maybeLogSampled(messageId, "STATEFLOW_FLUSH", mapOf(
            "delta" to deltaLen.toString(),
            "len" to currentLen.toString(),
            "count" to f.count.toString(),
            "avgIntervalMs" to f.avgIntervalMs.toString()
        ), sampleIndex = f.count, force = deltaLen >= deltaLenThreshold)
    }

    fun recordRetry(messageId: String, kind: String) {
        if (!enabled) return
        val s = stats(messageId)
        s.retriesTotal++
        when (kind) {
            "timeout" -> s.retriesTimeout++
            "connect" -> s.retriesConnect++
            "429" -> s.retries429++
        }
    }

    fun recordMemory(messageId: String, usedMB: Int, maxMB: Int) {
        if (!enabled) return
        val s = stats(messageId)
        s.usedMemoryMB = usedMB
        s.maxMemoryMB = maxMB
    }

    fun onFinish(messageId: String) {
        if (!enabled) return
        val s = stats(messageId)
        s.endTs = System.currentTimeMillis()
        emitSummary("SESSION_SUMMARY", s)
        sessions.remove(messageId)
    }

    fun onAbort(messageId: String, reason: String) {
        if (!enabled) return
        val s = stats(messageId)
        s.endTs = System.currentTimeMillis()
        emitSummary("SESSION_ABORT", s, extra = mapOf("reason" to reason))
        sessions.remove(messageId)
    }

    private fun maybeEmitInterim(messageId: String) {
        if (!enabled || interimSummaryIntervalMs <= 0) return
        val s = stats(messageId)
        val now = System.currentTimeMillis()
        if (s.lastInterimEmit == 0L || now - s.lastInterimEmit >= interimSummaryIntervalMs) {
            s.lastInterimEmit = now
            emitSummary("INTERIM_SUMMARY", s)
        }
    }

    private fun maybeLogSampled(
        messageId: String,
        kind: String,
        fields: Map<String, String>,
        sampleIndex: Int,
        force: Boolean = false
    ) {
        if (!enabled) return
        val should =
            force ||
            sampleIndex <= firstNStraightLogs ||
            (everyMSampled > 0 && sampleIndex % everyMSampled == 0)

        if (should) {
            val s = stats(messageId)
            val hdr = "mode=${s.mode} messageId=$messageId"
            val kv = fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
            Log.i(tag, "$kind $hdr $kv")
        }
    }

    private fun emitSummary(label: String, s: SessionStats, extra: Map<String, String> = emptyMap()) {
        if (!enabled) return
        val dur = (if (s.endTs == 0L) System.currentTimeMillis() else s.endTs) - s.startTs
        val memPct = if (s.maxMemoryMB > 0) (s.usedMemoryMB * 100 / s.maxMemoryMB) else 0
        val fields = mutableListOf(
            "mode=${s.mode}",
            "messageId=${s.messageId}",
            "durationMs=$dur",
            "events.total=${s.events.total}",
            "events.Content=${s.events.content}",
            "events.Text=${s.events.text}",
            "events.Reasoning=${s.events.reasoning}",
            "events.ContentFinal=${s.events.contentFinal}",
            "events.Finish=${s.events.finish}",
            "buffer.flush.count=${s.buffer.count}",
            "buffer.flush.avgIntervalMs=${s.buffer.avgIntervalMs}",
            "buffer.flush.maxChars=${s.buffer.maxChars}",
            "stateflow.flush.count=${s.stateFlow.count}",
            "text.totalChars=${s.textTotalChars}",
            "reasoning.totalChars=${s.reasoningTotalChars}",
            "retries.total=${s.retriesTotal}",
            "retries.timeout=${s.retriesTimeout}",
            "retries.connect=${s.retriesConnect}",
            "retries.429=${s.retries429}",
            "memory.usedMB=${s.usedMemoryMB}",
            "memory.maxMB=${s.maxMemoryMB}",
            "memory.usagePct=$memPct"
        )
        s.backend?.let { fields.add("backend=$it") }
        s.model?.let { fields.add("model=$it") }
        if (extra.isNotEmpty()) {
            fields.addAll(extra.entries.map { "${it.key}=${it.value}" })
        }
        Log.i(tag, "$label ${fields.joinToString(" ")}")
    }

    // ===== Generic lightweight perf markers (component-level) =====

    /**
     * 解析耗时埋点（无需会话上下文）
     */
    fun recordParsing(component: String, durationMs: Long, inputSize: Int) {
        if (!enabled) return
        Log.i(tag, "PARSING component=$component durationMs=$durationMs inputSize=$inputSize")
    }

    /**
     * 缓存命中埋点（无需会话上下文）
     */
    fun recordCacheHit(component: String, durationMs: Long, key: String? = null) {
        if (!enabled) return
        if (key.isNullOrBlank()) {
            Log.i(tag, "CACHE_HIT component=$component durationMs=$durationMs")
        } else {
            Log.i(tag, "CACHE_HIT component=$component durationMs=$durationMs key=$key")
        }
    }

    /**
     * 缓存未命中埋点（无需会话上下文）
     */
    fun recordCacheMiss(component: String, durationMs: Long, key: String? = null) {
        if (!enabled) return
        if (key.isNullOrBlank()) {
            Log.i(tag, "CACHE_MISS component=$component durationMs=$durationMs")
        } else {
            Log.i(tag, "CACHE_MISS component=$component durationMs=$durationMs key=$key")
        }
    }

    /**
     * 虚拟化渲染埋点（行数/阈值）
     */
    fun recordVirtualizedRender(component: String, lines: Int, threshold: Int) {
        if (!enabled) return
        Log.i(tag, "VIRTUALIZED_RENDER component=$component lines=$lines threshold=$threshold")
    }
}