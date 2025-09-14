package com.example.everytalk.ui.screens.settings

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

val DialogTextFieldColors
    @Composable get() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
    )
val DialogShape = RoundedCornerShape(24.dp)

private fun normalizeBaseUrlForPreview(url: String): String =
    url.trim().trimEnd('#')

private fun shouldBypassPath(url: String): Boolean =
    url.trim().endsWith("#")

private fun endsWithSlash(url: String): Boolean {
    val u = url.trim().trimEnd('#')
    return u.endsWith("/")
}

private fun hasPathAfterHost(url: String): Boolean {
    val u = url.trim().trimEnd('#').trimEnd('/')
    val schemeIdx = u.indexOf("://")
    return if (schemeIdx >= 0) {
        u.indexOf('/', schemeIdx + 3) >= 0
    } else {
        u.indexOf('/') >= 0
    }
}

private fun endpointPathFor(provider: String, channel: String?, withV1: Boolean): String {
    val p = provider.lowercase().trim()
    val ch = channel?.lowercase()?.trim().orEmpty()
    return if (p.contains("google") || ch.contains("gemini")) {
        if (withV1) "v1beta/models:generateContent" else "models:generateContent"
    } else {
        if (withV1) "v1/chat/completions" else "chat/completions"
    }
}

private fun buildFullEndpointPreview(base: String, provider: String, channel: String?): String {
    val raw = base.trim()
    if (raw.isEmpty()) return ""
    val noHash = raw.trimEnd('#')
    
    val p = provider.lowercase().trim()
    val ch = channel?.lowercase()?.trim().orEmpty()
    val isGemini = p.contains("google") || ch.contains("gemini")

    // 规则1: 末尾有#，直接使用用户地址，不添加任何路径
    if (shouldBypassPath(raw)) {
        return noHash
    }

    // Gemini特殊处理：官方API接口固定，不按通用逻辑处理
    if (isGemini) {
        // 规则3: 地址已经包含路径，按输入直连
        if (hasPathAfterHost(noHash) || endsWithSlash(noHash)) {
            return noHash.trimEnd('/')
        }
        // 规则4: 什么都没有，自动添加Gemini固定路径
        val path = endpointPathFor(provider, channel, true)
        return "$noHash/$path"
    }

    // 非Gemini的通用逻辑
    // 规则2: 末尾有/，不要v1，添加/chat/completions
    if (endsWithSlash(noHash)) {
        val path = endpointPathFor(provider, channel, false)
        return noHash + path
    }

    // 规则3: 地址已经包含路径，按输入直连
    if (hasPathAfterHost(noHash)) {
        return noHash
    }

    // 规则4: 什么都没有，自动添加/v1/...
    val path = endpointPathFor(provider, channel, true)
    return "$noHash/$path"
}

