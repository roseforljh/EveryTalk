package com.android.everytalk.ui.screens.MainScreen.drawer
import com.android.everytalk.statecontroller.*

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.android.everytalk.R
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.android.everytalk.ui.components.dialog.AppDialogButtonShape
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppDialogTextFieldShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogCancelColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldColors

/**
 * 删除确认对话框。
 * @param showDialog 是否显示对话框。
 * @param selectedItemCount 要删除的项的数量。
 * @param onDismiss 当请求关闭对话框时调用。
 * @param onConfirm 当确认删除时调用。
 */
@Composable
internal fun DeleteConfirmationDialog(
    showDialog: Boolean,
    selectedItemCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (showDialog) {
        val dialogBg = appDialogContainerColor()
        val contentColor = appDialogContentColor()
        val cancelButtonColor = appDialogCancelColor()
        val confirmButtonColor = contentColor
        val confirmButtonTextColor = dialogBg

        AlertDialog(
            modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
            shape = AppDialogShape,
            onDismissRequest = onDismiss,
            title = {
                Text(
                    stringResource(
                        when {
                            selectedItemCount > 1 -> R.string.drawer_delete_selected_multiple
                            selectedItemCount == 1 -> R.string.drawer_delete_selected_single
                            else -> R.string.drawer_delete_this_item
                        }
                    )
                )
            },
            // text = { Text("此操作无法撤销。") }, // 可选
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = dialogBg,
                            contentColor = cancelButtonColor
                        ),
                        border = BorderStroke(1.dp, cancelButtonColor)
                    ) {
                        Text(
                            text = stringResource(R.string.action_cancel),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }

                    Button(
                        onClick = {
                            onConfirm()
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = confirmButtonColor,
                            contentColor = confirmButtonTextColor,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.action_confirm),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            },
            dismissButton = {},
            containerColor = dialogBg,
            titleContentColor = contentColor,
            textContentColor = contentColor
        )
    }
}

/**
 * 清空所有记录确认对话框。
 * @param showDialog 是否显示对话框。
 * @param onDismiss 当请求关闭对话框时调用。
 * @param onConfirm 当确认清空所有记录时调用。
 */
@Composable
internal fun ClearAllConfirmationDialog(
    showDialog: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (showDialog) {
        val dialogBg = appDialogContainerColor()
        val contentColor = appDialogContentColor()
        val cancelButtonColor = appDialogCancelColor()
        val confirmButtonColor = contentColor
        val confirmButtonTextColor = dialogBg

        AlertDialog(
            modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
            shape = AppDialogShape,
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.drawer_clear_chats_title)) },
            text = { Text(stringResource(R.string.drawer_clear_chats_description)) },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = dialogBg,
                            contentColor = cancelButtonColor
                        ),
                        border = BorderStroke(1.dp, cancelButtonColor)
                    ) {
                        Text(
                            text = stringResource(R.string.action_cancel),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }

                    Button(
                        onClick = {
                            onConfirm()
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = confirmButtonColor,
                            contentColor = confirmButtonTextColor,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.drawer_confirm_clear),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            },
            dismissButton = {},
            containerColor = dialogBg,
            titleContentColor = contentColor,
            textContentColor = contentColor
        )
    }
}
@Composable
internal fun ClearImageHistoryConfirmationDialog(
   showDialog: Boolean,
   onDismiss: () -> Unit,
   onConfirm: () -> Unit
) {
   if (showDialog) {
       val dialogBg = appDialogContainerColor()
       val contentColor = appDialogContentColor()
       val cancelButtonColor = appDialogCancelColor()
       val confirmButtonColor = contentColor
       val confirmButtonTextColor = dialogBg

       AlertDialog(
           modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
           shape = AppDialogShape,
           onDismissRequest = onDismiss,
           title = { Text(stringResource(R.string.drawer_clear_image_history_title)) },
           text = { Text(stringResource(R.string.drawer_clear_image_history_description)) },
           confirmButton = {
               Row(
                   modifier = Modifier.fillMaxWidth(),
                   horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
               ) {
                   OutlinedButton(
                       onClick = onDismiss,
                       modifier = Modifier
                           .weight(1f)
                           .height(48.dp),
                       shape = RoundedCornerShape(24.dp),
                       colors = ButtonDefaults.outlinedButtonColors(
                           containerColor = dialogBg,
                           contentColor = cancelButtonColor
                       ),
                       border = BorderStroke(1.dp, cancelButtonColor)
                   ) {
                       Text(
                           text = stringResource(R.string.action_cancel),
                           style = MaterialTheme.typography.labelLarge.copy(
                               fontWeight = FontWeight.SemiBold
                           )
                       )
                   }

                   Button(
                       onClick = {
                           onConfirm()
                           onDismiss()
                       },
                       modifier = Modifier
                           .weight(1f)
                           .height(48.dp),
                       shape = RoundedCornerShape(24.dp),
                       colors = ButtonDefaults.buttonColors(
                           containerColor = confirmButtonColor,
                           contentColor = confirmButtonTextColor,
                           disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                           disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                       )
                   ) {
                       Text(
                           text = stringResource(R.string.drawer_confirm_clear),
                           style = MaterialTheme.typography.labelLarge.copy(
                               fontWeight = FontWeight.SemiBold
                           )
                       )
                   }
               }
           },
           dismissButton = {},
           containerColor = dialogBg,
           titleContentColor = contentColor,
           textContentColor = contentColor
       )
   }
}

