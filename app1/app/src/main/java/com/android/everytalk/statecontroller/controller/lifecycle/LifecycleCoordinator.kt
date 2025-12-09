package com.android.everytalk.statecontroller.controller.lifecycle
import com.android.everytalk.statecontroller.controller.conversation.ConversationPreviewController

import android.util.Log
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import com.android.everytalk.util.cache.CacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * LifecycleCoordinator
 * 统一收敛应用生命周期相关的保存/清理策略，避免 AppViewModel 直接分散处理。
 *
 * 职责：
 * - 应用停止/暂停时保存当前文本/图像会话（含仅推理更新）
 * - 低内存时清理非必要缓存
 * - onCleared 时的资源清理（缓存、流式状态）
 */
class LifecycleCoordinator(
    private val stateHolder: ViewModelStateHolder,
    private val historyManager: HistoryManager,
    private val cacheManager: CacheManager,
    private val conversationPreviewController: ConversationPreviewController,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = { msg -> Log.d("LifecycleCoordinator", msg) }
) {

    /**
     * 在应用停止/暂停时调用：保存当前文本和图像模式的会话。
     * 使用 forceSave=true 确保“仅推理更新”也会被持久化。
     */
    fun saveOnStop() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    historyManager.saveCurrentChatToHistoryIfNeeded(
                        isImageGeneration = false,
                        forceSave = true
                    )
                    historyManager.saveCurrentChatToHistoryIfNeeded(
                        isImageGeneration = true,
                        forceSave = true
                    )
                }
                logger("App state saved on stop/pause")
            } catch (e: Exception) {
                Log.e("LifecycleCoordinator", "Failed to save app state on stop", e)
            }
        }
    }

    /**
     * 在 ViewModel.onCleared 时调用：清理缓存与流式状态。
     */
    fun onCleared() {
        // 清理缓存管理器（内部会停止自身协程等）
        cacheManager.cleanup()
        // 如未来 MessageContentController 等拥有内部资源，也应在其所在处清理
        // 清理流式消息状态管理器，防止协程泄漏
        try {
            stateHolder.streamingMessageStateManager.cleanup()
        } catch (e: Exception) {
            Log.w("LifecycleCoordinator", "Error cleaning streaming manager", e)
        }
        logger("ViewModel cleared; caches and streaming states cleaned up")
    }

    /**
     * 低内存回调：清理预览缓存（由控制器统一处理）。
     */
    fun onLowMemory() {
        Log.w("LifecycleCoordinator", "Low memory detected, clearing non-critical caches")
        conversationPreviewController.clearAllCaches()
        Log.i("LifecycleCoordinator", "Low memory caches cleared")
    }

    /**
     * 清理所有缓存：包含全局 CacheManager 与预览缓存。
     */
    fun clearAllCaches() {
        scope.launch {
            cacheManager.clearAllCaches()
            // 预览缓存交由控制器统一清理
            conversationPreviewController.clearAllCaches()
            logger("All caches cleared")
        }
    }
}