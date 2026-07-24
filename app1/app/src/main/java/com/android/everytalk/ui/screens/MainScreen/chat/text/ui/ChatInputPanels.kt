package com.android.everytalk.ui.screens.MainScreen.chat.text.ui
import com.android.everytalk.statecontroller.*

import kotlin.math.max
import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.res.painterResource
import com.android.everytalk.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.FileProvider
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.models.ImageSourceOption
import com.android.everytalk.models.MoreOptionsType
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.modifier.diffuseShadow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.android.everytalk.config.PerformanceConfig
import com.android.everytalk.data.mcp.McpServerState
import com.android.everytalk.data.mcp.McpServerConfig
import com.android.everytalk.ui.screens.mcp.McpServerListDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
internal fun FunctionPanelContent(
    isWebSearchEnabled: Boolean,
    isWebSearchAvailable: Boolean,
    onToggleWebSearch: () -> Unit,
    isCodeExecutionEnabled: Boolean,
    onToggleCodeExecution: () -> Unit,
    isGeminiChannel: Boolean,
    onToggleImagePanel: () -> Unit,
    onToggleMoreOptionsPanel: () -> Unit,
    hasContent: Boolean,
    onClearContent: () -> Unit,
    onDismiss: () -> Unit,
    isMcpEnabled: Boolean = false,
    onToggleMcp: () -> Unit = {},
    onOpenConversationParams: () -> Unit = {},
    onOpenFilePicker: () -> Unit = {},
    onOpenCamera: () -> Unit = {},
    onOpenGallery: () -> Unit = {},
    onOpenSystemPrompt: () -> Unit = {}
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF212121) else Color(0xFFFFFFFF)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color(0xFF0D0D0D).copy(alpha = 0.05f)
    val iconBg = if (isDark) Color(0xFF3B3B3B) else Color(0xFFE8E8E8)
    val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val iconTint = if (isDark) Color.White else Color(0xFF0D0D0D)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 370.dp)
            .shadow(8.dp, RoundedCornerShape(28.dp))
            .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        color = cardBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            FunctionPanelRow(
                iconRes = R.drawable.ic_globe,
                label = "联网搜索",
                iconBg = iconBg,
                iconTint = if (isWebSearchEnabled && isWebSearchAvailable) Color(0xFF66B5FF) else iconTint,
                textColor = textColor,
                isChecked = isWebSearchEnabled && isWebSearchAvailable,
                onClick = { onToggleWebSearch() }
            )
            FunctionPanelRow(
                iconRes = R.drawable.ic_hammer,
                label = "MCP",
                iconBg = iconBg,
                iconTint = if (isMcpEnabled) Color(0xFF66B5FF) else iconTint,
                textColor = textColor,
                isChecked = isMcpEnabled,
                onClick = { onToggleMcp() }
            )
            FunctionPanelRow(
                iconRes = R.drawable.ic_settings_slider,
                label = "会话参数",
                iconBg = iconBg,
                iconTint = iconTint,
                textColor = textColor,
                isChecked = false,
                onClick = { onOpenConversationParams(); onDismiss() }
            )
            FunctionPanelRow(
                iconRes = R.drawable.ic_prompt,
                label = "提示词",
                iconBg = iconBg,
                iconTint = iconTint,
                textColor = textColor,
                isChecked = false,
                onClick = { onOpenSystemPrompt(); onDismiss() }
            )
            FunctionPanelRow(
                iconRes = R.drawable.ic_paperclip,
                label = "附件",
                iconBg = iconBg,
                iconTint = iconTint,
                textColor = textColor,
                isChecked = false,
                onClick = { onOpenFilePicker(); onDismiss() }
            )
            FunctionPanelRow(
                iconRes = R.drawable.ic_image_gallery,
                label = "图片",
                iconBg = iconBg,
                iconTint = iconTint,
                textColor = textColor,
                isChecked = false,
                onClick = { onOpenGallery(); onDismiss() }
            )
            FunctionPanelRow(
                iconRes = R.drawable.ic_camera,
                label = "相机",
                iconBg = iconBg,
                iconTint = iconTint,
                textColor = textColor,
                isChecked = false,
                onClick = { onOpenCamera(); onDismiss() }
            )
        }
    }
}

@Composable
internal fun FunctionPanelRow(
    iconRes: Int,
    label: String,
    iconBg: Color,
    iconTint: Color,
    textColor: Color,
    isChecked: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(iconBg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            fontSize = 18.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
        if (isChecked) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
