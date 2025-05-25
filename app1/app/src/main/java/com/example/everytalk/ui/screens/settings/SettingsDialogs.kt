package com.example.everytalk.ui.screens.settings // 请确保包名与您的项目一致

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties


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

        val yAdjustmentDp: Dp = 74.dp
        val yAdjustmentInPx = with(density) { yAdjustmentDp.toPx() }.toInt()
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
                // --- 新的过渡动画效果 ---
                enter = fadeIn(animationSpec = tween(durationMillis = 200, delayMillis = 50)) +
                        slideInVertically(
                            animationSpec = tween(durationMillis = 250, delayMillis = 50),
                            initialOffsetY = { -it / 3 } // 从上方一点滑入 (it 是完整高度)
                        ),
                exit = fadeOut(animationSpec = tween(durationMillis = 150)) +
                        slideOutVertically(
                            animationSpec = tween(durationMillis = 200),
                            targetOffsetY = { -it / 3 } // 向上方一点滑出
                        )
                // --- 动画效果结束 ---
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
        Log.d(
            "DropdownAnimation",
            "shouldShowCustomMenuLogical: $shouldShowCustomMenuLogical, targetState set to: ${providerMenuTransitionState.targetState}"
        )
    }

    LaunchedEffect(allProviders) {
        Log.d(
            "DropdownDebug",
            "AddNewFullConfigDialog: allProviders size: ${allProviders.size}, isEmpty: ${allProviders.isEmpty()}"
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("添加配置 (1/2)", color = Color.Black) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ExposedDropdownMenuBox(
                    expanded = providerMenuExpanded,
                    onExpandedChange = {
                        providerMenuExpanded = !providerMenuExpanded
                        Log.d(
                            "DropdownDebug",
                            "ExposedDropdownMenuBox onExpandedChange. Toggled providerMenuExpanded to: $providerMenuExpanded"
                        )
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
                                Log.d(
                                    "DropdownDebug",
                                    "Anchor bounds updated: ${textFieldAnchorBounds}"
                                )
                            },
                        trailingIcon = {
                            IconButton(onClick = {
                                if (providerMenuExpanded) {
                                    providerMenuExpanded = false
                                    Log.d(
                                        "DropdownDebug",
                                        "Add custom provider icon clicked while menu was open, closing menu."
                                    )
                                }
                                onShowAddCustomProviderDialog()
                            }) { Icon(Icons.Outlined.Add, "添加自定义平台") }
                        },
                        shape = DialogShape,
                        colors = DialogTextFieldColors
                    )

                    Log.d(
                        "DropdownAnimation",
                        "Before CustomMenu: providerMenuExpanded=$providerMenuExpanded, " +
                                "allProvidersNotEmpty=${allProviders.isNotEmpty()}, " +
                                "anchorBoundsNotNull=${textFieldAnchorBounds != null}, " +
                                "shouldShowCustomMenuLogical=$shouldShowCustomMenuLogical, " +
                                "transition.currentState=${providerMenuTransitionState.currentState}, " +
                                "transition.targetState=${providerMenuTransitionState.targetState}"
                    )

                    CustomStyledDropdownMenu(
                        transitionState = providerMenuTransitionState,
                        onDismissRequest = {
                            providerMenuExpanded = false
                            Log.d(
                                "DropdownDebug",
                                "CustomStyledDropdownMenu dismissed via onDismissRequest."
                            )
                        },
                        anchorBounds = textFieldAnchorBounds
                    ) {
                        if (allProviders.isEmpty()) {
                            Log.d(
                                "DropdownDebug",
                                "CustomMenu content: allProviders is empty, showing no items."
                            )
                        }
                        allProviders.forEach { providerItem ->
                            Log.d(
                                "DropdownDebug",
                                "CustomMenu content: Rendering item: $providerItem"
                            )
                            DropdownMenuItem(
                                text = { Text(providerItem, color = Color.Black) },
                                onClick = {
                                    onProviderChange(providerItem)
                                    providerMenuExpanded = false
                                    Log.d(
                                        "DropdownDebug",
                                        "Item '$providerItem' selected. Closing menu."
                                    )
                                },
                                colors = MenuDefaults.itemColors(textColor = Color.Black)
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
            ) { Text("下一步：添加模型") }
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
        title = { Text("添加模型", color = Color.Black) },
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