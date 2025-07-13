package com.example.everytalk.ui.screens.MainScreen.drawer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

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
    popupPositionProvider: PopupPositionProvider,
    isRenameEnabled: Boolean = true // 默认重命名可用
) {
    Popup(
        popupPositionProvider = popupPositionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = false)
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = tween(150)) + scaleIn(
                animationSpec = tween(150),
                initialScale = 0.8f,
                transformOrigin = TransformOrigin.Center
            ),
            exit = fadeOut(animationSpec = tween(100)) + scaleOut(
                animationSpec = tween(100),
                targetScale = 0.9f,
                transformOrigin = TransformOrigin.Center
            )
        ) {
            Surface(
                color = Color.White,
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 120.dp)
            ) {
                Column(
                    modifier = Modifier.padding(
                        vertical = 4.dp,
                        horizontal = 8.dp
                    )
                ) {
                    // 重命名选项
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                            .clickable(
                                enabled = isRenameEnabled,
                                onClick = {
                                    if (isRenameEnabled) {
                                        onRenameClick()
                                        onDismissRequest()
                                    }
                                },
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.DriveFileRenameOutline,
                            "重命名图标",
                            tint = if (isRenameEnabled) Color.Black else Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "重命名",
                            color = if (isRenameEnabled) Color.Black else Color.Gray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Divider(color = Color.LightGray.copy(alpha = 0.5f))
                    // 删除选项
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                            .clickable(
                                onClick = {
                                    onDeleteClick()
                                    onDismissRequest()
                                },
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            "删除图标",
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "删除",
                            color = Color.Black,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}