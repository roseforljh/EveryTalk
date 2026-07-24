package com.android.everytalk.statecontroller.controller.conversation

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.statecontroller.ViewModelStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConversationPreviewControllerTest {
    private val stateHolder = ViewModelStateHolder()
    private val controller = ConversationPreviewController(stateHolder)

    @Test
    fun `预览标题同步生成并支持清理和覆盖缓存`() {
        stateHolder._historicalConversations.value = listOf(
            listOf(Message(id = "message-1", text = "第一条用户消息", sender = Sender.User)),
        )

        assertEquals("第一条用户消息", controller.getConversationPreviewText("session-1", 0))

        controller.setCachedTitle("session-1", " 自定义标题 ", isImageGeneration = false)
        assertEquals("自定义标题", controller.getConversationPreviewText("session-1", 0))

        stateHolder._historicalConversations.value = listOf(
            listOf(Message(id = "message-1", text = "更新后的用户消息", sender = Sender.User)),
        )
        controller.clearAllCaches()
        assertEquals("更新后的用户消息", controller.getConversationPreviewText("session-1", 0))
    }

    @Test
    fun `分享会话复用唯一预览控制器`() {
        val source = appViewModelSource()

        assertTrue(source.contains("val title = conversationPreviewController.getConversationPreviewText"))
        assertFalse(source.contains("historyController.getConversationPreviewText"))
    }

    private fun appViewModelSource(): String {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModel.kt"),
            File("app/src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModel.kt"),
            File("app1/app/src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModel.kt"),
        )
        val appViewModel = requireNotNull(candidates.firstOrNull(File::isFile)) { "找不到 AppViewModel.kt" }
            .readText(Charsets.UTF_8)
        val dataActions = candidates.map { file ->
            File(file.parentFile, "AppViewModelDataActions.kt")
        }.firstOrNull(File::isFile)?.readText(Charsets.UTF_8).orEmpty()
        return appViewModel + "\n" + dataActions
    }
}
