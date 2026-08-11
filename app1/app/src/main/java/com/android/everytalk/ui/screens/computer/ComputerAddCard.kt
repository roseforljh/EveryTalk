package com.android.everytalk.ui.screens.computer

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.android.everytalk.R
import com.android.everytalk.data.computer.ComputerAuthKind
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.data.computer.HostKeyProbeResult
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor

/** 复用设置页悬浮卡片容器的服务器表单内容。 */
@Composable
internal fun ComputerAddCard(
    form: ComputerAddFormState,
    isBusy: Boolean,
    progressText: String?,
    errorText: String?,
    onFormChange: (ComputerAddFormState) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp - 120.dp).coerceAtLeast(320.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.computer_add_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, enabled = !isBusy) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.action_cancel),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        ComputerTextField(
            value = form.displayName,
            onValueChange = { onFormChange(form.copy(displayName = it)) },
            label = stringResource(R.string.computer_field_name),
            enabled = !isBusy,
        )
        ComputerTextField(
            value = form.host,
            onValueChange = { onFormChange(form.copy(host = it)) },
            label = stringResource(R.string.computer_field_host),
            enabled = !isBusy,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ComputerTextField(
                value = form.port,
                onValueChange = { value ->
                    if (value.all(Char::isDigit)) onFormChange(form.copy(port = value))
                },
                label = stringResource(R.string.computer_field_port),
                enabled = !isBusy,
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(0.36f),
            )
            ComputerTextField(
                value = form.username,
                onValueChange = { onFormChange(form.copy(username = it)) },
                label = stringResource(R.string.computer_field_username),
                enabled = !isBusy,
                modifier = Modifier.weight(0.64f),
            )
        }

        SectionLabel(stringResource(R.string.computer_field_auth))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = form.authKind == ComputerAuthKind.PASSWORD,
                onClick = { onFormChange(form.copy(authKind = ComputerAuthKind.PASSWORD)) },
                label = { Text(stringResource(R.string.computer_auth_password)) },
                enabled = !isBusy,
            )
            FilterChip(
                selected = form.authKind == ComputerAuthKind.PRIVATE_KEY,
                onClick = { onFormChange(form.copy(authKind = ComputerAuthKind.PRIVATE_KEY)) },
                label = { Text(stringResource(R.string.computer_auth_private_key)) },
                enabled = !isBusy,
            )
        }

        if (form.authKind == ComputerAuthKind.PASSWORD) {
            ComputerTextField(
                value = form.password,
                onValueChange = { onFormChange(form.copy(password = it)) },
                label = stringResource(R.string.computer_field_password),
                enabled = !isBusy,
                isPassword = true,
            )
        } else {
            ComputerTextField(
                value = form.privateKey,
                onValueChange = { onFormChange(form.copy(privateKey = it)) },
                label = stringResource(R.string.computer_field_private_key),
                enabled = !isBusy,
                minLines = 4,
                maxLines = 8,
            )
            ComputerTextField(
                value = form.privateKeyPassphrase,
                onValueChange = { onFormChange(form.copy(privateKeyPassphrase = it)) },
                label = stringResource(R.string.computer_field_private_key_passphrase),
                enabled = !isBusy,
                isPassword = true,
            )
        }

        SectionLabel(stringResource(R.string.computer_field_mode))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = form.runMode == ComputerRunMode.CONTAINER,
                onClick = { onFormChange(form.copy(runMode = ComputerRunMode.CONTAINER)) },
                label = { Text(stringResource(R.string.computer_mode_container)) },
                enabled = !isBusy,
            )
            FilterChip(
                selected = form.runMode == ComputerRunMode.DIRECT,
                onClick = { onFormChange(form.copy(runMode = ComputerRunMode.DIRECT)) },
                label = { Text(stringResource(R.string.computer_mode_direct)) },
                enabled = !isBusy,
            )
        }
        Text(
            text = stringResource(
                if (form.runMode == ComputerRunMode.CONTAINER) {
                    R.string.computer_mode_container_description
                } else {
                    R.string.computer_mode_direct_description
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (form.runMode == ComputerRunMode.CONTAINER && form.username.trim() != "root") {
            ComputerTextField(
                value = form.sudoPassword,
                onValueChange = { onFormChange(form.copy(sudoPassword = it)) },
                label = stringResource(R.string.computer_field_sudo_password),
                enabled = !isBusy,
                isPassword = true,
            )
        }

        if (progressText != null) {
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(progressText, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (errorText != null) {
            Text(
                text = errorText,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onSubmit,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.computer_add_save))
        }
    }
}

@Composable
private fun ComputerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.padding(top = 8.dp),
        enabled = enabled,
        singleLine = maxLines == 1,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
        ),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
    )
}

@Composable
internal fun ComputerHostKeyDialog(
    hostKey: HostKeyProbeResult?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (hostKey == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        title = { Text(stringResource(R.string.computer_host_key_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.computer_host_key_body))
                Text(stringResource(R.string.computer_host_key_endpoint, hostKey.host, hostKey.port))
                Text(hostKey.fingerprint, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.computer_host_key_algorithm, hostKey.algorithm))
                Text(stringResource(R.string.computer_host_key_address, hostKey.resolvedAddress))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.computer_host_key_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
