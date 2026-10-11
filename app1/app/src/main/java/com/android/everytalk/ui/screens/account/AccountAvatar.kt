package com.android.everytalk.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.compose.AsyncImage

/** 账户页与侧栏复用同一显示规则；图片加载失败时仍有昵称首字，游客保留默认图标。 */
@Composable
internal fun AccountAvatar(avatarUrl: String?, name: String?, description: String, modifier: Modifier = Modifier) {
    // URL 变化重新等待解码；缓存命中也经过短淡入，避免抽屉首次挂载时头像突然跳出。
    var imageReady by remember(avatarUrl) { mutableStateOf(false) }
    val imageAlpha by animateFloatAsState(if (imageReady) 1f else 0f,
        animationSpec = tween(220), label = "AccountAvatarFadeIn")
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        // 占位层始终存在，覆盖 Empty、加载中、失败和淡入过程，任何一帧都不会透明。
        if (name.isNullOrBlank()) Icon(Icons.Default.AccountCircle, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxSize())
        else Text(String(Character.toChars(name.codePointAt(0))), style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface)
        if (!avatarUrl.isNullOrBlank()) AsyncImage(
            model = avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha },
            onLoading = { imageReady = false }, onError = { imageReady = false },
            onSuccess = { imageReady = true },
        )
    }
}
