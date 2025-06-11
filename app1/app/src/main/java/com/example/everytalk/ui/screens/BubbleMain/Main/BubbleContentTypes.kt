package com.example.everytalk.ui.screens.BubbleMain.Main

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.ui.screens.BubbleMain.ThreeDotsLoadingAnimation

private const val CONTEXT_MENU_ANIMATION_DURATION_MS = 150
private val CONTEXT_MENU_CORNER_RADIUS = 16.dp
private val CONTEXT_MENU_ITEM_ICON_SIZE = 20.dp
private val CONTEXT_MENU_FINE_TUNE_OFFSET_X = (-120).dp
private val CONTEXT_MENU_FINE_TUNE_OFFSET_Y = (-8).dp
private val CONTEXT_MENU_FIXED_WIDTH = 120.dp


@Composable
internal fun UserOrErrorMessageContent(
    message: Message,
    displayedText: String,
    showLoadingDots: Boolean,
    bubbleColor: Color,
    contentColor: Color,
    isError: Boolean,
    maxWidth: Dp,
    onUserInteraction: () -> Unit,
    onEditRequest: (Message) -> Unit,
    onRegenerateRequest: (Message) -> Unit,
    modifier: Modifier = Modifier
) {
    var isContextMenuVisible by remember(message.id) { mutableStateOf(false) }
    var pressOffset by remember(message.id) { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .widthIn(max = maxWidth)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Surface(
            color = bubbleColor,
            contentColor = contentColor,
            shape = RoundedCornerShape(18.dp),
            tonalElevation = if (isError) 0.dp else 1.dp,
            modifier = Modifier
                .pointerInput(message.id) {
                    detectTapGestures(
                        onLongPress = { offset ->
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onUserInteraction()
                            if (!isError) {
                                pressOffset = offset
                                isContextMenuVisible = true
                            }
                        }
                    )
                }
        ) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .wrapContentWidth()
                    .defaultMinSize(minHeight = 28.dp)
            ) {
                if (showLoadingDots && !isError) {
                    ThreeDotsLoadingAnimation(
                        dotColor = contentColor,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = (-6).dp)
                    )
                } else if (displayedText.isNotBlank() || isError) {
                    Text(
                        text = displayedText.trim(),
                        textAlign = TextAlign.Start,
                        color = contentColor
                    )
                }
            }
        }

        if (isContextMenuVisible && !isError) {
            val dropdownMenuOffsetX =
                with(density) { pressOffset.x.toDp() } + CONTEXT_MENU_FINE_TUNE_OFFSET_X
            val dropdownMenuOffsetY =
                with(density) { pressOffset.y.toDp() } + CONTEXT_MENU_FINE_TUNE_OFFSET_Y

            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(
                    x = with(density) { dropdownMenuOffsetX.roundToPx() },
                    y = with(density) { dropdownMenuOffsetY.roundToPx() }),
                onDismissRequest = { isContextMenuVisible = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    clippingEnabled = false
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS),
                    color = Color.White,
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .width(CONTEXT_MENU_FIXED_WIDTH)
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS)
                        )
                        .padding(1.dp)
                ) {
                    Column {
                        val menuVisibility =
                            remember { MutableTransitionState(false) }
                        LaunchedEffect(isContextMenuVisible) {
                            menuVisibility.targetState = isContextMenuVisible
                        }

                        @Composable
                        fun AnimatedDropdownMenuItem(
                            visibleState: MutableTransitionState<Boolean>,
                            delay: Int = 0,
                            text: @Composable () -> Unit,
                            onClick: () -> Unit,
                            leadingIcon: @Composable (() -> Unit)? = null
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

                        AnimatedDropdownMenuItem(
                            menuVisibility,
                            text = { Text("复制") },
                            onClick = {
                                clipboardManager.setText(AnnotatedString(message.text))
                                Toast.makeText(context, "内容已复制", Toast.LENGTH_SHORT).show()
                                isContextMenuVisible = false
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    "复制",
                                    Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
                                )
                            })

                        AnimatedDropdownMenuItem(
                            menuVisibility,
                            delay = 30,
                            text = { Text("编辑") },
                            onClick = {
                                onEditRequest(message)
                                isContextMenuVisible = false
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Edit,
                                    "编辑",
                                    Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
                                )
                            })

                        AnimatedDropdownMenuItem(
                            menuVisibility,
                            delay = 60,
                            text = { Text("重新回答") },
                            onClick = {
                                onRegenerateRequest(message)
                                isContextMenuVisible = false
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Refresh,
                                    "重新回答",
                                    Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
                                )
                            })
                    }
                }
            }
        }
    }
}

sealed class TextSegment {
    data class Normal(val text: String) : TextSegment()
    data class CodeBlock(val language: String?, val code: String) : TextSegment()
}

