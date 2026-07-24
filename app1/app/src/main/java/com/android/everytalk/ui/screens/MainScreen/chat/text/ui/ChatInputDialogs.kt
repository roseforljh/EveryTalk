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
internal fun ChatInputDialogs(
    showConversationParamsDialog: Boolean,
    onShowConversationParamsDialogChange: (Boolean) -> Unit,
    showMcpServerListDialog: Boolean,
    onShowMcpServerListDialogChange: (Boolean) -> Unit,
    viewModel: com.android.everytalk.statecontroller.AppViewModel,
    mcpServerStates: Map<String, McpServerState>,
    onAddMcpServer: (McpServerConfig) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onToggleMcpServer: (String, Boolean) -> Unit,
    tempCameraImageUri: Uri?,
    context: Context,
) {
    // Conversation Parameters Dialog
    if (showConversationParamsDialog) {
        // Get current conversation parameters if they exist
        val currentParams = viewModel.getCurrentConversationParameters()
        
        com.android.everytalk.ui.screens.MainScreen.chat.dialog.ConversationParametersDialog(
            onDismissRequest = { onShowConversationParamsDialogChange(false) },
            onConfirm = { temperature, topP, maxTokens ->
                // Save parameters to current conversation
                viewModel.updateConversationParameters(temperature, topP, maxTokens)
                onShowConversationParamsDialogChange(false)
            },
            initialTemperature = currentParams?.temperature,
            initialTopP = currentParams?.topP,
            initialMaxTokens = currentParams?.maxOutputTokens
        )
    }

    // MCP Server List Dialog
    if (showMcpServerListDialog) {
        McpServerListDialog(
            serverStates = mcpServerStates,
            onAddServer = onAddMcpServer,
            onUpdateServer = onAddMcpServer,
            onRemoveServer = onRemoveMcpServer,
            onToggleServer = onToggleMcpServer,
            onDismiss = { onShowMcpServerListDialogChange(false) }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            tempCameraImageUri?.let { uri ->
                safeDeleteTempFile(context, uri)
            }
        }
    }
}
