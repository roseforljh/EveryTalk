package com.example.app1.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.example.app1.ui.components.HistoryTopBar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreenContent(
    historicalConversations: List<List<Message>>,
    onNavigateBack: () -> Unit,
    onConversationClick: (List<Message>, Int) -> Unit,
    onDeleteConversation: (Int) -> Unit,
    onNewChatClick: () -> Unit // 新建对话按钮的回调
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            HistoryTopBar(onBackClick = onNavigateBack)
            ConversationHistoryList( // 将列表提取为单独的 Composable
                conversations = historicalConversations,
                onConversationClick = onConversationClick,
                onDeleteConversation = onDeleteConversation,
                contentPadding = PaddingValues(bottom = 80.dp) // 为 FAB 留出空间
            )
        }

        FloatingActionButton(
            onClick = onNewChatClick, // 调用新建对话回调
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
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
}

// 将历史记录列表的 UI 提取出来
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ConversationHistoryList(
    conversations: List<List<Message>>,
    onConversationClick: (List<Message>, index: Int) -> Unit,
    onDeleteConversation: (index: Int) -> Unit,
    contentPadding: PaddingValues // 接收来自外部的 Padding
) {
    if (conversations.isEmpty()) {
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                "暂无历史记录",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 应用传入的 Padding，特别是底部为 FAB 留空
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 8.dp // 加上列表项自身的间距
        )
    ) {
        itemsIndexed(
            items = conversations,
            key = { index, conversation -> conversation.firstOrNull()?.id ?: index }
        ) { index, conversation ->
            val currentItemIndex = rememberUpdatedState(index)
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = {
                    if (it == SwipeToDismissBoxValue.EndToStart) {
                        onDeleteConversation(currentItemIndex.value); true
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
                            SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        }, label = "bgColor"
                    )
                    val iconColor by animateColorAsState(
                        targetValue = when (dismissState.targetValue) {
                            SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.onErrorContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }, label = "iconColor"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(color, RoundedCornerShape(12.dp))
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) { Icon(Icons.Default.Delete, "删除", tint = iconColor) }
                }
            ) { // Foreground Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConversationClick(conversation, currentItemIndex.value) },
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
            } // End SwipeToDismissBox
        } // End itemsIndexed
    } // End LazyColumn
}