package com.android.everytalk.ui.components.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.android.everytalk.R

/**
 * 全局统一可展开/收起胶囊搜索栏。
 *
 * 折叠状态展示 [collapsedContent]（如分类 Tab/芯片）与右侧搜索圆形按钮；
 * 展开状态平滑滑出输入框并自动对焦，右侧变为关闭按钮。
 */
@Composable
fun ExpandableSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    collapsedContent: @Composable () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val buttonBg = if (isDark) Color(0xFF303030) else Color.White
    val buttonContent = if (isDark) Color.White else Color(0xFF0D0D0D)
    val buttonBorder = if (isDark) Color(0xFF414141) else Color(0xFFECECEC)
    val fieldBg = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF2F2F2)
    val fieldBorder = if (isDark) Color(0xFF383838) else Color(0xFFE5E5E5)
    val iconMutedColor = if (isDark) Color(0xFF888888) else Color(0xFF999999)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // 折叠状态下的内容（Tab 标签/芯片）
        AnimatedVisibility(
            visible = !isExpanded,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier
                .padding(end = 52.dp)
                .fillMaxWidth(),
        ) {
            collapsedContent()
        }

        // 展开动画
        val searchAlpha by animateFloatAsState(
            targetValue = if (isExpanded) 1f else 0f,
            animationSpec = tween(durationMillis = 240),
            label = "expandable_search_alpha",
        )

        if (searchAlpha > 0f) {
            var isFocused by remember { mutableStateOf(false) }
            val focusRequester = remember { FocusRequester() }
            val focusManager = LocalFocusManager.current

            LaunchedEffect(isExpanded) {
                if (isExpanded) {
                    focusRequester.requestFocus()
                }
            }

            Box(
                modifier = Modifier
                    .padding(end = 52.dp)
                    .fillMaxWidth()
                    .height(46.dp)
                    .graphicsLayer {
                        alpha = searchAlpha
                        translationX = (1f - searchAlpha) * 15.dp.toPx()
                        scaleX = 0.96f + 0.04f * searchAlpha
                    }
                    .background(fieldBg, RoundedCornerShape(percent = 50))
                    .border(1.dp, fieldBorder, RoundedCornerShape(percent = 50))
                    .padding(start = 16.dp, end = if (query.isEmpty()) 16.dp else 40.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty() && !isFocused) {
                    Text(
                        text = placeholder,
                        color = iconMutedColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { isFocused = it.isFocused },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = buttonContent),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    cursorBrush = SolidColor(buttonContent),
                )

                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.settings_search_clear),
                            tint = iconMutedColor,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        // 位于右侧的展开/收起搜索圆形按钮
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(46.dp)
                .shadow(3.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(buttonBg)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Rounded.Close else Icons.Rounded.Search,
                contentDescription = if (isExpanded) stringResource(R.string.settings_search_clear) else stringResource(R.string.settings_search_hint),
                tint = buttonContent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
