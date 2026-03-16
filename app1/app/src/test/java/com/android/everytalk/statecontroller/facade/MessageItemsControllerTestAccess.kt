package com.android.everytalk.statecontroller.facade

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.statecontroller.StreamingMessageStateManager
import com.android.everytalk.statecontroller.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

internal object MessageItemsControllerTestAccess {
    fun newController(): MessageItemsControllerForTest {
        return MessageItemsControllerForTest(
            stateHolder = ViewModelStateHolder(),
            streamingMessageStateManager = StreamingMessageStateManager(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
    }
}

internal class MessageItemsControllerForTest(
    val stateHolder: ViewModelStateHolder,
    streamingMessageStateManager: StreamingMessageStateManager,
    scope: CoroutineScope
) : MessageItemsController(stateHolder, streamingMessageStateManager, scope) {
    fun normalizeStatusTextForTest(message: Message): String = normalizeStatusText(message)

    fun chatListItemsForTest(): List<com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem> = runBlocking {
        chatListItems.first { it.isNotEmpty() }
    }
}
