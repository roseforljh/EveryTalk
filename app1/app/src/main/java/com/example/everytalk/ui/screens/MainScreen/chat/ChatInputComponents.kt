package com.example.everytalk.ui.screens.MainScreen.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.everytalk.models.ImageSourceOption
import com.example.everytalk.models.MoreOptionsType
import com.example.everytalk.models.SelectedMediaItem
import com.example.everytalk.ui.theme.SeaBlue

/**
 * 优化的媒体项预览组件 - 使用记忆化避免重复渲染
 */
@Composable
fun OptimizedSelectedItemPreview(
    mediaItem: SelectedMediaItem,
    onRemoveClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 记忆化图标和文本，避免重复计算
    val (icon, text) = remember(mediaItem) {
        when (mediaItem) {
            is SelectedMediaItem.GenericFile -> {
                val iconRes = when (mediaItem.mimeType) {
                    "application/pdf" -> Icons.Outlined.PictureAsPdf
                    "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> Icons.Outlined.Description
                    "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> Icons.Outlined.TableChart
                    "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> Icons.Outlined.Slideshow
                    "application/zip", "application/x-rar-compressed" -> Icons.Outlined.Archive
                    else -> when {
                        mediaItem.mimeType?.startsWith("video/") == true -> Icons.Outlined.Videocam
                        mediaItem.mimeType?.startsWith("audio/") == true -> Icons.Outlined.Audiotrack
                        mediaItem.mimeType?.startsWith("image/") == true -> Icons.Outlined.Image
                        else -> Icons.Outlined.AttachFile
                    }
                }
                iconRes to mediaItem.displayName
            }
            is SelectedMediaItem.Audio -> Icons.Outlined.Audiotrack to "Audio"
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
                contentDescription = "Selected image from gallery",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            is SelectedMediaItem.ImageFromBitmap -> AsyncImage(
                model = mediaItem.bitmap,
                contentDescription = "Selected image from camera",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            is SelectedMediaItem.GenericFile, is SelectedMediaItem.Audio -> {
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
        
        IconButton(
            onClick = onRemoveClicked,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(20.dp)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f), CircleShape)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove item",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp)
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
    val panelBackgroundColor = MaterialTheme.colorScheme.surfaceDim
    val darkerBackgroundColor = MaterialTheme.colorScheme.surfaceVariant

    Surface(
        modifier = modifier
            .width(150.dp)
            .wrapContentHeight(),
        shape = RoundedCornerShape(20.dp),
        color = panelBackgroundColor
    ) {
        Column {
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
                    Icon(
                        imageVector = option.icon,
                        contentDescription = option.label,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(text = option.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                }
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
    onOptionSelected: (MoreOptionsType) -> Unit
) {
    var activeOption by remember { mutableStateOf<MoreOptionsType?>(null) }
    val panelBackgroundColor = MaterialTheme.colorScheme.surfaceDim
    val darkerBackgroundColor = MaterialTheme.colorScheme.surfaceVariant

    Surface(
        modifier = modifier
            .width(150.dp)
            .wrapContentHeight(),
        shape = RoundedCornerShape(20.dp),
        color = panelBackgroundColor
    ) {
        Column {
            MoreOptionsType.values().forEach { option ->
                val isSelected = activeOption == option
                val animatedBackgroundColor by animateColorAsState(
                    targetValue = if (isSelected) darkerBackgroundColor else panelBackgroundColor,
                    animationSpec = tween(durationMillis = 150), // 减少动画时间
                    label = "MoreOptionPanelItemBackground"
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
                    Icon(
                        imageVector = option.icon,
                        contentDescription = option.label,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(text = option.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
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

/**
 * 优化的控制按钮行组件 - 记忆化所有回调
 */
@Composable
fun OptimizedControlButtonsRow(
    isWebSearchEnabled: Boolean,
    onToggleWebSearch: () -> Unit,
    onToggleImagePanel: () -> Unit,
    onToggleMoreOptionsPanel: () -> Unit,
    showImageSelectionPanel: Boolean,
    showMoreOptionsPanel: Boolean,
    text: String,
    selectedMediaItems: List<SelectedMediaItem>,
    onClearContent: () -> Unit,
    onSendClick: () -> Unit,
    isApiCalling: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleWebSearch) {
                Icon(
                    if (isWebSearchEnabled) Icons.Outlined.TravelExplore else Icons.Filled.Language,
                    if (isWebSearchEnabled) "网页搜索已开启" else "网页搜索已关闭",
                    tint = SeaBlue,
                    modifier = Modifier.size(25.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onToggleImagePanel) {
                Icon(
                    Icons.Outlined.Image,
                    if (showImageSelectionPanel) "关闭图片选项" else "选择图片",
                    tint = Color(0xff2cb334)
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onToggleMoreOptionsPanel) {
                Icon(
                    Icons.Filled.Tune,
                    if (showMoreOptionsPanel) "关闭更多选项" else "更多选项",
                    tint = Color(0xfff76213)
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (text.isNotEmpty() || selectedMediaItems.isNotEmpty()) {
                IconButton(onClick = onClearContent) {
                    Icon(
                        Icons.Filled.Clear,
                        "清除内容和所选项目",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            FilledIconButton(
                onClick = onSendClick,
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    if (isApiCalling) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                    if (isApiCalling) "停止" else "发送"
                )
            }
        }
    }
}