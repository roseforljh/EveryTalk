package com.android.everytalk.ui.screens.settings

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.data.DataClass.ModalityType

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
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    )
val DialogShape = RoundedCornerShape(12.dp)

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
    // val focusRequester = remember { FocusRequester() } // Removed auto-focus

    val isDarkTheme = isSystemInDarkTheme()
    val cancelButtonColor = if (isDarkTheme) Color(0xFFFF5252) else Color(0xFFD32F2F)
    val confirmButtonColor = if (isDarkTheme) Color.White else Color(0xFF212121)
    val confirmButtonTextColor = if (isDarkTheme) Color.Black else Color.White

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
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SettingsFieldLabel("平台名称")
                    OutlinedTextField(
                        value = newProviderName,
                        onValueChange = onNewProviderNameChange,
                        placeholder = { Text("例如: OpenRouter, Anthropic...") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth(),
                            // .focusRequester(focusRequester), // Removed auto-focus
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (newProviderName.isNotBlank()) onConfirm() }),
                        shape = DialogShape,
                        colors = DialogTextFieldColors
                    )
                }
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
                                "",
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 取消按钮（红色描边）
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = cancelButtonColor
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, cancelButtonColor)
                ) {
                    Text(
                        text = "取消",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                // 确定按钮（与语音模式一致）
                Button(
                    onClick = onConfirm,
                    enabled = newProviderName.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = confirmButtonColor,
                        contentColor = confirmButtonTextColor,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 4.dp)
                    )
                    Text(
                        text = "添加平台",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        },
        dismissButton = {}
    )

    // LaunchedEffect(Unit) {
    //     focusRequester.requestFocus() // Removed auto-focus
    // }
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
        // 使用 DropdownMenu 并设置样式和颜色
        MaterialTheme(
            shapes = MaterialTheme.shapes.copy(
                extraSmall = RoundedCornerShape(32.dp)
            ),
            colorScheme = MaterialTheme.colorScheme.copy(
                surface = MaterialTheme.colorScheme.surfaceContainerHighest
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
    onConfirm: (String, String, String, String, String?, Int?, Float?, Boolean?, String?) -> Unit,
    isImageMode: Boolean
) {
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var channelMenuExpanded by remember { mutableStateOf(false) }
    val channels = listOf("OpenAI兼容", "Gemini")
    var selectedChannel by remember { mutableStateOf(channels.first()) }
    // val focusRequesterApiKey = remember { FocusRequester() } // Removed auto-focus
    var textFieldAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    var channelTextFieldAnchorBounds by remember { mutableStateOf<Rect?>(null) }
   var imageSize by remember { mutableStateOf("1024x1024") }
   var numInferenceSteps by remember { mutableStateOf("20") }
   var guidanceScale by remember { mutableStateOf("7.5") }
    // 新增：Function Calling 配置
    var enableCodeExecution by remember { mutableStateOf(false) }
    var toolsJson by remember { mutableStateOf("") }
    var showToolsConfig by remember { mutableStateOf(false) }

    val isDarkTheme = isSystemInDarkTheme()
    val cancelButtonColor = if (isDarkTheme) Color(0xFFFF5252) else Color(0xFFD32F2F)
    val confirmButtonColor = if (isDarkTheme) Color.White else Color(0xFF212121)
    val confirmButtonTextColor = if (isDarkTheme) Color.Black else Color.White

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
    // 确保进入对话框时不显示“默认”占位值
    LaunchedEffect(Unit) {
        val lower = provider.trim().lowercase()
        if (lower in listOf("默认","default","default_text")) {
            onProviderChange("")
        }
    }

    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val dialogHeight = screenHeight * 0.8f

    AlertDialog(
        modifier = Modifier.height(dialogHeight),
        shape = RoundedCornerShape(32.dp),
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
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
                // 图像模式与文本模式一致：展示所有可用平台
                val providersToShow = allProviders.filter {
                    val lower = it.trim().lowercase()
                    lower !in listOf("默认", "default", "default_text")
                }
                val isDefaultSel = false // 移除默认选项，此变量保持为 false
                val isGoogleProvider = provider.trim().lowercase() in listOf("google","谷歌")
                // 当平台为 Google 时，通道锁定为 Gemini
                LaunchedEffect(provider) {
                    if (isGoogleProvider) {
                        selectedChannel = "Gemini"
                    }
                }

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
                            .menuAnchor()
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                textFieldAnchorBounds = coordinates.boundsInWindow()
                            },
                        trailingIcon = {
                            // 统一允许添加自定义平台
                            IconButton(onClick = {
                                if (providerMenuExpanded && providersToShow.isNotEmpty()) {
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
                                            val lower = providerItem.lowercase().trim()
                                            val nonDeletableProviders = listOf(
                                                "openai compatible",
                                                "google",
                                                "阿里云百炼",
                                                "火山引擎",
                                                "深度求索",
                                                "openrouter",
                                                "硅基流动",
                                                "siliconflow",
                                                "siliconflow",
                                                "seedream",
                                                "gemini"
                                            )
                                            val canDelete = !nonDeletableProviders.contains(lower)
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

                    SettingsFieldLabel("API接口地址")
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

                    SettingsFieldLabel("API密钥")
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = onApiKeyChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                            // .focusRequester(focusRequesterApiKey), // Removed auto-focus
                        singleLine = true,
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (apiKey.isNotBlank() && provider.isNotBlank() && apiAddress.isNotBlank()) {
                               onConfirm(provider, apiAddress, apiKey, selectedChannel, imageSize, numInferenceSteps.toIntOrNull(), guidanceScale.toFloatOrNull(), enableCodeExecution, toolsJson.ifBlank { null })
                            }
                        }),
                        shape = DialogShape,
                        colors = DialogTextFieldColors
                    )

                    // 工具配置区域 (仅文本模式)
                    if (!isImageMode) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showToolsConfig = !showToolsConfig }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "高级工具配置",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (showToolsConfig) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = null
                            )
                        }
                        
                        AnimatedVisibility(
                            visible = showToolsConfig,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { enableCodeExecution = !enableCodeExecution }
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "启用代码执行",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    RadioButton(
                                        selected = enableCodeExecution,
                                        onClick = null // null to pass click to parent Row
                                    )
                                }
                                
                                SettingsFieldLabel("自定义 Tools (JSON)")
                                OutlinedTextField(
                                    value = toolsJson,
                                    onValueChange = { toolsJson = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(150.dp)
                                        .padding(vertical = 8.dp),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                    placeholder = { Text("[\n  {\n    \"type\": \"function\",\n    \"function\": { ... }\n  }\n]") },
                                    shape = DialogShape,
                                    colors = DialogTextFieldColors
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val canSubmit = apiKey.isNotBlank()
                    && apiAddress.isNotBlank()
                    && provider.isNotBlank()
                    && provider.trim().lowercase() !in listOf("默认","default","default_text")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 取消按钮（红色描边）
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = cancelButtonColor
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, cancelButtonColor)
                ) {
                    Text(
                        text = "取消",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                // 确定按钮（与语音模式一致）
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
                                enableCodeExecution,
                                toolsJson.ifBlank { null }
                            )
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = confirmButtonColor,
                        contentColor = confirmButtonTextColor,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 4.dp)
                    )
                    Text(
                        text = "确定添加",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        },
        dismissButton = {},
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
    onConfirm: (newProvider: String, newAddress: String, newKey: String, newChannel: String, newEnableCodeExecution: Boolean?, newToolsJson: String?) -> Unit
) {
    Log.d("EditConfigDialog", "Opening dialog for config: ${representativeConfig.name}, provider: ${representativeConfig.provider}")
    var provider by remember { mutableStateOf(representativeConfig.provider) }
    var apiAddress by remember { mutableStateOf(representativeConfig.address) }
    var apiKey by remember { mutableStateOf(representativeConfig.key) }
    var selectedChannel by remember { mutableStateOf(representativeConfig.channel) }
    // 新增：Function Calling 编辑
    var enableCodeExecution by remember { mutableStateOf(representativeConfig.enableCodeExecution ?: false) }
    var toolsJson by remember { mutableStateOf(representativeConfig.toolsJson ?: "") }
    var showToolsConfig by remember { mutableStateOf(false) }

    val isDarkTheme = isSystemInDarkTheme()
    val cancelButtonColor = if (isDarkTheme) Color(0xFFFF5252) else Color(0xFFD32F2F)
    val confirmButtonColor = if (isDarkTheme) Color.White else Color(0xFF212121)
    val confirmButtonTextColor = if (isDarkTheme) Color.Black else Color.White

    // val focusRequester = remember { FocusRequester() } // Removed auto-focus
     
    // 固定的渠道类型选项
    val channelTypes = listOf("OpenAI兼容", "Gemini")

    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val dialogHeight = screenHeight * 0.8f

    AlertDialog(
        modifier = Modifier.height(dialogHeight),
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
                SettingsFieldLabel("API密钥")
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                        // .focusRequester(focusRequester), // Removed auto-focus
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

                // 工具配置区域 (仅文本模式)
                if (representativeConfig.modalityType == ModalityType.TEXT) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showToolsConfig = !showToolsConfig }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "高级工具配置",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (showToolsConfig) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }
                    
                    AnimatedVisibility(
                        visible = showToolsConfig,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { enableCodeExecution = !enableCodeExecution }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "启用代码执行",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                RadioButton(
                                    selected = enableCodeExecution ?: false,
                                    onClick = null // null to pass click to parent Row
                                )
                            }
                            
                            SettingsFieldLabel("自定义 Tools (JSON)")
                            OutlinedTextField(
                                value = toolsJson,
                                onValueChange = { toolsJson = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp)
                                    .padding(vertical = 8.dp),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                placeholder = { Text("[\n  {\n    \"type\": \"function\",\n    \"function\": { ... }\n  }\n]") },
                                shape = DialogShape,
                                colors = DialogTextFieldColors
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val canSubmit = apiKey.isNotBlank() && apiAddress.isNotBlank() && provider.isNotBlank()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 取消按钮（红色描边）
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = cancelButtonColor
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, cancelButtonColor)
                ) {
                    Text(
                        text = "取消",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                // 保存按钮（与语音模式一致）
                Button(
                    onClick = {
                        if (canSubmit) {
                            onConfirm(
                                provider,
                                apiAddress,
                                apiKey,
                                selectedChannel,
                                enableCodeExecution,
                                toolsJson.ifBlank { null }
                            )
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = confirmButtonColor,
                        contentColor = confirmButtonTextColor,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Text(
                        text = "保存更新",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        },
        dismissButton = {}
    )

    // LaunchedEffect(Unit) {
    //     focusRequester.requestFocus() // Removed auto-focus
    // }
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
                            "",
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
    onExport: (includeHistory: Boolean) -> Unit,
    onImport: () -> Unit,
    isExportEnabled: Boolean,
    chatHistoryCount: Int,
    imageHistoryCount: Int
) {
    var includeHistory by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(28.dp),
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 导出配置卡片
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                    onClick = { onExport(includeHistory) },
                    enabled = isExportEnabled
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 20.dp),
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
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // 包含历史选项
                AnimatedVisibility(
                    visible = chatHistoryCount > 0 || imageHistoryCount > 0,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { includeHistory = !includeHistory },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = includeHistory,
                                onCheckedChange = { includeHistory = it }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    "包含聊天历史",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "文本: $chatHistoryCount 个会话, 图像: $imageHistoryCount 个会话",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // 警告提示
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Text(
                                "导出文件包含API密钥等敏感信息，请妥善保管",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                
                // 导入配置卡片
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.15f),
                    onClick = onImport
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 20.dp),
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
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                
                // 提示信息
                Text(
                    "导出的配置文件可在其他设备导入使用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp)
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
    // val focusRequester = remember { FocusRequester() } // Removed auto-focus

    val isDarkTheme = isSystemInDarkTheme()
    val cancelButtonColor = if (isDarkTheme) Color(0xFFFF5252) else Color(0xFFD32F2F)
    val confirmButtonColor = if (isDarkTheme) Color.White else Color(0xFF212121)
    val confirmButtonTextColor = if (isDarkTheme) Color.Black else Color.White

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
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SettingsFieldLabel("模型名称")
                    OutlinedTextField(
                        value = modelName,
                        onValueChange = { modelName = it },
                        placeholder = { Text("例如: gpt-4-turbo") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth(),
                            // .focusRequester(focusRequester), // Removed auto-focus
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (modelName.isNotBlank()) onConfirm(modelName) }),
                        shape = DialogShape,
                        colors = DialogTextFieldColors
                    )
                }
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
                                "",
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 取消按钮（红色描边）
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = cancelButtonColor
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, cancelButtonColor)
                ) {
                    Text(
                        text = "取消",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                // 确定按钮（与语音模式一致）
                Button(
                    onClick = { onConfirm(modelName) },
                    enabled = modelName.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = confirmButtonColor,
                        contentColor = confirmButtonTextColor,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 4.dp)
                    )
                    Text(
                        text = "添加模型",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        },
        dismissButton = {}
    )

    // LaunchedEffect(Unit) {
    //     focusRequester.requestFocus() // Removed auto-focus
    // }
}