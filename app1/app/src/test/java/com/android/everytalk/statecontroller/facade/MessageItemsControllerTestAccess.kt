package com.android.everytalk.statecontroller.facade

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.statecontroller.StreamingMessageStateManager
import com.android.everytalk.statecontroller.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    stateHolder: ViewModelStateHolder,
    streamingMessageStateManager: StreamingMessageStateManager,
    scope: CoroutineScope
) : MessageItemsController(stateHolder, streamingMessageStateManager, scope) {
    fun normalizeStatusTextForTest(message: Message): String = normalizeStatusText(message)
}
