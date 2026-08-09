package com.android.everytalk.ui.components.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

private const val MAIN_CONTENT_START_DELAY_MS = 100L
private const val FINISHED_FRAME_HOLD_MS = 500L

@Composable
fun PixelPenguinSplash(
    modifier: Modifier = Modifier,
    onAnimationVisible: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnAnimationVisible = rememberUpdatedState(onAnimationVisible)
    val currentOnFinished = rememberUpdatedState(onFinished)
    val animationStarted = remember { mutableStateOf(false) }
    val animationFinished = remember { mutableStateOf(false) }
    val request = remember(context) {
        ImageRequest.Builder(context)
            .data(R.drawable.pixel_penguin_splash)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .repeatCount(0)
            .onAnimationStart { animationStarted.value = true }
            .onAnimationEnd { animationFinished.value = true }
            .build()
    }

    LaunchedEffect(animationStarted.value) {
        if (animationStarted.value) {
            delay(MAIN_CONTENT_START_DELAY_MS)
            currentOnAnimationVisible.value()
        }
    }

    LaunchedEffect(animationFinished.value) {
        if (animationFinished.value) {
            delay(FINISHED_FRAME_HOLD_MS)
            currentOnFinished.value()
        }
    }

    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier
            .fillMaxSize()
            .background(Color.White),
        contentScale = ContentScale.Fit,
        onError = {
            currentOnAnimationVisible.value()
            currentOnFinished.value()
        },
    )
}
