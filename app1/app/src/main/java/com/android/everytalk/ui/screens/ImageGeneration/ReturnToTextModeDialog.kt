package com.android.everytalk.ui.screens.ImageGeneration

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.everytalk.ui.components.dialog.AppDialogButtonShape
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogCancelColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor

@Composable
fun ReturnToTextModeDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    val dialogBg = appDialogContainerColor()
    val contentColor = appDialogContentColor()
    val cancelButtonColor = appDialogCancelColor()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = dialogBg,
        titleContentColor = contentColor,
        textContentColor = contentColor,
        title = { Text("杩斿洖鏂囨湰妯″紡") },
        text = { Text("鎮ㄧ‘瀹氳杩斿洖鏂囨湰妯″紡鍚楋紵") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.height(48.dp).padding(horizontal = 4.dp),
                shape = AppDialogButtonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = contentColor,
                    contentColor = dialogBg
                )
            ) {
                Text("纭畾", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismissRequest,
                modifier = Modifier.height(48.dp).padding(horizontal = 4.dp),
                shape = AppDialogButtonShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = dialogBg,
                    contentColor = cancelButtonColor
                ),
                border = BorderStroke(1.dp, cancelButtonColor)
            ) {
                Text("鍙栨秷", fontWeight = FontWeight.SemiBold)
            }
        }
    )
}
