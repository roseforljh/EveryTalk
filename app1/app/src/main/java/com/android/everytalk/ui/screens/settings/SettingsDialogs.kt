package com.android.everytalk.ui.screens.settings

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

val DialogTextFieldColors
    @Composable get() = run {
        val borderColor = appDialogTextFieldBorderColor()
        val defaultBorderColor = appDialogTextFieldDefaultBorderColor()
        OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            cursorColor = borderColor,
            focusedBorderColor = borderColor,
            unfocusedBorderColor = defaultBorderColor,
            disabledBorderColor = defaultBorderColor.copy(alpha = 0.5f),
            focusedLabelColor = borderColor,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent
        )
    }
val DialogShape = RoundedCornerShape(16.dp)

@OptIn(ExperimentalMaterial3Api::class)
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
                text = "编辑 ${provider.displayName}",
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
                            text = provider.description,
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
                    label = { Text("API Key") },
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
                                contentDescription = if (apiKeyVisible) "隐藏" else "显示",
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
                    Text("取消", fontWeight = FontWeight.SemiBold)
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
                    Text("保存", fontWeight = FontWeight.SemiBold)
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
                "添加新模型平台",
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
                SettingsFieldLabel("平台名称")
                OutlinedTextField(
                    value = newProviderName,
                    onValueChange = onNewProviderNameChange,
                    placeholder = { Text("例如: OpenRouter, Anthropic...") },
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
                    Text("取消", fontWeight = FontWeight.SemiBold)
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
                    Text("添加", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
private fun CustomStyledDropdownMenu(
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
        val isDark = isSystemInDarkTheme()
        val menuBg = if (isDark) Color(0xFF212121) else Color(0xFFFFFFFF)
        val menuBorder = if (isDark) Color(0xFF414141) else Color(0xFFF3F3F3)

        MaterialTheme(
            shapes = MaterialTheme.shapes.copy(
                extraSmall = RoundedCornerShape(20.dp)
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
                    .border(1.dp, menuBorder, RoundedCornerShape(20.dp)),
                offset = DpOffset(0.dp, yOffsetDp),
                properties = PopupProperties(
                    focusable = true,
                    dismissOnClickOutside = true,
                    dismissOnBackPress = true
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddNewFullConfigDialog(
    provider: String,
    onProviderChange: (String) -> Unit,
    allProviders: List<String>,
    onShowAddCustomProviderDialog: () -> Unit,
    onDeleteProvider: (String) -> Unit,
    apiAddress: String,
    onApiAddressChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirm: (String, String, String, String, String?, Int?, Float?, Boolean?, String?) -> Unit,
    isImageMode: Boolean
) {
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var channelMenuExpanded by remember { mutableStateOf(false) }
    val channels = if (isImageMode) {
        listOf("OpenAI兼容", "Gemini")
    } else {
        listOf("OpenAI兼容", "Gemini", "OpenClaw", "Codex")
    }
    var selectedChannel by remember { mutableStateOf(channels.first()) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    // val focusRequesterApiKey = remember { FocusRequester() } // Removed auto-focus
    var textFieldAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    var channelTextFieldAnchorBounds by remember { mutableStateOf<Rect?>(null) }
   var imageSize by remember { mutableStateOf("1024x1024") }
   var numInferenceSteps by remember { mutableStateOf("20") }
   var guidanceScale by remember { mutableStateOf("7.5") }
    val isDarkTheme = isSystemInDarkTheme()
    val dialogBg = if (isDarkTheme) Color.Black else Color.White
    val borderColor = if (isDarkTheme) Color(0xFF414141) else Color(0xFFF3F3F3)
    val contentColor = if (isDarkTheme) Color.White else Color(0xFF0D0D0D)
    val subtextColor = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color(0xFF0D0D0D).copy(alpha = 0.6f)

    val providerMenuTransitionState = remember { MutableTransitionState(initialState = false) }
    val channelMenuTransitionState = remember { MutableTransitionState(initialState = false) }

    // 图像模式与文本模式一致：展示所有可用平台
    val providersToShow = allProviders.filter {
        val lower = it.trim().lowercase()
        lower !in listOf("默认", "default", "default_text")
    }
    val isDefaultSel = false // 移除默认选项，此变量保持为 false
    val isGoogleProvider = provider.trim().lowercase() in listOf("google", "gemini", "谷歌")
    // 当平台为 Google 时，通道锁定为 Gemini
    LaunchedEffect(provider) {
        if (isGoogleProvider) {
            selectedChannel = "Gemini"
        }
    }

    val shouldShowCustomMenuLogical =
        providerMenuExpanded && allProviders.isNotEmpty() && textFieldAnchorBounds != null
    val shouldShowChannelMenuLogical = channelMenuExpanded && channelTextFieldAnchorBounds != null

    LaunchedEffect(shouldShowCustomMenuLogical) {
        providerMenuTransitionState.targetState = shouldShowCustomMenuLogical
    }

    LaunchedEffect(shouldShowChannelMenuLogical) {
        channelMenuTransitionState.targetState = shouldShowChannelMenuLogical
    }

    LaunchedEffect(allProviders) {
        Log.d("DropdownDebug", "AddNewFullConfigDialog: allProviders size: ${allProviders.size}")
    }
    // 确保进入对话框时不显示"默认"占位值
    LaunchedEffect(Unit) {
        val lower = provider.trim().lowercase()
        if (lower in listOf("默认","default","default_text")) {
            onProviderChange("")
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val canSubmit = apiKey.isNotBlank()
                && apiAddress.isNotBlank()
                && provider.isNotBlank()
                && provider.trim().lowercase() !in listOf("默认","default","default_text")
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
                    onClick = onDismissRequest
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
                    "添加配置",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    SettingsFieldLabel("模型平台")
                    ExposedDropdownMenuBox(
                        expanded = providerMenuExpanded && providersToShow.isNotEmpty(),
                        onExpandedChange = {
                            if (providersToShow.isNotEmpty()) {
                                providerMenuExpanded = !providerMenuExpanded
                            }
                        },
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        OutlinedTextField(
                            value = if (provider.trim().lowercase() in listOf("默认","default","default_text")) "" else provider,
                            onValueChange = {},
                            readOnly = true,
                            placeholder = { Text("请选择平台") },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                                .fillMaxWidth()
                                .onGloballyPositioned { coordinates -> 
                                    textFieldAnchorBounds = coordinates.boundsInWindow()
                                },
                            trailingIcon = {
                                // 统一允许添加自定义平台
                                IconButton(onClick = {
                                    if (providerMenuExpanded && allProviders.isNotEmpty()) {
                                        providerMenuExpanded = false
                                    }
                                    onShowAddCustomProviderDialog()
                                }) {
                                    Icon(painter = painterResource(R.drawable.ic_plus), contentDescription = "添加自定义平台")
                                }
                            },
                            shape = DialogShape,
                            colors = DialogTextFieldColors
                        )

                        if (providersToShow.isNotEmpty()) {
                            CustomStyledDropdownMenu(
                                transitionState = providerMenuTransitionState,
                                onDismissRequest = {
                                    providerMenuExpanded = false
                                },
                                anchorBounds = textFieldAnchorBounds
                            ) {
                                providersToShow.forEach { providerItem ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = providerItem,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                // 统一删除策略：仅保护部分系统预置平台，其他均可删除
                                                val canDelete = OpenClawSettingsRules.canDeleteProvider(providerItem)
                                                if (canDelete) {
                                                    IconButton(
                                                        onClick = {
                                                            onDeleteProvider(providerItem)
                                                        },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(
                                                            painter = painterResource(R.drawable.ic_close),
                                                            contentDescription = "删除 $providerItem",
                                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        onClick = {
                                            // 选平台时若为 Google/谷歌，则锁定渠道为 Gemini
                                            val low = providerItem.trim().lowercase()
                                            if (low == "google" || low == "gemini" || low == "谷歌") {
                                                selectedChannel = "Gemini"
                                            }
                                            onProviderChange(providerItem)
                                            providerMenuExpanded = false
                                        },
                                        colors = MenuDefaults.itemColors(
                                            textColor = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // 当选择"默认"时隐藏渠道/地址/密钥等输入
                    if (!isDefaultSel) {
                        SettingsFieldLabel("渠道")
                        ExposedDropdownMenuBox(
                            expanded = channelMenuExpanded,
                            onExpandedChange = {
                                if (!isGoogleProvider) {
                                    channelMenuExpanded = !channelMenuExpanded
                                } else {
                                    channelMenuExpanded = false
                                }
                            },
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            OutlinedTextField(
                                value = selectedChannel,
                                onValueChange = {},
                                readOnly = true,
                                enabled = !isGoogleProvider, // Google 平台时禁用手动切换
                                modifier = Modifier
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = !isGoogleProvider)
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

                            if (!isGoogleProvider) {
                                CustomStyledDropdownMenu(
                                    transitionState = channelMenuTransitionState,
                                    onDismissRequest = {
                                        channelMenuExpanded = false
                                    },
                                    anchorBounds = channelTextFieldAnchorBounds
                                ) {
                                    channels.forEach { channel ->
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
                        }

                        SettingsFieldLabel(OpenClawSettingsRules.addressLabelFor(provider, selectedChannel))
                        OutlinedTextField(
                            value = apiAddress,
                            onValueChange = onApiAddressChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Next),
                            shape = DialogShape,
                            colors = DialogTextFieldColors
                        )
                        // 实时预览 + 固定使用说明
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
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 12.dp, bottom = 12.dp)
                                )
                            }
                        }

                        SettingsFieldLabel(OpenClawSettingsRules.keyLabelFor(provider, selectedChannel))
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = onApiKeyChange,
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
                                if (apiKey.isNotBlank() && provider.isNotBlank() && apiAddress.isNotBlank()) {
                                   onConfirm(provider, apiAddress, apiKey, selectedChannel, imageSize, numInferenceSteps.toIntOrNull(), guidanceScale.toFloatOrNull(), null, null)
                                }
                            }),
                            shape = DialogShape,
                            colors = DialogTextFieldColors
                        )

                    }
                }

                // 底部固定按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismissRequest,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = dialogBg,
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
                                    imageSize,
                                    numInferenceSteps.toIntOrNull(),
                                    guidanceScale.toFloatOrNull(),
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
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Text(
                            text = "添加",
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

@Composable
internal fun ConfirmDeleteDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    title: String,
    text: String
) {
    val isDarkTheme = isSystemInDarkTheme()
    val dialogBg = if (isDarkTheme) Color.Black else Color.White
    val borderColor = if (isDarkTheme) Color(0xFF414141) else Color(0xFFF3F3F3)
    val contentColor = if (isDarkTheme) Color.White else Color(0xFF0D0D0D)
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
                title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.8f)
            )
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
                    Text("取消", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = {
                        onConfirm()
                        onDismissRequest()
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF5350),
                        contentColor = Color.White
                    )
                ) {
                    Text("删除", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
internal fun ImportExportDialog(
    onDismissRequest: () -> Unit,
    onExport: (includeHistory: Boolean) -> Unit,
    onImport: () -> Unit,
    isExportEnabled: Boolean,
    chatHistoryCount: Int,
    imageHistoryCount: Int
) {
    val isDarkTheme = isSystemInDarkTheme()
    val dialogBg = if (isDarkTheme) Color.Black else Color.White
    val borderColor = if (isDarkTheme) Color(0xFF414141) else Color(0xFFF3F3F3)
    val contentColor = if (isDarkTheme) Color.White else Color(0xFF0D0D0D)
    val subtextColor = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color(0xFF0D0D0D).copy(alpha = 0.6f)
    var includeHistory by remember { mutableStateOf(false) }

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
                "导入 / 导出",
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
                if (chatHistoryCount > 0 || imageHistoryCount > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                            .clickable { includeHistory = !includeHistory }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (includeHistory) contentColor else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (includeHistory) contentColor else borderColor,
                                    RoundedCornerShape(7.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (includeHistory) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = dialogBg,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "包含聊天历史",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = contentColor
                            )
                            Text(
                                "文本: $chatHistoryCount 个会话, 图像: $imageHistoryCount 个会话",
                                style = MaterialTheme.typography.bodySmall,
                                color = subtextColor
                            )
                        }
                    }
                }

                Text(
                    "导出文件包含API密钥等敏感信息，请妥善保管",
                    style = MaterialTheme.typography.bodySmall,
                    color = subtextColor
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = onImport,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = contentColor
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
                ) {
                    Text("导入", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { onExport(includeHistory) },
                    enabled = isExportEnabled,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = contentColor,
                        contentColor = dialogBg,
                        disabledContainerColor = borderColor,
                        disabledContentColor = subtextColor
                    )
                ) {
                    Text("导出", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddModelDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var modelName by remember { mutableStateOf("") }

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
                "添加新模型",
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
                SettingsFieldLabel("模型名称")
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    placeholder = { Text("例如: gpt-4-turbo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (modelName.isNotBlank()) onConfirm(modelName) }),
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
                    Text("取消", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { onConfirm(modelName) },
                    enabled = modelName.isNotBlank(),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = contentColor,
                        contentColor = dialogBg,
                        disabledContainerColor = borderColor,
                        disabledContentColor = contentColor.copy(alpha = 0.4f)
                    )
                ) {
                    Text("添加", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {}
    )
}
