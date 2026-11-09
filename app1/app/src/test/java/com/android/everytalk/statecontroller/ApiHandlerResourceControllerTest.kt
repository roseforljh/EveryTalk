package com.android.everytalk.statecontroller

import com.android.everytalk.util.AppLogger
import com.android.everytalk.util.PromptLeakGuard
import com.android.everytalk.util.messageprocessor.MessageProcessor
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiHandlerResourceControllerTest {
    @Test
    fun `清理仅删除非活跃处理标记并保留后台任务消息`() {
        val state = ViewModelStateHolder()
        state.textApiMessageIds["background"] = "background-message"
        val processors = ConcurrentHashMap<String, MessageProcessor>().apply {
            put("background-message", MessageProcessor())
            put("inactive-message", MessageProcessor())
        }
        val processed = ConcurrentHashMap.newKeySet<String>().apply {
            add("background-message")
            add("inactive-message")
        }
        val controller = ApiHandlerResourceController(
            stateHolder = state,
            viewModelScope = CoroutineScope(Job()),
            messageProcessorMap = processors,
            processedMessageIds = processed,
            generatedImageSourceFingerprints = ConcurrentHashMap(),
            promptLeakDetectors = ConcurrentHashMap<String, PromptLeakGuard.StreamingDetector>(),
            retryCountMap = ConcurrentHashMap(),
            logger = mockk<AppLogger.ComponentLogger>(relaxed = true),
            onAiMessageFullTextChanged = { _, _ -> },
        )

        controller.clearTextChatResources()

        assertTrue(processed.contains("background-message"))
        assertFalse(processed.contains("inactive-message"))
        assertTrue(processors.containsKey("background-message"))
        assertFalse(processors.containsKey("inactive-message"))
    }
}
