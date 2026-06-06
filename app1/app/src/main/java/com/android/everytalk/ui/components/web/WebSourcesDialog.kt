package com.android.everytalk.ui.components

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil3.compose.AsyncImage
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogSubtextColor
import java.net.URI

private fun sourceHost(href: String): String {
    return runCatching {
        URI(href).host?.removePrefix("www.") ?: ""
    }.getOrDefault("")
}

private fun sourceFaviconUrl(href: String): String {
    val host = sourceHost(href)
    return if (host.isBlank()) "" else "https://www.google.com/s2/favicons?domain=$host&sz=64"
}

private fun sourceInitial(source: WebSearchResult): String {
    val raw = sourceHost(source.href).ifBlank { source.title }.trim()
    return raw.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

@Composable
fun WebSourcesDialog(
    sources: List<WebSearchResult>,
    onDismissRequest: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialogWindow?.setDimAmount(0f)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .clip(AppDialogShape)
                    .border(1.dp, appDialogBorderColor(), AppDialogShape),
                shape = AppDialogShape,
                color = appDialogContainerColor(),
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp)
                ) {
                    sources.forEachIndexed { index, source ->
                        SourceItem(
                            source = source,
                            onOpen = {
                                try {
                                    uriHandler.openUri(source.href)
                                } catch (_: Exception) {
                                }
                            }
                        )
                        if (index != sources.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 12.dp),
                                color = appDialogBorderColor()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceItem(
    source: WebSearchResult,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        verticalAlignment = Alignment.Top
    ) {
        SourceFavicon(source = source)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${source.index}. ${source.title}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = appDialogContentColor()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildAnnotatedString {
                    withStyle(
                        style = SpanStyle(
                            color = appDialogContentColor(),
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(source.href)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (source.snippet.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = source.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = appDialogSubtextColor()
                )
            }
        }
    }
}

@Composable
private fun SourceFavicon(source: WebSearchResult) {
    val iconSize = 34.dp
    Box(
        modifier = Modifier
            .size(iconSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = sourceInitial(source),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val faviconUrl = sourceFaviconUrl(source.href)
        if (faviconUrl.isNotBlank()) {
            AsyncImage(
                model = faviconUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(iconSize)
                    .clip(CircleShape)
            )
        }
    }
}
