package com.android.everytalk.ui.screens.ImageGeneration
import com.android.everytalk.statecontroller.*

import android.annotation.SuppressLint
import com.android.everytalk.R
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
import android.net.Uri
import android.graphics.drawable.Drawable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.asDrawable
import android.content.Context
import java.util.UUID
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Base64
import android.widget.Toast
import com.android.everytalk.data.DataClass.Message
import android.graphics.BitmapFactory
import com.android.everytalk.data.network.SafeHttpDownloader
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.freezeWhileStreamingPaused
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.screens.MainScreen.chat.text.state.ChatScrollStateManager
import com.android.everytalk.ui.screens.BubbleMain.Main.AttachmentsContent
import com.android.everytalk.ui.screens.BubbleMain.Main.MessageContextMenu
import com.android.everytalk.ui.screens.BubbleMain.Main.ImageContextMenu
import com.android.everytalk.ui.screens.BubbleMain.Main.UserOrErrorMessageContent
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.HistoryLoadingBubblePlaceholderItem
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.android.everytalk.ui.components.streaming.UnifiedMarkdownRenderer
import com.android.everytalk.ui.components.streaming.buildStreamingRenderState
import com.android.everytalk.ui.theme.ChatDimensions
import com.android.everytalk.ui.theme.chatColors
import com.android.everytalk.ui.components.scrollFadeEdge
import com.android.everytalk.ui.topanchor.BottomScrollReason
import com.android.everytalk.ui.topanchor.RunTopAnchorReserveEngine
import com.android.everytalk.ui.topanchor.TopAnchorConfig
import com.android.everytalk.ui.topanchor.TopAnchorReserveEngineState
import com.android.everytalk.ui.topanchor.appendTopAnchorReserve
import com.android.everytalk.ui.topanchor.mapChatItemsToTopAnchorItems
import com.android.everytalk.ui.topanchor.resolveActiveTopAnchorTurn
import com.android.everytalk.ui.topanchor.resolveTopAnchorResponseTargetId
import com.android.everytalk.ui.topanchor.shouldAllowBottomScroll
import com.android.everytalk.util.storage.CappedByteArrayOutputStream
import com.android.everytalk.util.storage.readAtMost
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil3.size.Size
import kotlin.math.min

