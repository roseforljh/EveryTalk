package com.android.everytalk.ui.screens.MainScreen.chat.text.ui
import com.android.everytalk.statecontroller.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.android.everytalk.R
import com.android.everytalk.models.ImageSourceOption
import com.android.everytalk.models.MoreOptionsType
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.theme.SeaBlue

internal val ChatAgentColor = Color(0xFF009688)
internal val ChatMcpColor = Color(0xFFFF6B00)

internal enum class AgentToggleAction {
    DISABLE,
    OPEN_SERVER_PICKER,
    ENABLE_SELECTED,
}

/** 短按 Agent 的决定保持纯函数，避免准备中重复发起连接。 */
internal fun resolveAgentToggleAction(
    isEnabled: Boolean,
    isPreparing: Boolean,
    hasSelectedComputer: Boolean,
): AgentToggleAction = when {
    isEnabled || isPreparing -> AgentToggleAction.DISABLE
    !hasSelectedComputer -> AgentToggleAction.OPEN_SERVER_PICKER
    else -> AgentToggleAction.ENABLE_SELECTED
}

internal fun webSearchToggleLabelRes(isSupported: Boolean, isEnabled: Boolean): Int {
    return if (!isSupported) {
        R.string.chat_input_search_unavailable
    } else if (isEnabled) {
        R.string.chat_input_disable_search
    } else {
        R.string.chat_input_web_search
    }
}

/** 输入框内共用的紧凑功能标签，三项同时开启时仍保持短文本。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ActiveFunctionTag(
    iconRes: Int,
    label: String,
    tint: Color,
    lightBackground: Color,
    closeContentDescription: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val background = if (isSystemInDarkTheme()) Color(0xFF2A2A2A) else lightBackground
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = background,
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 5.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(2.dp))
            Text(text = label, fontSize = 13.sp, color = tint, maxLines = 1)
            Spacer(Modifier.width(2.dp))
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = closeContentDescription,
                tint = tint,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/**
 * 优化的媒体项预览组件 - 使用记忆化避免重复渲染
 */
