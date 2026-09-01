package com.android.everytalk.ui.components.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
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
    val isDarkTheme = isSystemInDarkTheme()
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
            .background(if (isDarkTheme) Color.Black else Color.White),
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            // 夜间模式对整张 GIF 做反色，白底变黑底，黑色图案变白色。
            colorFilter = if (isDarkTheme) {
                ColorFilter.colorMatrix(
                    ColorMatrix(
                        // Android ColorMatrix 使用 0..255 的颜色通道值，反色偏移必须是 255。
                        floatArrayOf(
                            -1f, 0f, 0f, 0f, 255f,
                            0f, -1f, 0f, 0f, 255f,
                            0f, 0f, -1f, 0f, 255f,
                            0f, 0f, 0f, 1f, 0f,
                        )
                    )
                )
            } else {
                null
            },
            onError = { finishSplash() },
        )
    }
}
