@file:OptIn(ExperimentalFoundationApi::class)
package com.android.everytalk.ui.screens.MainScreen.chat.text.ui
import com.android.everytalk.statecontroller.*
import android.annotation.SuppressLint
import com.android.everytalk.R
import androidx.compose.ui.res.painterResource

import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
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
import com.android.everytalk.ui.components.safety.AiContentReportDialog
import com.android.everytalk.ui.components.safety.AiContentReportMenuItem
import com.android.everytalk.ui.components.scrollFadeEdge
import com.android.everytalk.ui.components.markdown.FootnoteNavigationState
import com.android.everytalk.ui.components.streaming.PreparedMessage
import com.android.everytalk.ui.components.streaming.StreamBlock
import com.android.everytalk.ui.components.streaming.MathBlockState
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.android.everytalk.ui.components.streaming.UnifiedMarkdownRenderer
import com.android.everytalk.ui.components.streaming.UnifiedMarkdownNodesRenderer
import com.android.everytalk.ui.components.streaming.buildStreamingRenderState
import com.android.everytalk.ui.components.streaming.StreamingRenderState
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
    streamingRenderStateOverride: StreamingRenderState? = null,
    onImageClick: ((String) -> Unit)? = null
) {
    val shape = RectangleShape
    val aiReplyMessageDescription = stringResource(id = R.string.ai_reply_message)
    val codeCopiedMessage = stringResource(R.string.chat_code_copied)

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
            val streamingRenderState = if (streamingRenderStateOverride != null) {
                streamingRenderStateOverride
            } else {
                val streamingRenderStateSource = remember(message.id, viewModel) {
                    viewModel.getStreamingRenderState(message.id)
                }
                val pauseAwareRenderState = remember(streamingRenderStateSource, viewModel) {
                    streamingRenderStateSource.freezeWhileStreamingPaused(viewModel.isStreamingPaused)
                }
                val observedRenderState by pauseAwareRenderState.collectAsState(
                    initial = streamingRenderStateSource.value
                )
                observedRenderState
            }

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
                            viewModel.showSnackbar(codeCopiedMessage)
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
    conversationTotalTokens: Long,
    viewModel: AppViewModel,
    scrollStateManager: ChatScrollStateManager,
    onShowOptions: (Message) -> Unit = {},
) {
    var showPopupMenu by remember { mutableStateOf(false) }
    var showContextUsage by remember(message.id) { mutableStateOf(false) }
    var showReportDialog by remember(message.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val shareFailedMessage = stringResource(R.string.chat_share_failed)
    val availableModels by viewModel.apiConfigs.collectAsState()
    val selectedModel by viewModel.selectedApiConfig.collectAsState()
    val liveContextWindowTokens = remember(
        message.contextUsageSnapshot?.configId,
        message.modelName,
        message.providerName,
        availableModels,
        selectedModel?.id,
    ) {
        resolveLiveContextWindowTokens(
            message = message,
            configs = availableModels,
            activeConfigId = selectedModel?.id,
        )
    }

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
                    contentDescription = stringResource(R.string.action_copy),
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
                            onFailure = { viewModel.showSnackbar(shareFailedMessage) },
                        )
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_share),
                    contentDescription = stringResource(R.string.action_share),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            AiContextUsageButton(
                message = message,
                conversationTotalTokens = conversationTotalTokens,
                liveContextWindowTokens = liveContextWindowTokens,
                expanded = showContextUsage,
                onClick = {
                    showPopupMenu = false
                    showContextUsage = true
                },
                onDismiss = { showContextUsage = false },
            )
            Box {
                IconButton(
                    onClick = {
                        showContextUsage = false
                        showPopupMenu = true
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_dots_horizontal),
                        contentDescription = stringResource(R.string.action_more),
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
                    availableModels = availableModels,
                    selectedModelId = selectedModel?.id,
                    onChangeModelConfirm = { config ->
                        val latestMessage = viewModel.getMessageById(message.id) ?: message
                        scrollStateManager.lockAutoScroll()
                        viewModel.regenerateAiResponseWithConfig(latestMessage, config, scrollToNewMessage = true)
                    },
                    onExport = {
                        val latestMessage = viewModel.getMessageById(message.id) ?: message
                        viewModel.exportMessageText(latestMessage.text)
                    },
                    onReport = { showReportDialog = true },
                )
            }
        }
    }

    if (showReportDialog) {
        AiContentReportDialog(
            onDismiss = { showReportDialog = false },
            onSubmit = { category, details ->
                val latestMessage = viewModel.getMessageById(message.id) ?: message
                viewModel.submitAiContentReport(
                    message = latestMessage,
                    category = category,
                    details = details,
                    isImageGeneration = false,
                )
                showReportDialog = false
            },
        )
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
    onReport: () -> Unit,
) {
    var showModelPicker by remember { mutableStateOf(false) }
    var pendingConfirmModel by remember { mutableStateOf<ApiConfig?>(null) }
    val textColor = MaterialTheme.colorScheme.onSurface
    val iconTint = textColor

    AiMessageFloatingPopupCard(
        expanded = expanded,
        onDismiss = {
            showModelPicker = false
            onDismiss()
        },
        modifier = Modifier.wrapContentWidth(),
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
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .padding(vertical = 12.dp),
            ) {
                PopupMenuItem(
                    painter = painterResource(R.drawable.ic_regenerate),
                    text = stringResource(R.string.chat_action_regenerate),
                    textColor = textColor,
                    iconTint = iconTint,
                    onClick = { onRegenerate(); onDismiss() }
                )
                PopupMenuItem(
                    painter = painterResource(R.drawable.ic_robot_head),
                    text = modelName ?: stringResource(R.string.chat_switch_model),
                    textColor = textColor,
                    iconTint = iconTint,
                    onClick = { showModelPicker = true }
                )
                PopupMenuItem(
                    painter = painterResource(R.drawable.ic_export),
                    text = stringResource(R.string.chat_action_export_text),
                    textColor = textColor,
                    iconTint = iconTint,
                    onClick = { onExport(); onDismiss() }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    color = textColor.copy(alpha = 0.08f),
                )
                AiContentReportMenuItem(
                    onClick = {
                        onDismiss()
                        onReport()
                    },
                )
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
                text = stringResource(R.string.chat_no_available_models),
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
                text = stringResource(R.string.chat_regenerate_with_model_title),
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Text(
                text = stringResource(R.string.chat_regenerate_with_model_description, modelName),
                color = subtextColor,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_confirm), color = Color(0xFF66B5FF), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.navigation_back), color = textColor)
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

