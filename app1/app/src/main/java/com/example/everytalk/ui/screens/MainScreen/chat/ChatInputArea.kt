package com.example.everytalk.ui.screens.MainScreen.chat

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.everytalk.data.DataClass.ApiConfig // Ensure this path is correct
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputArea(
    text: String,
    onTextChange: (String) -> Unit,
    onSendMessageRequest: (messageText: String, isKeyboardVisible: Boolean) -> Unit, // Modified callback
    isApiCalling: Boolean,
    isWebSearchEnabled: Boolean,
    onToggleWebSearch: () -> Unit,
    onClearText: () -> Unit,
    onStopApiCall: () -> Unit,
    focusRequester: FocusRequester,
    selectedApiConfig: ApiConfig?,
    onShowSnackbar: (String) -> Unit,
    imeInsets: WindowInsets,
    density: Density,
    keyboardController: SoftwareKeyboardController?,
    onFocusChange: (isFocused: Boolean) -> Unit // Callback for focus changes
) {
    // This state is now managed within ChatInputArea as it's closely tied to its send logic.
    var pendingMessageTextForSend by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        snapshotFlow { imeInsets.getBottom(density) > 0 }
            .distinctUntilChanged()
            .filter { isKeyboardVisible -> !isKeyboardVisible && pendingMessageTextForSend != null }
            .collect {
                pendingMessageTextForSend?.let { msg ->
                    Log.d("ChatInputArea", "Keyboard hidden, sending pending message: $msg")
                    // When keyboard hides and there was a pending message, use the onSendMessageRequest.
                    // The isKeyboardVisible is now false.
                    onSendMessageRequest(msg, false)
                    pendingMessageTextForSend = null // Clear after attempting to send
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), clip = false)
            .background(
                Color.White, // Or MaterialTheme.colorScheme.surfaceContainer
                RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            )
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChange(it.isFocused) } // Use callback
                .padding(bottom = 4.dp),
            placeholder = { Text("输入消息…") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                focusedContainerColor = Color.White, // Or MaterialTheme.colorScheme.surface
                unfocusedContainerColor = Color.White, // Or MaterialTheme.colorScheme.surface
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
            ),
            minLines = 1, maxLines = 5,
            shape = RoundedCornerShape(16.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggleWebSearch,
            ) {
                Icon(
                    imageVector = Icons.Filled.TravelExplore,
                    contentDescription = if (isWebSearchEnabled) "关闭联网搜索" else "开启联网搜索",
                    tint = if (isWebSearchEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (text.isNotEmpty()) {
                    IconButton(onClick = onClearText) { // Use callback
                        Icon(
                            Icons.Filled.Clear, "清除内容",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                FilledIconButton(
                    onClick = {
                        if (isApiCalling) {
                            onStopApiCall() // Use callback
                        } else if (text.isNotBlank() && selectedApiConfig != null) {
                            val isKeyboardCurrentlyVisible = imeInsets.getBottom(density) > 0
                            if (isKeyboardCurrentlyVisible) {
                                // Store text, clear input, and request keyboard hide.
                                // The actual sending will happen via LaunchedEffect when keyboard hides.
                                pendingMessageTextForSend = text
                                onTextChange("") // Clear current input immediately
                                keyboardController?.hide()
                                Log.d("ChatInputArea", "Keyboard visible, text stored: $pendingMessageTextForSend. Hiding keyboard.")
                            } else {
                                // Keyboard not visible, send directly.
                                Log.d("ChatInputArea", "Keyboard not visible, sending directly: $text")
                                onSendMessageRequest(text, false)
                            }
                        } else if (selectedApiConfig == null) {
                            onShowSnackbar("请先选择 API 配置")
                        } else {
                            onShowSnackbar("请输入消息内容")
                        }
                    },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Black, // Or MaterialTheme.colorScheme.primary
                        contentColor = Color.White    // Or MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        if (isApiCalling) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                        if (isApiCalling) "停止" else "发送"
                    )
                }
            }
        }
    }
}