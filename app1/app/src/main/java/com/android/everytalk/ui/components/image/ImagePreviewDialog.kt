package com.android.everytalk.ui.components

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.android.everytalk.R
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.android.everytalk.util.storage.FileManager

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
            dismissOnClickOutside = true
        )
    ) {
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
                                contentDescription = "关闭预览",
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
                                                    val mx = (newScale - 1f) * w / 2f
                                                    val my = (newScale - 1f) * h / 2f
                                                    pageOffsetX = (pageOffsetX + panChange.x).coerceIn(-mx, mx)
                                                    pageOffsetY = (pageOffsetY + panChange.y).coerceIn(-my, my)
                                                } else {
                                                    pageOffsetX = 0f
                                                    pageOffsetY = 0f
                                                }
                                                scale = newScale
                                                event.changes.forEach { it.consume() }
                                            }
                                        } else if (pointerCount == 1 && animatedScale.value > 1f) {
                                            val panChange = event.calculatePan()
                                            val s = animatedScale.value
                                            val mx = (s - 1f) * w / 2f
                                            val my = (s - 1f) * h / 2f
                                            pageOffsetX = (pageOffsetX + panChange.x).coerceIn(-mx, mx)
                                            pageOffsetY = (pageOffsetY + panChange.y).coerceIn(-my, my)
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            .combinedClickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                role = Role.Button,
                                onClickLabel = "切换图片缩放",
                                onClick = toggleZoom,
                                onDoubleClick = toggleZoom,
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = currentUrl,
                            contentDescription = "预览图片 ${page + 1}/${urls.size}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = animatedScale.value
                                    scaleY = animatedScale.value
                                    translationX = pageOffsetX
                                    translationY = pageOffsetY
                                },
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
                            contentDescription = "下载图片",
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
                maxBytes = FileManager.MAX_MESSAGE_IMAGE_BYTES,
            )
            if (loaded == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "图片保存失败", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, "图片已保存到相册", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "图片保存失败", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ImagePreview", "保存图片失败", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "图片保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
