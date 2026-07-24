package com.android.everytalk.ui.components.markdown
import com.android.everytalk.statecontroller.*

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.everytalk.ui.components.streaming.DetailsRequest

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
                        stateDescription = if (expanded) "已展开" else "已收起"
                    }
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "收起" else "展开",
                    ) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = summary,
                    style = summaryStyle,
                    inlineContent = summaryInlineContent,
                    modifier = Modifier.weight(1f),
                )
            }

            if (expanded) {
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
