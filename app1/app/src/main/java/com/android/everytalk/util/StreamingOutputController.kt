package com.android.everytalk.util

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

/**
 * 流式输出控制器（直通版）
 * - 不做去重、不做截断、不做节流/合并
 * - 每次接收到增量，直接累积并把“完整累积文本”回传给 UI
 * - 不改写任何 AI 输出内容
 */
class StreamingOutputController(
    private val onUpdate: (String) -> Unit,
    private val updateIntervalMs: Long = 60L, // 保留签名，不使用
    private val minCharsToUpdate: Int = 6,    // 保留签名，不使用
    private val maxAccumulatedChars: Int = 500_000 // 保留签名，不使用
) {
    private val logger = AppLogger.forComponent("StreamingOutputController")
    private var accumulatedText = StringBuilder()
    private val lastUpdateTime = AtomicLong(0) // 保留字段，不参与控制
    private var updateJob: Job? = null         // 保留字段，不参与控制
    private var isOverflowing = false          // 保留字段，不参与控制

    /**
     * 添加文本块（直通）
     * - 直接累积原始文本
     * - 立即通知 UI 使用“当前完整文本”，不进行任何改写/过滤
     */
    fun addText(text: String): Boolean {
        synchronized(accumulatedText) {
            accumulatedText.append(text)
        }
        // 直接把“完整累积内容”回传，避免任何加工
        flushUpdate()
        return true
    }

    /**
     * 立即把当前完整文本发送给 UI（不做任何节流）
     */
    fun flushUpdate() {
        val textToSend = synchronized(accumulatedText) {
            accumulatedText.toString()
        }
        if (textToSend.isNotEmpty()) {
            onUpdate(textToSend)
            lastUpdateTime.set(System.currentTimeMillis())
            logger.debug("Flushed ${textToSend.length} characters (pass-through)")
        }
    }

    /**
     * 获取当前累积的文本
     */
    fun getCurrentText(): String = synchronized(accumulatedText) {
        accumulatedText.toString()
    }

    /**
     * 清空累积的文本
     */
    fun clear() {
        synchronized(accumulatedText) {
            accumulatedText.clear()
        }
        lastUpdateTime.set(0L)
        updateJob?.cancel()
        isOverflowing = false
        logger.debug("Controller cleared (pass-through)")
    }

    /**
     * 保留接口，始终返回 false（不再做溢出判断/截断）
     */
    fun isOverflowing(): Boolean = false

    /**
     * 获取当前累积的字符数
     */
    fun getCurrentLength(): Int = synchronized(accumulatedText) {
        accumulatedText.length
    }
}

/**
 * 流式输出管理器 - 为每个消息创建独立的控制器
 */
object StreamingOutputManager {
    private val controllers = mutableMapOf<String, StreamingOutputController>()

    /**
     * 为指定消息创建流式输出控制器
     * @param messageId 消息ID
     * @param onUpdate 更新回调
     * @param maxAccumulatedChars 保留参数，无实际限制
     * @return 控制器实例
     */
    fun createController(
        messageId: String,
        onUpdate: (String) -> Unit,
        updateIntervalMs: Long = 60L,
        minCharsToUpdate: Int = 6,
        maxAccumulatedChars: Int = 500_000
    ): StreamingOutputController {
        val controller = StreamingOutputController(onUpdate, updateIntervalMs, minCharsToUpdate, maxAccumulatedChars)
        controllers[messageId] = controller
        return controller
    }

    /**
     * 获取指定消息的控制器
     */
    fun getController(messageId: String): StreamingOutputController? {
        return controllers[messageId]
    }

    /**
     * 移除并清理指定消息的控制器
     */
    fun removeController(messageId: String) {
        controllers[messageId]?.clear()
        controllers.remove(messageId)
    }

    /**
     * 清理所有控制器
     */
    fun clearAll() {
        controllers.values.forEach { it.clear() }
        controllers.clear()
    }
}