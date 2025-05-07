package com.example.app1.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.* // Keep Delete for SwipeToDismissBox if needed, otherwise remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.example.app1.ui.components.HistoryTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    historicalConversations: List<List<Message>>,
    onNavigateBack: () -> Unit,
    onConversationClick: (Int) -> Unit,
    onDeleteConversation: (Int) -> Unit,
    onNewChatClick: () -> Unit,
    // REMOVED: onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appViewModel: AppViewModel = viewModel()
    val loadedHistoryIndex by appViewModel.loadedHistoryIndex.collectAsState()

    // REMOVED: var showClearConfirmationDialog by remember { mutableStateOf(false) }

    val userBubbleGreyColor = remember { Color(red = 200, green = 200, blue = 200, alpha = 128) }

    Scaffold(
        topBar = {
            HistoryTopBar(onBackClick = onNavigateBack)
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewChatClick,
                modifier = Modifier.padding(16.dp),
                shape = CircleShape,
                containerColor = Color.Black,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新建对话"
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(paddingValues)
        ) {
            ConversationHistoryList(
                conversations = historicalConversations,
                selectedConversationIndex = loadedHistoryIndex,
                selectedItemBackgroundColor = userBubbleGreyColor,
                onConversationClick = { _, index ->
                    onConversationClick(index)
                },
                onDeleteConversation = { index ->
                    onDeleteConversation(index)
                },
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp) // Keep padding for FAB
            )

            // REMOVED: "清空记录" button Box
            /*
            if (historicalConversations.isNotEmpty()) {
                Box(
                    // ...
                ) {
                    // ...
                }
            }
            */
        }

        // REMOVED: AlertDialog for clear confirmation
        /*
        if (showClearConfirmationDialog) {
            AlertDialog(
                // ...
            )
        }
        */
    }
}

// ConversationHistoryList remains as modified in Step 1 (no trash icon)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ConversationHistoryList(
    conversations: List<List<Message>>,
    selectedConversationIndex: Int?,
    selectedItemBackgroundColor: Color,
    onConversationClick: (List<Message>, index: Int) -> Unit,
    onDeleteConversation: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues
) {
    if (conversations.isEmpty()) {
        Box(modifier = modifier.padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                "暂无历史记录",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 8.dp // Adjusted for FAB
        )
    ) {
        itemsIndexed(
            items = conversations,
            key = { index, conversation ->
                val firstId = conversation.firstOrNull()?.id
                val lastId = conversation.lastOrNull()?.id
                val size = conversation.size
                if (firstId != null && lastId != null) {
                    "$firstId-$lastId-$size"
                } else if (firstId != null) {
                    "$firstId-$size"
                } else {
                    index.toString()
                }
            }
        ) { index, conversation ->
            val currentConversation = rememberUpdatedState(conversation)
            val currentIndex = rememberUpdatedState(index)

            val isSelected = selectedConversationIndex == currentIndex.value

            val cardBackgroundColor by animateColorAsState(
                targetValue = if (isSelected) selectedItemBackgroundColor else Color.White,
                label = "cardBackgroundColor"
            )

            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = {
                    if (it == SwipeToDismissBoxValue.EndToStart) {
                        onDeleteConversation(currentIndex.value); true
                    } else false
                },
                positionalThreshold = { dist -> dist * 0.40f }
            )
            SwipeToDismissBox(
                state = dismissState,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .animateItemPlacement(),
                enableDismissFromStartToEnd = false,
                enableDismissFromEndToStart = true,
                backgroundContent = {
                    val color by animateColorAsState(
                        targetValue = when (dismissState.targetValue) {
                            SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer.copy(
                                alpha = 0.7f
                            )

                            else -> Color.Transparent
                        }, label = "SwipeBackgroundColor"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(color, RoundedCornerShape(12.dp))
                    ) { /* No icon here */ }
                }
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onConversationClick(
                                currentConversation.value,
                                currentIndex.value
                            )
                        },
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = cardBackgroundColor
                    )
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        val firstUser = conversation.firstOrNull { it.sender == Sender.User }?.text
                        val firstAi =
                            conversation.firstOrNull { it.sender == Sender.AI && it.text.isNotBlank() && it.text != "..." }?.text
                        val previewSource = firstUser ?: firstAi
                        ?: conversation.firstOrNull { it.text.isNotBlank() }?.text ?: "空对话"
                        val preview = previewSource.replace("\n", " ").take(80)
                        Text(
                            text = preview + if (previewSource.length > 80) "..." else "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${conversation.size} 条消息",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}