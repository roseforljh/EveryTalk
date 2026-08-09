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
import coil3.gif.onAnimationStart
import coil3.gif.repeatCount
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.android.everytalk.R
import kotlinx.coroutines.delay

private const val FINISHED_FRAME_HOLD_MS = 200L
private const val FADE_OUT_DURATION_MS = 200

@Composable
fun PixelPenguinSplash(
    modifier: Modifier = Modifier,
    startAnimation: Boolean = true,
    onAnimationStarted: () -> Unit = {},
    onFinalFrameVisible: () -> Unit,
    onFinished: () -> Unit,
) {
    if (!startAnimation) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.White),
        )
        return
    }

    val context = LocalContext.current
    val currentOnAnimationStarted = rememberUpdatedState(onAnimationStarted)
    val currentOnFinalFrameVisible = rememberUpdatedState(onFinalFrameVisible)
    val currentOnFinished = rememberUpdatedState(onFinished)
    val animationFinished = remember { mutableStateOf(false) }
    val splashAlpha = remember { Animatable(1f) }
    val request = remember(context) {
        ImageRequest.Builder(context)
            .data(R.drawable.pixel_penguin_splash)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .repeatCount(0)
            .onAnimationStart { currentOnAnimationStarted.value() }
            .onAnimationEnd { animationFinished.value = true }
            .build()
    }

    LaunchedEffect(animationFinished.value) {
        if (animationFinished.value) {
            currentOnFinalFrameVisible.value()
            delay(FINISHED_FRAME_HOLD_MS)
            splashAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = FADE_OUT_DURATION_MS),
            )
            currentOnFinished.value()
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
            onError = {
                currentOnAnimationStarted.value()
                currentOnFinalFrameVisible.value()
                currentOnFinished.value()
            },
        )
    }
}
