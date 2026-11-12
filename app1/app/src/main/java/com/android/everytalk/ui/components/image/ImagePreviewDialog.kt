package com.android.everytalk.ui.components
import com.android.everytalk.statecontroller.*

import com.android.everytalk.util.AppToast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.android.everytalk.R
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.android.everytalk.util.storage.FileManager
import com.android.everytalk.util.image.ImageHandlingLimits

@Composable
fun ImagePreviewDialog(
    url: String,
    onDismiss: () -> Unit
) {
    ImagePreviewDialog(
        urls = listOf(url),
        initialIndex = 0,
        onDismiss = onDismiss
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImagePreviewDialog(
    urls: List<String>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (urls.size - 1).coerceAtLeast(0)),
        pageCount = { urls.size }
    )

    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            decorFitsSystemWindows = false
        )
    ) {
        // 让预览弹窗延伸到状态栏与导航栏后方，消除顶部和底部的系统栏黑块。
        val dialogWindowProvider = LocalView.current.parent as? DialogWindowProvider
        SideEffect {
            dialogWindowProvider?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.setTransparentSystemBars()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
        val controlBackgroundColor = Color.Gray.copy(alpha = 0.42f)
        val controlBorderColor = Color.White.copy(alpha = 0.75f)
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        Surface(
            color = Color.Transparent,
            contentColor = Color.White,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .zIndex(2f)
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (urls.size > 1) {
                            Spacer(modifier = Modifier.size(40.dp))
                            Text(
                                text = "${pagerState.currentPage + 1} / ${urls.size}",
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(40.dp)
                                .background(controlBackgroundColor, CircleShape)
                                .border(1.dp, controlBorderColor, CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_gpt_close_lg),
                                contentDescription = stringResource(R.string.image_preview_close),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = urls.size > 1 && scale == 1f,
                    beyondViewportPageCount = 1
                ) { page ->
                    val currentUrl = urls.getOrNull(page) ?: return@HorizontalPager
                    val animatedScale = remember(page) { Animatable(1f) }
                    var pageOffsetX by remember(page) { mutableFloatStateOf(0f) }
                    var pageOffsetY by remember(page) { mutableFloatStateOf(0f) }
                    val pageCoroutineScope = rememberCoroutineScope()
                    // 长图在 1 倍时靠纵向滚动查看；放大后由下面的手势平移，平移到边缘的余量转交给滚动。
                    val pageScrollState = rememberScrollState()
                    // ponytail: Coil 默认把位图限制在 4096px，长截图会被压糊；这里放宽到纵向 8192px（绝大多数 GPU 纹理上限内）。
                    // 超长图仍会被等比缩小，需要原图清晰度时再换分块解码的查看器。
                    val imageRequest = remember(currentUrl) {
                        ImageRequest.Builder(context)
                            .data(currentUrl)
                            .maxBitmapSize(coil3.size.Size(4096, 8192))
                            .build()
                    }
                    /**
                     * 放大状态下按手指位移平移图片。
                     * 纵向平移超出当前可见边界时，把超出部分换算回未缩放坐标交给滚动，长图放大后也能继续上下浏览。
                     */
                    val panZoomed: (Float, Float, Float, Float, Float) -> Unit = { dx, dy, s, w, h ->
                        val mx = (s - 1f) * w / 2f
                        val my = (s - 1f) * h / 2f
                        pageOffsetX = (pageOffsetX + dx).coerceIn(-mx, mx)
                        val targetY = pageOffsetY + dy
                        pageOffsetY = targetY.coerceIn(-my, my)
                        val overflowY = targetY - pageOffsetY
                        if (overflowY != 0f) pageScrollState.dispatchRawDelta(-overflowY / s)
                    }
                    val toggleZoom: () -> Unit = {
                        pageCoroutineScope.launch {
                            if (animatedScale.value > 1f) {
                                animatedScale.animateTo(1f, tween(250))
                                pageOffsetX = 0f
                                pageOffsetY = 0f
                            } else {
                                animatedScale.animateTo(2f, tween(250))
                            }
                            scale = animatedScale.value
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(page) {
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        val pointerCount = event.changes.size
                                        if (pointerCount >= 2) {
                                            val zoomChange = event.calculateZoom()
                                            val panChange = event.calculatePan()
                                            val curScale = animatedScale.value
                                            if (zoomChange != 1f || curScale > 1f) {
                                                val newScale = (curScale * zoomChange).coerceIn(1f, 5f)
                                                pageCoroutineScope.launch { animatedScale.snapTo(newScale) }
                                                if (newScale > 1f) {
                                                    panZoomed(panChange.x, panChange.y, newScale, w, h)
                                                } else {
                                                    pageOffsetX = 0f
                                                    pageOffsetY = 0f
                                                }
                                                scale = newScale
                                                event.changes.forEach { it.consume() }
                                            }
                                        } else if (pointerCount == 1 && animatedScale.value > 1f) {
                                            val panChange = event.calculatePan()
                                            panZoomed(panChange.x, panChange.y, animatedScale.value, w, h)
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            .combinedClickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.image_toggle_scale),
                                onClick = toggleZoom,
                                onDoubleClick = toggleZoom,
                            )
                            // 缩放作用在整个可视窗口上，以屏幕中心为轴心，与平移边界的计算一致。
                            .graphicsLayer {
                                scaleX = animatedScale.value
                                scaleY = animatedScale.value
                                translationX = pageOffsetX
                                translationY = pageOffsetY
                            }
                            // 放大时关闭滚动手势，避免和上面的平移手势同时生效。
                            .verticalScroll(pageScrollState, enabled = animatedScale.value <= 1f),
                        // verticalScroll 会保留一屏的最小高度：短图居中，长图从顶部开始完整排列。
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = stringResource(
                                R.string.image_preview_item,
                                page + 1,
                                urls.size,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = bottomInset + 16.dp)
                        .size(48.dp)
                        .zIndex(2f)
                        .background(controlBackgroundColor, CircleShape)
                        .border(1.dp, controlBorderColor, CircleShape)
                ) {
                    IconButton(
                        onClick = {
                            val currentUrl = urls.getOrNull(pagerState.currentPage) ?: return@IconButton
                            coroutineScope.launch {
                                saveImageToGallery(context, currentUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_gpt_download),
                            contentDescription = stringResource(R.string.image_download),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

private suspend fun saveImageToGallery(context: android.content.Context, url: String) {
    withContext(Dispatchers.IO) {
        try {
            val fileManager = FileManager(context)
            val loaded = fileManager.loadBytesFromFlexibleSource(
                source = url,
                maxBytes = ImageHandlingLimits.GENERATED_IMAGE_MAX_BYTES,
            )
            if (loaded == null) {
                withContext(Dispatchers.Main) {
                    AppToast.show(context, R.string.image_save_failed)
                }
                return@withContext
            }
            val (bytes, mime) = loaded
            val savedUri = fileManager.saveBytesToMediaStore(
                bytes = bytes,
                mime = mime,
                displayNameBase = "everytalk"
            )
            withContext(Dispatchers.Main) {
                if (savedUri != null) {
                    AppToast.show(context, R.string.image_saved)
                } else {
                    AppToast.show(context, R.string.image_save_failed)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ImagePreview", "保存图片失败", e)
            withContext(Dispatchers.Main) {
                AppToast.show(context, R.string.image_save_failed)
            }
        }
    }
}
