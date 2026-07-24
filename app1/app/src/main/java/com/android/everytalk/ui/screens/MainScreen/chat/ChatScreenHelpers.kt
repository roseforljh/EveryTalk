package com.android.everytalk.ui.screens.MainScreen
import com.android.everytalk.statecontroller.*

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.navigation.Screen
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.ConversationScrollState
import com.android.everytalk.statecontroller.SimpleModeManager
import com.android.everytalk.ui.components.AppTopBar
import com.android.everytalk.ui.components.AnimatedWebSourcesDialog
import com.android.everytalk.ui.components.EveryTalkLoadingIndicator
import com.android.everytalk.ui.components.ScrollToBottomButton
import com.android.everytalk.ui.components.WebSourcesDialogEdgeGap
import com.android.everytalk.ui.components.ImagePreviewDialog
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogCancelColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.ChatInputArea
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.ChatMessagesList
import com.android.everytalk.ui.components.content.LocalStickyHeaderTop
import com.android.everytalk.ui.components.image.buildImagePreviewSelection
import com.android.everytalk.ui.screens.MainScreen.chat.dialog.EditMessageDialog
import com.android.everytalk.ui.screens.MainScreen.chat.dialog.SystemPromptDialog
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.EmptyChatView
import com.android.everytalk.ui.screens.MainScreen.chat.models.ModelSelectionBottomSheet
import com.android.everytalk.ui.screens.MainScreen.chat.text.state.rememberChatScrollStateManager
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.screens.MainScreen.chat.core.PlaceholderRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay

internal fun shouldPreserveScrollSessionOnConversationIdChange(
    previousConversationId: String?,
    newConversationId: String,
    messages: List<Message>,
    isHistoryConversationLoad: Boolean = false,
): Boolean {
    if (isHistoryConversationLoad) return false
    if (previousConversationId.isNullOrBlank()) {
        return false
    }
    if (previousConversationId == newConversationId) {
        return true
    }

    val derivedStableConversationId = messages.firstOrNull { it.sender == Sender.User }?.id
        ?: messages.firstOrNull { it.sender == Sender.System && !it.isPlaceholderName }?.id
        ?: messages.firstOrNull()?.id

    val previousIdWasTemporary = previousConversationId.startsWith("new_chat_") ||
        previousConversationId.startsWith("chat_") ||
        previousConversationId.startsWith("user_temp_")
    return previousIdWasTemporary && derivedStableConversationId == newConversationId
}

internal fun buildHistoryLoadingBubblePlaceholders(conversationId: String): List<ChatListItem> {
    val keyPrefix = conversationId.ifBlank { "history" }
    return listOf(
        ChatListItem.LoadingBubblePlaceholder(
            id = "$keyPrefix-history-placeholder-0",
            role = PlaceholderRole.User,
            widthFraction = 0.58f,
            estimatedHeightDp = 52,
        ),
        ChatListItem.LoadingBubblePlaceholder(
            id = "$keyPrefix-history-placeholder-1",
            role = PlaceholderRole.Assistant,
            widthFraction = 0.82f,
            estimatedHeightDp = 92,
        ),
        ChatListItem.LoadingBubblePlaceholder(
            id = "$keyPrefix-history-placeholder-2",
            role = PlaceholderRole.User,
            widthFraction = 0.46f,
            estimatedHeightDp = 48,
        ),
        ChatListItem.LoadingBubblePlaceholder(
            id = "$keyPrefix-history-placeholder-3",
            role = PlaceholderRole.Assistant,
            widthFraction = 0.76f,
            estimatedHeightDp = 128,
        ),
        ChatListItem.LoadingBubblePlaceholder(
            id = "$keyPrefix-history-placeholder-4",
            role = PlaceholderRole.Assistant,
            widthFraction = 0.64f,
            estimatedHeightDp = 72,
        ),
    )
}

internal fun shouldHideHistoryLoadingSkeleton(
    chatItems: List<ChatListItem>,
): Boolean {
    return chatItems.isNotEmpty()
}

