package com.example.everytalk.ui.screens.settings // 请确保包名与您的项目一致

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.everytalk.data.DataClass.ModalityType

// 导入 Accompanist FlowRow
import com.google.accompanist.flowlayout.FlowRow
import com.google.accompanist.flowlayout.MainAxisAlignment


val DialogTextFieldColors
    @Composable get() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.Black,
        unfocusedTextColor = Color.Black,
        disabledTextColor = Color.DarkGray,
        cursorColor = Color.Gray,
        focusedBorderColor = Color.Black,
        unfocusedBorderColor = Color.Black,
        disabledBorderColor = Color.LightGray,
        focusedLabelColor = Color.Black,
        unfocusedLabelColor = Color.Gray,
        disabledLabelColor = Color.DarkGray,
        focusedContainerColor = Color.White,
        unfocusedContainerColor = Color.White,
        disabledContainerColor = Color.White.copy(alpha = 0.8f)
    )
val DialogShape = RoundedCornerShape(16.dp)


// --- 选择模态类型的对话框 (FlowRow 版本) ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectModalityDialog(
    onDismissRequest: () -> Unit,
    onModalitySelected: (ModalityType) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                "选择模型类型",
                color = Color.Black,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp, horizontal = 8.dp),
                mainAxisSpacing = 12.dp,
                crossAxisSpacing = 12.dp,
                mainAxisAlignment = MainAxisAlignment.Center
            ) {
                ModalityType.values().forEach { modality ->
                    ModalityCapsuleButton(
                        modalityType = modality,
                        onClick = { onModalitySelected(modality) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.Red
                )
            ) { Text("取消") }
        },
        containerColor = Color.White,
        shape = DialogShape,
        titleContentColor = Color.Black,
        textContentColor = Color.Black
    )
}

@Composable
private fun ModalityCapsuleButton(
    modalityType: ModalityType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(50.dp),
                clip = false
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(50.dp),
        color = Color.White,
    ) {
        Text(
            text = modalityType.displayName,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = Color.Black,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
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
        title = { Text("添加新模型平台", color = Color.Black) },
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
                    containerColor = Color.Black,
                    contentColor = Color.White
                )
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.Red
                )
            ) { Text("取消") }
        },
        containerColor = Color.White,
        titleContentColor = Color.Black,
        textContentColor = Color.Black
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
    content: @Composable ColumnScope.() -> Unit
) {
    Log.d(
        "DropdownAnimation",
        "CustomStyledDropdownMenu: transitionState.currentState=${transitionState.currentState}, transitionState.targetState=${transitionState.targetState}, anchorBounds is null: ${anchorBounds == null}"
    )

    if ((transitionState.currentState || transitionState.targetState) && anchorBounds != null) {
        val density = LocalDensity.current
        val menuWidth = with(density) { anchorBounds.width.toDp() }

        val yAdjustmentDp: Dp = 74.dp // Adjust this value if needed
        val yAdjustmentInPx = with(density) { yAdjustmentDp.toPx() }.toInt()
        val yOffset = anchorBounds.bottom.toInt() - yAdjustmentInPx

        val xAdjustmentDp: Dp = 24.dp // Adjust this value if needed
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
                    color = Color.White,
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
    onDeleteProvider: (String) -> Unit, // 新增回调用于删除 Provider
    apiAddress: String,
    onApiAddressChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    var providerMenuExpanded by remember { mutableStateOf(false) }
    val focusRequesterApiKey = remember { FocusRequester() }
    var textFieldAnchorBounds by remember { mutableStateOf<Rect?>(null) }

    val providerMenuTransitionState = remember { MutableTransitionState(initialState = false) }
    val shouldShowCustomMenuLogical =
        providerMenuExpanded && allProviders.isNotEmpty() && textFieldAnchorBounds != null


    LaunchedEffect(shouldShowCustomMenuLogical) {
        providerMenuTransitionState.targetState = shouldShowCustomMenuLogical
    }

    LaunchedEffect(allProviders) {
        Log.d("DropdownDebug", "AddNewFullConfigDialog: allProviders size: ${allProviders.size}")
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("添加配置 (2/3)", color = Color.Black) },
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
                                                color = Color.Black,
                                                modifier = Modifier.weight(1f)
                                            )
                                            // 添加删除图标，仅为非默认的 provider 显示
                                            // 预定义的不可删除平台列表（与 AppViewModel 中的保持一致）
                                            val nonDeletableProviders = listOf(
                                                "openai compatible",
                                                "google",
                                                "硅基流动",
                                                "阿里云百炼",
                                                "火山引擎",
                                                "深度求索",
                                                "openrouter" // OpenRouter 移到列表末尾，确保使用小写进行比较
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
                                                        tint = Color.Gray
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onClick = {
                                        onProviderChange(providerItem)
                                        providerMenuExpanded = false
                                    },
                                    colors = MenuDefaults.itemColors(textColor = Color.Black)
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
                    keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                    shape = DialogShape,
                    colors = DialogTextFieldColors
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = apiKey.isNotBlank() && provider.isNotBlank() && apiAddress.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White
                )
            ) { Text("添加模型") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
            ) { Text("取消") }
        },
        containerColor = Color.White,
        titleContentColor = Color.Black,
        textContentColor = Color.Black
    )
    LaunchedEffect(Unit) { focusRequesterApiKey.requestFocus() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddModelToExistingKeyDialog(
    targetProvider: String,
    targetAddress: String,
    newModelName: String,
    onNewModelNameChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    val focusRequesterModelName = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("添加模型 (3/3)", color = Color.Black) },
        text = {
            Column {
                Text(
                    "平台: $targetProvider",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.DarkGray
                )
                Text(
                    "地址: $targetAddress",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.DarkGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = newModelName,
                    onValueChange = onNewModelNameChange,
                    label = { Text("模型名称") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequesterModelName),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                    shape = DialogShape,
                    colors = DialogTextFieldColors
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = newModelName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White
                )
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
            ) { Text("取消") }
        },
        containerColor = Color.White,
        titleContentColor = Color.Black,
        textContentColor = Color.Black
    )
    LaunchedEffect(Unit) { focusRequesterModelName.requestFocus() }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditConfigDialog(
    representativeConfig: com.example.everytalk.data.DataClass.ApiConfig,
    onDismissRequest: () -> Unit,
    onConfirm: (newAddress: String, newKey: String) -> Unit
) {
    var apiAddress by remember { mutableStateOf(representativeConfig.address) }
    var apiKey by remember { mutableStateOf(representativeConfig.key) }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("编辑配置", color = Color.Black) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "平台: ${representativeConfig.provider}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "类型: ${representativeConfig.modalityType.displayName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                Spacer(Modifier.height(16.dp))

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
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API密钥") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .focusRequester(focusRequester),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (apiKey.isNotBlank() && apiAddress.isNotBlank()) onConfirm(apiAddress, apiKey) }),
                    shape = DialogShape,
                    colors = DialogTextFieldColors
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(apiAddress, apiKey) },
                enabled = apiKey.isNotBlank() && apiAddress.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White
                )
            ) { Text("更新") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
            ) { Text("取消") }
        },
        containerColor = Color.White,
        shape = DialogShape,
        titleContentColor = Color.Black,
        textContentColor = Color.Black
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
        title = { Text(title, color = Color.Black) },
        text = { Text(text, color = Color.Black, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismissRequest()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFC62828),
                    contentColor = Color.White
                )
            ) {
                Text("确认删除")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.Black)
            ) {
                Text("取消")
            }
        },
        containerColor = Color.White,
        shape = DialogShape,
        titleContentColor = Color.Black,
        textContentColor = Color.Black
    )
}