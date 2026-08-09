package com.android.everytalk.ui.screens.settings.dialogs
import com.android.everytalk.statecontroller.*

import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import com.android.everytalk.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AutoFetchModelsConfirmDialog(
    showDialog: Boolean,
    onDismiss: () -> Unit,
    onConfirmAutoFetch: () -> Unit,
    onManualInput: () -> Unit
) {
    if (!showDialog) return

    val isDark = isSystemInDarkTheme()
    val dialogBg = if (isDark) Color.Black else Color.White
    val borderColor = if (isDark) Color(0xFF414141) else Color(0xFFF3F3F3)
    val contentColor = if (isDark) Color.White else Color(0xFF0D0D0D)

    AlertDialog(
        modifier = Modifier.border(1.dp, borderColor, RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        onDismissRequest = onDismiss,
        containerColor = dialogBg,
        titleContentColor = contentColor,
        textContentColor = contentColor,
        title = {
            Text(
                stringResource(R.string.settings_fetch_models_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        },
        text = {
            Text(
                text = stringResource(R.string.settings_fetch_models_description),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.8f)
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmAutoFetch,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = contentColor,
                    contentColor = dialogBg
                )
            ) {
                Text(stringResource(R.string.settings_fetch_automatically), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onManualInput,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = contentColor
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
            ) {
                Text(stringResource(R.string.settings_enter_manually), fontWeight = FontWeight.SemiBold)
            }
        }
    )
}