internal fun shouldShowHistoryConversationLoadingOverlay(
    isLoadingHistory: Boolean,
    overlayKey: String?,
    observedLoadGeneration: Long,
    currentLoadGeneration: Long,
): Boolean = isLoadingHistory ||
    overlayKey != null ||
    shouldStartHistoryConversationLoadingOverlay(
        observedLoadGeneration = observedLoadGeneration,
        currentLoadGeneration = currentLoadGeneration,
    )

internal fun shouldShowInitialConversationLoadingOverlay(
    isHistoryLoadingOverlayVisible: Boolean,
    initialScrollHandled: Boolean,
): Boolean = isHistoryLoadingOverlayVisible ||
    !initialScrollHandled

internal fun shouldStartHistoryConversationLoadingOverlay(
    observedLoadGeneration: Long,
    currentLoadGeneration: Long,
): Boolean = currentLoadGeneration > 0L && currentLoadGeneration != observedLoadGeneration

internal fun shouldClearHistoryLoadingOverlay(
    completedGeneration: Long,
    currentGeneration: Long,
    completedOverlayKey: String?,
    currentOverlayKey: String?,
): Boolean = completedOverlayKey != null &&
    completedGeneration == currentGeneration &&
    completedOverlayKey == currentOverlayKey

internal fun isHistoryConversationReadyForInitialBottom(
    currentConversationId: String,
    scrollSessionKey: String,
    isLoadingHistory: Boolean,
    isLoadingHistoryData: Boolean = false,
    requireMatchingScrollSession: Boolean = true,
    messages: List<Message>,
    chatItems: List<ChatListItem>,
    laidOutItemCount: Int,
    requireLaidOutItemCount: Boolean = true,
): Boolean {
    if (isLoadingHistory || isLoadingHistoryData) return false
    if (requireMatchingScrollSession && currentConversationId != scrollSessionKey) return false

    val expectedMessageIds = messages
        .dropWhile { message ->
            message.sender == Sender.System && !message.isPlaceholderName && message.text.isNotBlank()
        }
        .filter { message -> message.sender != Sender.Tool || message.isError }
        .mapTo(mutableSetOf()) { message -> message.id }
    val renderedMessageIds = chatItems.mapNotNullTo(mutableSetOf()) { item ->
        when (item) {
            is ChatListItem.UserMessage -> item.messageId
            is ChatListItem.SystemMessage -> item.messageId
            is ChatListItem.AiMessage -> item.messageId
            is ChatListItem.AiMessageCode -> item.messageId
            is ChatListItem.AiMessageSources -> item.messageId
            is ChatListItem.AiMarkdownNode -> item.messageId
            is ChatListItem.AiMessageStreaming -> item.messageId
            is ChatListItem.AiMessageCodeStreaming -> item.messageId
            is ChatListItem.AiMessageReasoning -> item.message.id
            is ChatListItem.AiMessageFooter -> item.message.id
            is ChatListItem.ErrorMessage -> item.messageId
            is ChatListItem.LoadingIndicator -> item.messageId
            is ChatListItem.StatusIndicator -> item.messageId
            is ChatListItem.LoadingBubblePlaceholder -> null
        }
    }

    return expectedMessageIds == renderedMessageIds &&
        (!requireLaidOutItemCount || laidOutItemCount >= chatItems.size)
}

@Composable
internal fun HistoryConversationLoadingOverlay(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.88f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { change ->
                            change.consume()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        EveryTalkLoadingIndicator(
            size = 32.dp,
            strokeWidth = 3.dp,
            contentDescription = "正在加载会话",
        )
    }
}

internal fun calculateSourcesDialogBottomAvoidance(
    inputAreaHeight: Dp,
    inputBottomInset: Dp,
    edgeGap: Dp = WebSourcesDialogEdgeGap
): Dp {
    val inputContentHeight = if (inputAreaHeight > inputBottomInset) {
        inputAreaHeight - inputBottomInset
    } else {
        0.dp
    }
    return inputContentHeight + edgeGap
}

internal fun calculateSourcesDialogTopAvoidance(
    topControlsBottom: Dp,
    statusBarHeight: Dp,
    edgeGap: Dp = WebSourcesDialogEdgeGap
): Dp {
    val controlsBottomBelowStatusBar = if (topControlsBottom > statusBarHeight) {
        topControlsBottom - statusBarHeight
    } else {
        0.dp
    }
    return controlsBottomBelowStatusBar + edgeGap
}

