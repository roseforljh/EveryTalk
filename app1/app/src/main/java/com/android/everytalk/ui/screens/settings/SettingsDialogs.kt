package com.android.everytalk.ui.screens.settings
import com.android.everytalk.statecontroller.*

import android.util.Log
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Surface
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.android.everytalk.R
import com.android.everytalk.ui.components.dialog.appDialogTextFieldDefaultBorderColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldBorderColor
import com.android.everytalk.ui.components.popup.AppFloatingCardElevation
import com.android.everytalk.ui.components.popup.AppFloatingCardShape
import com.android.everytalk.ui.components.popup.appFloatingCardBorderColor
import com.android.everytalk.ui.components.popup.appFloatingCardContainerColor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.data.network.ExternalWebSearchProvider

@Composable
internal fun EditExternalWebSearchProviderDialog(
    provider: ExternalWebSearchProvider,
    currentApiKey: String,
    onApiKeyChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var apiKey by remember(currentApiKey, provider.providerId) { mutableStateOf(currentApiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    val dialogBg = if (isSystemInDarkTheme()) Color.Black else Color.White; val borderColor = if (isSystemInDarkTheme()) Color(0xFF414141) else Color(0xFFF3F3F3); val contentColor = if (isSystemInDarkTheme()) Color.White else Color(0xFF0D0D0D)

    AlertDialog(
        modifier = Modifier
            .wrapContentHeight()
            .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = dialogBg,
        title = {
            Text(
                text = stringResource(R.string.web_provider_edit_title, provider.displayName),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = provider.accentColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, provider.accentColor.copy(alpha = 0.35f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(provider.descriptionRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_link),
                                contentDescription = null,
                                tint = provider.accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = provider.baseUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.settings_api_key_label)) },
                    placeholder = { Text(provider.apiKeyPlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = DialogTextFieldColors,
                    visualTransformation = if (apiKeyVisible) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_eye),
                                contentDescription = stringResource(
                                    if (apiKeyVisible) R.string.action_hide else R.string.action_show,
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = dialogBg,
                        contentColor = contentColor
                    ),
                    border = BorderStroke(1.dp, borderColor)
                ) {
                    Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = {
                        onApiKeyChange(apiKey)
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = contentColor,
                        contentColor = dialogBg
                    )
                ) {
                    Text(stringResource(R.string.action_save), fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
internal fun SettingsFieldLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 8.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddProviderDialog(
    newProviderName: String,
    onNewProviderNameChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
    val dialogBg = if (isDarkTheme) Color.Black else Color.White; val borderColor = if (isDarkTheme) Color(0xFF414141) else Color(0xFFF3F3F3); val contentColor = if (isDarkTheme) Color.White else Color(0xFF0D0D0D)

    AlertDialog(
        modifier = Modifier
            .wrapContentHeight()
            .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(28.dp),
        containerColor = dialogBg,
        titleContentColor = contentColor,
        textContentColor = contentColor,
        title = {
            Text(
                stringResource(R.string.settings_add_model_platform_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsFieldLabel(stringResource(R.string.settings_platform_name_label))
                OutlinedTextField(
                    value = newProviderName,
                    onValueChange = onNewProviderNameChange,
                    placeholder = { Text(stringResource(R.string.settings_platform_name_example)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (newProviderName.isNotBlank()) onConfirm() }),
                    shape = DialogShape,
                    colors = DialogTextFieldColors
                )
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismissRequest,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = contentColor
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
                ) {
                    Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onConfirm,
                    enabled = newProviderName.isNotBlank(),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = contentColor,
                        contentColor = dialogBg,
                        disabledContainerColor = borderColor,
                        disabledContentColor = contentColor.copy(alpha = 0.4f)
                    )
                ) {
                    Text(stringResource(R.string.action_add), fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
internal fun CustomStyledDropdownMenu(
    transitionState: MutableTransitionState<Boolean>,
    onDismissRequest: () -> Unit,
    anchorBounds: Rect?,
    modifier: Modifier = Modifier,
    yOffsetDp: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Log.d(
        "DropdownAnimation",
        "CustomStyledDropdownMenu: transitionState.currentState=${transitionState.currentState}, transitionState.targetState=${transitionState.targetState}, anchorBounds is null: ${anchorBounds == null}"
    )

    if ((transitionState.currentState || transitionState.targetState) && anchorBounds != null) {
        val density = LocalDensity.current
        val menuWidth = with(density) { anchorBounds.width.toDp() }
        val menuBg = appFloatingCardContainerColor()
        val menuBorder = appFloatingCardBorderColor()

        MaterialTheme(
            shapes = MaterialTheme.shapes.copy(
                extraSmall = AppFloatingCardShape
            ),
            colorScheme = MaterialTheme.colorScheme.copy(
                surface = menuBg
            )
        ) {
            DropdownMenu(
                expanded = transitionState.currentState || transitionState.targetState,
                onDismissRequest = onDismissRequest,
                modifier = modifier
                    .width(menuWidth)
                    .heightIn(max = 280.dp)
                    .border(1.dp, menuBorder, AppFloatingCardShape),
                offset = DpOffset(0.dp, yOffsetDp),
                properties = PopupProperties(
                    focusable = true,
                    dismissOnClickOutside = true,
                    dismissOnBackPress = true
                ),
                shape = AppFloatingCardShape,
                containerColor = menuBg,
                tonalElevation = 0.dp,
                shadowElevation = AppFloatingCardElevation,
            ) {
                content()
            }
        }
    } else if ((transitionState.currentState || transitionState.targetState) && anchorBounds == null) {
        Log.w(
            "DropdownAnimation",
            "CustomStyledDropdownMenu: Animation state active BUT anchorBounds is NULL. Menu will not be shown."
        )
    }
}
