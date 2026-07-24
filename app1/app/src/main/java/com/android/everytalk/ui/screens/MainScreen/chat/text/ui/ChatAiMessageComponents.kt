@file:OptIn(ExperimentalFoundationApi::class)
package com.android.everytalk.ui.screens.MainScreen.chat.text.ui
import com.android.everytalk.statecontroller.*
import android.annotation.SuppressLint
import com.android.everytalk.R
import androidx.compose.ui.res.painterResource

import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.freezeWhileStreamingPaused
import com.android.everytalk.ui.screens.BubbleMain.Main.AttachmentsContent
import com.android.everytalk.ui.screens.BubbleMain.Main.ReasoningToggleAndContent
import com.android.everytalk.ui.screens.BubbleMain.Main.UserOrErrorMessageContent
import com.android.everytalk.ui.screens.BubbleMain.Main.resolveUserBubbleMaxHeightDp
import com.android.everytalk.ui.screens.BubbleMain.Main.MessageContextMenu
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.screens.MainScreen.chat.core.PlaceholderRole
import com.android.everytalk.ui.screens.MainScreen.chat.models.sortModelConfigs
import com.android.everytalk.ui.screens.MainScreen.chat.text.state.ChatScrollStateManager
import com.android.everytalk.ui.theme.ChatDimensions
import com.android.everytalk.ui.theme.chatColors

import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.android.everytalk.ui.components.FullScreenCodeViewerDialog
import com.android.everytalk.ui.components.WebMarkdownSourcesExtractor
import com.android.everytalk.ui.components.everyTalkLoadingElapsedText
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogSubtextColor
import com.android.everytalk.ui.components.scrollFadeEdge
import com.android.everytalk.ui.components.markdown.FootnoteNavigationState
import com.android.everytalk.ui.components.streaming.PreparedMessage
import com.android.everytalk.ui.components.streaming.StreamBlock
import com.android.everytalk.ui.components.streaming.MathBlockState
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.android.everytalk.ui.components.streaming.UnifiedMarkdownRenderer
import com.android.everytalk.ui.components.streaming.UnifiedMarkdownNodesRenderer
import com.android.everytalk.ui.components.streaming.buildStreamingRenderState
import com.android.everytalk.ui.components.streaming.contentVersionForRendering
import com.android.everytalk.ui.topanchor.RunTopAnchorReserveEngine
import com.android.everytalk.ui.topanchor.TopAnchorConfig
import com.android.everytalk.ui.topanchor.TopAnchorReserveEngineState
import com.android.everytalk.ui.topanchor.appendTopAnchorReserve
import com.android.everytalk.ui.topanchor.mapChatItemsToTopAnchorItems
import com.android.everytalk.ui.topanchor.resolveActiveTopAnchorTurn
import com.android.everytalk.ui.topanchor.resolveTopAnchorResponseTargetId
import com.android.everytalk.util.message.prepareTextForExternalTransfer
import com.android.everytalk.util.web.linkFaviconInitial
import com.android.everytalk.util.web.linkFaviconUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil3.compose.AsyncImage

