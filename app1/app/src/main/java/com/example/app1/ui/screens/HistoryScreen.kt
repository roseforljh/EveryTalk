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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showClearConfirmationDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            HistoryTopBar(onBackClick = onNavigateBack)
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewChatClick,
                modifier = Modifier.padding(16.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新建对话",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            ConversationHistoryList(
                conversations = historicalConversations,
                onConversationClick = { _, index -> onConversationClick(index) },
                onDeleteConversation = onDeleteConversation,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            )

            // --- 纯白大圆角背景的清空按钮 ---
            if (historicalConversations.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 25.dp)
                        .navigationBarsPadding()
                        .background(
                            color = MaterialTheme.colorScheme.background.copy(alpha = 1f), // 不透明白色
                            shape = RoundedCornerShape(28.dp)
                        )
                ) {
                    TextButton(
                        onClick = { showClearConfirmationDialog = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("清空记录")
                    }
                }
            }
            // --- 结束新增按钮 ---
        }

        if (showClearConfirmationDialog) {
            AlertDialog(
                onDismissRequest = { showClearConfirmationDialog = false },
                title = { Text("确认操作") },
                text = { Text("确定要清空所有聊天历史记录吗？此操作无法撤销。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onClearAll()
                            showClearConfirmationDialog = false
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("确认清空")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showClearConfirmationDialog = false }
                    ) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ConversationHistoryList(
    conversations: List<List<Message>>,
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
            bottom = contentPadding.calculateBottomPadding() + 8.dp
        )
    ) {
        itemsIndexed(
            items = conversations,
            key = { index, conversation ->
                val firstId = conversation.firstOrNull()?.id;
                val lastId = conversation.lastOrNull()?.id;
                val size = conversation.size
                if (firstId != null && lastId != null) {
                    "$firstId-$lastId-$size"
                } else if (firstId != null) {
                    "$firstId-$size"
                } else {
                    index
                }
            }
        ) { index, conversation ->
            val currentConversation = rememberUpdatedState(conversation)
            val currentIndex = rememberUpdatedState(index)
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
                modifier = Modifier.padding(vertical = 4.dp),
                enableDismissFromStartToEnd = false,
                enableDismissFromEndToStart = true,
                backgroundContent = {
                    val color by animateColorAsState(
                        targetValue = when (dismissState.targetValue) {
                            SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer; else -> MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = 0.5f
                            )
                        }, label = "SwipeBackgroundColor"
                    )
                    val iconColor by animateColorAsState(
                        targetValue = when (dismissState.targetValue) {
                            SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.onErrorContainer; else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }, label = "SwipeIconColor"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(color, RoundedCornerShape(12.dp))
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) { Icon(Icons.Default.Delete, "删除", tint = iconColor) }
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
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                            fontWeight = FontWeight.Medium
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
