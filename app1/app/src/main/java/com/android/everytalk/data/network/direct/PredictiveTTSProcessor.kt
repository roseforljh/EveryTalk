package com.android.everytalk.data.network.direct

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 预测性 TTS 处理器
 * 
 * 实现 LLM 输出与 TTS 合成的并行处理，减少语音响应延迟。
 * 
 * 移植自 Python 版本：ET-Backend-code/eztalk_proxy/services/predictive_tts.py
 * 
 * 核心思想：
 * - LLM 输出片段后立即提交 TTS 任务
 * - TTS 任务在后台并行执行
 * - 按顺序输出音频，确保播放连贯性
 * 
 * 优化特性：
 * - 首句优先处理：第一个任务使用更高优先级
 * - 智能重试：支持指数退避的错误重试
 * - 流式输出模式：支持边生成边发送
 */
class PredictiveTTSProcessor(
    private val ttsExecutor: suspend (String) -> Flow<ByteArray>,
    private val maxConcurrent: Int = 5,
    private val maxRetry: Int = 2,
    private val taskTimeout: Long = 30_000L,
    private val firstTaskTimeout: Long = 15_000L  // 首句使用更短超时，快速失败
) {
    companion object {
        private const val TAG = "PredictiveTTSProcessor"
    }
    
    /**
     * 任务状态
     */
    enum class TaskStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }
    
    /**
     * TTS 任务数据结构
     */
    private data class TTSTask(
        val sequenceId: Int,
        val text: String,
        var status: TaskStatus = TaskStatus.PENDING,
        val audioChunks: MutableList<ByteArray> = mutableListOf(),
        var retryCount: Int = 0,
        var error: String? = null,
        val completedEvent: CompletableDeferred<Unit> = CompletableDeferred()
    )
    
    // 并发控制信号量
    private val semaphore = Semaphore(maxConcurrent)
    
    // 任务存储
    private val tasks = ConcurrentHashMap<Int, TTSTask>()
    
    // 下一个应输出的序列号
    private val nextOutputId = AtomicInteger(0)
    
    // 输入是否已完成
    private val inputComplete = AtomicBoolean(false)
    
    // 总任务数
    private val totalTasks = AtomicInteger(0)
    
    // 用于通知输出有新任务完成
    private val outputNotify = Channel<Unit>(Channel.CONFLATED)
    
    // 活跃的任务协程
    private val activeJobs = mutableListOf<Job>()
    
    // 作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 性能统计
    private var firstTaskStartTime: Long? = null
    private var firstAudioTime: Long? = null
    
    init {
        Log.i(TAG, "PredictiveTTSProcessor initialized: maxConcurrent=$maxConcurrent, " +
                "maxRetry=$maxRetry, firstTimeout=$firstTaskTimeout")
    }
    
    /**
     * 提交 TTS 任务
     * 
     * @param sequenceId 任务序列号
     * @param text 待合成文本
     */
    suspend fun submitTask(sequenceId: Int, text: String) {
        if (text.isBlank()) {
            Log.d(TAG, "Skipping empty text for sequence $sequenceId")
            return
        }
        
        // 首个任务记录开始时间
        if (sequenceId == 0) {
            firstTaskStartTime = System.currentTimeMillis()
        }
        
        val task = TTSTask(sequenceId = sequenceId, text = text)
        tasks[sequenceId] = task
        totalTasks.updateAndGet { maxOf(it, sequenceId + 1) }
        
        val isFirst = sequenceId == 0
        Log.i(TAG, "[TTS Task $sequenceId] ${if (isFirst) "[首句] " else ""}提交任务: " +
                "'${text.take(50)}${if (text.length > 50) "..." else ""}'")
        
        // 启动处理协程
        val job = scope.launch {
            if (isFirst) {
                processFirstTask(task)
            } else {
                processTaskWithRetry(task)
            }
        }
        activeJobs.add(job)
    }
    
    /**
     * 处理首句任务（优先级最高，不受信号量限制）
     */
    private suspend fun processFirstTask(task: TTSTask) {
        task.status = TaskStatus.PROCESSING
        val startTime = System.currentTimeMillis()
        
        for (attempt in 0..maxRetry) {
            try {
                Log.i(TAG, "[TTS Task 0] [首句] 开始处理 (尝试 ${attempt + 1}/${maxRetry + 1})")
                
                withTimeout(firstTaskTimeout) {
                    executeTts(task)
                }
                
                task.status = TaskStatus.COMPLETED
                
                // 计算音频数据总大小和延迟
                val totalBytes = task.audioChunks.sumOf { it.size }
                val elapsed = System.currentTimeMillis() - startTime
                
                // 记录首句音频时间
                if (firstAudioTime == null && task.audioChunks.isNotEmpty()) {
                    firstAudioTime = System.currentTimeMillis()
                    firstTaskStartTime?.let { start ->
                        val firstAudioLatency = firstAudioTime!! - start
                        Log.i(TAG, "[TTS Task 0] [首句] ✓ 完成: ${task.audioChunks.size} 块, " +
                                "$totalBytes 字节, 首字延迟=${firstAudioLatency}ms")
                    }
                } else {
                    Log.i(TAG, "[TTS Task 0] [首句] ✓ 完成: ${task.audioChunks.size} 块, " +
                            "$totalBytes 字节, 处理耗时=${elapsed}ms")
                }
                break
                
            } catch (e: TimeoutCancellationException) {
                task.error = "Timeout"
                val elapsed = System.currentTimeMillis() - startTime
                Log.w(TAG, "[TTS Task 0] [首句] ⚠ 超时 (${elapsed}ms) (尝试 ${attempt + 1}/${maxRetry + 1})")
                
                if (attempt < maxRetry) {
                    task.retryCount++
                    delay(300)  // 首句使用更短的重试间隔
                } else {
                    task.status = TaskStatus.FAILED
                    Log.e(TAG, "[TTS Task 0] [首句] ✗ 失败: 重试 ${maxRetry + 1} 次后仍超时")
                }
                
            } catch (e: Exception) {
                task.error = e.message
                val elapsed = System.currentTimeMillis() - startTime
                Log.w(TAG, "[TTS Task 0] [首句] ⚠ 错误 (${elapsed}ms): $e " +
                        "(尝试 ${attempt + 1}/${maxRetry + 1})")
                
                if (attempt < maxRetry) {
                    task.retryCount++
                    delay(300)
                } else {
                    task.status = TaskStatus.FAILED
                    Log.e(TAG, "[TTS Task 0] [首句] ✗ 失败: $e")
                }
            }
        }
        
        // 标记完成（无论成功或失败）
        task.completedEvent.complete(Unit)
        outputNotify.trySend(Unit)
    }
    
    /**
     * 处理单个 TTS 任务，支持重试
     */
    private suspend fun processTaskWithRetry(task: TTSTask) {
        semaphore.acquire()  // 并发控制
        
        try {
            task.status = TaskStatus.PROCESSING
            val startTime = System.currentTimeMillis()
            
            for (attempt in 0..maxRetry) {
                try {
                    Log.i(TAG, "[TTS Task ${task.sequenceId}] 开始处理 (尝试 ${attempt + 1}/${maxRetry + 1})")
                    
                    withTimeout(taskTimeout) {
                        executeTts(task)
                    }
                    
                    task.status = TaskStatus.COMPLETED
                    
                    val totalBytes = task.audioChunks.sumOf { it.size }
                    Log.i(TAG, "[TTS Task ${task.sequenceId}] ✓ 完成: ${task.audioChunks.size} 块, $totalBytes 字节")
                    break
                    
                } catch (e: TimeoutCancellationException) {
                    task.error = "Timeout"
                    Log.w(TAG, "[TTS Task ${task.sequenceId}] ⚠ 超时 (尝试 ${attempt + 1}/${maxRetry + 1})")
                    
                    if (attempt < maxRetry) {
                        task.retryCount++
                        delay(500)
                    } else {
                        task.status = TaskStatus.FAILED
                        Log.e(TAG, "[TTS Task ${task.sequenceId}] ✗ 失败: 重试 ${maxRetry + 1} 次后仍超时")
                    }
                    
                } catch (e: Exception) {
                    task.error = e.message
                    val errorStr = e.message?.lowercase() ?: ""
                    
                    // 检查是否是限速错误 (429)
                    val isRateLimit = "429" in errorStr || "rate limit" in errorStr || "too many" in errorStr
                    
                    Log.w(TAG, "[TTS Task ${task.sequenceId}] ⚠ 错误: $e (尝试 ${attempt + 1}/${maxRetry + 1})")
                    
                    if (attempt < maxRetry) {
                        task.retryCount++
                        
                        // 如果是限速错误，使用指数退避
                        if (isRateLimit) {
                            val backoffTime = (1 shl attempt) * 1000L  // 1s, 2s, 4s...
                            Log.i(TAG, "[TTS Task ${task.sequenceId}] 检测到限速，等待 ${backoffTime}ms 后重试")
                            delay(backoffTime)
                        } else {
                            delay(500)
                        }
                    } else {
                        task.status = TaskStatus.FAILED
                        Log.e(TAG, "[TTS Task ${task.sequenceId}] ✗ 失败: $e")
                    }
                }
            }
            
            // 标记完成（无论成功或失败）
            task.completedEvent.complete(Unit)
            outputNotify.trySend(Unit)
            
        } finally {
            semaphore.release()
        }
    }
    
    /**
     * 执行 TTS 合成
     */
    private suspend fun executeTts(task: TTSTask) {
        task.audioChunks.clear()
        
        ttsExecutor(task.text).collect { chunk ->
            if (chunk.isNotEmpty()) {
                task.audioChunks.add(chunk)
            }
        }
    }
    
    /**
     * 标记输入已完成，不会再有新任务提交
     */
    fun markInputComplete() {
        inputComplete.set(true)
        outputNotify.trySend(Unit)
        Log.d(TAG, "Input marked as complete")
    }
    
    /**
     * 按顺序输出音频
     * 
     * @return 按序输出的音频数据流
     */
    fun yieldAudioInOrder(): Flow<ByteArray> = channelFlow {
        while (true) {
            val currentId = nextOutputId.get()
            
            // 检查下一个应输出的任务
            val task = tasks[currentId]
            if (task != null) {
                // 等待任务完成
                task.completedEvent.await()
                
                // 输出音频
                when (task.status) {
                    TaskStatus.COMPLETED -> {
                        if (task.audioChunks.isNotEmpty()) {
                            val totalBytes = task.audioChunks.sumOf { it.size }
                            for (chunk in task.audioChunks) {
                                send(chunk)
                            }
                            Log.i(TAG, "[TTS Output $currentId] 输出音频: ${task.audioChunks.size} 块, $totalBytes 字节")
                        } else {
                            Log.w(TAG, "[TTS Output $currentId] ⚠ 任务完成但无音频数据")
                        }
                    }
                    TaskStatus.FAILED -> {
                        Log.e(TAG, "[TTS Output $currentId] ✗ 跳过失败任务: ${task.error}")
                        // 如果是严重的 API 错误 (如配额不足)，应该抛出异常终止流程
                        if (task.error?.contains("40000001") == true ||
                            task.error?.contains("DAILY_LIMIT_EXCEEDED") == true ||
                            task.error?.contains("FREE_TRIAL_EXPIRED") == true) {
                            throw Exception("TTS Error: ${task.error}")
                        }
                    }
                    else -> {
                        Log.w(TAG, "[TTS Output $currentId] ⚠ 意外状态: ${task.status}")
                    }
                }
                
                // 清理已输出的任务
                tasks.remove(currentId)
                nextOutputId.incrementAndGet()
                
            } else {
                // 检查是否所有任务都已完成
                if (inputComplete.get() && nextOutputId.get() >= totalTasks.get()) {
                    Log.d(TAG, "All tasks completed, stopping output")
                    break
                }
                
                // 等待新任务完成
                withTimeoutOrNull(1000L) {
                    outputNotify.receive()
                }
            }
        }
    }
    
    /**
     * 清理资源，取消所有未完成的任务
     */
    suspend fun cleanup() {
        // 取消所有活跃的任务
        activeJobs.forEach { job ->
            if (job.isActive) {
                job.cancel()
            }
        }
        
        // 等待所有任务结束
        activeJobs.forEach { job ->
            try {
                job.join()
            } catch (_: Exception) {}
        }
        
        activeJobs.clear()
        tasks.clear()
        
        // 关闭通道
        outputNotify.close()
        
        Log.d(TAG, "PredictiveTTSProcessor cleaned up")
    }
    
    /**
     * 取消整个处理器
     */
    fun cancel() {
        scope.cancel()
        Log.d(TAG, "PredictiveTTSProcessor cancelled")
    }
}