@Composable
@SuppressLint("StateFlowValueCalledInComposition")
fun AiMessageItem(
    message: Message,
    text: String,
    maxWidth: Dp,
    isStreaming: Boolean,
    messageOutputType: String,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
    blocks: List<StreamBlock> = emptyList(),
    staticDisplayText: String? = null,
    staticPageSources: List<WebSearchResult> = emptyList(),
    staticPreparedMessage: PreparedMessage? = null,
    onImageClick: ((String) -> Unit)? = null
) {
    val shape = RectangleShape
    val aiReplyMessageDescription = stringResource(id = R.string.ai_reply_message)

    var previewCode by remember { mutableStateOf<String?>(null) }
    var previewLanguage by remember { mutableStateOf("text") }

    if (previewCode != null) {
        FullScreenCodeViewerDialog(
            code = previewCode!!,
            language = previewLanguage,
            onDismiss = {
                previewCode = null
                previewLanguage = "text"
            }
        )
    }

    val streamingHeightCachePx = remember(message.id) { intArrayOf(0) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .semantics { contentDescription = aiReplyMessageDescription },
            shape = shape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 0.dp
        ) {
            val streamingRenderStateSource = remember(message.id, viewModel) {
                viewModel.getStreamingRenderState(message.id)
            }
            val pauseAwareRenderState = remember(streamingRenderStateSource, viewModel) {
                streamingRenderStateSource.freezeWhileStreamingPaused(viewModel.isStreamingPaused)
            }
            val streamingRenderState by pauseAwareRenderState.collectAsState(
                initial = streamingRenderStateSource.value
            )

            val shouldPreferStreamingContent =
                isStreaming ||
                    streamingRenderState.isStreaming

            val effectiveContent = if (shouldPreferStreamingContent) {
                streamingRenderState.content.ifBlank { message.text }
            } else {
                // 流式结束后，优先使用 message.text；但如果 message.text 为空或明显短于
                // streamingRenderState.content，说明存在同步竞态，使用流式内容兜底防止闪烁
                if (message.text.isBlank() && streamingRenderState.content.isNotBlank()) {
                    streamingRenderState.content
                } else if (message.text.length < streamingRenderState.content.length * 0.8 && streamingRenderState.content.isNotBlank()) {
                    streamingRenderState.content
                } else {
                    message.text
                }
            }
            val usePreparedStaticRender = shouldUsePreparedStaticAiRender(
                shouldPreferStreamingContent = shouldPreferStreamingContent,
                hasPreparedMessage = staticPreparedMessage != null,
                itemText = text,
                effectiveContent = effectiveContent,
            )

            val renderMessage = if (effectiveContent == message.text) {
                message
            } else {
                message.copy(text = effectiveContent)
            }
            val dynamicSourcesExtraction = remember(effectiveContent, usePreparedStaticRender) {
                if (usePreparedStaticRender) null else WebMarkdownSourcesExtractor.extract(effectiveContent)
            }
            val extractedSources = dynamicSourcesExtraction?.sources.orEmpty()
            val pageSources = if (usePreparedStaticRender) {
                staticPageSources
            } else {
                message.webSearchResults?.takeIf { it.isNotEmpty() } ?: extractedSources
            }
            val displayContent = if (usePreparedStaticRender) {
                requireNotNull(staticDisplayText)
            } else {
                dynamicSourcesExtraction?.displayText ?: effectiveContent
            }
            val displayMessage = if (displayContent == renderMessage.text) {
                renderMessage
            } else {
                renderMessage.copy(text = displayContent)
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                if (pageSources.isNotEmpty()) {
                    PageSourcesButton(
                        pageSources = pageSources,
                        onClick = { viewModel.showSourcesDialog(pageSources) },
                        modifier = Modifier.padding(
                            start = ChatMarkdownTextStyle.ASSISTANT_CONTENT_START_PADDING_DP.dp,
                            top = ChatMarkdownTextStyle.ASSISTANT_CONTENT_TOP_PADDING_DP.dp,
                            bottom = 6.dp
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .retainGrowingHeightWhileStreaming(
                            isStreaming = isStreaming,
                            heightCachePx = streamingHeightCachePx,
                        )
                        .fillMaxWidth()
                        .padding(
                            start = ChatMarkdownTextStyle.ASSISTANT_CONTENT_START_PADDING_DP.dp,
                            top = ChatMarkdownTextStyle.ASSISTANT_CONTENT_TOP_PADDING_DP.dp,
                            end = ChatMarkdownTextStyle.ASSISTANT_CONTENT_END_PADDING_DP.dp,
                            bottom = ChatMarkdownTextStyle.ASSISTANT_CONTENT_BOTTOM_PADDING_DP.dp
                        )
                ) {

                val useStreamingBlocks =
                    !usePreparedStaticRender &&
                        extractedSources.isEmpty() &&
                        streamingRenderState.content == effectiveContent &&
                        streamingRenderState.blocks.isNotEmpty()

                val sourceStrippedRenderState = remember(
                    message.id,
                    displayContent,
                    effectiveContent,
                    messageOutputType,
                    extractedSources.size,
                    shouldPreferStreamingContent,
                ) {
                    if (shouldBuildSourceStrippedRenderBlocks(
                            messageOutputType = messageOutputType,
                            extractedSourceCount = extractedSources.size,
                            effectiveContent = effectiveContent,
                            displayContent = displayContent,
                        )
                    ) {
                        buildStreamingRenderState(
                            messageId = "${message.id}:sources-stripped",
                            content = displayContent,
                            isStreaming = shouldPreferStreamingContent,
                            isComplete = !shouldPreferStreamingContent,
                        )
                    } else {
                        null
                    }
                }

                val renderBlocks = when {
                    usePreparedStaticRender -> blocks
                    useStreamingBlocks -> streamingRenderState.blocks
                    sourceStrippedRenderState != null -> sourceStrippedRenderState.blocks
                    extractedSources.isNotEmpty() -> emptyList()
                    blocks.isNotEmpty() && (text == effectiveContent || message.text == effectiveContent) -> blocks
                    else -> emptyList()
                }

                val localRenderState = remember(
                    message.id,
                    displayContent,
                    messageOutputType,
                    blocks.size,
                    streamingRenderState.blocks.size,
                    sourceStrippedRenderState?.blocks?.size ?: 0,
                    extractedSources.size,
                    usePreparedStaticRender,
                ) {
                    if (!usePreparedStaticRender && shouldBuildLocalRenderBlocks(
                            messageOutputType = messageOutputType,
                            displayContent = displayContent,
                            hasUpstreamBlocks = blocks.isNotEmpty(),
                            hasStreamingBlocks = useStreamingBlocks,
                            hasSourceStrippedBlocks = sourceStrippedRenderState != null,
                            hasExtractedSources = extractedSources.isNotEmpty(),
                        )
                    ) {
                        buildStreamingRenderState(
                            messageId = "${message.id}:local",
                            content = displayContent,
                            isStreaming = false,
                            isComplete = true,
                        )
                    } else {
                        null
                    }
                }

                val fallbackRenderState = remember(
                    message.id,
                    displayContent,
                    renderBlocks.size,
                    localRenderState?.blocks?.size ?: 0,
                    usePreparedStaticRender,
                ) {
                    if (
                        !usePreparedStaticRender &&
                        displayContent.isNotBlank() &&
                        renderBlocks.isEmpty() &&
                        localRenderState == null
                    ) {
                        buildStreamingRenderState(
                            messageId = "${message.id}:fallback",
                            content = displayContent,
                            isStreaming = shouldPreferStreamingContent,
                            isComplete = !shouldPreferStreamingContent,
                        )
                    } else {
                        null
                    }
                }

                val selectedRenderState = when {
                    useStreamingBlocks -> streamingRenderState
                    sourceStrippedRenderState != null -> sourceStrippedRenderState
                    localRenderState != null -> localRenderState
                    fallbackRenderState != null -> fallbackRenderState
                    else -> null
                }

                val effectiveRenderBlocks = renderBlocks.ifEmpty {
                    selectedRenderState?.blocks ?: emptyList()
                }

                val preparedMessage = remember(
                    usePreparedStaticRender,
                    staticPreparedMessage,
                    selectedRenderState?.preparedMessage,
                    displayMessage.text,
                    effectiveRenderBlocks,
                ) {
                    when {
                        usePreparedStaticRender -> requireNotNull(staticPreparedMessage)
                        selectedRenderState != null -> selectedRenderState.preparedMessage
                        else -> {
                            val hasPendingFormula = effectiveRenderBlocks.any { block ->
                                when (block) {
                                    is StreamBlock.MathInline -> block.state != MathBlockState.RENDERED
                                    is StreamBlock.MathBlock -> block.state != MathBlockState.RENDERED
                                    else -> false
                                }
                            }
                            StreamBlockParser.prepareMessage(
                                content = displayMessage.text,
                                blocks = effectiveRenderBlocks,
                                hasPendingFormula = hasPendingFormula,
                                contentVersion = contentVersionForRendering(displayMessage.text),
                            )
                        }
                    }
                }

                if (effectiveRenderBlocks.isNotEmpty()) {
                    UnifiedMarkdownRenderer(
                        preparedMessage = preparedMessage,
                        sender = displayMessage.sender,
                        isStreaming = shouldPreferStreamingContent,
                        onCodePreviewRequested = { lang, code ->
                            previewLanguage = lang
                            previewCode = code
                        },
                        onCodeCopied = {
                            viewModel.showSnackbar("已复制代码")
                        },
                        onImageClick = onImageClick,
                    )
                }
                }
            }
        }
    }
}

@Composable
fun AiMessageFooterItem(
    message: Message,
    viewModel: AppViewModel,
    scrollStateManager: ChatScrollStateManager,
    onShowOptions: (Message) -> Unit = {},
) {
    var showPopupMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ChatDimensions.HORIZONTAL_PADDING)
    ) {
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val latestMessage = viewModel.getMessageById(message.id) ?: message
                    viewModel.copyToClipboard(latestMessage.text)
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = "复制",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            IconButton(
                onClick = {
                    val latestMessage = viewModel.getMessageById(message.id) ?: message
                    coroutineScope.launch {
                        shareMessageText(
                            context = context,
                            text = latestMessage.text,
                            onFailure = { viewModel.showSnackbar("分享失败") },
                        )
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_share),
                    contentDescription = "分享",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Box {
                IconButton(
                    onClick = { showPopupMenu = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_dots_horizontal),
                        contentDescription = "更多",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                AiMessagePopupMenu(
                    expanded = showPopupMenu,
                    onDismiss = { showPopupMenu = false },
                    onRegenerate = {
                        val latestMessage = viewModel.getMessageById(message.id) ?: message
                        scrollStateManager.lockAutoScroll()
                        viewModel.regenerateAiResponse(latestMessage, scrollToNewMessage = true)
                    },
                    modelName = message.modelName,
                    availableModels = viewModel.apiConfigs.collectAsState().value,
                    selectedModelId = viewModel.selectedApiConfig.collectAsState().value?.id,
                    onChangeModelConfirm = { config ->
                        val latestMessage = viewModel.getMessageById(message.id) ?: message
                        scrollStateManager.lockAutoScroll()
                        viewModel.regenerateAiResponseWithConfig(latestMessage, config, scrollToNewMessage = true)
                    },
                    onExport = {
                        val latestMessage = viewModel.getMessageById(message.id) ?: message
                        viewModel.exportMessageText(latestMessage.text)
                    }
                )
            }
        }
    }
}

@Composable
private fun AiMessagePopupMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRegenerate: () -> Unit,
    modelName: String?,
    availableModels: List<ApiConfig>,
    selectedModelId: String?,
    onChangeModelConfirm: (ApiConfig) -> Unit,
    onExport: () -> Unit,
) {
    var showPopup by remember { mutableStateOf(false) }
    val scaleAnim = remember { Animatable(0.8f) }
    val alphaAnim = remember { Animatable(0f) }

    val emphasizedDecelerate = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val decelerateEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    LaunchedEffect(expanded) {
        if (expanded) {
            showPopup = true
            scaleAnim.snapTo(0.8f)
            alphaAnim.snapTo(0f)
            coroutineScope {
                launch { scaleAnim.animateTo(1f, tween(120, easing = emphasizedDecelerate)) }
                launch { alphaAnim.animateTo(1f, tween(30, easing = decelerateEasing)) }
            }
        } else if (showPopup) {
            coroutineScope {
                launch { alphaAnim.animateTo(0f, tween(75, easing = decelerateEasing)) }
                launch {
                    delay(74)
                    scaleAnim.snapTo(0.8f)
                }
            }
            showPopup = false
        }
    }

    var showModelPicker by remember { mutableStateOf(false) }
    var pendingConfirmModel by remember { mutableStateOf<ApiConfig?>(null) }

    if (!showPopup) return

    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF212121) else Color(0xFFFFFFFF)
    val textColor = MaterialTheme.colorScheme.onSurface
    val iconTint = textColor
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color(0xFF0D0D0D).copy(alpha = 0.05f)

    Popup(
        alignment = Alignment.BottomStart,
        onDismissRequest = {
            showModelPicker = false
            onDismiss()
        },
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(min = 200.dp)
                .graphicsLayer {
                    this.scaleX = scaleAnim.value
                    this.scaleY = scaleAnim.value
                    this.alpha = alphaAnim.value
                    this.transformOrigin = TransformOrigin(0f, 1f)
                }
                .shadow(8.dp, RoundedCornerShape(28.dp))
                .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp),
            color = cardBg
        ) {
            if (showModelPicker) {
                ModelPickerPopupContent(
                    availableModels = availableModels,
                    selectedModelId = selectedModelId,
                    textColor = textColor,
                    iconTint = iconTint,
                    onModelSelected = { pendingConfirmModel = it }
                )
            } else {
                Column(modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .padding(vertical = 12.dp)
                ) {
                    PopupMenuItem(
                        painter = painterResource(R.drawable.ic_regenerate),
                        text = "重新回答",
                        textColor = textColor,
                        iconTint = iconTint,
                        onClick = { onRegenerate(); onDismiss() }
                    )
                    PopupMenuItem(
                        painter = painterResource(R.drawable.ic_robot_head),
                        text = modelName ?: "切换模型",
                        textColor = textColor,
                        iconTint = iconTint,
                        onClick = { showModelPicker = true }
                    )
                    PopupMenuItem(
                        painter = painterResource(R.drawable.ic_export),
                        text = "导出文本",
                        textColor = textColor,
                        iconTint = iconTint,
                        onClick = { onExport(); onDismiss() }
                    )
                }
            }
        }
    }

    pendingConfirmModel?.let { config ->
        ConfirmModelRegenerateDialog(
            modelName = config.name.takeIf { it.isNotBlank() } ?: config.model,
            onBack = { pendingConfirmModel = null },
            onConfirm = {
                pendingConfirmModel = null
                showModelPicker = false
                onChangeModelConfirm(config)
                onDismiss()
            }
        )
    }
}

