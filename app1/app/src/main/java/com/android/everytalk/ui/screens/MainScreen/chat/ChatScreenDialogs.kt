package com.android.everytalk.ui.screens.MainScreen
import com.android.everytalk.statecontroller.*

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.navigation.Screen
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.ConversationScrollState
import com.android.everytalk.statecontroller.SimpleModeManager
import com.android.everytalk.ui.components.AppTopBar
import com.android.everytalk.ui.components.AnimatedWebSourcesDialog
import com.android.everytalk.ui.components.EveryTalkLoadingIndicator
import com.android.everytalk.ui.components.ScrollToBottomButton
import com.android.everytalk.ui.components.WebSourcesDialogEdgeGap
import com.android.everytalk.ui.components.ImagePreviewDialog
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogCancelColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.ChatInputArea
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.ChatMessagesList
import com.android.everytalk.ui.components.content.LocalStickyHeaderTop
import com.android.everytalk.ui.components.image.buildImagePreviewSelection
import com.android.everytalk.ui.screens.MainScreen.chat.dialog.EditMessageDialog
import com.android.everytalk.ui.screens.MainScreen.chat.dialog.SystemPromptDialog
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.EmptyChatView
import com.android.everytalk.ui.screens.MainScreen.chat.models.ModelSelectionBottomSheet
import com.android.everytalk.ui.screens.MainScreen.chat.text.state.rememberChatScrollStateManager
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.screens.MainScreen.chat.core.PlaceholderRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay

@Composable
internal fun AboutDialog(
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val packageInfo = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
    val versionName = packageInfo.versionName
    val versionLabel = stringResource(R.string.about_version, versionName.orEmpty())
    val description = stringResource(R.string.about_description)
    val githubLabel = stringResource(R.string.about_github_label)

    val dialogBg = appDialogContainerColor()
    val contentColor = appDialogContentColor()
    val cancelButtonColor = appDialogCancelColor()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = dialogBg,
        titleContentColor = contentColor,
        textContentColor = contentColor,
        title = { Text(stringResource(R.string.about_title)) },
        text = {
            val annotatedString = buildAnnotatedString {
                append(versionLabel)
                append("\n\n")
                append(description)
                append("\n\n")
                append(githubLabel)
                append(" ")
                withLink(
                    LinkAnnotation.Url(
                        url = "https://github.com/roseforljh/KunTalkwithAi",
                        styles = TextLinkStyles(style = SpanStyle(color = Color(0xFF007eff)))
                    )
                ) {
                    append("EveryTalk")
                }
            }

            Text(
                text = annotatedString,
                style = MaterialTheme.typography.bodyMedium.copy(color = contentColor)
            )
        },
        confirmButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = dialogBg,
                    contentColor = cancelButtonColor
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, cancelButtonColor)
            ) {
                Text(
                    text = stringResource(R.string.action_close),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        },
        dismissButton = {}
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiMessageOptionsBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    onOptionSelected: (AiMessageOption) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceDim,
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            AiMessageOption.values().forEach { option ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = option.title,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = option.icon,
                            contentDescription = option.title,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier.clickable { onOptionSelected(option) },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceDim,
                        headlineColor = MaterialTheme.colorScheme.onSurface,
                        leadingIconColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    }
}

internal enum class AiMessageOption(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    COPY_FULL_TEXT("复制全文", Icons.Filled.ContentCopy),
    REGENERATE("重新回答", Icons.Filled.Refresh),
    EXPORT_TEXT("导出文本", Icons.Filled.IosShare)
}
