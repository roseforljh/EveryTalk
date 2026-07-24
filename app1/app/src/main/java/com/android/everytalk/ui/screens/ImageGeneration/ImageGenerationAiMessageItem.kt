package com.android.everytalk.ui.screens.ImageGeneration
import com.android.everytalk.statecontroller.*

import android.annotation.SuppressLint
import com.android.everytalk.R
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
import android.net.Uri
import android.graphics.drawable.Drawable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.asDrawable
import android.content.Context
import java.util.UUID
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Base64
import android.widget.Toast
import com.android.everytalk.data.DataClass.Message
import android.graphics.BitmapFactory
import com.android.everytalk.data.network.SafeHttpDownloader
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.freezeWhileStreamingPaused
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.screens.MainScreen.chat.text.state.ChatScrollStateManager
import com.android.everytalk.ui.screens.BubbleMain.Main.AttachmentsContent
import com.android.everytalk.ui.screens.BubbleMain.Main.MessageContextMenu
import com.android.everytalk.ui.screens.BubbleMain.Main.ImageContextMenu
import com.android.everytalk.ui.screens.BubbleMain.Main.UserOrErrorMessageContent
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.HistoryLoadingBubblePlaceholderItem
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.android.everytalk.ui.components.streaming.UnifiedMarkdownRenderer
import com.android.everytalk.ui.components.streaming.buildStreamingRenderState
import com.android.everytalk.ui.theme.ChatDimensions
import com.android.everytalk.ui.theme.chatColors
import com.android.everytalk.ui.components.scrollFadeEdge
import com.android.everytalk.ui.topanchor.BottomScrollReason
import com.android.everytalk.ui.topanchor.RunTopAnchorReserveEngine
import com.android.everytalk.ui.topanchor.TopAnchorConfig
import com.android.everytalk.ui.topanchor.TopAnchorReserveEngineState
import com.android.everytalk.ui.topanchor.appendTopAnchorReserve
import com.android.everytalk.ui.topanchor.mapChatItemsToTopAnchorItems
import com.android.everytalk.ui.topanchor.resolveActiveTopAnchorTurn
import com.android.everytalk.ui.topanchor.resolveTopAnchorResponseTargetId
import com.android.everytalk.ui.topanchor.shouldAllowBottomScroll
import com.android.everytalk.util.storage.CappedByteArrayOutputStream
import com.android.everytalk.util.storage.readAtMost
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil3.size.Size
import kotlin.math.min

