package com.example.everytalk.statecontroller

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * - Provides efficient content updates during streaming
 * - Automatically cleans up state when streaming completes
 * - Supports both text and image generation modes
 * 
 * Requirements: 1.4, 3.4
 * 
 * @see ViewModelStateHolder
 * @see StreamingBuffer
 */
class StreamingMessageStateManager {
    
    // Map of message ID to its streaming content StateFlow
    private val streamingStates = mutableMapOf<String, MutableStateFlow<String>>()
    
    // Track which messages are currently streaming
    private val activeStreamingMessages = mutableSetOf<String>()
    
    /**
     * Get or create a StateFlow for a message's streaming content
     * 
     * This method returns a StateFlow that UI components can observe
     * to get real-time updates of streaming content. The StateFlow
     * is created on-demand and cached for the lifetime of the stream.
     * 
     * @param messageId The ID of the message
     * @return StateFlow of the message content
     */
    fun getOrCreateStreamingState(messageId: String): StateFlow<String> {
        return streamingStates.getOrPut(messageId) {
            MutableStateFlow("")
        }.asStateFlow()
    }
    
    /**
     * Start streaming for a message
     * 
     * Initializes the streaming state for a new message.
     * Should be called when streaming begins.
     * 
     * @param messageId The ID of the message
     */
    fun startStreaming(messageId: String) {
        activeStreamingMessages.add(messageId)
        streamingStates.getOrPut(messageId) {
            MutableStateFlow("")
        }
        android.util.Log.d("StreamingMessageStateManager", "Started streaming for message: $messageId")
    }
    
    /**
     * Append text to a streaming message
     * 
     * Updates the StateFlow with new content. This will trigger
     * recomposition only in UI components observing this specific message.
     * 
     * @param messageId The ID of the message
     * @param text The text to append
     */
    fun appendText(messageId: String, text: String) {
        val stateFlow = streamingStates[messageId]
        if (stateFlow != null) {
            val currentContent = stateFlow.value
            stateFlow.value = currentContent + text
        } else {
            android.util.Log.w("StreamingMessageStateManager", 
                "Attempted to append to non-existent streaming state: $messageId")
        }
    }
    
    /**
     * Update the full content of a streaming message
     * 
     * Replaces the entire content with new text. Used when the
     * StreamingBuffer flushes its accumulated content.
     * 
     * @param messageId The ID of the message
     * @param content The new full content
     */
    fun updateContent(messageId: String, content: String) {
        val stateFlow = streamingStates[messageId]
        if (stateFlow != null) {
            // 🔍 [STREAM_DEBUG_ANDROID]
            android.util.Log.i("STREAM_DEBUG", "[StreamingMessageStateManager] ✅ Content updated: msgId=$messageId, len=${content.length}, preview='${content.take(50)}'")
            stateFlow.value = content
        } else {
            // Create new state if it doesn't exist
            android.util.Log.w("STREAM_DEBUG", "[StreamingMessageStateManager] ⚠️ Creating new state: msgId=$messageId, len=${content.length}")
            streamingStates[messageId] = MutableStateFlow(content)
        }
    }
    
    /**
     * Finish streaming for a message
     * 
     * Marks the message as no longer streaming and returns the final content.
     * The StateFlow is kept alive so UI components can continue observing it,
     * but it won't receive further updates.
     * 
     * @param messageId The ID of the message
     * @return The final content of the message
     */
    fun finishStreaming(messageId: String): String {
        activeStreamingMessages.remove(messageId)
        val finalContent = streamingStates[messageId]?.value ?: ""
        android.util.Log.d("StreamingMessageStateManager", 
            "Finished streaming for message: $messageId, final length: ${finalContent.length}")
        return finalContent
    }
    
    /**
     * Clear streaming state for a message
     * 
     * Removes the StateFlow and cleans up resources.
     * Should be called when a message is deleted or when
     * switching conversations.
     * 
     * @param messageId The ID of the message
     */
    fun clearStreamingState(messageId: String) {
        activeStreamingMessages.remove(messageId)
        streamingStates.remove(messageId)
        android.util.Log.d("StreamingMessageStateManager", "Cleared streaming state for message: $messageId")
    }
    
    /**
     * Clear all streaming states
     * 
     * Removes all StateFlows and cleans up resources.
     * Should be called when starting a new conversation or
     * when the app is being cleaned up.
     */
    fun clearAll() {
        val count = streamingStates.size
        activeStreamingMessages.clear()
        streamingStates.clear()
        android.util.Log.d("StreamingMessageStateManager", "Cleared all streaming states (count: $count)")
    }
    
    /**
     * Check if a message is currently streaming
     * 
     * @param messageId The ID of the message
     * @return True if the message is actively streaming
     */
    fun isStreaming(messageId: String): Boolean {
        return activeStreamingMessages.contains(messageId)
    }
    
    /**
     * Get the current content of a streaming message
     * 
     * Returns the current content without observing changes.
     * Useful for debugging or one-time reads.
     * 
     * @param messageId The ID of the message
     * @return The current content, or empty string if not found
     */
    fun getCurrentContent(messageId: String): String {
        return streamingStates[messageId]?.value ?: ""
    }
    
    /**
     * Get the count of active streaming messages
     * 
     * @return Number of messages currently streaming
     */
    fun getActiveStreamingCount(): Int {
        return activeStreamingMessages.size
    }
    
    /**
     * Get statistics about streaming state
     * 
     * Returns a map of statistics for debugging and monitoring.
     * 
     * @return Map of statistic name to value
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "activeStreamingCount" to activeStreamingMessages.size,
            "totalStatesCount" to streamingStates.size,
            "activeMessageIds" to activeStreamingMessages.toList()
        )
    }
}
