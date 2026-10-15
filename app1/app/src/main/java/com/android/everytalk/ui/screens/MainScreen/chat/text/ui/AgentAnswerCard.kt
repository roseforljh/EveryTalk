package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.everytalk.R
import com.android.everytalk.data.agent.PendingIntervention
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Agent 向用户提问时使用的内嵌回答卡片。
 * 回答和附件由调用方交给当前 AgentRun；卡片只负责输入状态和一次提交保护。
 */
@Composable
internal fun AgentAnswerCard(
    intervention: PendingIntervention,
    attachments: List<SelectedMediaItem>,
    onOpenAttachmentPicker: () -> Unit,
    onRemoveAttachmentAtIndex: (Int) -> Unit,
    onSubmit: suspend (String, List<SelectedMediaItem>) -> Boolean,
) {
    var answer by remember(intervention.suspensionId, intervention.rowVersion) { mutableStateOf("") }
    var submitting by remember(intervention.suspensionId) { mutableStateOf(false) }
    var submitError by remember(intervention.suspensionId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canSubmit = !submitting && (answer.isNotBlank() || attachments.isNotEmpty())
    val shape = RoundedCornerShape(24.dp)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .border(BorderStroke(1.dp, appDialogBorderColor()), shape),
        shape = shape,
        color = appDialogContainerColor(),
        shadowElevation = 10.dp,
    ) {
        Column(
            modifier = Modifier
                .heightIn(min = 0.dp, max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.agent_answer_question_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = intervention.reasonSafe,
                style = MaterialTheme.typography.bodyLarge,
            )
            intervention.userVisibleContext
                ?.takeIf(String::isNotBlank)
                ?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OptimizedMediaItemsList(
                selectedMediaItems = attachments,
                onRemoveMediaItemAtIndex = onRemoveAttachmentAtIndex,
            )
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = !submitting,
                minLines = 1,
                maxLines = 4,
                placeholder = { Text(stringResource(R.string.agent_answer_input_hint)) },
                shape = RoundedCornerShape(18.dp),
                colors = appDialogTextFieldColors(),
                leadingIcon = {
                    IconButton(
                        onClick = onOpenAttachmentPicker,
                        enabled = !submitting,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_plus),
                            contentDescription = stringResource(R.string.agent_answer_add_attachment),
                        )
                    }
                },
                trailingIcon = {
                    FilledIconButton(
                        onClick = {
                            if (!canSubmit) return@FilledIconButton
                            submitting = true
                            submitError = false
                            scope.launch {
                                try {
                                    if (onSubmit(answer, attachments)) answer = "" else submitError = true
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    submitError = true
                                } finally {
                                    submitting = false
                                }
                            }
                        },
                        enabled = canSubmit,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (canSubmit) MaterialTheme.colorScheme.primary else Color.Gray,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_up),
                            contentDescription = stringResource(R.string.agent_answer_send),
                        )
                    }
                },
            )
            if (submitError) {
                Text(
                    text = stringResource(R.string.agent_answer_submit_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
