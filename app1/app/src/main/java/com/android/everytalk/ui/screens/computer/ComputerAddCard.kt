package com.android.everytalk.ui.screens.computer

import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.android.everytalk.R
import com.android.everytalk.data.computer.ComputerAuthKind
import com.android.everytalk.data.computer.HostKeyProbeResult
import com.android.everytalk.ui.components.dialog.AppDialogButtonShape
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppDialogTextFieldShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldColors
import com.android.everytalk.ui.components.EveryTalkTimedLoadingStatus
import com.android.everytalk.ui.screens.settings.SettingsFieldLabel

/**
 * 添加服务器对话框。
 *
 * 容器、输入框和底部按钮直接沿用配置对话框的尺寸与颜色规则。
 * `imePadding` 会在输入法出现时压缩卡片可用高度，表单内容随后在卡片内滚动。
 */
@Composable
internal fun ComputerAddCard(
    form: ComputerAddFormState,
    isBusy: Boolean,
    progressText: String?,
    progressDetailText: String? = null,
    errorText: String?,
    onFormChange: (ComputerAddFormState) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.computer_add_title),
    submitLabel: String = stringResource(R.string.computer_add_save),
    keepCredentialHint: Boolean = false,
    allowBusyDismiss: Boolean = false,
) {
    val dialogBackground = appDialogContainerColor()
    val borderColor = appDialogBorderColor()
    val contentColor = appDialogContentColor()
    val choiceColors = FilterChipDefaults.filterChipColors(
        containerColor = dialogBackground,
        labelColor = contentColor,
        selectedContainerColor = contentColor,
        selectedLabelColor = dialogBackground,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
    )

    Dialog(
        onDismissRequest = { if (!isBusy || allowBusyDismiss) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialogWindow?.setDimAmount(0f)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (!isBusy || allowBusyDismiss) onDismiss() },
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(top = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .clip(AppDialogShape)
                    .border(1.dp, borderColor, AppDialogShape),
                shape = AppDialogShape,
                color = dialogBackground,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                ) {
                    Text(
                        text = title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                    )
                    Spacer(Modifier.height(18.dp))

                    ComputerFormSectionTitle(stringResource(R.string.computer_form_basic))

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
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ComputerTextField(
                            value = form.port,
                            onValueChange = { value ->
                                if (value.all(Char::isDigit)) onFormChange(form.copy(port = value))
                            },
                            label = stringResource(R.string.computer_field_port),
                            enabled = !isBusy,
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(0.4f),
                        )
                        ComputerTextField(
                            value = form.username,
                            onValueChange = { onFormChange(form.copy(username = it)) },
                            label = stringResource(R.string.computer_field_username),
                            enabled = !isBusy,
                            modifier = Modifier.weight(0.6f),
                        )
                    }

                    ComputerFormSectionTitle(
                        text = stringResource(R.string.computer_form_login),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    SettingsFieldLabel(stringResource(R.string.computer_field_auth))
                    FlowRow(
                        modifier = Modifier.padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            modifier = Modifier.height(40.dp),
                            selected = form.authKind == ComputerAuthKind.PASSWORD,
                            onClick = { onFormChange(form.copy(authKind = ComputerAuthKind.PASSWORD)) },
                            label = { Text(stringResource(R.string.computer_auth_password)) },
                            enabled = !isBusy,
                            shape = AppDialogTextFieldShape,
                            colors = choiceColors,
                        )
                        FilterChip(
                            modifier = Modifier.height(40.dp),
                            selected = form.authKind == ComputerAuthKind.PRIVATE_KEY,
                            onClick = { onFormChange(form.copy(authKind = ComputerAuthKind.PRIVATE_KEY)) },
                            label = { Text(stringResource(R.string.computer_auth_private_key)) },
                            enabled = !isBusy,
                            shape = AppDialogTextFieldShape,
                            colors = choiceColors,
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

                    if (keepCredentialHint) {
                        Text(
                            text = stringResource(R.string.computer_edit_secret_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }

                    if (!keepCredentialHint) {
                        Text(
                            text = stringResource(R.string.computer_mode_hybrid_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }

                    if (form.username.trim() != "root") {
                        ComputerTextField(
                            value = form.sudoPassword,
                            onValueChange = { onFormChange(form.copy(sudoPassword = it)) },
                            label = stringResource(R.string.computer_field_sudo_password),
                            enabled = !isBusy,
                            isPassword = true,
                            imeAction = ImeAction.Done,
                        )
                    }

                    if (progressText != null) {
                        Column(modifier = Modifier.padding(bottom = 12.dp)) {
                            EveryTalkTimedLoadingStatus(
                                text = progressText,
                                size = 20.dp,
                                showIndicator = false,
                                textStyle = MaterialTheme.typography.bodyMedium,
                            )
                            if (progressDetailText != null) {
                                Text(
                                    text = progressDetailText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    if (errorText != null) {
                        Text(
                            text = errorText,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            enabled = !isBusy || allowBusyDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = AppDialogButtonShape,
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                containerColor = dialogBackground,
                                contentColor = contentColor,
                            ),
                            border = BorderStroke(1.dp, borderColor),
                        ) {
                            Text(
                                text = stringResource(R.string.action_cancel),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        }
                        Button(
                            onClick = onSubmit,
                            enabled = !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = AppDialogButtonShape,
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = contentColor,
                                contentColor = dialogBackground,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            ),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = submitLabel,
                                    modifier = Modifier.alpha(if (isBusy) 0f else 1f),
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                )
                                if (isBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 表单分组只承担导航作用，避免继续用大卡片切碎对话框。 */
@Composable
private fun ComputerFormSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = appDialogContentColor(),
    )
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
    imeAction: ImeAction = ImeAction.Next,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        SettingsFieldLabel(label)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            enabled = enabled,
            singleLine = maxLines == 1,
            minLines = minLines,
            maxLines = maxLines,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
                imeAction = if (maxLines == 1) imeAction else ImeAction.Default,
            ),
            visualTransformation = if (isPassword && !passwordVisible) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            trailingIcon = if (isPassword) {
                {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            painter = painterResource(
                                if (passwordVisible) R.drawable.ic_eye else R.drawable.ic_eye_off,
                            ),
                            contentDescription = stringResource(
                                if (passwordVisible) R.string.settings_hide_key else R.string.settings_show_key,
                            ),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                null
            },
            shape = AppDialogTextFieldShape,
            colors = appDialogTextFieldColors(),
        )
    }
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
        containerColor = appDialogContainerColor(),
        titleContentColor = appDialogContentColor(),
        textContentColor = appDialogContentColor(),
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
