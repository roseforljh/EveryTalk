package com.example.everytalk.ui.screens.BubbleMain

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.everytalk.StateControler.AppViewModel
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.everytalk.util.MarkdownBlock
import com.example.everytalk.util.CodeHighlighter
import com.example.everytalk.util.LatexToUnicode
import com.example.everytalk.util.parseMarkdownToBlocks


@Composable
internal fun AiMessageContent(
    message: Message,
    appViewModel: AppViewModel,
    fullMessageTextToCopy: String,
    isStreaming: Boolean,
    showLoadingDots: Boolean,
    isListScrolling: Boolean,
    contentColor: Color,
    onUserInteraction: () -> Unit,
    onAiMessageLongPress: (Message) -> Unit,
    modifier: Modifier = Modifier
) {
    Log.d(
        "AiMessageContent_Lifecycle",
        "Composing for MsgID ${message.id.take(4)}. showLoadingDots: $showLoadingDots, message.text blank: ${message.text.isBlank()}, message.text='${
            message.text.take(30).replace("\n", "\\n")
        }'"
    )

    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .pointerInput(fullMessageTextToCopy) {
                detectTapGestures(onLongPress = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onUserInteraction()
                    onAiMessageLongPress(message)
                })
            }
    ) {
        if (showLoadingDots && message.text.isBlank()) {
            Log.d(
                "AiMessageContent_Render",
                "MsgID ${message.id.take(4)}: Showing loading dots (initial stream)."
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 28.dp)
            ) {
                ThreeDotsLoadingAnimation(
                    dotColor = contentColor,
                    modifier = Modifier.offset(y = (-6).dp)
                )
            }
        } else if (message.isError && message.text.isNotBlank()) {
            Log.d(
                "AiMessageContent_Render",
                "MsgID ${message.id.take(4)}: Is an error message."
            )
            Text(
                text = message.text,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        } else if (message.sender == Sender.AI) {
            // Use RichText to render markdown content natively in Compose, both for streaming and final text.
            val blocks = parseMarkdownToBlocks(message.text)
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                blocks.forEach { block ->
                    when (block) {
                        is MarkdownBlock.Header -> {
                            Text(
                                text = block.text,
                                style = TextStyle(
                                    fontSize = when (block.level) {
                                        1 -> 28.sp
                                        2 -> 24.sp
                                        else -> 20.sp
                                    },
                                    fontWeight = FontWeight.Bold
                                ),
                                color = contentColor
                            )
                        }
                        is MarkdownBlock.Text -> {
                            Text(
                                text = block.text,
                                color = contentColor
                            )
                        }
                        is MarkdownBlock.CodeBlock -> {
                            Box(modifier = Modifier.padding(vertical = 8.dp)) {
                                CodeBlock(
                                    rawText = block.rawText,
                                    language = block.language,
                                    contentColor = contentColor
                                )
                            }
                        }
                        is MarkdownBlock.ListItem -> {
                            Row {
                                Text(
                                    text = "• ",
                                    color = contentColor
                                )
                                Text(
                                    text = block.text,
                                    color = contentColor
                                )
                            }
                        }
                        is MarkdownBlock.MathBlock -> {
                            Text(
                                text = LatexToUnicode.convert(block.text),
                                color = contentColor,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        } else if (message.sender != Sender.AI && message.text.isNotBlank()) {
            Log.d(
                "AiMessageContent_Render",
                "MsgID ${message.id.take(4)}: Is a user message."
            )
            Text(
                text = message.text,
                color = contentColor,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        } else {
            Log.d(
                "AiMessageContent_Render",
                "MsgID ${message.id.take(4)}: Fallback to spacer for other cases."
            )
            Spacer(Modifier.height(0.dp))
        }

   }
}

@Composable
internal fun SelectableTextDialog(textToDisplay: String, onDismissRequest: () -> Unit) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 24.dp)
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.75f),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            SelectionContainer(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = textToDisplay,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun CodeBlock(rawText: String, language: String?, contentColor: Color) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val annotatedString = CodeHighlighter.highlightToAnnotatedString(rawText, language)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF3F3F3), RoundedCornerShape(16.dp))
            .padding(top = 12.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)
    ) {
        Text(
            text = annotatedString,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp
            ),
            color = contentColor
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(rawText))
                    Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "复制",
                    modifier = Modifier.size(20.dp),
                    tint = contentColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}


@Composable
fun ThreeDotsLoadingAnimation(
    modifier: Modifier = Modifier,
    dotColor: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        (0..2).forEach { index ->
            val infiniteTransition =
                rememberInfiniteTransition(label = "dot_loading_transition_$index")
            val animatedAlpha by infiniteTransition.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis =
                            1200; 0.3f at 0 with LinearEasing; 1.0f at 200 with LinearEasing
                        0.3f at 400 with LinearEasing; 0.3f at 1200 with LinearEasing
                    },
                    repeatMode = RepeatMode.Restart, initialStartOffset = StartOffset(index * 150)
                ), label = "dot_alpha_$index"
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor.copy(alpha = animatedAlpha), CircleShape)
            )
        }
    }
}