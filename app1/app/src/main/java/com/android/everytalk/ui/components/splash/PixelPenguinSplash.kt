package com.android.everytalk.ui.components.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.gif.onAnimationEnd
import coil3.gif.repeatCount
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.android.everytalk.R
import kotlinx.coroutines.delay

private const val FINISHED_FRAME_HOLD_MS = 200L
private const val FADE_OUT_DURATION_MS = 200
private const val MAX_SPLASH_DISPLAY_MS = 2_000L

@Composable
fun PixelPenguinSplash(
    modifier: Modifier = Modifier,
    onFinalFrameVisible: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnFinalFrameVisible = rememberUpdatedState(onFinalFrameVisible)
    val currentOnFinished = rememberUpdatedState(onFinished)
    val animationFinished = remember { mutableStateOf(false) }
    val mainContentStarted = remember { mutableStateOf(false) }
    val splashFinished = remember { mutableStateOf(false) }
    val splashAlpha = remember { Animatable(1f) }

    /** 确保主界面和启动屏完成回调都只执行一次。 */
    fun finishSplash() {
        if (splashFinished.value) return
        splashFinished.value = true
        if (!mainContentStarted.value) {
            mainContentStarted.value = true
            currentOnFinalFrameVisible.value()
        }
        currentOnFinished.value()
    }

    val request = remember(context) {
        ImageRequest.Builder(context)
            .data(R.drawable.pixel_penguin_splash)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .repeatCount(0)
            .onAnimationEnd { animationFinished.value = true }
            .build()
    }

    // 部分厂商系统可能不触发 GIF 结束回调。超过上限后直接进入主界面，避免永久白屏。
    LaunchedEffect(Unit) {
        delay(MAX_SPLASH_DISPLAY_MS)
        finishSplash()
    }

    LaunchedEffect(animationFinished.value) {
        if (animationFinished.value && !splashFinished.value) {
            if (!mainContentStarted.value) {
                mainContentStarted.value = true
                currentOnFinalFrameVisible.value()
            }
            delay(FINISHED_FRAME_HOLD_MS)
            splashAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = FADE_OUT_DURATION_MS),
            )
            finishSplash()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = splashAlpha.value }
            .background(Color.White),
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            onError = { finishSplash() },
        )
    }
}
