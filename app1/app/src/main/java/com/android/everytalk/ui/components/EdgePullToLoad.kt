package com.android.everytalk.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 列表到了“加载更多”的边界时明显拦住：继续拉只按很小比例跟手，越拉越沉；
 * 拉过 [thresholdDp] 再松手才加载，没拉够就弹回。惯性滑到边界不触发加载。
 * [fromTop] 为 true 表示边界在顶部（会话里往上翻），false 在底部（侧栏往下翻）。
 */
class EdgePullToLoadState internal constructor(
    private val fromTop: Boolean,
    private val thresholdPx: Float,
) {
    var enabled = false
    var onLoad: () -> Unit = {}

    /** 当前拉出的位移，挂到列表的 graphicsLayer.translationY 上。 */
    var offset by mutableFloatStateOf(0f)
        private set

    val reachedThreshold: Boolean get() = abs(offset) >= thresholdPx

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // 已经拉出时反向滑动先收回位移，不让列表跟着滚。
            if (offset == 0f || source != NestedScrollSource.UserInput) return Offset.Zero
            val dy = available.y
            val reversing = if (fromTop) dy < 0 else dy > 0
            if (!reversing) return Offset.Zero
            val next = if (fromTop) (offset + dy).coerceAtLeast(0f) else (offset + dy).coerceAtMost(0f)
            val used = next - offset
            offset = next
            return Offset(0f, used)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (!enabled || source != NestedScrollSource.UserInput) return Offset.Zero
            val dy = available.y
            if (if (fromTop) dy <= 0 else dy >= 0) return Offset.Zero
            // 阻尼随位移加重：开始约 1/3 跟手，接近两倍阈值时只剩 1/20。
            val progress = (abs(offset) / (thresholdPx * 2)).coerceIn(0f, 1f)
            val resistance = 0.33f * (1f - progress) + 0.05f
            offset += dy * resistance
            return Offset(0f, dy)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (offset == 0f) return Velocity.Zero
            if (reachedThreshold) onLoad()
            animate(offset, 0f) { value, _ -> offset = value }
            return available
        }
    }
}

@Composable
fun rememberEdgePullToLoadState(
    fromTop: Boolean,
    enabled: Boolean,
    onLoad: () -> Unit,
    thresholdDp: Float = 72f,
): EdgePullToLoadState {
    val thresholdPx = with(LocalDensity.current) { thresholdDp.dp.toPx() }
    val state = remember(fromTop, thresholdPx) { EdgePullToLoadState(fromTop, thresholdPx) }
    val latestOnLoad by rememberUpdatedState(onLoad)
    state.enabled = enabled
    state.onLoad = { latestOnLoad() }
    return state
}
