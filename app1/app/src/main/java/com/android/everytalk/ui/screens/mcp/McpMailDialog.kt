package com.android.everytalk.ui.screens.mcp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.android.everytalk.R
import com.android.everytalk.data.mcp.McpMailInput
import com.android.everytalk.data.mcp.McpMailProvider
import com.android.everytalk.ui.components.dialog.AppDialogButtonShape
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppDialogTextFieldShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.screens.settings.DialogTextFieldColors

/** 密码字段仅存活于当前弹窗，不使用 rememberSaveable，避免进入系统保存状态。 */
@Composable
internal fun McpMailDialog(provider: McpMailProvider, endpoint: String?, onSave: (McpMailInput) -> Unit, onDismiss: () -> Unit) {
    var gateway by remember { mutableStateOf(endpoint?.removeSuffix("/mcp/mail").orEmpty()) }
    var key by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val dialogBackground = appDialogContainerColor()
    val contentColor = appDialogContentColor()
    val borderColor = appDialogBorderColor()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, borderColor, AppDialogShape),
        shape = AppDialogShape,
        containerColor = dialogBackground,
        titleContentColor = contentColor,
        textContentColor = contentColor,
        title = {
            Text(
                text = provider.displayName,
                fontSize = MaterialTheme.typography.titleLarge.fontSize,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = stringResource(R.string.mcp_mail_setup_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = gateway,
                        onValueChange = { gateway = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.mcp_mail_gateway)) },
                        shape = AppDialogTextFieldShape,
                        colors = DialogTextFieldColors,
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.mcp_mail_gateway_key)) },
                        visualTransformation = PasswordVisualTransformation(),
                        shape = AppDialogTextFieldShape,
                        colors = DialogTextFieldColors,
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.mcp_mail_address)) },
                        supportingText = { Text(provider.domains.joinToString(" / ")) },
                        shape = AppDialogTextFieldShape,
                        colors = DialogTextFieldColors,
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.mcp_mail_code)) },
                        visualTransformation = PasswordVisualTransformation(),
                        shape = AppDialogTextFieldShape,
                        colors = DialogTextFieldColors,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = listOf(gateway, key, email, code).all { it.isNotBlank() },
                onClick = {
                    onSave(McpMailInput(gateway.trim(), key, email.trim(), code.trim()))
                },
                shape = AppDialogButtonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = contentColor,
                    contentColor = dialogBackground,
                    disabledContainerColor = borderColor,
                    disabledContentColor = contentColor.copy(alpha = 0.4f),
                ),
            ) {
                Text(stringResource(R.string.mcp_mail_connect), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = AppDialogButtonShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = dialogBackground,
                    contentColor = contentColor,
                ),
                border = BorderStroke(1.dp, borderColor),
            ) {
                Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