@Composable
fun OptimizedSelectedItemPreview(
    mediaItem: SelectedMediaItem,
    onRemoveClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val audioLabel = stringResource(R.string.attachment_audio)
    // 记忆化图标和文本，避免重复计算
    val (icon, text) = remember(mediaItem, audioLabel) {
        when (mediaItem) {
            is SelectedMediaItem.GenericFile -> {
                val iconRes = when (mediaItem.mimeType) {
                    "application/pdf" -> Icons.Outlined.PictureAsPdf
                    "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> Icons.Outlined.Description
                    "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> Icons.Outlined.TableChart
                    "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> Icons.Outlined.Slideshow
                    "application/zip", "application/x-rar-compressed" -> Icons.Outlined.Archive
                    else -> when {
                        mediaItem.mimeType.startsWith("video/") -> Icons.Outlined.Videocam
                        mediaItem.mimeType.startsWith("audio/") -> Icons.Outlined.Audiotrack
                        mediaItem.mimeType.startsWith("image/") -> Icons.Outlined.Image
                        else -> Icons.Outlined.AttachFile
                    }
                }
                iconRes to mediaItem.displayName
            }
            is SelectedMediaItem.Audio -> Icons.Outlined.Audiotrack to audioLabel
            else -> null to ""
        }
    }

    Box(
        modifier = modifier
            .size(width = 100.dp, height = 80.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        when (mediaItem) {
            is SelectedMediaItem.ImageFromUri -> AsyncImage(
                model = mediaItem.uri,
                contentDescription = stringResource(R.string.attachment_selected_gallery_image),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            is SelectedMediaItem.ImageFromBitmap -> {
                val imageModel = remember(mediaItem) { mediaItem.model }
                AsyncImage(
                    model = imageModel,
                    contentDescription = stringResource(R.string.attachment_selected_camera_image),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            is SelectedMediaItem.GenericFile -> {
                if (mediaItem.mimeType.startsWith("image/")) {
                    AsyncImage(
                        model = mediaItem.uri,
                        contentDescription = mediaItem.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        icon?.let {
                            Icon(
                                imageVector = it,
                                contentDescription = text,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = text,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            is SelectedMediaItem.Audio -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    icon?.let {
                        Icon(
                            imageVector = it,
                            contentDescription = text,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = text,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-2).dp, y = 2.dp)
                .size(22.dp)
                .background(
                    color = Color(0xFF616161),
                    shape = CircleShape
                )
                .clip(CircleShape)
                .clickable(onClick = onRemoveClicked),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(com.android.everytalk.R.drawable.ic_close_bold),
                contentDescription = stringResource(R.string.attachment_remove),
                tint = Color.White,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/**
 * 优化的图像选择面板组件 - 减少动画时间和记忆化回调
 */
@Composable
fun OptimizedImageSelectionPanel(
    modifier: Modifier = Modifier,
    onOptionSelected: (ImageSourceOption) -> Unit
) {
    var activeOption by remember { mutableStateOf<ImageSourceOption?>(null) }
    val panelBackgroundColor = Color.Transparent
    val darkerBackgroundColor = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = modifier
            .width(150.dp)
            .wrapContentHeight(),
    ) {
        ImageSourceOption.values().forEach { option ->
            val isSelected = activeOption == option
            val animatedBackgroundColor by animateColorAsState(
                targetValue = if (isSelected) darkerBackgroundColor else panelBackgroundColor,
                animationSpec = tween(durationMillis = 150), // 减少动画时间
                label = "ImageOptionPanelItemBackground"
            )

            // 记忆化点击回调
            val onClickCallback = remember(option) {
                {
                    activeOption = option
                    onOptionSelected(option)
                    Unit
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClickCallback)
                    .background(animatedBackgroundColor)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 为每个选项设置不同颜色
                val iconTint = when (option) {
                    ImageSourceOption.ALBUM -> Color(0xff2cb334)  // 绿色
                    ImageSourceOption.CAMERA -> Color(0xff2196F3) // 蓝色
                }
                Icon(
                    imageVector = option.icon,
                    contentDescription = stringResource(option.labelRes),
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(text = stringResource(option.labelRes), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
            }
        }
    }
}

/**
 * 优化的更多选项面板组件 - 减少动画时间和记忆化回调
 */
@Composable
fun OptimizedMoreOptionsPanel(
    modifier: Modifier = Modifier,
    isMcpEnabled: Boolean = false,
    onOptionSelected: (MoreOptionsType) -> Unit
) {
    var activeOption by remember { mutableStateOf<MoreOptionsType?>(null) }
    val panelBackgroundColor = Color.Transparent
    val darkerBackgroundColor = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = modifier
            .width(150.dp)
            .wrapContentHeight(),
    ) {
        MoreOptionsType.values().forEach { option ->
            val isSelected = activeOption == option
            val shouldHighlightBackground = option != MoreOptionsType.MCP && isSelected
            val animatedBackgroundColor by animateColorAsState(
                targetValue = if (shouldHighlightBackground) darkerBackgroundColor else panelBackgroundColor,
                animationSpec = tween(durationMillis = 150), // 减少动画时间
                label = "MoreOptionPanelItemBackground"
            )
            var isPressed by remember(option) { mutableStateOf(false) }
            val scale by animateFloatAsState(
                targetValue = if (isPressed) 0.92f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "MoreOptionPanelItemScale"
            )

            // 记忆化点击回调
            val onClickCallback = remember(option) {
                {
                    activeOption = option
                    isPressed = true
                    onOptionSelected(option)
                    Unit
                }
            }

            Surface(
                onClick = onClickCallback,
                shape = RoundedCornerShape(12.dp),
                color = animatedBackgroundColor,
                modifier = Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val iconTint = when (option) {
                        MoreOptionsType.ATTACHMENT -> Color(0xff607D8B)
                        MoreOptionsType.MCP -> if (isMcpEnabled) Color(0xff9C27B0) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                    Icon(
                        imageVector = option.icon,
                        contentDescription = stringResource(option.labelRes),
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(text = stringResource(option.labelRes), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                }
            }

            LaunchedEffect(isPressed) {
                if (isPressed) {
                    kotlinx.coroutines.delay(150)
                    isPressed = false
                }
            }
        }
    }
}

// noRippleClickable 函数在 ChatInputArea.kt 中定义，避免重复
@Composable
fun OptimizedMediaItemsList(
    selectedMediaItems: List<SelectedMediaItem>,
    onRemoveMediaItemAtIndex: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (selectedMediaItems.isNotEmpty()) {
        LazyRow(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = selectedMediaItems,
                key = { _, item -> item.id } // 使用稳定的key
            ) { index, media ->
                OptimizedSelectedItemPreview(
                    mediaItem = media,
                    onRemoveClicked = { onRemoveMediaItemAtIndex(index) }
                )
            }
        }
    }
}
