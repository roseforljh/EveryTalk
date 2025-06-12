package com.example.everytalk.ui.screens.MainScreen.chat

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.request.crossfade
import com.example.everytalk.StateControler.AppViewModel
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.ui.screens.BubbleMain.Main.AttachmentsContent
import com.example.everytalk.ui.screens.BubbleMain.Main.UserOrErrorMessageContent
import com.example.everytalk.util.CodeHighlighter
import com.example.everytalk.util.MarkdownBlock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun ChatMessagesList(
    chatItems: List<ChatListItem>,
    viewModel: AppViewModel,
    listState: LazyListState,
    nestedScrollConnection: NestedScrollConnection,
    bubbleMaxWidth: Dp,
    onResetInactivityTimer: () -> Unit,
    onShowAiMessageOptions: (Message) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
            .padding(horizontal = 8.dp),
        state = listState,
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
    ) {
        items(
            items = chatItems,
            key = { item -> item.key },
            contentType = { item -> item::class.java.simpleName }
        ) { item ->
            when (item) {
                is ChatListItem.UserMessage -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.End
                    ) {
                        if (!item.message.attachments.isNullOrEmpty()) {
                            AttachmentsContent(
                                attachments = item.message.attachments!!,
                                onAttachmentClick = { /* Handle attachment click */ }
                            )
                        }
                        if (item.message.text.isNotBlank()) {
                            UserOrErrorMessageContent(
                                message = item.message,
                                displayedText = item.message.text,
                                showLoadingDots = false,
                                bubbleColor = Color(0xFFF3F3F3),
                                contentColor = Color.Black,
                                isError = false,
                                maxWidth = bubbleMaxWidth * 0.85f,
                                onUserInteraction = onResetInactivityTimer,
                                onEditRequest = { viewModel.requestEditMessage(it) },
                                onRegenerateRequest = { viewModel.regenerateAiResponse(it) }
                            )
                        }
                    }
                }
                is ChatListItem.AiMessageReasoning -> {
                    val isApiCalling by viewModel.isApiCalling.collectAsState()
                    val reasoningCompleteMap = viewModel.reasoningCompleteMap
                    val isReasoningStreaming = isApiCalling && item.message.reasoning != null && !(reasoningCompleteMap[item.message.id] ?: false)

                    ReasoningToggleAndContent(
                        modifier = Modifier.fillMaxWidth(),
                        currentMessageId = item.message.id,
                        displayedReasoningText = item.message.reasoning ?: "",
                        isReasoningStreaming = isReasoningStreaming,
                        reasoningTextColor = Color(0xFF444444),
                        reasoningToggleDotColor = Color.Black
                    )
                }
                is ChatListItem.AiMessageBlock -> {
                    AiMessageBlockItem(
                        item = item,
                        viewModel = viewModel,
                        maxWidth = bubbleMaxWidth,
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onResetInactivityTimer()
                            viewModel.getMessageById(item.messageId)?.let {
                                onShowAiMessageOptions(it)
                            }
                        }
                    )
                }
                is ChatListItem.AiMessageFooter -> {
                    AiMessageFooterItem(
                        message = item.message,
                        viewModel = viewModel,
                        onUserInteraction = onResetInactivityTimer
                    )
                }
                is ChatListItem.ErrorMessage -> {
                    UserOrErrorMessageContent(
                        message = item.message,
                        displayedText = item.message.text,
                        showLoadingDots = false,
                        bubbleColor = Color.White,
                        contentColor = Color.Red,
                        isError = true,
                        maxWidth = bubbleMaxWidth,
                        onUserInteraction = onResetInactivityTimer,
                        onEditRequest = { viewModel.requestEditMessage(it) },
                        onRegenerateRequest = { viewModel.regenerateAiResponse(it) }
                    )
                }
                is ChatListItem.LoadingIndicator -> {
                    Row(
                        modifier = Modifier
                            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Text(
                            "正在连接API",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Black
                        )
                        Spacer(Modifier.width(4.dp))
                        ThreeDotsWaveAnimation(dotSize = 3.dp, spacing = 2.dp)
                    }
                }
            }
        }
        item(key = "chat_screen_footer_spacer_in_list") {
            Spacer(modifier = Modifier.height(1.dp))
        }
    }
}

@Composable
private fun AiMessageBlockItem(
    item: ChatListItem.AiMessageBlock,
    viewModel: AppViewModel,
    maxWidth: Dp,
    onLongPress: () -> Unit
) {
    val shape = RoundedCornerShape(
        topStart = if (item.isFirstBlock && !item.hasReasoning) 18.dp else 8.dp,
        topEnd = 18.dp,
        bottomStart = if (item.isLastBlock) 18.dp else 8.dp,
        bottomEnd = if (item.isLastBlock) 18.dp else 8.dp
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .pointerInput(Unit) {
                     detectTapGestures(onLongPress = { onLongPress() })
                },
            shape = shape,
            color = Color.White,
            contentColor = Color.Black,
            shadowElevation = 0.dp
        ) {
            RenderMarkdownBlock(
                block = item.block,
                viewModel = viewModel,
                contentColor = Color.Black
            )
        }
    }
}

@Composable
private fun AiMessageFooterItem(
    message: Message,
    viewModel: AppViewModel,
    onUserInteraction: () -> Unit
) {
    if (!message.webSearchResults.isNullOrEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            TextButton(
                onClick = {
                    onUserInteraction()
                    viewModel.showSourcesDialog(message.webSearchResults!!)
                },
            ) {
                Text("查看参考来源 (${message.webSearchResults?.size ?: 0})")
            }
        }
    }
}


