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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.ClickableText
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
import com.example.everytalk.ui.screens.BubbleMain.Main.ReasoningToggleAndContent
import com.example.everytalk.ui.screens.BubbleMain.Main.UserOrErrorMessageContent
import com.example.everytalk.util.CodeHighlighter
import com.example.everytalk.util.MarkdownBlock
import com.example.everytalk.util.parseInlineMarkdownToAnnotatedString
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
                                onAttachmentClick = { /* Handle attachment click */ },
                                maxWidth = bubbleMaxWidth * 0.85f
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
                    val isReasoningComplete = reasoningCompleteMap[item.message.id] ?: false

                    ReasoningToggleAndContent(
                        modifier = Modifier.fillMaxWidth(),
                        currentMessageId = item.message.id,
                        displayedReasoningText = item.message.reasoning ?: "",
                        isReasoningStreaming = isReasoningStreaming,
                        isReasoningComplete = isReasoningComplete,
                        messageIsError = item.message.isError,
                        mainContentHasStarted = item.message.contentStarted,
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
                            val message = viewModel.getMessageById(item.messageId)
                            android.util.Log.d("ChatMessagesList", "Long press on messageId: ${item.messageId}, message found: ${message != null}")
                            message?.let {
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
                            "正在连接大模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Black
                        )
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = Color.Black,
                            strokeWidth = 1.5.dp
                        )
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
                .widthIn(max = maxWidth),
            shape = shape,
            color = Color.White,
            contentColor = Color.Black,
            shadowElevation = 0.dp
        ) {
            RenderMarkdownBlock(
                block = item.block,
                viewModel = viewModel,
                contentColor = Color.Black,
                onLongPress = onLongPress
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
    contentColor: Color,
    onLongPress: () -> Unit
) {
    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        when (block) {
            is MarkdownBlock.Header -> MarkdownText(
                text = block.text.toString(),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = when (block.level) {
                        1 -> 28.sp
                        2 -> 24.sp
                        else -> 20.sp
                    },
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                ),
                onLongPress = onLongPress
            )
            is MarkdownBlock.Text -> MarkdownText(
                text = block.text.toString(),
                style = MaterialTheme.typography.bodyLarge.copy(color = contentColor),
                onLongPress = onLongPress
            )
            is MarkdownBlock.CodeBlock -> CodeBlock(
                rawText = block.rawText,
                language = block.language,
                contentColor = contentColor,
                onLongPress = onLongPress
            )
            is MarkdownBlock.ListItem -> Row {
                Text("• ", color = contentColor)
                MarkdownText(
                    text = block.text.toString(),
                    style = MaterialTheme.typography.bodyLarge.copy(color = contentColor),
                    modifier = Modifier.weight(1f),
                    onLongPress = onLongPress
                )
            }
            is MarkdownBlock.Image -> Box(
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(onLongPress = { onLongPress() })
                }
            ) {
                coil3.compose.AsyncImage(
                    model = coil3.request.ImageRequest.Builder(LocalContext.current).data(block.url).crossfade(true).build(),
                    contentDescription = block.altText,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                )
            }
            is MarkdownBlock.Table -> MarkdownTable(
                header = block.header,
                rows = block.rows,
                contentColor = contentColor,
                onLongPress = onLongPress
            )
        }
    }
}

@Composable
private fun CodeBlock(rawText: String, language: String?, contentColor: Color, onLongPress: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val annotatedString by produceState(initialValue = AnnotatedString(rawText), key1 = rawText, key2 = language) {
        value = withContext(NonCancellable) {
            CodeHighlighter.highlightToAnnotatedString(rawText, language)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF3F3F3), RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onLongPress() })
            }
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
private fun MarkdownTable(header: List<String>, rows: List<List<String>>, contentColor: Color, onLongPress: () -> Unit) {
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
                TableCell(text = text, isHeader = true, weight = 1f / columnCount, contentColor = contentColor, onLongPress = onLongPress)
            }
        }
        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                for(i in 0 until columnCount) {
                    val text = row.getOrNull(i) ?: ""
                    TableCell(text = text, isHeader = false, weight = 1f / columnCount, contentColor = contentColor, onLongPress = onLongPress)
                }
            }
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
        }
    }
}

@Composable
private fun RowScope.TableCell(text: String, isHeader: Boolean, weight: Float, contentColor: Color, onLongPress: () -> Unit) {
    MarkdownText(
        text = text,
        modifier = Modifier
            .weight(weight)
            .padding(12.dp),
        style = LocalTextStyle.current.copy(
            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
            color = contentColor
        ),
        onLongPress = onLongPress
    )
}

@Composable
private fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onLongPress: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var textLayoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }

    val annotatedString by produceState(initialValue = AnnotatedString(text), key1 = text) {
        value = withContext(NonCancellable) {
            parseInlineMarkdownToAnnotatedString(text)
        }
    }

    Text(
        text = annotatedString,
        modifier = modifier
            .graphicsLayer()
            .pointerInput(onLongPress) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onTap = { offset ->
                        textLayoutResult?.let { layoutResult ->
                            val characterIndex = layoutResult.getOffsetForPosition(offset)
                            annotatedString.getStringAnnotations(tag = "URL", start = characterIndex, end = characterIndex)
                                .firstOrNull()?.let { annotation ->
                                    try {
                                        uriHandler.openUri(annotation.item)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "无法打开链接: ${annotation.item}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                        }
                    }
                )
            },
        style = style,
        onTextLayout = { layoutResult ->
            textLayoutResult = layoutResult
        }
    )
}

