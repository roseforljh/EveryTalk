package com.example.everytalk.ui.screens.BubbleMain

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.everytalk.ui.components.PooledKatexWebView
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.StateControler.AppViewModel
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.ui.screens.BubbleMain.Main.toHexCss
import com.example.everytalk.util.convertMarkdownToHtml
import com.example.everytalk.util.convertMarkdownToPlainText
import com.example.everytalk.util.generateKatexBaseHtmlTemplateString
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter


private const val CONTEXT_MENU_ANIMATION_DURATION_MS = 150
private val CONTEXT_MENU_CORNER_RADIUS = 16.dp
private val CONTEXT_MENU_ITEM_ICON_SIZE = 20.dp
private val CONTEXT_MENU_FINE_TUNE_OFFSET_X = (-120).dp
private val CONTEXT_MENU_FINE_TUNE_OFFSET_Y = (-8).dp
private val CONTEXT_MENU_FIXED_WIDTH = 160.dp
private const val TYPEWRITER_DELAY_MS = 10L

@Composable
internal fun AnimatedDropdownMenuItem(
    visibleState: MutableTransitionState<Boolean>, delay: Int = 0,
    text: @Composable () -> Unit, onClick: () -> Unit, leadingIcon: @Composable (() -> Unit)? = null
) {
    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(
            animationSpec = tween(
                CONTEXT_MENU_ANIMATION_DURATION_MS,
                delayMillis = delay,
                easing = LinearOutSlowInEasing
            )
        ) +
                scaleIn(
                    animationSpec = tween(
                        CONTEXT_MENU_ANIMATION_DURATION_MS,
                        delayMillis = delay,
                        easing = LinearOutSlowInEasing
                    ), transformOrigin = TransformOrigin(0f, 0f)
                ),
        exit = fadeOut(
            animationSpec = tween(
                CONTEXT_MENU_ANIMATION_DURATION_MS,
                easing = FastOutLinearInEasing
            )
        ) +
                scaleOut(
                    animationSpec = tween(
                        CONTEXT_MENU_ANIMATION_DURATION_MS,
                        easing = FastOutLinearInEasing
                    ), transformOrigin = TransformOrigin(0f, 0f)
                )
    ) {
        DropdownMenuItem(
            text = text, onClick = onClick, leadingIcon = leadingIcon,
            colors = MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.onSurface,
                leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@Composable
fun rememberKatexBaseHtmlTemplate(
    backgroundColor: String, textColor: String, errorColor: String, throwOnError: Boolean
): String {
    return remember(backgroundColor, textColor, errorColor, throwOnError) {
        generateKatexBaseHtmlTemplateString(backgroundColor, textColor, errorColor, throwOnError)
    }
}

@Composable
internal fun AiMessageContent(
    message: Message,
    appViewModel: AppViewModel,
    fullMessageTextToCopy: String,
    showLoadingDots: Boolean,
    contentColor: Color,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Log.d(
        "AiMessageContent_Lifecycle",
        "Composing for MsgID ${message.id.take(4)}. showLoadingDots: $showLoadingDots, message.text blank: ${message.text.isBlank()}, message.text='${
            message.text.take(30).replace("\n", "\\n")
        }'"
    )

    val clipboardManager = LocalClipboardManager.current
    val density = LocalDensity.current

    var isAiContextMenuVisible by remember(fullMessageTextToCopy) { mutableStateOf(false) }
    var pressOffset by remember(fullMessageTextToCopy) { mutableStateOf(Offset.Zero) }
    var showSelectableTextDialog by remember(fullMessageTextToCopy) { mutableStateOf(false) }

    var displayedTextForTypewriter by remember(message.id) { mutableStateOf("") }
    var targetPlainTextMessage by remember(message.id) { mutableStateOf("") }

    val webViewContentId = remember(message.id) { "${message.id}_ai_content_wv_final" }

    LaunchedEffect(message.id, appViewModel.markdownChunkToAppendFlow) {
        var accumulatedPlainText = ""
        Log.d(
            "AiMessageContent_Stream",
            "MsgID ${message.id.take(4)}: Subscribing to markdown chunks."
        )
        appViewModel.markdownChunkToAppendFlow
            .filter { (msgId, _) -> msgId == message.id }
            .collect { (msgIdFromFlow, markdownChunkPair) ->
                val triggerKey = markdownChunkPair.first
                val markdownChunk = markdownChunkPair.second
                Log.d(
                    "AiMessageContent_Stream",
                    "MsgID ${message.id.take(4)} (Flow MsgID ${msgIdFromFlow.take(4)}): Received mdChunk (key ${
                        triggerKey.take(4)
                    }, len ${markdownChunk.length}): \"${
                        markdownChunk.take(50).replace("\n", "\\n")
                    }\""
                )
                if (markdownChunk.isNotBlank()) {
                    val plainTextChunk = convertMarkdownToPlainText(markdownChunk)
                    Log.d(
                        "AiMessageContent_Stream",
                        "MsgID ${message.id.take(4)}: Converted to plainTextChunk (len ${plainTextChunk.length}): \"${
                            plainTextChunk.take(50).replace("\n", "\\n")
                        }\""
                    )
                    if (plainTextChunk.isNotBlank()) {
                        accumulatedPlainText += plainTextChunk
                        targetPlainTextMessage = accumulatedPlainText
                        Log.d(
                            "AiMessageContent_Stream",
                            "MsgID ${message.id.take(4)}: Updated targetPlainTextMessage (len ${targetPlainTextMessage.length}): \"${
                                targetPlainTextMessage.take(50).replace("\n", "\\n")
                            }\""
                        )
                    } else {
                        Log.d(
                            "AiMessageContent_Stream",
                            "MsgID ${message.id.take(4)}: plainTextChunk was blank after conversion."
                        )
                    }
                } else {
                    Log.d(
                        "AiMessageContent_Stream",
                        "MsgID ${message.id.take(4)}: Received blank mdChunk."
                    )
                }
            }
        Log.d(
            "AiMessageContent_Stream",
            "MsgID ${message.id.take(4)}: Finished collecting markdown chunks (flow might have completed or coroutine cancelled)."
        )
    }

    LaunchedEffect(targetPlainTextMessage) {
        Log.d(
            "AiMessageContent_Typewriter",
            "Effect run for MsgID ${message.id.take(4)}. showLoadingDots: $showLoadingDots. Target='${
                targetPlainTextMessage.take(50).replace("\n", "\\n")
            }'. CurrentDisplay='${displayedTextForTypewriter.take(50).replace("\n", "\\n")}'"
        )
        if (showLoadingDots) {
            if (displayedTextForTypewriter.length < targetPlainTextMessage.length) {
                val newChars = targetPlainTextMessage.substring(displayedTextForTypewriter.length)
                Log.d(
                    "AiMessageContent_Typewriter",
                    "MsgID ${message.id.take(4)}: New chars to type: '${
                        newChars.take(50).replace("\n", "\\n")
                    }'"
                )
                newChars.forEach { char ->
                    displayedTextForTypewriter += char
                    delay(TYPEWRITER_DELAY_MS)
                }
                Log.d(
                    "AiMessageContent_Typewriter",
                    "MsgID ${message.id.take(4)}: Finished typing loop. Displayed: '${
                        displayedTextForTypewriter.take(50).replace("\n", "\\n")
                    }'"
                )
            } else if (displayedTextForTypewriter.length > targetPlainTextMessage.length) {
                Log.d(
                    "AiMessageContent_Typewriter",
                    "MsgID ${message.id.take(4)}: Target is shorter. Resetting displayed text to target."
                )
                displayedTextForTypewriter = targetPlainTextMessage
            } else {
                Log.d(
                    "AiMessageContent_Typewriter",
                    "MsgID ${message.id.take(4)}: No new chars to type or target is not shorter."
                )
            }
        } else {
            Log.d(
                "AiMessageContent_Typewriter",
                "MsgID ${message.id.take(4)}: Not in showLoadingDots mode. CurrentDisplay='${
                    displayedTextForTypewriter.take(50).replace("\n", "\\n")
                }'. message.text blank: ${message.text.isBlank()}"
            )
        }
    }


    val finalHtmlInput = remember(
        message.text,
        message.htmlContent,
        message.sender,
        message.isError,
        showLoadingDots
    ) {
        if (message.sender == Sender.AI && !message.isError && !showLoadingDots) {
            if (!message.htmlContent.isNullOrBlank()) {
                Log.d(
                    "AiMessageContent_FinalHTML",
                    "Using pre-rendered HTML for MsgID ${message.id.take(4)}"
                )
                message.htmlContent!!
            } else if (message.text.isNotBlank()) {
                Log.d(
                    "AiMessageContent_FinalHTML",
                    "MsgID ${message.id.take(4)}: Generating finalHtmlInput from message.text (len ${message.text.length})"
                )
                convertMarkdownToHtml(message.text.trim())
            } else {
                Log.d(
                    "AiMessageContent_FinalHTML",
                    "MsgID ${message.id.take(4)}: Final HTML is empty because message.text is blank."
                )
                ""
            }
        } else {
            Log.d(
                "AiMessageContent_FinalHTML",
                "MsgID ${message.id.take(4)}: Not generating final HTML. sender=${message.sender}, isError=${message.isError}, showLoadingDots=$showLoadingDots"
            )
            ""
        }
    }

    Column(
        modifier = modifier.pointerInput(fullMessageTextToCopy) {
            detectTapGestures(onLongPress = { offsetValue ->
                onUserInteraction(); pressOffset = offsetValue; isAiContextMenuVisible = true
            })
        }
    ) {
        Log.d(
            "AiMessageContent_Render",
            "Rendering Column for MsgID ${message.id.take(4)}. showLoadingDots: $showLoadingDots. displayedText='${
                displayedTextForTypewriter.take(30).replace("\n", "\\n")
            }', targetText='${
                targetPlainTextMessage.take(30).replace("\n", "\\n")
            }', finalHtmlInput blank: ${finalHtmlInput.isBlank()}"
        )

        if (showLoadingDots) {
            if (displayedTextForTypewriter.isBlank() && targetPlainTextMessage.isBlank()) {
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
            } else {
                Log.d(
                    "AiMessageContent_Render",
                    "MsgID ${message.id.take(4)}: Showing typewriter text (len ${displayedTextForTypewriter.length}): '${
                        displayedTextForTypewriter.take(50).replace("\n", "\\n")
                    }'"
                )
                SelectionContainer(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = displayedTextForTypewriter.trimStart().ifEmpty { " " },
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .defaultMinSize(minHeight = 20.dp)
                    )
                }
            }
        } else {
            if (message.sender == Sender.AI && !message.isError) {
                if (finalHtmlInput.isNotBlank()) {
                    Log.d(
                        "AiMessageContent_Render",
                        "MsgID ${message.id.take(4)}: Rendering PooledKatexWebView with final HTML (len ${finalHtmlInput.length})."
                    )
                    val textColorHex = remember(contentColor) { contentColor.toHexCss() }
                    val baseHtmlTemplate = rememberKatexBaseHtmlTemplate(
                        backgroundColor = "transparent",
                        textColor = textColorHex,
                        errorColor = "#CD5C5C",
                        throwOnError = false
                    )
                    PooledKatexWebView(
                        appViewModel = appViewModel,
                        contentId = webViewContentId,
                        initialLatexInput = finalHtmlInput,
                        htmlChunkToAppend = null,
                        htmlTemplate = baseHtmlTemplate,
                        modifier = Modifier.heightIn(min = 1.dp)
                    )
                } else if (message.text.isBlank()) {
                    Log.d(
                        "AiMessageContent_Render",
                        "MsgID ${message.id.take(4)}: AI finished, message is blank. Showing spacer."
                    )
                    Spacer(Modifier.height(1.dp))
                } else {
                    Log.d(
                        "AiMessageContent_Render",
                        "MsgID ${message.id.take(4)}: AI finished, finalHtmlInput is blank but message.text is not. Fallback to plain text."
                    )
                    Text(
                        text = convertMarkdownToPlainText(message.text.trimStart()),
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            } else {
                Log.d(
                    "AiMessageContent_Render",
                    "MsgID ${message.id.take(4)}: Not AI or isError. sender=${message.sender}, isError=${message.isError}"
                )
                if (message.isError && message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                } else if (message.sender != Sender.AI && message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        color = contentColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                } else {
                    Spacer(Modifier.height(1.dp))
                }
            }
        }

        if (isAiContextMenuVisible) {
            val localContextForToast = LocalContext.current
            val aiMenuVisibility = remember { MutableTransitionState(false) }.apply {
                targetState = isAiContextMenuVisible
            }
            val dropdownMenuOffsetX =
                with(density) { pressOffset.x.toDp() } + CONTEXT_MENU_FINE_TUNE_OFFSET_X
            val dropdownMenuOffsetY =
                with(density) { pressOffset.y.toDp() } + CONTEXT_MENU_FINE_TUNE_OFFSET_Y
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(
                    x = with(density) { dropdownMenuOffsetX.roundToPx() },
                    y = with(density) { dropdownMenuOffsetY.roundToPx() }),
                onDismissRequest = { isAiContextMenuVisible = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    clippingEnabled = false
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    modifier = Modifier
                        .width(CONTEXT_MENU_FIXED_WIDTH)
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS)
                        )
                        .padding(1.dp)
                ) {
                    Column {
                        AnimatedDropdownMenuItem(
                            visibleState = aiMenuVisibility,
                            delay = 0,
                            text = { Text("复制") },
                            onClick = {
                                clipboardManager.setText(AnnotatedString(fullMessageTextToCopy))
                                Toast.makeText(
                                    localContextForToast,
                                    "AI回复已复制",
                                    Toast.LENGTH_SHORT
                                ).show()
                                isAiContextMenuVisible = false
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    "复制AI回复",
                                    Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
                                )
                            })
                        AnimatedDropdownMenuItem(
                            visibleState = aiMenuVisibility,
                            delay = 30,
                            text = { Text("选择文本") },
                            onClick = {
                                showSelectableTextDialog = true; isAiContextMenuVisible = false
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.SelectAll,
                                    "选择文本",
                                    Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
                                )
                            })
                    }
                }
            }
        }

        if (showSelectableTextDialog) {
            SelectableTextDialog(
                textToDisplay = fullMessageTextToCopy,
                onDismissRequest = { showSelectableTextDialog = false })
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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