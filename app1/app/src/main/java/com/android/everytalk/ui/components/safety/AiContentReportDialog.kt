package com.android.everytalk.ui.components.safety

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.everytalk.data.safety.AiContentReportCategory
import com.android.everytalk.data.safety.AiContentReportRepository
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogSubtextColor

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

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = appDialogContainerColor(),
        title = {
            Text(
                text = "举报 AI 内容",
                color = textColor,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "请选择最符合的问题。举报会在应用内记录，并包含相关回复摘要、模型和服务商名称；不会发送 API 密钥或整段对话。",
                    color = subtextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                AiContentReportCategory.entries.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedCategoryName = category.name }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedCategory == category,
                            onClick = { selectedCategoryName = category.name },
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
                    label = { Text("补充说明（可选）") },
                    supportingText = {
                        Text("${details.length}/${AiContentReportRepository.MAX_DETAILS_CHARS}")
                    },
                    minLines = 3,
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedCategory != null,
                onClick = {
                    selectedCategory?.let { category -> onSubmit(category, details.trim()) }
                },
            ) {
                Text("提交举报")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = textColor)
            }
        },
    )
}
