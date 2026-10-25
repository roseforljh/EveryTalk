package com.android.everytalk.ui.components

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * 静态页背景粒子，移植自 React Bits Particles：球体内随机分布的点云沿视轴缓慢自转，
 * 每颗粒子按随机相位轻微漂浮，再透视投影到屏幕；近大远小、远处淡出、轻微闪烁，开场逐颗显现。
 * 只在绘制阶段读取时钟，动画期间不触发重组；系统关闭动画时保持静止画面。
 */
@Composable
fun ParticlesBackground(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onBackground,
    // 浅色主题下深色粒子更显眼，整体压低一些。
    maxAlpha: Float = if (color.luminance() < 0.5f) 0.4f else 0.6f,
    count: Int = 200,
) {
    val particles = remember(count) { createParticles(count) }
    val context = LocalContext.current
    val motion = remember {
        if (Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) 0f else 1f
    }
    val clock = remember { mutableFloatStateOf(0f) }
    val reveal = remember { mutableFloatStateOf(if (motion == 0f) 1f else 0f) }
    LaunchedEffect(motion) {
        if (motion == 0f) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0.0005f, 0.05f)
                last = now
                clock.floatValue += dt * SPEED
                reveal.floatValue = (reveal.floatValue + dt / INTRO_SECONDS).coerceAtMost(1f)
            }
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val t = clock.floatValue
        val intro = reveal.floatValue
        // 绕 X/Y 轻微摇摆，绕视轴持续自转，与原效果的旋转矩阵一致。
        val rx = sin(t * 0.2f) * 0.1f
        val ry = cos(t * 0.5f) * 0.15f
        val rz = t * 0.6f
        val sx = sin(rx); val cx = cos(rx); val sy = sin(ry); val cy = cos(ry); val sz = sin(rz); val cz = cos(rz)
        val m00 = cy * cz + sy * sx * sz; val m01 = -cy * sz + sy * sx * cz; val m02 = sy * cx
        val m10 = cx * sz; val m11 = cx * cz; val m12 = -sx
        val m20 = -sy * cz + cy * sx * sz; val m21 = sy * sz + cy * sx * cz; val m22 = cy * cx
        val aspect = size.width / size.height
        for (p in particles) {
            val px = p.x * SPREAD
            val py = p.y * SPREAD
            val pz = p.z * SPREAD * DEPTH
            // 每颗粒子三轴各自正弦漂浮。
            val wx = sin(t * p.r2 + TAU * p.r3) * (0.1f + 1.4f * p.r0)
            val wy = sin(t * p.r1 + TAU * p.r0) * (0.1f + 1.4f * p.r3)
            val wz = sin(t * p.r3 + TAU * p.r1) * (0.1f + 1.4f * p.r2)
            val vx = m00 * px + m01 * py + m02 * pz + wx
            val vy = m10 * px + m11 * py + m12 * pz + wy
            val vz = m20 * px + m21 * py + m22 * pz + wz - CAMERA
            val ahead = -vz
            if (ahead < NEAR || ahead > FAR) continue
            val sxScreen = (vx * FOCAL / aspect / ahead * 0.5f + 0.5f) * size.width
            val syScreen = (0.5f - vy * FOCAL / ahead * 0.5f) * size.height
            val dist = sqrt(vx * vx + vy * vy + vz * vz)
            val radius = (BASE_SIZE * (1f + (p.r0 - 0.5f)) / dist).coerceAtLeast(1f / density) * 0.5f * density
            if (sxScreen < -radius * 4 || sxScreen > size.width + radius * 4 ||
                syScreen < -radius * 4 || syScreen > size.height + radius * 4) continue
            val edge = smoothstep(1f, 4f, ahead) * smoothstep(FAR, FAR * 0.9f, ahead)
            val twinkle = 1f - TWINKLE * 0.6f * (0.5f + 0.5f * sin(t * (6f + p.r1 * 14f) + p.r3 * 40f))
            val appear = (intro * 1.6f - p.r2 * 0.6f).coerceIn(0f, 1f)
            val alpha = (twinkle * edge * appear * appear * maxAlpha).coerceIn(0f, 1f)
            if (alpha <= 0.003f) continue
            val center = Offset(sxScreen, syScreen)
            // 外圈淡光晕替代原效果的 glow，保持柔和但不需要逐颗创建渐变。
            drawCircle(color, radius * 2.4f, center, alpha * 0.12f)
            drawCircle(color, radius, center, alpha)
        }
    }
}

private class Particle(val x: Float, val y: Float, val z: Float, val r0: Float, val r1: Float, val r2: Float, val r3: Float)

/** 在单位球内均匀取点：拒绝采样方向，再用立方根校正半径分布。 */
private fun createParticles(count: Int): List<Particle> {
    val random = Random(7920)
    return List(count) {
        var x: Float; var y: Float; var z: Float; var len: Float
        do {
            x = random.nextFloat() * 2 - 1
            y = random.nextFloat() * 2 - 1
            z = random.nextFloat() * 2 - 1
            len = x * x + y * y + z * z
        } while (len > 1f || len == 0f)
        val r = cbrt(random.nextFloat())
        Particle(x * r, y * r, z * r, random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat())
    }
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private const val SPEED = 0.1f
private const val SPREAD = 10f
private const val DEPTH = 10f
private const val CAMERA = 20f
private const val NEAR = 0.1f
private const val FAR = 100f
private const val BASE_SIZE = 100f
private const val TWINKLE = 0.3f
private const val INTRO_SECONDS = 1.8f
private const val TAU = (2 * PI).toFloat()
private val FOCAL = (1 / tan(7.5 * PI / 180)).toFloat()