@Composable
internal fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    val dialogBg = appDialogContainerColor()
    val contentColor = appDialogContentColor()
    val cancelButtonColor = appDialogCancelColor()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = dialogBg,
        titleContentColor = contentColor,
        textContentColor = contentColor,
        title = { Text(stringResource(R.string.drawer_create_group_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text(stringResource(R.string.drawer_group_name_label)) },
                singleLine = true,
                shape = AppDialogTextFieldShape,
                modifier = Modifier.fillMaxWidth(),
                colors = appDialogTextFieldColors()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (groupName.isNotBlank()) {
                        onConfirm(groupName)
                    }
                    onDismiss()
                },
                shape = AppDialogButtonShape,
                modifier = Modifier.height(48.dp).padding(horizontal = 4.dp),
                enabled = groupName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = contentColor,
                    contentColor = dialogBg,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Text(stringResource(R.string.action_create), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = AppDialogButtonShape,
                modifier = Modifier.height(48.dp).padding(horizontal = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = dialogBg,
                    contentColor = cancelButtonColor
                ),
                border = BorderStroke(1.dp, cancelButtonColor)
            ) {
                Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.Medium)
            }
        }
    )
}

@Composable
internal fun MoveToGroupDialog(
    groups: List<String>,
    isCurrentlyGrouped: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    val dialogBg = appDialogContainerColor()
    val contentColor = appDialogContentColor()
    val cancelButtonColor = appDialogCancelColor()
    val isDark = isSystemInDarkTheme()
    val itemBg = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF5F5F5)
    val itemBorder = if (isDark) Color(0xFF333333) else Color(0xFFE5E5E5)
    val iconTint = if (isDark) Color(0xFF9E9E9E) else Color(0xFF616161)
    val removeColor = if (isDark) Color(0xFFFF6B6B) else Color(0xFFE53935)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = dialogBg,
        titleContentColor = contentColor,
        textContentColor = contentColor,
        title = { Text(stringResource(R.string.drawer_move_to_group_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            if (groups.isEmpty() && !isCurrentlyGrouped) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.drawer_no_groups_available), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isCurrentlyGrouped) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(itemBg)
                                    .border(1.dp, itemBorder, RoundedCornerShape(14.dp))
                                    .clickable {
                                        onConfirm(null) // null indicates moving to ungrouped
                                        onDismiss()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_remove_circle),
                                    contentDescription = stringResource(R.string.drawer_remove_from_group),
                                    tint = removeColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.drawer_remove_from_group),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = removeColor,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    items(groups) { groupName ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(itemBg)
                                .border(1.dp, itemBorder, RoundedCornerShape(14.dp))
                                .clickable {
                                    onConfirm(groupName)
                                    onDismiss()
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_folder),
                                contentDescription = stringResource(R.string.drawer_group_content_description),
                                tint = iconTint,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = groupName,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = contentColor,
                                    fontWeight = FontWeight.Medium
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = AppDialogButtonShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = dialogBg,
                    contentColor = cancelButtonColor
                ),
                border = BorderStroke(1.dp, cancelButtonColor)
            ) {
                Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {}
    )
}
