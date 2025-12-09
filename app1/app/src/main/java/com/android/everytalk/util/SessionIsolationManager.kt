package com.android.everytalk.util

import com.android.everytalk.config.SessionIsolationConfig
import com.android.everytalk.util.messageprocessor.MessageProcessor
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 会话隔离管理器 - 统一管理会话级别的资源，确保AI输出不会串流
 */
class SessionIsolationManager {
    private val logger = AppLogger.forComponent("SessionIsolationManager")
    
    // 会话级别的资源映射
    private val sessionProcessors = ConcurrentHashMap<String, ConcurrentHashMap<String, MessageProcessor>>()
    
    // 活跃会话跟踪
    private val activeTextSession = AtomicReference<String?>(null)
    private val activeImageSession = AtomicReference<String?>(null)
    
    // 会话切换锁
    private val sessionSwitchMutex = kotlinx.coroutines.sync.Mutex()
    
    /**
     * 获取会话的消息处理器
     */
    fun getMessageProcessor(sessionId: String, messageId: String): MessageProcessor {
        val sessionMap = sessionProcessors.getOrPut(sessionId) { ConcurrentHashMap() }
        return sessionMap.getOrPut(messageId) {
            MessageProcessor().apply {
                initialize(sessionId, messageId)
                logger.debug("Created MessageProcessor for session=$sessionId, message=$messageId")
            }
        }
    }
    
    /**
     * 获取会话的块管理器
     */
    
    /**
     * 切换到新的文本会话
     */
    suspend fun switchToTextSession(newSessionId: String) {
        if (!SessionIsolationConfig.ENABLE_STRICT_SESSION_ISOLATION) return
        
        sessionSwitchMutex.withLock {
            val oldSession = activeTextSession.getAndSet(newSessionId)
            if (oldSession != null && oldSession != newSessionId) {
                logger.debug("Switching text session from $oldSession to $newSessionId")
                if (SessionIsolationConfig.FORCE_RESOURCE_CLEANUP_ON_SESSION_SWITCH) {
                    clearSessionResources(oldSession)
                }
            }
        }
    }
    
    /**
     * 切换到新的图像会话
     */
    suspend fun switchToImageSession(newSessionId: String) {
        if (!SessionIsolationConfig.ENABLE_STRICT_SESSION_ISOLATION) return
        
        sessionSwitchMutex.withLock {
            val oldSession = activeImageSession.getAndSet(newSessionId)
            if (oldSession != null && oldSession != newSessionId) {
                logger.debug("Switching image session from $oldSession to $newSessionId")
                if (SessionIsolationConfig.FORCE_RESOURCE_CLEANUP_ON_SESSION_SWITCH) {
                    clearSessionResources(oldSession)
                }
            }
        }
    }
    
    /**
     * 清理指定会话的所有资源
     */
    fun clearSessionResources(sessionId: String) {
        logger.debug("Clearing all resources for session: $sessionId")
        
        // 取消所有会话相关的处理器
        sessionProcessors[sessionId]?.values?.forEach { processor ->
            processor.cancel()
        }
        
        // 清理资源映射
        val processorsRemoved = sessionProcessors.remove(sessionId)?.size ?: 0
        val blockManagersRemoved = 0
        
        logger.debug("Cleared session $sessionId: $processorsRemoved processors, $blockManagersRemoved block managers")
    }
    
    /**
     * 清理所有会话资源
     */
    fun clearAllSessions() {
        logger.debug("Clearing all session resources")
        
        // 取消所有处理器
        sessionProcessors.values.forEach { sessionMap ->
            sessionMap.values.forEach { it.cancel() }
        }
        
        val totalSessions = sessionProcessors.size
        sessionProcessors.clear()
        
        activeTextSession.set(null)
        activeImageSession.set(null)
        
        logger.debug("Cleared $totalSessions sessions")
    }
    
    /**
     * 强制完成指定会话的所有流
     */
    
    /**
     * 获取会话统计信息
     */
    fun getSessionStats(): String {
        val totalProcessors = sessionProcessors.values.sumOf { it.size }
        val totalBlockManagers = 0
        val activeSessions = sessionProcessors.keys.size
        
        return """
            会话隔离统计:
            - 活跃会话数: $activeSessions
            - 总处理器数: $totalProcessors
            - 总块管理器数: $totalBlockManagers
            - 当前文本会话: ${activeTextSession.get()}
            - 当前图像会话: ${activeImageSession.get()}
        """.trimIndent()
    }
    
    /**
     * 执行垃圾回收清理
     */
    fun performGarbageCollection() {
        if (sessionProcessors.size > SessionIsolationConfig.MAX_SESSION_PROCESSOR_CACHE_SIZE) {
            // 清理最旧的会话（这里简化为清理前一半）
            val sessionsToRemove = sessionProcessors.keys.take(sessionProcessors.size / 2)
            sessionsToRemove.forEach { sessionId ->
                clearSessionResources(sessionId)
            }
            logger.debug("Performed garbage collection, removed ${sessionsToRemove.size} old sessions")
        }
    }
}