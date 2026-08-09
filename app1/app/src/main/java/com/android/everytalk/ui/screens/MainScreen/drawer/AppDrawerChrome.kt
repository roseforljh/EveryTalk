package com.android.everytalk.ui.screens.MainScreen

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.android.everytalk.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrawerSearchBar(
    value: String,
    isSearchActive: Boolean,
    onValueChange: (String) -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    focusManager: FocusManager,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { newQuery ->
                onValueChange(newQuery)
                if (newQuery.isNotBlank() && !isSearchActive) {
                    onSearchActiveChange(true)
                }
            },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .focusRequester(focusRequester)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(50),
                    clip = false,
                    spotColor = Color.Black.copy(alpha = 0.22f),
                    ambientColor = Color.Black.copy(alpha = 0.16f),
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(50),
                ),
            placeholder = { Text("搜索历史记录") },
            leadingIcon = {
                Crossfade(
                    targetState = isSearchActive,
                    animationSpec = tween(EXPAND_ANIMATION_DURATION_MS),
                    label = "SearchIconCrossfade",
                ) { active ->
                    IconButton(
                        onClick = { onSearchActiveChange(!active) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            painter = painterResource(if (active) R.drawable.ic_arrow_back else R.drawable.ic_search),
                            contentDescription = if (active) "返回" else "搜索图标",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            trailingIcon = {
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(painterResource(R.drawable.ic_close), "清除搜索")
                    }
                }
            },
            shape = RoundedCornerShape(50),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
            singleLine = true,
            interactionSource = interactionSource,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        )
    }
}

@Composable
internal fun DrawerPrimaryActions(
    isImageGenerationMode: Boolean,
    onNewChatClick: () -> Unit,
    onClearClick: () -> Unit,
    onImageGenerationClick: () -> Unit,
) {
    Column {
        Spacer(Modifier.height(8.dp))
        DrawerActionButton(
            iconRes = R.drawable.ic_plus,
            label = if (isImageGenerationMode) "新建图像生成" else "新建会话",
            onClick = if (isImageGenerationMode) onImageGenerationClick else onNewChatClick,
        )
        Spacer(Modifier.height(5.dp))
        DrawerActionButton(
            iconRes = R.drawable.ic_trash,
            label = "清空记录",
            onClick = onClearClick,
        )
        Spacer(Modifier.height(5.dp))
        DrawerActionButton(
            iconRes = if (isImageGenerationMode) R.drawable.ic_writing else R.drawable.ic_image_gallery,
            label = if (isImageGenerationMode) "文本生成" else "图像生成",
            onClick = if (isImageGenerationMode) onNewChatClick else onImageGenerationClick,
        )
    }
}

@Composable
private fun DrawerActionButton(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(20.dp))
            Text(
                text = label,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
            )
        }
    }
}

@Composable
internal fun DrawerAppInfoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(52.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        shadowElevation = 8.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.gpt_user),
                contentDescription = "应用信息",
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
