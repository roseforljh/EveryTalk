package com.example.everytalk.ui.screens.MainScreen.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.everytalk.StateControler.AppViewModel
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.ui.screens.BubbleMain.Main.MessageBubble

@Composable
fun ChatMessagesList(
    messages: List<Message>,
    listState: LazyListState,
    viewModel: AppViewModel,
    currentStreamingAiMessageId: String?,
    isApiCalling: Boolean,
    reasoningCompleteMap: Map<String, Boolean>,
    bubbleMaxWidth: Dp,
    nestedScrollConnection: NestedScrollConnection,
    onUserInteraction: () -> Unit,
    onRequestEditMessage: (Message) -> Unit,
    onRequestRegenerateAiResponse: (Message) -> Unit
    // 1. 从函数参数中移除 onReasoningBoxBecameVisible
    // onReasoningBoxBecameVisible: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
            .padding(horizontal = 8.dp),
        state = listState,
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = 16.dp
        )
    ) {
        items(items = messages, key = { message -> message.id }) { message ->
            val isLoadingMessage = message.id == currentStreamingAiMessageId && isApiCalling

            // Capture mutable property in a local val
            val currentReasoning = message.reasoning

            val showLoadingDots = isLoadingMessage &&
                    message.text.isBlank() &&
                    // Use the local val for the check
                    (currentReasoning == null || currentReasoning.isBlank()) &&
                    !message.contentStarted &&
                    !message.isError


            MessageBubble(
                message = message,
                viewModel = viewModel,
                onUserInteraction = onUserInteraction,
                isMainContentStreaming = isLoadingMessage && message.contentStarted,
                isReasoningStreaming = isLoadingMessage && currentReasoning != null && !currentReasoning.isBlank() && !(reasoningCompleteMap[message.id]
                    ?: false), // Also use currentReasoning here
                isReasoningComplete = (reasoningCompleteMap[message.id] ?: false),
                maxWidth = bubbleMaxWidth,
                showLoadingBubble = showLoadingDots,
                onEditRequest = { msg ->
                    onUserInteraction()
                    onRequestEditMessage(msg)
                },
                onRegenerateRequest = { userMsg ->
                    onUserInteraction()
                    onRequestRegenerateAiResponse(userMsg)
                }
            )
        }
        item(key = "chat_screen_footer_spacer_in_list") {
            Spacer(modifier = Modifier.height(1.dp))
        }
    }
}