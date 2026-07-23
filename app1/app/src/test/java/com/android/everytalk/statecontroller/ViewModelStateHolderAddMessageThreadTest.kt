package com.android.everytalk.statecontroller

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ViewModelStateHolderAddMessageThreadTest {

    @Test
    fun `主线程添加消息保持调用顺序`() {
        val stateHolder = ViewModelStateHolder()

        stateHolder.addMessage(Message(id = "first", text = "一", sender = Sender.User))
        stateHolder.addMessage(Message(id = "second", text = "二", sender = Sender.AI))

        assertEquals(listOf("first", "second"), stateHolder.messages.map(Message::id))
    }

    @Test
    fun `后台线程添加消息立即失败且不修改列表`() {
        val stateHolder = ViewModelStateHolder()
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            runCatching {
                stateHolder.addMessage(Message(id = "background", text = "后台", sender = Sender.User))
            }.onFailure(failure::set)
        }

        thread.start()
        thread.join()

        assertTrue(failure.get() is IllegalStateException)
        assertTrue(stateHolder.messages.isEmpty())
    }
}
