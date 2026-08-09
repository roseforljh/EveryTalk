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
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import coil3.gif.onAnimationEnd
import coil3.gif.repeatCount
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.android.everytalk.R
import kotlinx.coroutines.delay

private const val FINISHED_FRAME_HOLD_MS = 500L

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
    val request = remember(context) {
        ImageRequest.Builder(context)
            .data(R.drawable.pixel_penguin_splash)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .crossfade(100)
            .repeatCount(0)
            .onAnimationEnd { animationFinished.value = true }
            .build()
    }

    LaunchedEffect(animationFinished.value) {
        if (animationFinished.value) {
            currentOnFinalFrameVisible.value()
            delay(FINISHED_FRAME_HOLD_MS)
            currentOnFinished.value()
        }
    }

    AsyncImage(
        model = request,
        contentDescription = null,
        placeholder = painterResource(R.drawable.pixel_penguin_logo),
        modifier = modifier
            .fillMaxSize()
            .background(Color.White),
        contentScale = ContentScale.Fit,
        onError = {
            currentOnFinalFrameVisible.value()
            currentOnFinished.value()
        },
    )
}
