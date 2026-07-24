package com.android.everytalk.ui.components
import com.android.everytalk.statecontroller.*

import android.annotation.SuppressLint
import android.content.ClipData
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.android.everytalk.R
import com.android.everytalk.ui.components.syntax.SyntaxHighlightTheme
import com.android.everytalk.ui.components.syntax.HighlightCache
import com.android.everytalk.ui.components.content.isPreviewSupported
import com.android.everytalk.ui.theme.chatColors
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FullScreenCodeViewerDialog(
    code: String,
    language: String,
    initialPreviewMode: Boolean = false,
    sourceBounds: androidx.compose.ui.geometry.Rect = androidx.compose.ui.geometry.Rect.Zero,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val windowSize = LocalWindowInfo.current.containerSize
    val scope = rememberCoroutineScope()
    val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val bgColor = MaterialTheme.colorScheme.background
    val previewBgColor = bgColor
    val previewTextColor = if (isDarkTheme) Color(0xFFEAEAEA) else Color.Black
    val headerColor = if (isDarkTheme) Color.White else Color.Black
    val capsuleBgColor = if (isDarkTheme) Color(0xFF383838) else Color(0xFFE2E2E2)
    val capsuleSelectedBgColor = if (isDarkTheme) Color(0xFF505050) else Color.White

    val canPreview = isPreviewSupported(language)
    val pagerState = rememberPagerState(
        initialPage = if (canPreview && initialPreviewMode) 1 else 0,
        pageCount = { if (canPreview) 2 else 1 }
    )
    val isPreviewMode = canPreview && pagerState.currentPage == 1
    var hasEntered by remember { mutableStateOf(false) }
    var isClosing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        hasEntered = true
    }
    LaunchedEffect(isClosing) {
        if (isClosing) {
            delay(CODE_VIEWER_DIALOG_TRANSITION_MILLIS.toLong())
            onDismiss()
        }
    }
    val animationTarget = resolveCodeViewerDialogAnimationTarget(
        hasEntered = hasEntered,
        isClosing = isClosing,
    )
    val entryScale by animateFloatAsState(
        targetValue = animationTarget.scale,
        animationSpec = tween(durationMillis = CODE_VIEWER_DIALOG_TRANSITION_MILLIS),
        label = "dialogEntryScale"
    )
    val entryAlpha by animateFloatAsState(
        targetValue = animationTarget.alpha,
        animationSpec = tween(durationMillis = CODE_VIEWER_DIALOG_ALPHA_MILLIS),
        label = "dialogEntryAlpha"
    )
    val transformOrigin = remember(sourceBounds, windowSize) {
        if (
            sourceBounds == androidx.compose.ui.geometry.Rect.Zero ||
            windowSize.width <= 0 ||
            windowSize.height <= 0
        ) {
            TransformOrigin.Center
        } else {
            TransformOrigin(
                pivotFractionX = (
                    (sourceBounds.left + sourceBounds.width / 2f) / windowSize.width
                    ).coerceIn(0f, 1f),
                pivotFractionY = (
                    (sourceBounds.top + sourceBounds.height / 2f) / windowSize.height
                    ).coerceIn(0f, 1f),
            )
        }
    }

    fun requestDismiss() {
        if (!isClosing) {
            isClosing = true
        }
    }

    Dialog(
        onDismissRequest = { requestDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogWindowProvider = LocalView.current.parent as? DialogWindowProvider
        SideEffect {
            dialogWindowProvider?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(CODE_VIEWER_DIALOG_WINDOW_DIM_AMOUNT)
                window.setTransparentSystemBars()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !isDarkTheme
                    isAppearanceLightNavigationBars = !isDarkTheme
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = entryAlpha
                    scaleX = entryScale
                    scaleY = entryScale
                    this.transformOrigin = transformOrigin
                }
                .background(bgColor)
                .statusBarsPadding()
        ) {
                // 顶部导航栏
                DisableSelection {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(1f)
                            .background(bgColor)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 左侧：关闭按钮
                        IconButton(onClick = { requestDismiss() }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = headerColor
                            )
                        }

                        // 中间：胶囊按钮 (代码/预览)
                        if (canPreview) {
                            val capsuleWidth = 140.dp
                            val indicatorWidth = 70.dp

                            Surface(
                                shape = RoundedCornerShape(50),
                                color = capsuleBgColor,
                                modifier = Modifier.size(width = capsuleWidth, height = 36.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    // 滑块指示器
                                    Box(
                                        modifier = Modifier
                                            .padding(2.dp)
                                            .offset {
                                                val progress = (
                                                    pagerState.currentPage +
                                                        pagerState.currentPageOffsetFraction
                                                    ).coerceIn(0f, 1f)
                                                IntOffset(
                                                    x = (indicatorWidth * progress).roundToPx(),
                                                    y = 0,
                                                )
                                            }
                                            .size(width = indicatorWidth - 4.dp, height = 32.dp)
                                            .background(capsuleSelectedBgColor, RoundedCornerShape(50))
                                    )

                                    // 按钮文本层
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(50))
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null
                                                ) {
                                                    scope.launch { pagerState.animateScrollToPage(0) }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "代码",
                                                color = headerColor,
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(50))
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null
                                                ) {
                                                    scope.launch { pagerState.animateScrollToPage(1) }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "预览",
                                                color = headerColor,
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        // 右侧：预览模式为分享，代码模式为复制
                        val context = LocalContext.current
                        AnimatedContent(
                            targetState = isPreviewMode,
                            transitionSpec = {
                                (fadeIn(tween(220)) + scaleIn(
                                    tween(220),
                                    initialScale = 0.8f
                                )).togetherWith(
                                    fadeOut(tween(150)) + scaleOut(
                                        tween(150),
                                        targetScale = 0.6f
                                    )
                                )
                            },
                            label = "PreviewHeaderActionButton"
                        ) { previewMode ->
                            if (previewMode) {
                                IconButton(
                                    onClick = {
                                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, code)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(shareIntent, "分享代码"))
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_gpt_share),
                                        contentDescription = "分享",
                                        tint = headerColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", code)))
                                        }
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_gpt_copy),
                                        contentDescription = "复制",
                                        tint = headerColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 主体内容
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = false
                    ) { page ->
                        if (page == 1) {
                            // 全屏预览时，在底部增加圆角和内边距，使其有悬浮感并避免遮挡底部系统导航条
                            Surface(
                                modifier = Modifier
                                    .fillMaxSize(),
                                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                                color = previewBgColor
                            ) {
                                WebPreviewContent(
                                    code = code,
                                    language = language,
                                    previewBackgroundColor = previewBgColor,
                                    previewTextColor = previewTextColor,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            SelectableCodeTextView(
                                code = code,
                                language = language,
                                isDarkTheme = isDarkTheme,
                                headerColor = headerColor,
                                codeTextColor = previewTextColor,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

