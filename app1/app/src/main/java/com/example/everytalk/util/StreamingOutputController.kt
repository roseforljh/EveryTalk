package com.example.everytalk.util

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong

/**
 * 流式输出控制器 - 控制AI输出的流式显示效果
 * 解决"一次性全部吐出"的问题,实现打字机效果
 */
class StreamingOutputController(
    private val onUpdate: (String) -> Unit,
    private val updateIntervalMs: Long = 100L,
    private val minCharsToUpdate: Int = 10
) {
    private val logger = AppLogger.forComponent("StreamingOutputController")
    private var accumulatedText = StringBuilder()
    private val lastUpdateTime = AtomicLong(0)
    private var updateJob: Job? = null
    
    /**
     * 添加文本块
     * @param text 新增的文本内容
     */
    fun addText(text: String) {
        synchronized(accumulatedText) {
            accumulatedText.append(text)
        }
        
        // 检查是否满足更新条件
        val now = System.currentTimeMillis()
        val timeSinceLastUpdate = now - lastUpdateTime.get()
        val hasEnoughChars = accumulatedText.length >= minCharsToUpdate
        
        if (hasEnoughChars && timeSinceLastUpdate >= updateIntervalMs) {
            flushUpdate()
        }
    }
    
    /**
     * 强制刷新更新
     */
    fun flushUpdate() {
        val textToSend = synchronized(accumulatedText) {
            accumulatedText.toString()
        }
        
        if (textToSend.isNotEmpty()) {
            onUpdate(textToSend)
            lastUpdateTime.set(System.currentTimeMillis())
            logger.debug("Flushed ${textToSend.length} characters")
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
        logger.debug("Controller cleared")
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
     * @return 控制器实例
     */
    fun createController(
        messageId: String,
        onUpdate: (String) -> Unit,
        updateIntervalMs: Long = 100L,
        minCharsToUpdate: Int = 10
    ): StreamingOutputController {
        val controller = StreamingOutputController(onUpdate, updateIntervalMs, minCharsToUpdate)
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