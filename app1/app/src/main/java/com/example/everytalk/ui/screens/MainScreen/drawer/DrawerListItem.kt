package com.example.everytalk.ui.screens.MainScreen.drawer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.GestureCancellationException
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

private val LIST_ITEM_MIN_HEIGHT = 64.dp

@Composable
internal fun DrawerConversationListItem(
    itemData: FilteredConversationItem,
    isSearchActive: Boolean,
    currentSearchQuery: String,
    loadedHistoryIndex: Int?,
    getPreviewForIndex: (Int) -> String,
    onConversationClick: (Int) -> Unit,
    onRenameRequest: (Int) -> Unit,
    onDeleteTriggered: (Int) -> Unit,
    expandedItemIndex: Int?,
    onExpandItem: (index: Int, position: Offset) -> Unit,
    onCollapseMenu: () -> Unit,
    longPressPositionForMenu: Offset?,
) {
    val originalIndex = itemData.originalIndex
    val definitivePreviewText = getPreviewForIndex(originalIndex)

    val isActuallyActive = loadedHistoryIndex == originalIndex

    var rippleState by remember { mutableStateOf<CustomRippleState>(CustomRippleState.Idle) }
    var currentPressPosition by remember { mutableStateOf(Offset.Zero) }
    val animationProgress by animateFloatAsState(
        targetValue = if (rippleState is CustomRippleState.Animating) 1f else 0f,
        animationSpec = tween(
            durationMillis = CUSTOM_RIPPLE_ANIMATION_DURATION_MS,
            easing = LinearEasing
        ),
        finishedListener = {
            if (rippleState !is CustomRippleState.Idle && it == 0f) {
                rippleState = CustomRippleState.Idle
            }
        },
        label = "listItemRippleProgress"
    )
    val scope = rememberCoroutineScope()
    var pressAndHoldJob by remember { mutableStateOf<Job?>(null) }
    val haptic = LocalHapticFeedback.current

    val alpha = remember { Animatable(0f) }
    val translationY = remember { Animatable(50f) }

    LaunchedEffect(itemData.originalIndex) {
        launch {
            alpha.animateTo(1f, animationSpec = tween(durationMillis = 300))
        }
        launch {
            translationY.animateTo(0f, animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LIST_ITEM_MIN_HEIGHT)
            .clipToBounds()
            .graphicsLayer {
                this.alpha = alpha.value
                this.translationY = translationY.value
            }
            .pointerInput(originalIndex) {
                detectTapGestures(
                    onPress = { offset ->
                        pressAndHoldJob?.cancel()
                        currentPressPosition = offset
                        rippleState = CustomRippleState.Animating(offset)
                        pressAndHoldJob = scope.launch {
                            try {
                                awaitRelease()
                                rippleState = CustomRippleState.Idle
                            } catch (_: GestureCancellationException) {
                                rippleState = CustomRippleState.Idle
                            }
                        }
                    },
                    onTap = {
                        if (expandedItemIndex == originalIndex) {
                            onCollapseMenu()
                        } else {
                            onCollapseMenu()
                            onConversationClick(originalIndex)
                        }
                    },
                    onLongPress = { offset ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        pressAndHoldJob?.cancel()
                        rippleState = CustomRippleState.Idle
                        onExpandItem(originalIndex, offset)
                    }
                )
            }
            .drawBehind {
                if (animationProgress > 0f) {
                    val rippleRadius = max(size.width, size.height) * animationProgress * 0.8f
                    val alpha = CUSTOM_RIPPLE_START_ALPHA * (1f - animationProgress)
                    if (alpha > 0f) {
                        drawCircle(
                            color = CUSTOM_RIPPLE_COLOR,
                            radius = rippleRadius,
                            center = currentPressPosition,
                            alpha = alpha.coerceIn(
                                CUSTOM_RIPPLE_END_ALPHA,
                                CUSTOM_RIPPLE_START_ALPHA
                            ),
                            style = Fill
                        )
                    }
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LIST_ITEM_MIN_HEIGHT),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isActuallyActive) {
                    Spacer(Modifier.width(16.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = if (isSearchActive && currentSearchQuery.isNotBlank())
                        rememberHighlightedText(definitivePreviewText, currentSearchQuery)
                    else
                        AnnotatedString(definitivePreviewText),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (isActuallyActive) 0.dp else 32.dp, end = 16.dp)
                )
            }

            val currentLongPressPosition = longPressPositionForMenu
            if (expandedItemIndex == originalIndex && currentLongPressPosition != null) {
                ConversationItemMenu(
                    expanded = true,
                    onDismissRequest = { onCollapseMenu() },
                    onRenameClick = { onRenameRequest(originalIndex) },
                    onDeleteClick = { onDeleteTriggered(originalIndex) },
                    popupPositionProvider = object :
                        PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize
                        ): IntOffset {
                            val x = anchorBounds.left + currentLongPressPosition.x.roundToInt()
                            val y = anchorBounds.top + currentLongPressPosition.y.roundToInt()
                            val finalX = x.coerceIn(0, windowSize.width - popupContentSize.width)
                            val finalY = y.coerceIn(0, windowSize.height - popupContentSize.height)
                            return IntOffset(finalX, finalY)
                        }
                    }
                )
            }
        }
    }
}