private val GFM_CLOSED_CODE_BLOCK_REGEX =
    Regex("```([a-zA-Z0-9_.-]*)[ \t]*\\n([\\s\\S]*?)\\n```")

private val CODE_BLOCK_START_REGEX = Regex("```")

fun parseMarkdownSegments(markdownInput: String): List<TextSegment> {
    if (markdownInput.isBlank()) {
        return emptyList()
    }

    val segments = mutableListOf<TextSegment>()
    var currentIndex = 0

    var matchResult = GFM_CLOSED_CODE_BLOCK_REGEX.find(markdownInput, currentIndex)

    while (matchResult != null) {
        val matchStart = matchResult.range.first
        val matchEnd = matchResult.range.last

        if (matchStart > currentIndex) {
            val normalText = markdownInput.substring(currentIndex, matchStart)
            if (normalText.isNotBlank()) {
                segments.add(TextSegment.Normal(normalText.trim()))
            }
        }

        val language = matchResult.groups[1]?.value?.trim()
            ?.takeIf { it.isNotEmpty() }
        val code = matchResult.groups[2]?.value ?: ""
        segments.add(TextSegment.CodeBlock(language, code))

        currentIndex = matchEnd + 1
        matchResult = GFM_CLOSED_CODE_BLOCK_REGEX.find(markdownInput, currentIndex)
    }

    if (currentIndex < markdownInput.length) {
        val remainingText = markdownInput.substring(currentIndex)

        val openBlockMatch = CODE_BLOCK_START_REGEX.find(remainingText)
        if (openBlockMatch != null) {
            val openBlockStartInRemaining = openBlockMatch.range.first

            if (openBlockStartInRemaining > 0) {
                val normalPrefix = remainingText.substring(0, openBlockStartInRemaining)
                if (normalPrefix.isNotBlank()) {
                    segments.add(TextSegment.Normal(normalPrefix.trim()))
                }
            }

            val codeBlockCandidate =
                remainingText.substring(openBlockStartInRemaining + 3)
            val firstNewlineIndex = codeBlockCandidate.indexOf('\n')
            var lang: String? = null
            var codeContent: String

            if (firstNewlineIndex != -1) {
                val langLine = codeBlockCandidate.substring(0, firstNewlineIndex).trim()
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                    lang = langLine.takeIf { it.isNotEmpty() }
                }
                codeContent = codeBlockCandidate.substring(firstNewlineIndex + 1)
            } else {
                val langLine = codeBlockCandidate.trim()
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                    lang = langLine.takeIf { it.isNotEmpty() }
                }
                codeContent = ""
            }
            segments.add(TextSegment.CodeBlock(lang, codeContent))

        } else {
            if (remainingText.isNotBlank()) {
                segments.add(TextSegment.Normal(remainingText.trim()))
            }
        }
    }

    if (segments.isEmpty() && markdownInput.isNotBlank()) {
        if (markdownInput.startsWith("```")) {
            val codeBlockCandidate = markdownInput.substring(3)
            val firstNewlineIndex = codeBlockCandidate.indexOf('\n')
            var lang: String? = null
            var codeContent: String
            if (firstNewlineIndex != -1) {
                val langLine = codeBlockCandidate.substring(0, firstNewlineIndex).trim()
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                    lang = langLine.takeIf { it.isNotEmpty() }
                }
                codeContent = codeBlockCandidate.substring(firstNewlineIndex + 1)
            } else {
                val langLine = codeBlockCandidate.trim()
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                    lang = langLine.takeIf { it.isNotEmpty() }
                }
                codeContent = ""
            }
            segments.add(TextSegment.CodeBlock(lang, codeContent))
        } else {
            segments.add(TextSegment.Normal(markdownInput.trim()))
        }
    }

    return segments
}

fun extractStreamingCodeContent(textAlreadyTrimmedAndStartsWithTripleQuote: String): Pair<String?, String> {
    val contentAfterTripleTicks =
        textAlreadyTrimmedAndStartsWithTripleQuote.substring(3)
    val firstNewlineIndex = contentAfterTripleTicks.indexOf('\n')

    if (firstNewlineIndex != -1) {
        val langHint = contentAfterTripleTicks.substring(0, firstNewlineIndex).trim()
        val code = contentAfterTripleTicks.substring(firstNewlineIndex + 1)
        val validatedLangHint =
            if (langHint.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                langHint.takeIf { it.isNotBlank() }
            } else {
                null
            }
        return Pair(validatedLangHint, code)
    } else {
        val langLine = contentAfterTripleTicks.trim()
        val validatedLangHint =
            if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                langLine.takeIf { it.isNotBlank() }
            } else {
                null
            }
        return Pair(validatedLangHint, "")
    }
}