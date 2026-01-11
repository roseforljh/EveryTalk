package com.android.everytalk.ui.screens.MainScreen.drawer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch

/**
 * 对话列表项长按后显示的上下文菜单。
 * @param expanded 是否显示菜单。
 * @param onDismissRequest 当请求关闭菜单时调用 (例如点击外部或按返回键)。
 * @param onRenameClick 当点击“重命名”时调用。
 * @param onDeleteClick 当点击“删除”时调用。
 * @param popupPositionProvider 用于计算菜单位置的提供者。
 */
@Composable
internal fun ConversationItemMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onTogglePinClick: () -> Unit,
    isPinned: Boolean,
    popupPositionProvider: PopupPositionProvider,
    isRenameEnabled: Boolean = true, // 默认重命名可用
    groups: List<String>,
    onMoveToGroup: (String?) -> Unit,
    onMoveToGroupClick: () -> Unit,
    onShareClick: () -> Unit = {} // 新增：分享回调
) {
    if (expanded) {
        Popup(
            popupPositionProvider = popupPositionProvider,
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = false)
        ) {
            val alpha = remember { Animatable(0f) }
            val scale = remember { Animatable(0.8f) }

            LaunchedEffect(Unit) {
                launch {
                    alpha.animateTo(1f, animationSpec = tween(durationMillis = 300))
                }
                launch {
                    scale.animateTo(1f, animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing))
                }
            }

            Box(
                modifier = Modifier
                    .graphicsLayer {
                        this.alpha = alpha.value
                        this.scaleX = scale.value
                        this.scaleY = scale.value
                    }
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceDim,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.width(IntrinsicSize.Max)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        MenuItem(
                            text = if (isPinned) "取消置顶" else "置顶",
                            icon = Icons.Filled.PushPin,
                            onClick = {
                                onTogglePinClick()
                                onDismissRequest()
                            }
                        )
                        MenuItem(
                            text = "重命名",
                            icon = Icons.Filled.DriveFileRenameOutline,
                            enabled = isRenameEnabled,
                            onClick = {
                                if (isRenameEnabled) {
                                    onRenameClick()
                                    onDismissRequest()
                                }
                            }
                        )
                        MenuItem(
                            text = "移动到",
                            icon = Icons.Filled.Folder,
                            onClick = {
                                onMoveToGroupClick()
                                onDismissRequest()
                            }
                        )
                        MenuItem(
                            text = "分享",
                            icon = Icons.Filled.Share,
                            onClick = {
                                onShareClick()
                                onDismissRequest()
                            }
                        )
                        MenuItem(
                            text = "删除",
                            icon = Icons.Filled.Delete,
                            onClick = {
                                onDeleteClick()
                                onDismissRequest()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                onClick = onClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material3.ripple()
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
