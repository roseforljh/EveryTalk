package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ViewModelStateHolderConversationParamsCleanupTest {

    @Test
    fun `清理参数保留全部现存稳定会话并移除孤儿`() {
        val stateHolder = ViewModelStateHolder()
        val conversations = (0 until 60).map { index ->
            listOf(Message(id = "uuid-$index", text = "消息 $index", sender = Sender.User))
        }
        val initialParams = buildMap {
            conversations.forEachIndexed { index, conversation ->
                put(conversation.first().id, GenerationConfig(temperature = index / 100f))
            }
            put("orphan", GenerationConfig(temperature = 0.99f))
        }
        var persisted: Map<String, GenerationConfig>? = null
        stateHolder._historicalConversations.value = conversations
        stateHolder.setCurrentConversationId("current-empty-chat")
        stateHolder.initializePersistence(
            saveCallback = { persisted = it },
            initialParams = initialParams,
        )
        stateHolder.markTextHistoryReadyForParameterCleanup()

        stateHolder.cleanupOldConversationParameters()

        assertEquals(60, stateHolder.conversationGenerationConfigs.value.size)
        assertFalse(stateHolder.conversationGenerationConfigs.value.containsKey("orphan"))
        assertEquals(stateHolder.conversationGenerationConfigs.value, persisted)
    }

    @Test
    fun `历史尚未完整加载时不得清理已有参数`() {
        val stateHolder = ViewModelStateHolder()
        val initialParams = mapOf(
            "persisted-history" to GenerationConfig(temperature = 0.4f),
        )
        var saveCalls = 0
        stateHolder.initializePersistence(
            saveCallback = { saveCalls++ },
            initialParams = initialParams,
        )

        stateHolder.cleanupOldConversationParameters()

        assertEquals(initialParams, stateHolder.conversationGenerationConfigs.value)
        assertEquals(0, saveCalls)
    }
}
