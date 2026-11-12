package com.android.everytalk.ui.components

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.android.everytalk.util.web.linkIcon
import com.android.everytalk.util.web.remoteFaviconUrls

/**
 * 显示网页图标：
 * 1. 命中内置默认图标时直接显示；
 * 2. 未命中时先显示统一地球图标，同时后台按候选地址依次获取在线网站图标，成功即替换，全部失败则保留统一地球图标。
 */
@Composable
fun WebsiteIcon(
    href: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val icon = remember(href) { linkIcon(href) }
    val remoteUrls = remember(href) { remoteFaviconUrls(href) }
    var candidateIndex by remember(remoteUrls) { mutableIntStateOf(0) }
    val currentRemoteUrl = remoteUrls.getOrNull(candidateIndex)

    if (currentRemoteUrl != null) {
        val remotePainter = rememberAsyncImagePainter(
            model = currentRemoteUrl,
            onError = {
                if (candidateIndex < remoteUrls.size) {
                    candidateIndex += 1
                }
            },
        )
        val painterState by remotePainter.state.collectAsState()
        if (painterState is AsyncImagePainter.State.Success) {
            Image(
                painter = remotePainter,
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = ContentScale.Fit,
            )
            return
        }
    }

    Image(
        painter = painterResource(icon.resource),
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = if (icon.multicolor) null else ColorFilter.tint(tint),
    )
}