@Composable
private fun RenderMarkdownBlock(
    block: MarkdownBlock,
    viewModel: AppViewModel,
    contentColor: Color
) {
    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        when (block) {
            is MarkdownBlock.Header -> Text(text = block.text, style = MaterialTheme.typography.headlineSmall.copy(fontSize = when (block.level) {
                1 -> 28.sp
                2 -> 24.sp
                else -> 20.sp
            }, fontWeight = FontWeight.Bold, color = contentColor))
            is MarkdownBlock.Text -> Text(text = block.text, style = MaterialTheme.typography.bodyLarge.copy(color = contentColor))
            is MarkdownBlock.CodeBlock -> CodeBlock(rawText = block.rawText, language = block.language, contentColor = contentColor)
            is MarkdownBlock.ListItem -> Row {
                Text("• ", color = contentColor)
                Text(text = block.text, style = MaterialTheme.typography.bodyLarge.copy(color = contentColor))
            }
            is MarkdownBlock.MathBlock -> Text(text = "KaTeX unsupported in this view: ${block.text}", style = MaterialTheme.typography.bodyMedium.copy(color = contentColor.copy(alpha = 0.7f)))
            is MarkdownBlock.Image -> coil3.compose.AsyncImage(model = coil3.request.ImageRequest.Builder(LocalContext.current).data(block.url).crossfade(true).build(), contentDescription = block.altText, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)))
            is MarkdownBlock.Table -> MarkdownTable(header = block.header, rows = block.rows, contentColor = contentColor)
        }
    }
}

@Composable
private fun CodeBlock(rawText: String, language: String?, contentColor: Color) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val annotatedString = CodeHighlighter.highlightToAnnotatedString(rawText, language)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF3F3F3), RoundedCornerShape(16.dp))
            .padding(top = 12.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)
    ) {
        Text(text = annotatedString, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp), color = contentColor)
        Box(modifier = Modifier.fillMaxWidth()) {
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(rawText))
                    Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "复制", modifier = Modifier.size(20.dp), tint = contentColor.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun MarkdownTable(header: List<AnnotatedString>, rows: List<List<AnnotatedString>>, contentColor: Color) {
    val columnCount = header.size
    if (columnCount == 0) return

    Column(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
    ) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))) {
            header.forEach { text ->
                TableCell(text = text, isHeader = true, weight = 1f / columnCount, contentColor = contentColor)
            }
        }
        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                for(i in 0 until columnCount) {
                    val text = row.getOrNull(i) ?: AnnotatedString("")
                    TableCell(text = text, isHeader = false, weight = 1f / columnCount, contentColor = contentColor)
                }
            }
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
        }
    }
}

@Composable
private fun RowScope.TableCell(text: AnnotatedString, isHeader: Boolean, weight: Float, contentColor: Color) {
    Text(text = text, modifier = Modifier.weight(weight).padding(12.dp), fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal, color = contentColor)
}

@Composable
private fun ThreeDotsWaveAnimation(modifier: Modifier = Modifier, dotColor: Color = Color.Black, dotSize: Dp = 6.dp, spacing: Dp = 4.dp) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave_transition")
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        (0..2).forEach { index ->
            val yOffset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 800
                        0f at 0
                        -dotSize.value at 200
                        0f at 400
                    },
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(index * 100)
                ), label = "dot_wave_$index"
            )
            Box(modifier = Modifier.size(dotSize).offset(y = yOffset.dp).background(color = dotColor, shape = CircleShape))
        }
    }
}

@Composable
private fun ReasoningToggleAndContent(
    modifier: Modifier = Modifier,
    currentMessageId: String,
    displayedReasoningText: String,
    isReasoningStreaming: Boolean,
    reasoningTextColor: Color,
    reasoningToggleDotColor: Color
) {
    var showDialog by remember(currentMessageId) { mutableStateOf(false) }

    val dotSize by animateDpAsState(
        targetValue = if (showDialog) 6.dp else 8.dp,
        animationSpec = tween(durationMillis = 200),
        label = "dotSizeAnimation"
    )

    if (isReasoningStreaming) {
        val boxBackgroundColor = Color.White.copy(alpha = 0.95f)
        val scrimColor = boxBackgroundColor
        val scrimHeight = 28.dp
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = boxBackgroundColor,
            shadowElevation = 2.dp,
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 6.dp, top = 4.dp)
                .heightIn(min = 50.dp, max = 180.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val scrollState = rememberScrollState()
                LaunchedEffect(Unit) {
                    try {
                        while (isActive) {
                            delay(1000L)
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                    } finally {
                        withContext(NonCancellable) {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 12.dp, vertical = scrimHeight)
                ) {
                    Text(
                        text = displayedReasoningText.ifBlank { " " },
                        color = reasoningTextColor,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(scrimHeight)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(scrimColor, Color.Transparent)
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(scrimHeight)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, scrimColor)
                            )
                        )
                )
            }
        }
    } else {
        Column(modifier = modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp)) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable { showDialog = true },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(dotSize)
                        .background(Color.Black, CircleShape)
                )
            }
        }
    }

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.7f),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Thinking Process",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                            .align(Alignment.CenterHorizontally)
                    )
                    HorizontalDivider(thickness = 1.dp, color = Color.Gray.copy(alpha = 0.2f))
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = displayedReasoningText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = reasoningTextColor
                        )
                    }
                }
            }
        }
    }
}