@Composable
@SuppressLint("StateFlowValueCalledInComposition")
internal fun AiMessageItem(
    message: Message,
    text: String,
    maxWidth: Dp,
    onLongPress: (Message, Offset) -> Unit,
    onOpenPreview: (Any) -> Unit,
    isStreaming: Boolean,
    onImageLoaded: () -> Unit,
    scrollStateManager: ChatScrollStateManager,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier
) {
    val shape = androidx.compose.ui.graphics.RectangleShape
    val aiReplyMessageDescription = stringResource(id = R.string.ai_reply_message)
    val streamingRenderStateSource = remember(message.id, viewModel) {
        viewModel.getStreamingRenderState(message.id)
    }
    val pauseAwareRenderState = remember(streamingRenderStateSource, viewModel) {
        streamingRenderStateSource.freezeWhileStreamingPaused(viewModel.isStreamingPaused)
    }
    val streamingRenderState by pauseAwareRenderState.collectAsState(
        initial = streamingRenderStateSource.value
    )
    val shouldPreferStreamingContent = isStreaming || streamingRenderState.isStreaming
    val effectiveText = if (shouldPreferStreamingContent) {
        streamingRenderState.content.ifBlank { text.ifBlank { message.text } }
    } else {
        val staticText = text.ifBlank { message.text }
        if (staticText.isBlank() && streamingRenderState.content.isNotBlank()) {
            streamingRenderState.content
        } else {
            staticText
        }
    }
    val renderMessage = if (effectiveText == message.text) {
        message
    } else {
        message.copy(text = effectiveText)
    }
    val useUpstreamRenderState = shouldPreferStreamingContent &&
        streamingRenderState.content == effectiveText &&
        streamingRenderState.blocks.isNotEmpty()
    val localRenderState = remember(
        message.id,
        effectiveText,
        shouldPreferStreamingContent,
        useUpstreamRenderState,
    ) {
        if (!useUpstreamRenderState && effectiveText.isNotBlank()) {
            buildStreamingRenderState(
                messageId = "${message.id}:image-generation",
                content = effectiveText,
                isStreaming = shouldPreferStreamingContent,
                isComplete = !shouldPreferStreamingContent,
            )
        } else {
            null
        }
    }
    val renderState = if (useUpstreamRenderState) {
        streamingRenderState
    } else {
        localRenderState
    }
    val currentMessage by rememberUpdatedState(message)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    Row(
        modifier = modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        var itemGlobalPosition by remember(message.id) { mutableStateOf(Offset.Zero) }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    itemGlobalPosition = coords.localToRoot(Offset.Zero)
                }
                .pointerInput(message.id) {
                    detectTapGestures(
                        onLongPress = { localOffset ->
                            // 将本地偏移转换为全局，统一与附件一致的定位体验
                            currentOnLongPress(currentMessage, itemGlobalPosition + localOffset)
                        }
                    )
                }
                .semantics {
                    contentDescription = aiReplyMessageDescription
                },
            shape = shape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .padding(
                        start = ChatMarkdownTextStyle.ASSISTANT_CONTENT_START_PADDING_DP.dp,
                        top = ChatMarkdownTextStyle.ASSISTANT_CONTENT_TOP_PADDING_DP.dp,
                        end = ChatMarkdownTextStyle.ASSISTANT_CONTENT_END_PADDING_DP.dp,
                        bottom = ChatMarkdownTextStyle.ASSISTANT_CONTENT_BOTTOM_PADDING_DP.dp
                    )
            ) {
                Column {
                    if (renderState != null && renderState.blocks.isNotEmpty()) {
                        UnifiedMarkdownRenderer(
                            preparedMessage = renderState.preparedMessage,
                            sender = renderMessage.sender,
                            isStreaming = shouldPreferStreamingContent,
                        )
                    }
                    if (message.imageUrls != null && message.imageUrls.isNotEmpty()) {
                        if (text.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        AttachmentsContent(
                            attachments = message.imageUrls.map { urlStr ->
                                val safeUri = try {
                                    when {
                                        urlStr.startsWith("data:image", ignoreCase = true) -> urlStr.toUri()
                                        urlStr.startsWith("file://", ignoreCase = true) -> urlStr.toUri()
                                        urlStr.startsWith("/", ignoreCase = true) -> Uri.fromFile(File(urlStr))
                                        else -> urlStr.toUri()
                                    }
                                } catch (_: Exception) {
                                    if (urlStr.startsWith("/")) Uri.fromFile(File(urlStr)) else urlStr.toUri()
                                }
                                SelectedMediaItem.ImageFromUri(safeUri, UUID.randomUUID().toString())
                            },
                            onAttachmentClick = { _ ->
                                val firstUrl = message.imageUrls.firstOrNull()
                                if (!firstUrl.isNullOrBlank()) {
                                    onOpenPreview(firstUrl)
                                }
                            },
                            maxWidth = maxWidth,
                            message = message,
                            onEditRequest = {},
                            onRegenerateRequest = {},
                            onLongPress = { msg, offset -> onLongPress(msg, offset) },
                            onImageLoaded = onImageLoaded,
                            bubbleColor = MaterialTheme.chatColors.aiBubble,
                            scrollStateManager = scrollStateManager,
                            isAiGenerated = true,
                            onImageClick = { url -> onOpenPreview(url) }
                        )
                    }
                }
            }
        }
    }
}
