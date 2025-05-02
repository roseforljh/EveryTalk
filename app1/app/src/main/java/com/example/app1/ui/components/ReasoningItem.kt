package com.example.app1.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ReasoningItem(
    reasoningText: String,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val reasoningScrollState = rememberScrollState()
    val reasoningBgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val reasoningTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val maxBubbleWidth = LocalConfiguration.current.screenWidthDp.dp * 0.85f
    val coroutineScope = rememberCoroutineScope()

    // Scroll to bottom when expanded and text changes
    LaunchedEffect(reasoningText, isExpanded) {
        if (isExpanded) {
            coroutineScope.launch {
                delay(100) // Allow layout
                try {
                    reasoningScrollState.animateScrollTo(reasoningScrollState.maxValue)
                } catch (e: Exception) {
                    println("Reasoning scroll error: ${e.message}")
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 8.dp,
                end = (LocalConfiguration.current.screenWidthDp.dp * 0.15f).coerceAtLeast(16.dp),
                top = 2.dp, bottom = 2.dp
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxBubbleWidth)
                .clip(
                    RoundedCornerShape(
                        topStart = 4.dp,
                        topEnd = 12.dp,
                        bottomStart = 12.dp,
                        bottomEnd = 12.dp
                    )
                )
                .background(reasoningBgColor.copy(alpha = 0.5f))
                .animateContentSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() }, // Add interaction source
                        indication = LocalIndication.current, // Use default indication
                        onClick = onToggleExpand
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Psychology,
                        "思考过程图标",
                        tint = reasoningTextColor.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "思考过程",
                        style = MaterialTheme.typography.labelMedium,
                        color = reasoningTextColor.copy(alpha = 0.8f)
                    )
                }
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (isExpanded) "收起" else "展开",
                    tint = reasoningTextColor.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
            }
            // AnimatedVisibility for the content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(initialAlpha = 0.3f),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(reasoningBgColor) // Full background for text area
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .heightIn(max = 250.dp) // Max height constraint
                        .verticalScroll(reasoningScrollState) // Make it scrollable
                ) {
                    SelectionContainer {
                        Text(
                            reasoningText,
                            style = MaterialTheme.typography.bodySmall,
                            color = reasoningTextColor,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
            }
        }
    }
}