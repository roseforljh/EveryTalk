package com.android.everytalk.ui.screens.settings

import android.util.Log
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

val DialogTextFieldColors
    @Composable get() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        cursorColor = MaterialTheme.colorScheme.tertiary,
        focusedBorderColor = MaterialTheme.colorScheme.tertiary,
        unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f),
        disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        focusedLabelColor = MaterialTheme.colorScheme.tertiary,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent
    )
val DialogShape = RoundedCornerShape(32.dp)

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
        shape = RoundedCornerShape(32.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp).size(24.dp)
                )
                Text(
                    "添加新模型平台",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "为自定义API提供商添加一个标识名称，方便后续管理和配置",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.5f
                )
                OutlinedTextField(
                    value = newProviderName,
                    onValueChange = onNewProviderNameChange,
                    label = { Text("平台名称", fontWeight = FontWeight.Medium) },
                    placeholder = { Text("例如: OpenRouter, Anthropic...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (newProviderName.isNotBlank()) onConfirm() }),
                    shape = RoundedCornerShape(16.dp),
                    colors = DialogTextFieldColors
                )
                // 提示信息
                if (newProviderName.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "✓",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                "平台名称: $newProviderName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = onConfirm,
                enabled = newProviderName.isNotBlank(),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.height(52.dp).padding(horizontal = 4.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).padding(end = 4.dp)
                )
                Text("添加平台", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.height(52.dp).padding(horizontal = 4.dp)
            ) {
                Text("取消", fontWeight = FontWeight.Medium)
            }
        }
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
    yOffsetDp: Dp = 8.dp,
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

        // 使用 DropdownMenu 并设置样式和颜色，深色模式使用灰色背景
        MaterialTheme(
            shapes = MaterialTheme.shapes.copy(
                extraSmall = RoundedCornerShape(32.dp)
            ),
            colorScheme = MaterialTheme.colorScheme.copy(
                surface = if (isDark) Color(0xFF424242) else MaterialTheme.colorScheme.surfaceContainerHighest
            )
        ) {
            DropdownMenu(
                expanded = transitionState.currentState || transitionState.targetState,
                onDismissRequest = onDismissRequest,
                modifier = modifier
                    .width(menuWidth)
                    .heightIn(max = 280.dp),
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
    onConfirm: (String, String, String, String, String?, Int?, Float?) -> Unit,
    isImageMode: Boolean
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

    val isDark = isSystemInDarkTheme()
    AlertDialog(
        shape = RoundedCornerShape(32.dp),
        onDismissRequest = onDismissRequest,
        containerColor = if (isDark) Color.Black else MaterialTheme.colorScheme.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp).size(24.dp)
                )
                Column {
                    Text(
                        "添加配置",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "为模型平台配置API访问信息",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // 仅图像模式下限制平台列表为：即梦、硅基流动、Nano Banana
                val imageModeProviders = listOf("即梦", "硅基流动", "Nano Banana")
                val providersToShow = if (isImageMode) imageModeProviders else allProviders
                val isDefaultSel = false // 移除默认选项，此变量保持为 false
                val isGoogleProvider = provider.trim().lowercase() in listOf("google","谷歌")
                // 当平台为 Google 时，通道锁定为 Gemini
                LaunchedEffect(provider) {
                    if (isGoogleProvider) {
                        selectedChannel = "Gemini"
                    }
                }

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
                            // 图像模式下不允许新增/自定义平台（仅显示三项），隐藏右侧加号
                            if (!isImageMode) {
                                IconButton(onClick = {
                                    if (providerMenuExpanded && allProviders.isNotEmpty()) {
                                        providerMenuExpanded = false
                                    }
                                    onShowAddCustomProviderDialog()
                                }) {
                                    Icon(Icons.Outlined.Add, "添加自定义平台")
                                }
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
                                            // 图像模式下固定三项且不可删除，隐藏右侧删除按钮
                                            val lower = providerItem.lowercase().trim()
                                            val imageModeLocked = isImageMode && listOf("即梦","硅基流动","nano banana").contains(lower)
                                            val nonDeletableProviders = listOf(
                                                "openai compatible",
                                                "google",
                                                "硅基流动",
                                                "阿里云百炼",
                                                "火山引擎",
                                                "深度求索",
                                                "openrouter"
                                            )
                                            val canDelete = !imageModeLocked && !nonDeletableProviders.contains(lower)
                                            if (canDelete) {
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
                                        // 选平台时若为 Google/谷歌，则锁定渠道为 Gemini
                                        val low = providerItem.trim().lowercase()
                                        if (low == "google" || low == "谷歌") {
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

                // 当选择“默认”时隐藏渠道/地址/密钥等输入
                if (!isDefaultSel) {
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
                    if (selectedChannel != "Gemini") {
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
                        if (selectedChannel == "OpenAI兼容") {
                            Text(
                                text = "用法: 末尾#：直连 ； 末尾/：不加v1 ； 仅域名自动加v1",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp, bottom = 12.dp)
                            )
                        }
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
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { onConfirm(provider, apiAddress, apiKey, selectedChannel, imageSize, numInferenceSteps.toIntOrNull(), guidanceScale.toFloatOrNull()) },
                enabled = apiKey.isNotBlank() && provider.isNotBlank() && apiAddress.isNotBlank(),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.height(52.dp).padding(horizontal = 4.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).padding(end = 4.dp)
                )
                Text("确定添加", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.height(52.dp).padding(horizontal = 4.dp)
            ) {
                Text("取消", fontWeight = FontWeight.Medium)
            }
        },
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditConfigDialog(
    representativeConfig: com.android.everytalk.data.DataClass.ApiConfig,
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

    val isDark = isSystemInDarkTheme()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(32.dp),
        containerColor = if (isDark) Color.Black else MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(end = 12.dp).size(24.dp)
                )
                Column {
                    Text(
                        "编辑配置",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "修改API访问配置信息",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        },
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
                if (selectedChannel != "Gemini") {
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
                    if (selectedChannel == "OpenAI兼容") {
                        Text(
                            text = "用法: 末尾#：直连 ； 末尾/：不加v1 ； 仅域名自动加v1",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, bottom = 12.dp)
                        )
                    }
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
                var channelMenuExpanded by remember { mutableStateOf(false) }
                var channelTextFieldAnchorBounds by remember { mutableStateOf<Rect?>(null) }
                val channelMenuTransitionState = remember { MutableTransitionState(initialState = false) }
                val shouldShowChannelMenuLogical = channelMenuExpanded && channelTextFieldAnchorBounds != null

                LaunchedEffect(shouldShowChannelMenuLogical) {
                    channelMenuTransitionState.targetState = shouldShowChannelMenuLogical
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
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { onConfirm(apiAddress, apiKey, selectedChannel) },
                enabled = apiKey.isNotBlank() && apiAddress.isNotBlank(),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.height(52.dp).padding(horizontal = 4.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Text("保存更新", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.height(52.dp).padding(horizontal = 4.dp)
            ) {
                Text("取消", fontWeight = FontWeight.Medium)
            }
        }
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
        shape = RoundedCornerShape(32.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 12.dp).size(24.dp)
                )
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.5f
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "⚠️",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            "此操作不可撤销，请谨慎操作",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    onConfirm()
                    onDismissRequest()
                },
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.height(52.dp).padding(horizontal = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).padding(end = 4.dp)
                )
                Text("确认删除", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.height(52.dp).padding(horizontal = 4.dp)
            ) {
                Text("取消", fontWeight = FontWeight.Medium)
            }
        }
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
        title = {
            Text(
                "配置管理",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 导出配置卡片
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    onClick = onExport,
                    enabled = isExportEnabled
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "导出配置",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isExportEnabled)
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "保存当前配置到文件",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isExportEnabled)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "导出",
                            tint = if (isExportEnabled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                
                // 导入配置卡片
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                    onClick = onImport
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "导入配置",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "从文件加载配置",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowUp,
                            contentDescription = "导入",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                
                // 提示信息
                Text(
                    "💡 导出的配置文件可在其他设备导入使用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    "关闭",
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error
                )
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
        shape = RoundedCornerShape(32.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(end = 12.dp).size(24.dp)
                )
                Column {
                    Text(
                        "添加新模型",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "添加模型到当前配置组",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "输入模型的完整名称（例如: gpt-4, claude-3-opus, gemini-pro）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.5f
                )
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text("模型名称", fontWeight = FontWeight.Medium) },
                    placeholder = { Text("例如: gpt-4-turbo") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (modelName.isNotBlank()) onConfirm(modelName) }),
                    shape = RoundedCornerShape(16.dp),
                    colors = DialogTextFieldColors
                )
                // 提示信息
                if (modelName.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "✓",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                "将添加模型: $modelName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { onConfirm(modelName) },
                enabled = modelName.isNotBlank(),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.height(52.dp).padding(horizontal = 4.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).padding(end = 4.dp)
                )
                Text("添加模型", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.height(52.dp).padding(horizontal = 4.dp)
            ) {
                Text("取消", fontWeight = FontWeight.Medium)
            }
        }
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}