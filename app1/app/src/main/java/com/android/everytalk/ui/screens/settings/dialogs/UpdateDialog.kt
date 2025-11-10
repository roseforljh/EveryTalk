package com.android.everytalk.ui.screens.settings.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.everytalk.data.DataClass.VersionUpdateInfo

/**
 * 版本更新对话框
 * 
 * @param updateInfo 版本更新信息
 * @param onUpdateNow 点击"立即更新"按钮的回调
 * @param onUpdateLater 点击"稍后更新"按钮的回调（仅在非强制更新时显示）
 */
@Composable
fun UpdateDialog(
    updateInfo: VersionUpdateInfo,
    onUpdateNow: () -> Unit,
    onUpdateLater: () -> Unit
) {
    Dialog(
        onDismissRequest = {
            // 如果是强制更新，不允许通过点击外部关闭对话框
            if (!updateInfo.isForceUpdate) {
                onUpdateLater()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !updateInfo.isForceUpdate,
            dismissOnClickOutside = !updateInfo.isForceUpdate
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 图标
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = "更新",
                    modifier = Modifier.size(64.dp),
                    tint = if (updateInfo.isForceUpdate) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 标题
                Text(
                    text = if (updateInfo.isForceUpdate) "发现重要更新" else "发现新版本",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (updateInfo.isForceUpdate) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 版本信息
                Text(
                    text = "当前版本: ${updateInfo.currentVersion}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Text(
                    text = "最新版本: ${updateInfo.latestVersion}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                // 强制更新提示
                if (updateInfo.isForceUpdate) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "⚠️ 您的版本过旧，必须更新才能继续使用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                // 更新说明
                if (!updateInfo.releaseNotes.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "更新内容:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 可滚动的更新说明
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            text = updateInfo.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (updateInfo.isForceUpdate) {
                        Arrangement.Center
                    } else {
                        Arrangement.spacedBy(12.dp)
                    }
                ) {
                    // 稍后更新按钮（仅在非强制更新时显示）
                    if (!updateInfo.isForceUpdate) {
                        OutlinedButton(
                            onClick = onUpdateLater,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("稍后更新")
                        }
                    }
                    
                    // 立即更新按钮
                    Button(
                        onClick = onUpdateNow,
                        modifier = if (updateInfo.isForceUpdate) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier.weight(1f)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (updateInfo.isForceUpdate) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    ) {
                        Text(
                            text = if (updateInfo.isForceUpdate) "立即更新" else "立即更新",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}