private fun buildEndpointHintForPreview(base: String, provider: String, channel: String?): String {
    val raw = base.trim()
    if (shouldBypassPath(raw)) {
        return "末尾#：直连，不追加任何路径（自动去掉#）"
    }
    
    val noHash = raw.trimEnd('#')
    val p = provider.lowercase().trim()
    val ch = channel?.lowercase()?.trim().orEmpty()
    val isGemini = p.contains("google") || ch.contains("gemini")
    
    if (isGemini) {
        if (hasPathAfterHost(noHash) || endsWithSlash(noHash)) {
            return "Gemini官方API：按输入直连（去掉末尾/）"
        }
        return "仅域名→ 自动拼接Gemini固定路径 /v1beta/models:generateContent"
    }
    
    if (endsWithSlash(noHash)) {
        return "末尾/：不要v1，添加/chat/completions"
    }
    
    if (hasPathAfterHost(noHash)) {
        return "地址已含路径→ 按输入直连，不追加路径"
    }
    
    return "仅域名→ 自动拼接默认路径 /v1/chat/completions"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddProviderDialog(
    newProviderName: String,
    onNewProviderNameChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("添加新模型平台", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            OutlinedTextField(
                value = newProviderName,
                onValueChange = onNewProviderNameChange,
                label = { Text("平台名称") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                shape = DialogShape,
                colors = DialogTextFieldColors
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = newProviderName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text("取消") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
private fun CustomStyledDropdownMenu(
    transitionState: MutableTransitionState<Boolean>,
    onDismissRequest: () -> Unit,
    anchorBounds: Rect?,
    modifier: Modifier = Modifier,
    yOffsetDp: Dp = 74.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Log.d(
        "DropdownAnimation",
        "CustomStyledDropdownMenu: transitionState.currentState=${transitionState.currentState}, transitionState.targetState=${transitionState.targetState}, anchorBounds is null: ${anchorBounds == null}"
    )

    if ((transitionState.currentState || transitionState.targetState) && anchorBounds != null) {
        val density = LocalDensity.current
        val menuWidth = with(density) { anchorBounds.width.toDp() }

        val yAdjustmentInPx = with(density) { yOffsetDp.toPx() }.toInt()
        val yOffset = anchorBounds.bottom.toInt() - yAdjustmentInPx

        val xAdjustmentDp: Dp = 24.dp
        val xAdjustmentInPx = with(density) { xAdjustmentDp.toPx() }.toInt()
        val xOffset = anchorBounds.left.toInt() - xAdjustmentInPx

        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(xOffset, yOffset),
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(
                focusable = true,
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
                usePlatformDefaultWidth = false
            )
        ) {
            AnimatedVisibility(
                visibleState = transitionState,
                enter = fadeIn(animationSpec = tween(durationMillis = 200, delayMillis = 50)) +
                        slideInVertically(
                            animationSpec = tween(durationMillis = 250, delayMillis = 50),
                            initialOffsetY = { -it / 3 }
                        ),
                exit = fadeOut(animationSpec = tween(durationMillis = 150)) +
                        slideOutVertically(
                            animationSpec = tween(durationMillis = 200),
                            targetOffsetY = { -it / 3 }
                        )
            ) {
                Log.d(
                    "DropdownAnimation",
                    "AnimatedVisibility content rendering. CurrentState: ${transitionState.currentState}"
                )
                Surface(
                    shape = DialogShape,
                    color = MaterialTheme.colorScheme.surfaceDim,
                    shadowElevation = 6.dp,
                    tonalElevation = 0.dp,
                    modifier = modifier
                        .width(menuWidth)
                        .heightIn(max = 240.dp)
                        .padding(vertical = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        content()
                    }
                }
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
    onConfirm: (String, String, String, String, String?, Int?, Float?) -> Unit
) {
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var channelMenuExpanded by remember { mutableStateOf(false) }
    val channels = listOf("OpenAI兼容", "Gemini")
    var selectedChannel by remember { mutableStateOf(channels.first()) }
    val focusRequesterApiKey = remember { FocusRequester() }
    var textFieldAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    var channelTextFieldAnchorBounds by remember { mutableStateOf<Rect?>(null) }
   var imageSize by remember { mutableStateOf("1024x1024") }
   var numInferenceSteps by remember { mutableStateOf("20") }
   var guidanceScale by remember { mutableStateOf("7.5") }

    val providerMenuTransitionState = remember { MutableTransitionState(initialState = false) }
    val channelMenuTransitionState = remember { MutableTransitionState(initialState = false) }

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

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("添加配置", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ExposedDropdownMenuBox(
                    expanded = providerMenuExpanded && allProviders.isNotEmpty(),
                    onExpandedChange = {
                        if (allProviders.isNotEmpty()) {
                            providerMenuExpanded = !providerMenuExpanded
                        }
                    },
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    OutlinedTextField(
                        value = provider,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("模型平台") },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                textFieldAnchorBounds = coordinates.boundsInWindow()
                            },
                        trailingIcon = {
                            IconButton(onClick = {
                                if (providerMenuExpanded && allProviders.isNotEmpty()) {
                                    providerMenuExpanded = false
                                }
                                onShowAddCustomProviderDialog()
                            }) {
                                Icon(Icons.Outlined.Add, "添加自定义平台")
                            }
                        },
                        shape = DialogShape,
                        colors = DialogTextFieldColors
                    )

                    if (allProviders.isNotEmpty()) {
                        CustomStyledDropdownMenu(
                            transitionState = providerMenuTransitionState,
                            onDismissRequest = {
                                providerMenuExpanded = false
                            },
                            anchorBounds = textFieldAnchorBounds
                        ) {
                            allProviders.forEach { providerItem ->
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
                                            val nonDeletableProviders = listOf(
                                                "openai compatible",
                                                "google",
                                                "硅基流动",
                                                "阿里云百炼",
                                                "火山引擎",
                                                "深度求索",
                                                "openrouter"
                                            )
                                            if (!nonDeletableProviders.contains(providerItem.lowercase().trim())) {
                                                IconButton(
                                                    onClick = {
                                                        onDeleteProvider(providerItem)
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Filled.Close,
                                                        contentDescription = "删除 $providerItem",
                                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onClick = {
                                        onProviderChange(providerItem)
                                        providerMenuExpanded = false
                                    },
                                    colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onSurface)
                                )
                            }
                        }
                    }
                }
                ExposedDropdownMenuBox(
                    expanded = channelMenuExpanded,
                    onExpandedChange = { channelMenuExpanded = !channelMenuExpanded },
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    OutlinedTextField(
                        value = selectedChannel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("渠道") },
                        modifier = Modifier
                            .menuAnchor()
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
                        anchorBounds = channelTextFieldAnchorBounds,
                        yOffsetDp = 150.dp
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
                OutlinedTextField(
                    value = apiAddress,
                    onValueChange = onApiAddressChange,
                    label = { Text("API接口地址") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Next),
                    shape = DialogShape,
                    colors = DialogTextFieldColors
                )
                // 实时预览 + 固定使用说明
                run {
                    val fullUrlPreview = remember(apiAddress, provider, selectedChannel) {
                        buildFullEndpointPreview(apiAddress, provider, selectedChannel)
                    }
                    if (fullUrlPreview.isNotEmpty()) {
                        Text(
                            text = "预览: $fullUrlPreview",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                        )
                    }
                    Text(
                        text = "用法: 末尾#：直连 ； 末尾/：不加v1 ； 仅域名自动加v1",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, bottom = 12.dp)
                    )
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text("API密钥") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .focusRequester(focusRequesterApiKey),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (apiKey.isNotBlank() && provider.isNotBlank() && apiAddress.isNotBlank()) {
                           onConfirm(provider, apiAddress, apiKey, selectedChannel, imageSize, numInferenceSteps.toIntOrNull(), guidanceScale.toFloatOrNull())
                        }
                    }),
                    shape = DialogShape,
                    colors = DialogTextFieldColors
                )
               if (selectedChannel == "Image") {
                   OutlinedTextField(
                       value = imageSize,
                       onValueChange = { imageSize = it },
                       label = { Text("Image Size") },
                       modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                       singleLine = true,
                       shape = DialogShape,
                       colors = DialogTextFieldColors
                   )
                   OutlinedTextField(
                       value = numInferenceSteps,
                       onValueChange = { numInferenceSteps = it },
                       label = { Text("Num Inference Steps") },
                       modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                       singleLine = true,
                       shape = DialogShape,
                       colors = DialogTextFieldColors
                   )
                   OutlinedTextField(
                       value = guidanceScale,
                       onValueChange = { guidanceScale = it },
                       label = { Text("Guidance Scale") },
                       modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                       singleLine = true,
                       shape = DialogShape,
                       colors = DialogTextFieldColors
                   )
               }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(provider, apiAddress, apiKey, selectedChannel, imageSize, numInferenceSteps.toIntOrNull(), guidanceScale.toFloatOrNull()) },
                enabled = apiKey.isNotBlank() && provider.isNotBlank() && apiAddress.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("取消") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditConfigDialog(
    representativeConfig: com.example.everytalk.data.DataClass.ApiConfig,
    allProviders: List<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (newAddress: String, newKey: String, newChannel: String) -> Unit
) {
    var apiAddress by remember { mutableStateOf(representativeConfig.address) }
    var apiKey by remember { mutableStateOf(representativeConfig.key) }
    var selectedChannel by remember { mutableStateOf(representativeConfig.channel) }
    val focusRequester = remember { FocusRequester() }
    
    // 固定的渠道类型选项
    val channelTypes = listOf("OpenAI兼容", "Gemini")

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("编辑配置", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = apiAddress,
                    onValueChange = { apiAddress = it },
                    label = { Text("API接口地址") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Next),
                    shape = DialogShape,
                    colors = DialogTextFieldColors
                )
                // 实时预览 + 固定使用说明
                run {
                    val fullUrlPreview = remember(apiAddress) {
                        buildFullEndpointPreview(apiAddress, representativeConfig.provider, null)
                    }
                    if (fullUrlPreview.isNotEmpty()) {
                        Text(
                            text = "预览: $fullUrlPreview",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                        )
                    }
                    Text(
                        text = "用法: 末尾#：直连 ； 末尾/：不加v1 ； 仅域名自动加v1",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, bottom = 12.dp)
                    )
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API密钥") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .focusRequester(focusRequester),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Next),
                    shape = DialogShape,
                    colors = DialogTextFieldColors
                )
                
                // 渠道类型选择下拉框
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    OutlinedTextField(
                        value = selectedChannel,
                        onValueChange = { },
                        label = { Text("渠道类型") },
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = DialogShape,
                        colors = DialogTextFieldColors
                    )
                    
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        channelTypes.forEach { channelType ->
                            DropdownMenuItem(
                                text = { Text(channelType) },
                                onClick = {
                                    selectedChannel = channelType
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(apiAddress, apiKey, selectedChannel) },
                enabled = apiKey.isNotBlank() && apiAddress.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { Text("更新") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("取消") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = DialogShape,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
internal fun ConfirmDeleteDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    title: String,
    text: String
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        text = { Text(text, color = MaterialTheme.colorScheme.onSurface) },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismissRequest()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("确认删除")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
            ) {
                Text("取消")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = DialogShape,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
internal fun ImportExportDialog(
    onDismissRequest: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    isExportEnabled: Boolean
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text("导入 / 导出配置", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = onExport,
                    enabled = isExportEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("导出配置")
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("导入配置")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            ) {
                Text("取消")
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
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("添加新模型", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                label = { Text("模型名称") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (modelName.isNotBlank()) onConfirm(modelName) }),
                shape = DialogShape,
                colors = DialogTextFieldColors
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(modelName) },
                enabled = modelName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("取消") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = DialogShape,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}