package com.android.everytalk.ui.components.markdown
import com.android.everytalk.statecontroller.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.android.everytalk.R
import com.android.everytalk.ui.components.streaming.DetailsRequest

private const val DETAILS_HEIGHT_ANIMATION_MS = 240
private const val DETAILS_EXPAND_FADE_MS = 160
private const val DETAILS_COLLAPSE_FADE_MS = 120
private const val DETAILS_ARROW_ANIMATION_MS = 200

@Composable
internal fun MarkdownDetailsBlock(
    request: DetailsRequest,
    modifier: Modifier = Modifier,
    summary: AnnotatedString = AnnotatedString(decodeMarkdownHtmlEntities(request.summary)),
    summaryInlineContent: Map<String, InlineTextContent> = emptyMap(),
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(request.id, request.contentVersion) { mutableStateOf(false) }
    val summaryStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
    val expandedStateDescription = stringResource(R.string.state_expanded)
    val collapsedStateDescription = stringResource(R.string.state_collapsed)
    val expandAction = stringResource(R.string.action_expand)
    val collapseAction = stringResource(R.string.action_collapse)
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(DETAILS_ARROW_ANIMATION_MS, easing = FastOutSlowInEasing),
        label = "markdownDetailsArrow",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        stateDescription = if (expanded) expandedStateDescription else collapsedStateDescription
                    }
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) collapseAction else expandAction,
                    ) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.graphicsLayer { rotationZ = arrowRotation },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = summary,
                    style = summaryStyle,
                    inlineContent = summaryInlineContent,
                    modifier = Modifier.weight(1f),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(DETAILS_EXPAND_FADE_MS)) + expandVertically(
                    animationSpec = tween(DETAILS_HEIGHT_ANIMATION_MS, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Top,
                ),
                exit = fadeOut(tween(DETAILS_COLLAPSE_FADE_MS)) + shrinkVertically(
                    animationSpec = tween(DETAILS_HEIGHT_ANIMATION_MS, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Top,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = 12.dp,
                    )
                ) {
                    content()
                }
            }
        }
    }
}
