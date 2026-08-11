package com.android.everytalk.ui.screens.computer

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.android.everytalk.R
import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerPreview
import com.android.everytalk.data.computer.ComputerPreviewVisibility
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.data.computer.ComputerWorkspace
import com.android.everytalk.data.computer.ComputerWorkspaceSecret
import com.android.everytalk.data.computer.ComputerWorkspaceStatus
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.deleteComputerWorkspace
import com.android.everytalk.statecontroller.deleteComputerWorkspaceSecret
import com.android.everytalk.statecontroller.observeComputerPreviews
import com.android.everytalk.statecontroller.observeComputerWorkspaceSecrets
import com.android.everytalk.statecontroller.openComputerPrivatePreview
import com.android.everytalk.statecontroller.openComputerPublicPreview
import com.android.everytalk.statecontroller.saveComputerWorkspaceSecret
import com.android.everytalk.statecontroller.stopComputerPreview
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ComputerWorkspaceCard(
    viewModel: AppViewModel,
    computer: Computer,
    workspace: ComputerWorkspace,
    onMessage: (String) -> Unit,
) {
    val previewsFlow = remember(workspace.id) { viewModel.observeComputerPreviews(workspace.id) }
    val secretsFlow = remember(workspace.id) { viewModel.observeComputerWorkspaceSecrets(workspace.id) }
    val previews by previewsFlow.collectAsState(initial = emptyList())
    val secrets by secretsFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var secretDialogVisible by remember { mutableStateOf(false) }
    var previewVisibility by remember { mutableStateOf<ComputerPreviewVisibility?>(null) }
    var deleteDialogVisible by remember { mutableStateOf(false) }
    var previewUrl by remember { mutableStateOf<String?>(null) }
    var busyAction by remember { mutableStateOf<String?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    val actionFailedMessage = stringResource(R.string.computer_action_failed)
    val secretSavedMessage = stringResource(R.string.computer_secret_saved)
    val workspaceDeletedMessage = stringResource(R.string.computer_workspace_deleted)

    fun launchAction(action: String, block: suspend () -> Unit) {
        if (busyAction != null) return
        busyAction = action
        dialogError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (error: Throwable) {
                dialogError = error.message ?: actionFailedMessage
                onMessage(dialogError.orEmpty())
            } finally {
                busyAction = null
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .weight(1f),
                ) {
                    Text(
                        text = workspace.id,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.computer_workspace_mode_status,
                            workspaceModeLabel(workspace.runMode),
                            workspaceStatusLabel(workspace.status),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busyAction != null) {
                    CircularProgressIndicator(modifier = Modifier.height(22.dp), strokeWidth = 2.dp)
                }
            }

            ComputerDetailValue(
                label = stringResource(R.string.computer_workspace_path),
                value = workspace.hostPath,
            )
            ComputerDetailValue(
                label = stringResource(R.string.computer_workspace_last_used),
                value = formatComputerDate(workspace.lastUsedAt),
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { secretDialogVisible = true; dialogError = null },
                    enabled = busyAction == null,
                ) {
                    Text(stringResource(R.string.computer_secret_add))
                }
                OutlinedButton(
                    onClick = { previewVisibility = ComputerPreviewVisibility.PRIVATE; dialogError = null },
                    enabled = busyAction == null && workspace.status == ComputerWorkspaceStatus.READY,
                ) {
                    Text(stringResource(R.string.computer_preview_private_create))
                }
                OutlinedButton(
                    onClick = { previewVisibility = ComputerPreviewVisibility.PUBLIC; dialogError = null },
                    enabled = busyAction == null && workspace.status == ComputerWorkspaceStatus.READY,
                ) {
                    Text(stringResource(R.string.computer_preview_public_create))
                }
                TextButton(
                    onClick = { deleteDialogVisible = true; dialogError = null },
                    enabled = busyAction == null,
                ) {
                    Text(
                        text = stringResource(R.string.computer_workspace_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            HorizontalDivider()
            Text(
                text = stringResource(R.string.computer_secret_section),
                style = MaterialTheme.typography.titleSmall,
            )
            ComputerSecretList(
                secrets = secrets,
                enabled = busyAction == null,
                onDelete = { secret ->
                    launchAction("secret-delete") {
                        viewModel.deleteComputerWorkspaceSecret(workspace.id, secret.name)
                    }
                },
            )

            HorizontalDivider()
            Text(
                text = stringResource(R.string.computer_preview_section),
                style = MaterialTheme.typography.titleSmall,
            )
            if (
                computer.runMode == ComputerRunMode.DIRECT &&
                previews.any { it.visibility == ComputerPreviewVisibility.PUBLIC }
            ) {
                Text(
                    text = stringResource(R.string.computer_preview_direct_stop_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ComputerPreviewList(
                computer = computer,
                previews = previews,
                onOpen = { previewUrl = it },
                onStop = { preview ->
                    launchAction("preview-stop") { viewModel.stopComputerPreview(preview.id) }
                },
            )
        }
    }

    ComputerSecretEditorDialog(
        visible = secretDialogVisible,
        isBusy = busyAction == "secret-save",
        errorText = dialogError,
        onDismiss = { if (busyAction == null) secretDialogVisible = false },
        onSave = { name, value ->
            launchAction("secret-save") {
                viewModel.saveComputerWorkspaceSecret(workspace.id, name, value.toCharArray())
                withContext(Dispatchers.Main) {
                    secretDialogVisible = false
                    onMessage(secretSavedMessage)
                }
            }
        },
    )

    ComputerPreviewCreateDialog(
        visibility = previewVisibility,
        isBusy = busyAction == "preview-create",
        errorText = dialogError,
        onDismiss = { if (busyAction == null) previewVisibility = null },
        onCreate = { port, protocol, expiresInSeconds ->
            val currentVisibility = previewVisibility ?: return@ComputerPreviewCreateDialog
            launchAction("preview-create") {
                val result = if (currentVisibility == ComputerPreviewVisibility.PRIVATE) {
                    viewModel.openComputerPrivatePreview(workspace, port, protocol)
                } else {
                    viewModel.openComputerPublicPreview(workspace, port, protocol, expiresInSeconds)
                }
                withContext(Dispatchers.Main) {
                    previewVisibility = null
                    previewUrl = result.url
                }
            }
        },
    )

    ComputerWorkspaceDeleteDialog(
        workspace = workspace.takeIf { deleteDialogVisible },
        isBusy = busyAction == "workspace-delete",
        errorText = dialogError,
        onDismiss = { if (busyAction == null) deleteDialogVisible = false },
        onDelete = { deleteRemoteFiles ->
            launchAction("workspace-delete") {
                viewModel.deleteComputerWorkspace(workspace.id, deleteRemoteFiles)
                withContext(Dispatchers.Main) {
                    deleteDialogVisible = false
                    onMessage(workspaceDeletedMessage)
                }
            }
        },
    )

    ComputerUrlPreviewDialog(url = previewUrl, onDismiss = { previewUrl = null })
}

@Composable
private fun ComputerSecretList(
    secrets: List<ComputerWorkspaceSecret>,
    enabled: Boolean,
    onDelete: (ComputerWorkspaceSecret) -> Unit,
) {
    if (secrets.isEmpty()) {
        Text(
            text = stringResource(R.string.computer_secret_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        secrets.forEach { secret ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(secret.name, style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = stringResource(
                            R.string.computer_secret_updated,
                            formatComputerDate(secret.updatedAt),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onDelete(secret) }, enabled = enabled) {
                    Icon(
                        painter = painterResource(R.drawable.ic_trash),
                        contentDescription = stringResource(R.string.computer_secret_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComputerSecretEditorDialog(
    visible: Boolean,
    isBusy: Boolean,
    errorText: String?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    if (!visible) return
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    val invalidText = stringResource(R.string.computer_secret_invalid)
    var localError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        title = { Text(stringResource(R.string.computer_secret_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.computer_secret_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; localError = null },
                    label = { Text(stringResource(R.string.computer_secret_name)) },
                    singleLine = true,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; localError = null },
                    label = { Text(stringResource(R.string.computer_secret_value)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    ),
                    singleLine = true,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
                (localError ?: errorText)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !isBusy,
                onClick = {
                    if (name.isBlank() || value.isEmpty()) localError = invalidText else onSave(name.trim(), value)
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ComputerWorkspaceDeleteDialog(
    workspace: ComputerWorkspace?,
    isBusy: Boolean,
    errorText: String?,
    onDismiss: () -> Unit,
    onDelete: (Boolean) -> Unit,
) {
    if (workspace == null) return
    var deleteFiles by remember(workspace.id) { mutableStateOf(false) }
    var confirmedFiles by remember(workspace.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        title = { Text(stringResource(R.string.computer_workspace_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        if (workspace.runMode == ComputerRunMode.CONTAINER) {
                            R.string.computer_workspace_delete_container_body
                        } else {
                            R.string.computer_workspace_delete_direct_body
                        },
                    ),
                )
                Text(
                    text = workspace.hostPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = deleteFiles,
                        onCheckedChange = { deleteFiles = it; if (!it) confirmedFiles = false },
                        enabled = !isBusy,
                    )
                    Text(stringResource(R.string.computer_workspace_delete_files))
                }
                if (deleteFiles) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = confirmedFiles,
                            onCheckedChange = { confirmedFiles = it },
                            enabled = !isBusy,
                        )
                        Text(
                            text = stringResource(R.string.computer_workspace_delete_files_confirm),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                errorText?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDelete(deleteFiles) },
                enabled = !isBusy && (!deleteFiles || confirmedFiles),
            ) {
                Text(stringResource(R.string.computer_workspace_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun ComputerDetailValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.62f),
        )
    }
}

@Composable
private fun workspaceModeLabel(mode: ComputerRunMode): String = stringResource(
    if (mode == ComputerRunMode.CONTAINER) R.string.computer_mode_container else R.string.computer_mode_direct,
)

@Composable
private fun workspaceStatusLabel(status: ComputerWorkspaceStatus): String = stringResource(
    when (status) {
        ComputerWorkspaceStatus.CREATING -> R.string.computer_workspace_status_creating
        ComputerWorkspaceStatus.READY -> R.string.computer_workspace_status_ready
        ComputerWorkspaceStatus.STOPPED -> R.string.computer_workspace_status_stopped
        ComputerWorkspaceStatus.RECOVERING -> R.string.computer_workspace_status_recovering
        ComputerWorkspaceStatus.ERROR -> R.string.computer_workspace_status_error
        ComputerWorkspaceStatus.DELETING -> R.string.computer_workspace_status_deleting
        ComputerWorkspaceStatus.DELETED -> R.string.computer_workspace_status_deleted
    },
)

internal fun formatComputerDate(timestamp: Long?): String {
    if (timestamp == null) return "—"
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
}