@Composable
private fun ModelPickerPopupContent(
    availableModels: List<ApiConfig>,
    selectedModelId: String?,
    textColor: Color,
    iconTint: Color,
    onModelSelected: (ApiConfig) -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(min = 240.dp, max = 320.dp)
            .heightIn(max = 360.dp)
            .padding(vertical = 12.dp)
    ) {
        if (availableModels.isEmpty()) {
            Text(
                text = "当前无可用模型",
                color = textColor.copy(alpha = 0.7f),
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        } else {
            LazyColumn {
                items(sortModelConfigs(availableModels), key = { it.id }) { config ->
                    val displayName = config.name.takeIf { it.isNotBlank() } ?: config.model
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onModelSelected(config) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_robot_head),
                            contentDescription = null,
                            tint = if (config.id == selectedModelId) Color(0xFF66B5FF) else iconTint,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayName,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (config.model != displayName) {
                                Text(
                                    text = config.model,
                                    fontSize = 12.sp,
                                    color = textColor.copy(alpha = 0.65f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmModelRegenerateDialog(
    modelName: String,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cardBg = appDialogContainerColor()
    val textColor = appDialogContentColor()
    val subtextColor = appDialogSubtextColor()

    AlertDialog(
        onDismissRequest = onBack,
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = cardBg,
        title = {
            Text(
                text = "切换模型重新回答",
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Text(
                text = "将使用“$modelName”重新回答这个问题。",
                color = subtextColor,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确定", color = Color(0xFF66B5FF), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onBack) {
                Text("返回", color = textColor)
            }
        }
    )
}

@Composable
private fun PopupMenuItem(
    painter: androidx.compose.ui.graphics.painter.Painter,
    text: String,
    textColor: Color,
    iconTint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

