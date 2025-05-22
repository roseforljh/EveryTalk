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
import com.example.everytalk.ui.components.PooledKatexWebView // Using the modified PooledKatexWebView
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.StateControler.AppViewModel
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.ui.screens.BubbleMain.Main.toHexCss
import com.example.everytalk.util.convertMarkdownToHtml

import com.example.everytalk.util.generateKatexBaseHtmlTemplateString // Assuming path is correct
import kotlinx.coroutines.flow.filter


// Attribute provider to add "language-xxx" class to <code> tags within <pre> for Prism.js
class FencedCodeBlockAttributeProvider : org.commonmark.renderer.html.AttributeProvider {
    override fun setAttributes(
        node: org.commonmark.node.Node,
        tagName: String,
        attributes: MutableMap<String, String>
    ) {
        if (node is org.commonmark.node.FencedCodeBlock && tagName == "code") {
            val language = node.info // This is the string after ``` (e.g., "java")
            if (language != null && language.isNotBlank()) {
                attributes["class"] = "language-$language"
            }
        }
    }
}


private const val CONTEXT_MENU_ANIMATION_DURATION_MS = 150
private val CONTEXT_MENU_CORNER_RADIUS = 16.dp
private val CONTEXT_MENU_ITEM_ICON_SIZE = 20.dp
private val CONTEXT_MENU_FINE_TUNE_OFFSET_X = (-120).dp
private val CONTEXT_MENU_FINE_TUNE_OFFSET_Y = (-8).dp
private val CONTEXT_MENU_FIXED_WIDTH = 160.dp

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
    fullMessageTextToCopy: String, // This should be message.text
    showLoadingDots: Boolean,      // True if message.text is blank but AI is active
    contentColor: Color,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val density = LocalDensity.current

    var isAiContextMenuVisible by remember(fullMessageTextToCopy) { mutableStateOf(false) }
    var pressOffset by remember(fullMessageTextToCopy) { mutableStateOf(Offset.Zero) }
    var showSelectableTextDialog by remember(fullMessageTextToCopy) { mutableStateOf(false) }

    val webViewContentId = remember(message.id) { "${message.id}_ai_content_stream_wv" }

    // State for the HTML chunk to append, with a UUID as trigger key
    var htmlChunkToAppendState by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Subscribe to markdown chunks from ViewModel for this specific message
    LaunchedEffect(message.id, appViewModel.markdownChunkToAppendFlow) {
        appViewModel.markdownChunkToAppendFlow
            .filter { (msgId, _) -> msgId == message.id } // Only process chunks for this message
            .collect { (_, markdownChunkPair) -> // markdownChunkPair is Pair<TriggerKey, MarkdownChunk>
                val triggerKey = markdownChunkPair.first
                val markdownChunk = markdownChunkPair.second
                if (markdownChunk.isNotBlank()) {
                    val htmlChunk =
                        convertMarkdownToHtml(markdownChunk) // Convert markdown chunk to HTML
                    Log.d(
                        "AiMessageContent_Stream",
                        "MsgID ${message.id.take(4)}: Received mdChunk (key ${triggerKey.take(4)}), converted to htmlChunk (len ${htmlChunk.length}): \"${
                            htmlChunk.take(50).replace("\n", "\\n")
                        }\""
                    )
                    htmlChunkToAppendState =
                        triggerKey to htmlChunk // Update state to pass to PooledKatexWebView
                }
            }
    }

    // The initial/full HTML content derived from the complete message.text
    val initialOrFullHtmlInput = remember(message.text, message.sender, message.isError) {
        if (message.sender == Sender.AI && !message.isError) {
            if (!message.htmlContent.isNullOrBlank()) { // ★★★ 优先使用预渲染的 HTML ★★★
                Log.d("AiMessageContent_Full", "Using pre-rendered HTML for MsgID ${message.id.take(4)}")
                message.htmlContent!!
            } else if (message.text.isNotBlank()) { // Fallback for older messages or if htmlContent somehow not generated yet
                Log.d("AiMessageContent_Full", "MsgID ${message.id.take(4)}: Generating initialOrFullHtmlInput from message.text (len ${message.text.length}) as htmlContent is missing.")
                convertMarkdownToHtml(message.text.trim()) // 这里的 convertMarkdownToHtml 应该也从 util 导入
            } else {
                Log.d("AiMessageContent_Full", "MsgID ${message.id.take(4)}: initialOrFullHtmlInput is empty.")
                "" // Start with empty for new streaming messages or if no text
            }
        } else {
            Log.d(
                "AiMessageContent_Full",
                "MsgID ${message.id.take(4)}: initialOrFullHtmlInput is empty."
            )
            "" // Start with empty for new streaming messages or if no text
        }
    }

    Column(
        modifier = modifier.pointerInput(fullMessageTextToCopy) {
            detectTapGestures(onLongPress = { offsetValue ->
                onUserInteraction(); pressOffset = offsetValue; isAiContextMenuVisible = true
            })
        }
    ) {
        if (showLoadingDots && message.text.isBlank() && !message.isError) {
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
        } else if (message.sender == Sender.AI && !message.isError) {
            // Always render PooledKatexWebView for AI messages if not an error.
            // It will handle empty initial input gracefully and wait for chunks.
            val textColorHex = remember(contentColor) { contentColor.toHexCss() }
            val baseHtmlTemplate = rememberKatexBaseHtmlTemplate(
                backgroundColor = "transparent",
                textColor = textColorHex,
                errorColor = "#CD5C5C",
                throwOnError = false
            )

            Log.d(
                "AiMessageContent_Render",
                "MsgID ${message.id.take(4)}: Rendering PooledKatexWebView. InitialHTML len: ${initialOrFullHtmlInput.length}. Current chunk key: ${
                    htmlChunkToAppendState?.first?.take(4)
                }"
            )

            PooledKatexWebView(
                appViewModel = appViewModel,
                contentId = webViewContentId,
                initialLatexInput = initialOrFullHtmlInput,
                htmlChunkToAppend = htmlChunkToAppendState,
                htmlTemplate = baseHtmlTemplate,
                modifier = Modifier.heightIn(min = 1.dp) // Allow content to define its height
            )
        } else if (message.sender == Sender.AI && message.text.isBlank() && !message.isError && !showLoadingDots) {
            // AI message is blank, not an error, and not in a loading state (e.g., stream ended with no content)
            Spacer(Modifier.height(1.dp))
            Log.d(
                "AiMessageContent_Render",
                "MsgID ${message.id.take(4)}: AI message is blank, not loading, not error. Showing spacer."
            )
        }
        // Context Menu (remains unchanged)
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

        // Selectable Text Dialog (remains unchanged)
        if (showSelectableTextDialog) {
            SelectableTextDialog(
                textToDisplay = fullMessageTextToCopy,
                onDismissRequest = { showSelectableTextDialog = false })
        }
    }
}

// SelectableTextDialog Composable (remains unchanged)
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

// ThreeDotsLoadingAnimation Composable (remains unchanged)
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