package com.android.everytalk.ui.screens.MainScreen
import com.android.everytalk.statecontroller.*

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.painterResource
import com.android.everytalk.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.ui.screens.MainScreen.drawer.* // 导入抽屉子包下的所有内容
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import com.android.everytalk.ui.components.dialog.AppDialogButtonShape
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppDialogTextFieldShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogCancelColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldColors

@Composable
fun CollapsibleGroupHeader(
    groupName: String,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
    onRename: ((String) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    isPinnedGroup: Boolean = false
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(groupName) }
    var showMenu by remember { mutableStateOf(false) }

    // 为箭头图标添加旋转动画
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "arrowRotation"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        onClick = onToggleExpand,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_end),
                contentDescription = if (isExpanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        rotationZ = arrowRotation
                    }
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = groupName,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            
            // 只有非置顶分组才显示菜单按钮
            if (!isPinnedGroup && (onRename != null || onDelete != null)) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_dots_vertical),
                            "更多选项",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if (onRename != null) {
                            DropdownMenuItem(
                                text = { Text("重命名") },
                                onClick = {
                                    showMenu = false
                                    showRenameDialog = true
                                },
                                leadingIcon = {
                                    Icon(painter = painterResource(R.drawable.ic_pencil), "重命名")
                                }
                            )
                        }
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text("删除") },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(painter = painterResource(R.drawable.ic_trash), "删除")
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    if (showRenameDialog && onRename != null) {
        val dialogBg = appDialogContainerColor()
        val contentColor = appDialogContentColor()
        val cancelButtonColor = appDialogCancelColor()
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
            shape = AppDialogShape,
            containerColor = dialogBg,
            titleContentColor = contentColor,
            textContentColor = contentColor,
            title = { Text("重命名分组") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("新名称") },
                    shape = AppDialogTextFieldShape,
                    colors = appDialogTextFieldColors()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onRename(newName)
                        }
                        showRenameDialog = false
                    },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = contentColor,
                        contentColor = dialogBg
                    )
                ) {
                    Text("重命名")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showRenameDialog = false },
                    shape = AppDialogButtonShape,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = dialogBg,
                        contentColor = cancelButtonColor
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, cancelButtonColor)
                ) {
                    Text("取消")
                }
            }
        )
    }
}

