package com.android.everytalk.ui.screens.MainScreen.drawer
import com.android.everytalk.statecontroller.*

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
    var showPopup by remember { mutableStateOf(false) }
    val scaleAnim = remember { Animatable(0.8f) }
    val alphaAnim = remember { Animatable(0f) }

    val emphasizedDecelerate = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val decelerateEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    LaunchedEffect(expanded) {
        if (expanded) {
            showPopup = true
            scaleAnim.snapTo(0.8f)
            alphaAnim.snapTo(0f)
            coroutineScope {
                launch { scaleAnim.animateTo(1f, tween(120, easing = emphasizedDecelerate)) }
                launch { alphaAnim.animateTo(1f, tween(30, easing = decelerateEasing)) }
            }
        } else if (showPopup) {
            coroutineScope {
                launch { alphaAnim.animateTo(0f, tween(75, easing = decelerateEasing)) }
                launch { kotlinx.coroutines.delay(74); scaleAnim.snapTo(0.8f) }
            }
            showPopup = false
        }
    }

    if (!showPopup) return

    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF212121) else Color(0xFFFFFFFF)
    val popupBorderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color(0xFF0D0D0D).copy(alpha = 0.05f)

    Popup(
        popupPositionProvider = popupPositionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(min = 200.dp)
                .graphicsLayer {
                    this.scaleX = scaleAnim.value
                    this.scaleY = scaleAnim.value
                    this.alpha = alphaAnim.value
                    this.transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .shadow(8.dp, RoundedCornerShape(28.dp))
                .border(1.dp, popupBorderColor, RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp),
            color = cardBg
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
                    text = if (isPinned) "取消置顶" else "置顶",
                    tint = textColor,
                    onClick = { onTogglePinClick(); onDismissRequest() }
                )
                DrawerMenuItem(
                    iconRes = R.drawable.ic_pencil,
                    text = "重命名",
                    tint = if (isRenameEnabled) textColor else disabledColor,
                    onClick = {
                        if (isRenameEnabled) { onRenameClick(); onDismissRequest() }
                    }
                )
                DrawerMenuItem(
                    iconRes = R.drawable.ic_folder,
                    text = "移动到",
                    tint = textColor,
                    onClick = { onMoveToGroupClick(); onDismissRequest() }
                )
                DrawerMenuItem(
                    iconRes = R.drawable.ic_share,
                    text = "分享",
                    tint = textColor,
                    onClick = { onShareClick(); onDismissRequest() }
                )
                DrawerMenuItem(
                    iconRes = R.drawable.ic_trash,
                    text = "删除",
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
