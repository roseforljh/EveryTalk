package com.android.everytalk.ui.components.safety

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.android.everytalk.R
import com.android.everytalk.data.safety.AiContentReportCategory
import com.android.everytalk.data.safety.AiContentReportRepository
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppDialogTextFieldShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogSubtextColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldColors

private val ReportReasonShape = RoundedCornerShape(16.dp)

@Composable
fun AiContentReportMenuItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionColor = MaterialTheme.colorScheme.error

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_report_ai_content),
                contentDescription = null,
                tint = actionColor,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "举报 AI 内容",
            color = actionColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun AiContentReportDialog(
    onDismiss: () -> Unit,
    onSubmit: (AiContentReportCategory, String) -> Unit,
) {
    var selectedCategoryName by rememberSaveable { mutableStateOf<String?>(null) }
    var details by rememberSaveable { mutableStateOf("") }
    val selectedCategory = AiContentReportCategory.entries
        .firstOrNull { it.name == selectedCategoryName }
    val textColor = appDialogContentColor()
    val subtextColor = appDialogSubtextColor()
    val accentColor = MaterialTheme.colorScheme.primary
    val reasonBorderColor = appDialogBorderColor()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = appDialogContainerColor(),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_report_ai_content),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = "举报 AI 内容",
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "选择原因类别，并按需补充具体原因。举报包含相关回复摘要、模型和服务商名称，不会发送 API 密钥或整段对话。",
                    color = subtextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                AiContentReportCategory.entries.forEach { category ->
                    val selected = selectedCategory == category
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ReportReasonShape)
                            .background(
                                if (selected) accentColor.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent
                            )
                            .border(
                                width = 1.dp,
                                color = if (selected) accentColor.copy(alpha = 0.45f) else reasonBorderColor,
                                shape = ReportReasonShape,
                            )
                            .clickable(role = Role.RadioButton) {
                                selectedCategoryName = category.name
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { selectedCategoryName = category.name },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = accentColor,
                                unselectedColor = subtextColor,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = category.displayName,
                                color = textColor,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = category.description,
                                color = subtextColor,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = details,
                    onValueChange = {
                        details = it.take(AiContentReportRepository.MAX_DETAILS_CHARS)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    label = { Text("具体原因（可选）") },
                    placeholder = { Text("请补充说明这条内容存在的问题") },
                    supportingText = {
                        Text(
                            text = "${details.length}/${AiContentReportRepository.MAX_DETAILS_CHARS}",
                            color = subtextColor,
                        )
                    },
                    shape = AppDialogTextFieldShape,
                    colors = appDialogTextFieldColors(),
                    minLines = 3,
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedCategory != null,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = accentColor,
                    disabledContentColor = subtextColor.copy(alpha = 0.45f),
                ),
                onClick = {
                    selectedCategory?.let { category -> onSubmit(category, details.trim()) }
                },
            ) {
                Text("提交举报", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = textColor)
            }
        },
    )
}
