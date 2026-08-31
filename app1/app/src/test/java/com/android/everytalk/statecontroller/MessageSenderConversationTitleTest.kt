package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.util.ConversationNameHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MessageSenderConversationTitleTest {

    @Test
    fun `模型上下文剔除会话名称元数据`() {
        val messages = listOf(
            Message(
                id = "title-1",
                text = "不应发送给模型的会话名称",
                sender = Sender.System,
                isPlaceholderName = true,
            ),
            Message(id = "user-1", text = "实际问题", sender = Sender.User),
        )

        val contextMessages = ConversationNameHelper.withoutStoredConversationTitle(messages)

        assertEquals(listOf("user-1"), contextMessages.map(Message::id))
    }

    @Test
    fun `Agent加载占位不影响首条用户消息判断`() {
        val messages = listOf(
            Message(id = "user-1", text = "查看服务器配置", sender = Sender.User),
            Message(id = "ai-loading", text = "", sender = Sender.AI),
        )

        assertTrue(isFirstUserMessageForNewChat(messages, loadedHistoryIndex = null))
        assertFalse(isFirstUserMessageForNewChat(messages, loadedHistoryIndex = 0))
    }

    @Test
    fun `Agent先冻结Workspace快照再触发首次会话迁移`() {
        val source = messageSenderSendFlowSource()
        val sendBlock = source.substringAfter("val computerPreparation = async(Dispatchers.IO)")
        val userMessageIndex = sendBlock.indexOf("addOrReplaceRegeneratedUserMessage(")
        val loadingMessageIndex = sendBlock.indexOf("apiHandler.prepareStreamingAiMessage(")
        val preparationAwaitIndex = sendBlock.indexOf("computerPreparation.await()")
        val historySaveIndex = sendBlock.indexOf("historyManager.saveCurrentChatToHistoryNow(")

        assertTrue("用户消息应在等待 Workspace 前显示", userMessageIndex in 0 until preparationAwaitIndex)
        assertTrue("Agent 加载状态应在等待 Workspace 前显示", loadingMessageIndex in 0 until preparationAwaitIndex)
        assertTrue("Workspace 快照必须在首次历史保存迁移会话 ID 前冻结", preparationAwaitIndex in 0 until historySaveIndex)
    }

    @Test
    fun `Agent每次请求都在模型调用前同步保存可见消息`() {
        val source = messageSenderSendFlowSource()
        val saveCondition = source
            .substringBefore("historyManager.saveCurrentChatToHistoryNow(")
            .substringAfterLast("if (")
        val historySaveIndex = source.indexOf("historyManager.saveCurrentChatToHistoryNow(")
        val modelRequestIndex = source.indexOf("apiHandler.streamChatResponse(")

        assertTrue("已有会话的 Agent 请求也必须同步保存 AI 占位", saveCondition.contains("isAgentEnabledForRequest"))
        assertTrue("可见消息必须先落库，随后才能启动模型请求", historySaveIndex in 0 until modelRequestIndex)
    }

    @Test
    fun `Agent预创建占位前取消旧请求且创建后不再取消当前发送任务`() {
        val source = messageSenderSendFlowSource()
        val preparationBlock = source
            .substringAfter("val isNewImageChatFirstMessage")
            .substringBefore("val computerPreparationResult")
        val cancelIndex = preparationBlock.indexOf("apiHandler.cancelCurrentApiJob(")
        val placeholderIndex = preparationBlock.indexOf("apiHandler.prepareStreamingAiMessage(")
        val afterPlaceholderBlock = source
            .substringAfter("val computerPreparationResult")
            .substringBefore("val messagesInChatUiSnapshot")

        assertTrue("必须在 Agent 占位登记当前任务前取消旧请求", cancelIndex in 0 until placeholderIndex)
        assertFalse("Agent 占位登记当前任务后不能再次取消它", afterPlaceholderBlock.contains("apiHandler.cancelCurrentApiJob("))
    }

    private fun messageSenderSendFlowSource(): String {
        val relativePath = "statecontroller/message/MessageSenderSendFlow.kt"
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/$relativePath"),
            File("app/src/main/java/com/android/everytalk/$relativePath"),
            File("app1/app/src/main/java/com/android/everytalk/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "找不到 $relativePath"
        }.readText(Charsets.UTF_8)
    }
}
