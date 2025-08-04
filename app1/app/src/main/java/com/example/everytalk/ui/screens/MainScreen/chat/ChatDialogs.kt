package com.example.everytalk.ui.screens.MainScreen.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMessageDialog(
    editDialogInputText: String,
    onDismissRequest: () -> Unit,
    onEditDialogTextChanged: (String) -> Unit,
    onConfirmMessageEdit: () -> Unit
) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.8f) }

    LaunchedEffect(Unit) {
        launch {
            alpha.animateTo(1f, animationSpec = tween(durationMillis = 300))
        }
        launch {
            scale.animateTo(1f, animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing))
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha.value
            this.scaleX = scale.value
            this.scaleY = scale.value
        },
        title = { Text("编辑消息", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            OutlinedTextField(
                value = editDialogInputText,
                onValueChange = onEditDialogTextChanged,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        text = "消息内容",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                ),
                singleLine = false, maxLines = 5,
                shape = RoundedCornerShape(8.dp)
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirmMessageEdit,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            ) { Text("取消") }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

