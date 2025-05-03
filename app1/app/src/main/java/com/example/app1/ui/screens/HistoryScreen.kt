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
import androidx.compose.material.icons.filled.* // 导入所有 filled 图标
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.example.app1.ui.components.HistoryTopBar // 确认 HistoryTopBar 导入正确

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen( // 重命名，去掉 Content 后缀
    historicalConversations: List<List<Message>>,
    onNavigateBack: () -> Unit,
    onConversationClick: (Int) -> Unit, // 修改为传递索引
    onDeleteConversation: (Int) -> Unit,
    onNewChatClick: () -> Unit,
    modifier: Modifier = Modifier // 添加 modifier 参数
) {
    Scaffold( // HistoryScreen 使用自己的 Scaffold
        topBar = {
            // 使用你原来的 HistoryTopBar，假设它只需要 onBackClick
            HistoryTopBar(onBackClick = onNavigateBack)
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewChatClick,
                modifier = Modifier.padding(16.dp), // FAB 的标准 Padding
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
    ) { paddingValues -> // Scaffold 提供的内边距
        // 将 ConversationHistoryList 直接放在这里
        ConversationHistoryList(
            conversations = historicalConversations,
            onConversationClick = { _, index -> onConversationClick(index) }, // 调整 lambda 以匹配
            onDeleteConversation = onDeleteConversation,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues), // 应用 Scaffold 的 Padding
            contentPadding = PaddingValues(bottom = 80.dp) // 为 FAB 留出空间 (Scaffold 会处理一些，这里额外加)
        )
    }
}

// 将历史记录列表的 UI 提取出来 (保持不变，但添加 modifier)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ConversationHistoryList(
    conversations: List<List<Message>>,
    onConversationClick: (List<Message>, index: Int) -> Unit, // 保持原始签名以便复用
    onDeleteConversation: (index: Int) -> Unit,
    modifier: Modifier = Modifier, // 添加 modifier 参数
    contentPadding: PaddingValues // 接收来自外部的 Padding
) {
    if (conversations.isEmpty()) {
        Box(
            modifier = modifier.padding(16.dp), // 应用 modifier 和 padding
            contentAlignment = Alignment.Center
        ) {
            Text(
                "暂无历史记录",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier, // 应用 modifier
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
            val currentConversation = rememberUpdatedState(conversation) // 记住当前 conversation
            val currentIndex = rememberUpdatedState(index) // 记住当前 index
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = {
                    if (it == SwipeToDismissBoxValue.EndToStart) {
                        onDeleteConversation(currentIndex.value); true
                    } else false
                },
                positionalThreshold = { dist -> dist * 0.40f } // 调整滑动阈值
            )
            SwipeToDismissBox(
                state = dismissState,
                modifier = Modifier
                    .padding(vertical = 4.dp),
                enableDismissFromStartToEnd = false,
                enableDismissFromEndToStart = true,
                backgroundContent = {
                    // --- (滑动背景保持不变) ---
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
            ) { // --- (前景卡片保持不变) ---
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConversationClick(currentConversation.value, currentIndex.value) }, // 使用记住的状态
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