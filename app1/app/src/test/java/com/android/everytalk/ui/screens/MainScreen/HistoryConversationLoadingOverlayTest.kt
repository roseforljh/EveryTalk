package com.android.everytalk.ui.screens.MainScreen

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import org.junit.Assert.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HistoryConversationLoadingOverlayTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `就绪扫描在后台完成且布局重组不重复读取整段历史`() {
        val reads = AtomicInteger()
        val readerThreads = ConcurrentLinkedQueue<Thread>()
        val originals = List(2_000) { Message(id = "m-$it", text = "", sender = Sender.AI) }
        val trackedMessages = object : AbstractList<Message>() {
            override val size: Int get() = originals.size
            override fun get(index: Int): Message {
                reads.incrementAndGet()
                readerThreads.add(Thread.currentThread())
                return originals[index]
            }
        }
        val layoutVersion = mutableStateOf(0)
        var ready = false
        var uiThread: Thread? = null
        composeRule.setContent {
            uiThread = Thread.currentThread()
            val matches = rememberHistoryMessageItemsMatch(trackedMessages, emptyList(), true)
            Text("布局 ${layoutVersion.value}")
            SideEffect { ready = matches }
        }
        composeRule.waitUntil(5_000L) { ready }
        assertTrue(reads.get() >= originals.size)
        assertTrue(readerThreads.all { it !== uiThread })
        val initialReads = reads.get()
        repeat(10) {
            composeRule.runOnIdle { layoutVersion.value++ }
            composeRule.waitForIdle()
        }
        assertEquals(initialReads, reads.get())
    }

    @Test
    fun `相同内容的新列表不会卡住且换会话后不复用旧就绪结果`() {
        val message = Message(id = "one", text = "问题", sender = Sender.User)
        val messages = mutableStateOf(listOf(message))
        val items = mutableStateOf<List<ChatListItem>>(listOf(ChatListItem.UserMessage(message.id, message.text, message.attachments)))
        val version = mutableStateOf(0)
        var ready = false
        composeRule.setContent {
            version.value
            val matches = rememberHistoryMessageItemsMatch(messages.value.toMutableList(), items.value.toMutableList(), true)
            SideEffect { ready = matches }
        }
        composeRule.waitUntil(5_000L) { ready }
        composeRule.runOnIdle { version.value++ }
        composeRule.waitForIdle()
        assertTrue(ready)
        composeRule.runOnIdle { messages.value = listOf(message.copy(id = "two")) }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000L) { !ready }
        composeRule.runOnIdle {
            val current = messages.value.single()
            items.value = listOf(ChatListItem.UserMessage(current.id, current.text, current.attachments))
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000L) { ready }
    }

    @Test
    fun `history loading overlay displays full page skeleton`() {
        composeRule.setContent {
            MaterialTheme {
                HistoryConversationLoadingOverlay()
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_loading_conversation)).assertIsDisplayed()
    }
}
