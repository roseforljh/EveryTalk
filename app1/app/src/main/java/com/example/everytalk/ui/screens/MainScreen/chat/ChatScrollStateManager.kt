package com.example.everytalk.ui.screens.MainScreen.chat

import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okio.`-DeprecatedOkio`.source
import kotlin.coroutines.cancellation.CancellationException

@Composable
fun rememberChatScrollStateManager(
    listState: LazyListState,
    coroutineScope: CoroutineScope
): ChatScrollStateManager {
    return remember(listState, coroutineScope) {
        ChatScrollStateManager(listState, coroutineScope)
    }
}

class ChatScrollStateManager(
    val listState: LazyListState,
    private val coroutineScope: CoroutineScope
) {
    var isAutoScrolling by mutableStateOf(false)
        private set

    var userManuallyScrolledAwayFromBottom by mutableStateOf(false)

    private var ongoingScrollJob by mutableStateOf<Job?>(null)

    fun scrollToBottomGuaranteed(reason: String = "Unknown") {
        if (userManuallyScrolledAwayFromBottom && reason != "FAB_Click") {
            return
        }

        ongoingScrollJob?.cancel()
        ongoingScrollJob = coroutineScope.launch {
            isAutoScrolling = true
            try {
                val targetIndex = listState.layoutInfo.totalItemsCount - 1
                if (targetIndex >= 0) {
                    listState.animateScrollToItem(index = targetIndex)
                }
            } finally {
                isAutoScrolling = false
            }
        }
    }

    fun cancelAutoScroll(reason: String = "User interaction") {
        if (ongoingScrollJob?.isActive == true) {
            ongoingScrollJob?.cancel(CancellationException(reason))
        }
    }

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) {
                cancelAutoScroll("User scrolled")
                userManuallyScrolledAwayFromBottom = true
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            // Fling is a user input, so we should always interrupt auto-scroll.
            cancelAutoScroll("User interrupted scroll by flinging")
            userManuallyScrolledAwayFromBottom = true
            return Velocity.Zero
        }
    }
}