// 简易画笔编辑器覆盖层：支持在图片上涂抹，完成后返回合成后的Bitmap
@Composable
internal fun BrushEditorOverlay(
    baseBitmap: Bitmap,
    onCancel: () -> Unit,
    onDone: (Bitmap) -> Unit
) {
    // 数据与状态
    val coroutineScope = rememberCoroutineScope()
    val imageBitmap = remember(baseBitmap) { baseBitmap.asImageBitmap() }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val undoneStrokes = remember { mutableStateListOf<List<Offset>>() }
    val currentStroke = remember { mutableStateListOf<Offset>() } // 使用可观察列表，支持实时绘制
    var isCompositing by remember { mutableStateOf(false) }
    val strokeWidthPx = 24f // 固定画笔粗细（不提供调节控件）
    val strokeColor = Color(0xFF82A8FF) // 接近示例中的淡蓝色

    Surface(
        color = Color.Black.copy(alpha = 0.95f),
        contentColor = Color.White,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 计算显示区域尺寸，保持等比
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(top = 40.dp, bottom = 64.dp)
            ) {
                val maxW = constraints.maxWidth.toFloat()
                val maxH = constraints.maxHeight.toFloat()
                val bmpW = imageBitmap.width.toFloat()
                val bmpH = imageBitmap.height.toFloat()
                val scale = minOf(maxW / bmpW, maxH / bmpH).coerceAtMost(1f)
                val drawW = bmpW * scale
                val drawH = bmpH * scale

                val leftPad = (maxW - drawW) / 2f
                val topPad = (maxH - drawH) / 2f

                Box(
                    modifier = Modifier
                        .width(with(LocalDensity.current) { drawW.toDp() })
                        .height(with(LocalDensity.current) { drawH.toDp() })
                        .align(Alignment.Center)
                ) {
                    // 画底图
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.FillBounds
                    )

                    // 叠加一层 Canvas 仅绘制笔画，并处理绘制手势
                    Canvas(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(drawW, drawH) {
                                // 兼容性更好的连续绘制：基于 awaitPointerEvent 循环
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: continue
                                        val p = change.position
                                        if (change.pressed) {
                                            if (p.x in 0f..drawW && p.y in 0f..drawH) {
                                                currentStroke.add(p) // 实时追加点，触发重绘
                                            }
                                        } else {
                                            if (currentStroke.size > 1) {
                                                strokes.add(currentStroke.toList())
                                                undoneStrokes.clear()
                                            }
                                            currentStroke.clear() // 清空以便下一笔实时绘制
                                        }
                                    }
                                }
                            }
                    ) {
                        // 仅绘制笔画到覆盖层
                        // 已完成笔画：使用贝塞尔平滑路径，避免"断点"观感
                        strokes.forEach { pts ->
                            if (pts.size > 1) {
                                val path = Path().apply {
                                    moveTo(pts.first().x, pts.first().y)
                                    for (i in 1 until pts.size) {
                                        val prev = pts[i - 1]
                                        val cur = pts[i]
                                        // 使用二次贝塞尔，控制点为上一个点，终点为中点，获得平滑曲线
                                        val mid = Offset(
                                            (prev.x + cur.x) / 2f,
                                            (prev.y + cur.y) / 2f
                                        )
                                    quadraticTo(prev.x, prev.y, mid.x, mid.y)
                                    }
                                    // 收尾：最后一段到最终点
                                    val last = pts.last()
                                    lineTo(last.x, last.y)
                                }
                                drawPath(
                                    path = path,
                                    color = strokeColor,
                                    style = Stroke(
                                        width = strokeWidthPx,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                        }
                        // 正在进行中的笔画：同样用路径实时绘制
                        if (currentStroke.size > 1) {
                            val pts = currentStroke
                            val path = Path().apply {
                                moveTo(pts.first().x, pts.first().y)
                                for (i in 1 until pts.size) {
                                    val prev = pts[i - 1]
                                    val cur = pts[i]
                                    val mid = Offset(
                                        (prev.x + cur.x) / 2f,
                                        (prev.y + cur.y) / 2f
                                    )
                                    quadraticTo(prev.x, prev.y, mid.x, mid.y)
                                }
                                val last = pts.last()
                                lineTo(last.x, last.y)
                            }
                            drawPath(
                                path = path,
                                color = strokeColor,
                                style = Stroke(
                                    width = strokeWidthPx,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }
                }

                // 顶部标题、左侧竖向笔刷调节、底部工具条（取消/撤销/重做/下一步）
                Box(modifier = Modifier.fillMaxSize()) {

                    // 顶部标题
                    Text(
                        text = "选择要编辑的区域",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 20.dp, top = 16.dp)
                    )

                    // 左侧不显示笔刷调节控件（按要求固定画笔大小与颜色）

                    // 底部工具条：取消 | 撤销/重做 | 下一步（等分布局，避免单个按钮占满）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 20.dp, vertical = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左：取消（占1/3）
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                contentColor = Color.White,
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier
                                    .height(44.dp)
                                    .widthIn(min = 84.dp)
                                    .clickable { onCancel() }
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("取消", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }

                        // 中：撤销 / 重做（占1/3）
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(28.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        if (strokes.isNotEmpty()) {
                                            val last = strokes.removeAt(strokes.lastIndex)
                                            undoneStrokes.add(last)
                                        }
                                    },
                                    enabled = strokes.isNotEmpty()
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_back),
                                        contentDescription = "撤销",
                                        tint = if (strokes.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.4f)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (undoneStrokes.isNotEmpty()) {
                                            val last = undoneStrokes.removeAt(undoneStrokes.lastIndex)
                                            strokes.add(last)
                                        }
                                    },
                                    enabled = undoneStrokes.isNotEmpty()
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_end),
                                        contentDescription = "重做",
                                        tint = if (undoneStrokes.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }

                        // 右：下一步（占1/3）
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier
                                    .height(44.dp)
                                    .widthIn(min = 112.dp)
                                    .clickable(enabled = !isCompositing) {
                                        isCompositing = true
                                        val strokeSnapshot = buildList {
                                            addAll(strokes.map { it.toList() })
                                            if (currentStroke.isNotEmpty()) add(currentStroke.toList())
                                        }
                                        coroutineScope.launch {
                                            var pendingBitmap: Bitmap? = null
                                            try {
                                                pendingBitmap = withContext(Dispatchers.Default) {
                                                    val result = createBitmap(
                                                        baseBitmap.width,
                                                        baseBitmap.height,
                                                        Bitmap.Config.ARGB_8888,
                                                    )
                                                    val canvas = Canvas(result)
                                                    canvas.drawBitmap(baseBitmap, 0f, 0f, null)
                                                    val paint = android.graphics.Paint().apply {
                                                        color = strokeColor.toArgb()
                                                        isAntiAlias = true
                                                        strokeWidth = strokeWidthPx / scale
                                                        style = android.graphics.Paint.Style.STROKE
                                                        strokeCap = android.graphics.Paint.Cap.ROUND
                                                        strokeJoin = android.graphics.Paint.Join.ROUND
                                                    }
                                                    val sx = bmpW / drawW
                                                    val sy = bmpH / drawH
                                                    strokeSnapshot.forEach { points ->
                                                        for (index in 0 until points.lastIndex) {
                                                            canvas.drawLine(
                                                                points[index].x * sx,
                                                                points[index].y * sy,
                                                                points[index + 1].x * sx,
                                                                points[index + 1].y * sy,
                                                                paint,
                                                            )
                                                        }
                                                    }
                                                    result
                                                }
                                                onDone(requireNotNull(pendingBitmap))
                                                pendingBitmap = null
                                            } finally {
                                                pendingBitmap?.takeIf { !it.isRecycled }?.recycle()
                                                isCompositing = false
                                            }
                                        }
                                    }
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("下一步", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 底部操作按钮，使用与"历史项点击"一致的Ripple特效
 * - 固定图标容器尺寸，防止布局抬高
 * - 单击时显示有界Ripple（白色半透明）
 */
