package com.android.everytalk.ui.components.dialog
import com.android.everytalk.statecontroller.*

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val AppDialogShape = RoundedCornerShape(28.dp)
val AppDialogButtonShape = RoundedCornerShape(24.dp)
val AppDialogTextFieldShape = RoundedCornerShape(16.dp)

@Composable
fun appDialogContainerColor(): Color = if (isSystemInDarkTheme()) Color.Black else Color.White

@Composable
fun appDialogBorderColor(): Color = if (isSystemInDarkTheme()) Color(0xFF414141) else Color(0xFFF3F3F3)

@Composable
fun appDialogContentColor(): Color = if (isSystemInDarkTheme()) Color.White else Color(0xFF0D0D0D)

@Composable
fun appDialogTextFieldBorderColor(): Color = if (isSystemInDarkTheme()) Color.White else Color.Black

@Composable
fun appDialogTextFieldDefaultBorderColor(): Color = appDialogTextFieldBorderColor().copy(alpha = 0.55f)

@Composable
fun appDialogSubtextColor(alpha: Float = 0.7f): Color = appDialogContentColor().copy(alpha = alpha)

@Composable
fun appDialogCancelColor(): Color = if (isSystemInDarkTheme()) Color(0xFFFF5252) else Color(0xFFD32F2F)

@Composable
fun appDialogTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = appDialogContentColor(),
    unfocusedTextColor = appDialogContentColor(),
    disabledTextColor = appDialogContentColor().copy(alpha = 0.6f),
    cursorColor = appDialogContentColor(),
    focusedBorderColor = appDialogTextFieldBorderColor(),
    unfocusedBorderColor = appDialogTextFieldDefaultBorderColor(),
    disabledBorderColor = appDialogTextFieldDefaultBorderColor().copy(alpha = 0.5f),
    focusedLabelColor = appDialogContentColor(),
    unfocusedLabelColor = appDialogSubtextColor(0.6f),
    disabledLabelColor = appDialogSubtextColor(0.4f),
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
