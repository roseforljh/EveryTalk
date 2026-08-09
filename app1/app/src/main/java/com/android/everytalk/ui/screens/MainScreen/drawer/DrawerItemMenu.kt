package com.android.everytalk.ui.screens.MainScreen.drawer
import com.android.everytalk.statecontroller.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.R
import com.android.everytalk.ui.components.popup.AppFloatingCard

@Composable
internal fun ConversationItemMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onTogglePinClick: () -> Unit,
    isPinned: Boolean,
    popupPositionProvider: PopupPositionProvider,
    isRenameEnabled: Boolean = true,
    groups: List<String>,
    onMoveToGroup: (String?) -> Unit,
    onMoveToGroupClick: () -> Unit,
    onShareClick: () -> Unit = {}
) {
    if (!expanded) return

    val isDark = isSystemInDarkTheme()

    Popup(
        popupPositionProvider = popupPositionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        AppFloatingCard(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(min = 200.dp),
        ) {
            val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
            val deleteColor = Color(0xFFEF5350)
            val disabledColor = textColor.copy(alpha = 0.4f)

            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .padding(vertical = 12.dp)
            ) {
                DrawerMenuItem(
                    iconRes = R.drawable.ic_pin,
                    text = stringResource(if (isPinned) R.string.action_unpin else R.string.action_pin),
                    tint = textColor,
                    onClick = { onTogglePinClick(); onDismissRequest() }
                )
                DrawerMenuItem(
                    iconRes = R.drawable.ic_pencil,
                    text = stringResource(R.string.action_rename),
                    tint = if (isRenameEnabled) textColor else disabledColor,
                    onClick = {
                        if (isRenameEnabled) { onRenameClick(); onDismissRequest() }
                    }
                )
                DrawerMenuItem(
                    iconRes = R.drawable.ic_folder,
                    text = stringResource(R.string.drawer_move_to),
                    tint = textColor,
                    onClick = { onMoveToGroupClick(); onDismissRequest() }
                )
                DrawerMenuItem(
                    iconRes = R.drawable.ic_share,
                    text = stringResource(R.string.action_share),
                    tint = textColor,
                    onClick = { onShareClick(); onDismissRequest() }
                )
                DrawerMenuItem(
                    iconRes = R.drawable.ic_trash,
                    text = stringResource(R.string.action_delete),
                    tint = deleteColor,
                    onClick = { onDeleteClick(); onDismissRequest() }
                )
            }
        }
    }
}

@Composable
private fun DrawerMenuItem(
    iconRes: Int,
    text: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            color = tint,
            maxLines = 1
        )
    }
}
