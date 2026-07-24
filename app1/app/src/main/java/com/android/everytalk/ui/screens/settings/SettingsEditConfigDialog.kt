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
import com.android.everytalk.R
import com.android.everytalk.ui.components.dialog.appDialogTextFieldDefaultBorderColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldBorderColor
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditConfigDialog(
    representativeConfig: com.android.everytalk.data.DataClass.ApiConfig,
    allProviders: List<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (newProvider: String, newAddress: String, newKey: String, newChannel: String, newEnableCodeExecution: Boolean?, newToolsJson: String?) -> Unit,
    isImageMode: Boolean = false
) {
    Log.d("EditConfigDialog", "Opening dialog for config: ${representativeConfig.name}, provider: ${representativeConfig.provider}")
    var provider by remember { mutableStateOf(representativeConfig.provider) }
    var apiAddress by remember { mutableStateOf(representativeConfig.address) }
    var apiKey by remember { mutableStateOf(representativeConfig.key) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var selectedChannel by remember { mutableStateOf(representativeConfig.channel) }
    val isDarkTheme = isSystemInDarkTheme()
    val dialogBg = if (isDarkTheme) Color.Black else Color.White
    val borderColor = if (isDarkTheme) Color(0xFF414141) else Color(0xFFF3F3F3)
    val contentColor = if (isDarkTheme) Color.White else Color(0xFF0D0D0D)
    val subtextColor = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color(0xFF0D0D0D).copy(alpha = 0.6f)

    val channelTypes = if (isImageMode) {
        listOf("OpenAI兼容", "Gemini")
    } else {
        listOf("OpenAI兼容", "Gemini", "OpenClaw", "Codex")
    }
    
    var channelMenuExpanded by remember { mutableStateOf(false) }
    var channelTextFieldAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    val channelMenuTransitionState = remember { MutableTransitionState(initialState = false) }
    val shouldShowChannelMenuLogical = channelMenuExpanded && channelTextFieldAnchorBounds != null

    LaunchedEffect(shouldShowChannelMenuLogical) {
        channelMenuTransitionState.targetState = shouldShowChannelMenuLogical
    }

    var isDialogVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isDialogVisible = true }
    val dialogAlpha by animateFloatAsState(
        targetValue = if (isDialogVisible) 1f else 0f,
        animationSpec = tween(if (isDialogVisible) 140 else 280),
        label = "editConfigDialogAlpha"
    )
    val dialogScale by animateFloatAsState(
        targetValue = if (isDialogVisible) 1f else 0.96f,
        animationSpec = tween(if (isDialogVisible) 140 else 280),
        label = "editConfigDialogScale"
    )
    val dialogScope = rememberCoroutineScope()
    fun dismissWithAnimation() {
        isDialogVisible = false
        dialogScope.launch {
            kotlinx.coroutines.delay(280)
            onDismissRequest()
        }
    }

    Dialog(
        onDismissRequest = { dismissWithAnimation() },
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val canSubmit = apiKey.isNotBlank() && apiAddress.isNotBlank() && provider.isNotBlank()
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialogWindow?.setDimAmount(0f)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dismissWithAnimation() }
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(top = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .graphicsLayer {
                        alpha = dialogAlpha
                        scaleX = dialogScale
                        scaleY = dialogScale
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
                    .clip(RoundedCornerShape(28.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
                shape = RoundedCornerShape(28.dp),
                color = dialogBg
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp)
                ) {
                Text(
                    "编辑配置",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SettingsFieldLabel("模型平台")
                    OutlinedTextField(
                        value = provider,
                        onValueChange = { provider = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Next),
                        shape = DialogShape,
                        colors = DialogTextFieldColors
                    )

                    SettingsFieldLabel("渠道")
                    ExposedDropdownMenuBox(
                        expanded = channelMenuExpanded,
                        onExpandedChange = { channelMenuExpanded = !channelMenuExpanded },
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        OutlinedTextField(
                            value = selectedChannel,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                                .fillMaxWidth()
                                .onGloballyPositioned { coordinates -> 
                                    channelTextFieldAnchorBounds = coordinates.boundsInWindow()
                                },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = channelMenuExpanded)
                            },
                            shape = DialogShape,
                            colors = DialogTextFieldColors
                        )

                        CustomStyledDropdownMenu(
                            transitionState = channelMenuTransitionState,
                            onDismissRequest = {
                                channelMenuExpanded = false
                            },
                            anchorBounds = channelTextFieldAnchorBounds
                        ) {
                            channelTypes.forEach { channel ->
                                DropdownMenuItem(
                                    text = { Text(channel) },
                                    onClick = {
                                        selectedChannel = channel
                                        channelMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    SettingsFieldLabel("API接口地址")
                    OutlinedTextField(
                        value = apiAddress,
                        onValueChange = { apiAddress = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Next),
                        shape = DialogShape,
                        colors = DialogTextFieldColors
                    )
                    if (selectedChannel != "Gemini") {
                        val fullUrlPreview = remember(apiAddress, provider, selectedChannel) {
                            OpenClawSettingsRules.buildFullEndpointPreview(
                                base = apiAddress,
                                provider = provider,
                                channel = selectedChannel,
                                accessMode = if (OpenClawSettingsRules.isOpenClaw(provider, selectedChannel)) "bridge" else null,
                                isImageMode = isImageMode
                            )
                        }
                        if (fullUrlPreview.isNotEmpty()) {
                            Text(
                                text = "预览: $fullUrlPreview",
                                style = MaterialTheme.typography.labelSmall,
                                color = subtextColor,
                                modifier = Modifier.padding(start = 12.dp, bottom = 12.dp)
                            )
                        }
                    }

                    SettingsFieldLabel("API密钥")
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        singleLine = true,
                        visualTransformation = if (apiKeyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    painter = painterResource(if (apiKeyVisible) R.drawable.ic_eye else R.drawable.ic_eye_off),
                                    contentDescription = if (apiKeyVisible) "隐藏密钥" else "显示密钥",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (canSubmit) {
                                onConfirm(provider, apiAddress, apiKey, selectedChannel, null, null)
                            }
                        }),
                        shape = DialogShape,
                        colors = DialogTextFieldColors
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { dismissWithAnimation() },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = contentColor
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
                    ) {
                        Text(
                            text = "取消",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }

                    Button(
                        onClick = {
                            if (canSubmit) {
                                onConfirm(
                                    provider,
                                    apiAddress,
                                    apiKey,
                                    selectedChannel,
                                    null,
                                    null
                                )
                            }
                        },
                        enabled = canSubmit,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = contentColor,
                            contentColor = dialogBg,
                            disabledContainerColor = borderColor,
                            disabledContentColor = subtextColor
                        )
                    ) {
                        Text(
                            text = "保存",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
                }
            }
        }
    }
}

