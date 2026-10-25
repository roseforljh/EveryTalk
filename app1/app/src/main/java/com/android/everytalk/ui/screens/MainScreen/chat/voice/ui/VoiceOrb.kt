package com.android.everytalk.ui.screens.MainScreen.chat.voice.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.exp
import kotlin.math.sin

enum class VoiceOrbState { IDLE, CONNECTING, LISTENING, SPEAKING }

/** 各状态的目标参数：速度、扰动幅度、外发光、亮度、脉冲、饱和度，取自 assistant-ui VoiceOrb。 */
private val STATE_PARAMS = mapOf(
    VoiceOrbState.IDLE to floatArrayOf(0.15f, 0.04f, 0.15f, 0.55f, 0f, 0.7f),
    VoiceOrbState.CONNECTING to floatArrayOf(0.5f, 0.1f, 0.45f, 0.75f, 1f, 0.9f),
    VoiceOrbState.LISTENING to floatArrayOf(0.4f, 0.14f, 0.5f, 0.85f, 0f, 1f),
    VoiceOrbState.SPEAKING to floatArrayOf(1.4f, 0.35f, 0.9f, 1f, 0f, 1f),
)

// 蓝色配色：主色、高光色、暗部色。
private val ORB_COLORS = arrayOf(floatArrayOf(0.2f, 0.5f, 1f), floatArrayOf(0.4f, 0.7f, 1f), floatArrayOf(0.1f, 0.3f, 0.8f))

/**
 * 语音球：噪声驱动的液态球体，状态切换时参数逐帧缓动，音量叠加到速度、扰动和外发光上。
 * Android 13 起用 AGSL 着色器逐像素绘制；更低版本退化为带高光的渐变圆，随音量轻微缩放。
 * 球体直径约为画布的 44%，其余空间留给外发光。
 */
@Composable
fun VoiceOrb(
    state: VoiceOrbState,
    volume: Float,
    modifier: Modifier = Modifier,
    size: Dp = 300.dp,
) {
    val target by rememberUpdatedState(STATE_PARAMS.getValue(state))
    val latestVolume by rememberUpdatedState(volume.coerceIn(0f, 1f))
    // current: 6 个缓动中的参数；phase 为按速度积分的噪声时间，避免速度变化时噪声跳变。
    val current = remember { STATE_PARAMS.getValue(VoiceOrbState.IDLE).copyOf() }
    val clock = remember { floatArrayOf(0f, 0f) }
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                // 原效果每帧按 4.5% 逼近目标，这里换算成与刷新率无关的指数缓动。
                val follow = 1f - exp(-dt / 0.36f)
                for (i in current.indices) current[i] += (target[i] - current[i]) * follow
                clock[0] += dt
                clock[1] += dt * (current[0] + latestVolume * 0.4f)
                frame.longValue = now
            }
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ShaderOrb(current, clock, frame, { latestVolume }, modifier.size(size))
    } else {
        FallbackOrb(current, frame, { latestVolume }, modifier.size(size))
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderOrb(current: FloatArray, clock: FloatArray, frame: State<Long>, volume: () -> Float, modifier: Modifier) {
    val shader = remember { RuntimeShader(ORB_SHADER) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Canvas(modifier) {
        frame.value
        val vol = volume()
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("time", clock[0])
        shader.setFloatUniform("phase", clock[1])
        shader.setFloatUniform("amplitude", current[1] + vol * 0.12f)
        shader.setFloatUniform("glow", current[2] + vol * 0.2f)
        shader.setFloatUniform("brightness", current[3])
        shader.setFloatUniform("pulse", current[4])
        shader.setFloatUniform("saturation", current[5])
        shader.setFloatUniform("color0", ORB_COLORS[0])
        shader.setFloatUniform("color1", ORB_COLORS[1])
        shader.setFloatUniform("color2", ORB_COLORS[2])
        drawRect(brush)
    }
}

@Composable
private fun FallbackOrb(current: FloatArray, frame: State<Long>, volume: () -> Float, modifier: Modifier) {
    val (c0, c1, c2) = ORB_COLORS.map { Color(it[0], it[1], it[2]) }
    Canvas(modifier) {
        val now = frame.value / 1e9f
        val vol = volume()
        val pulse = 1f + current[4] * sin(now * 3.5f) * 0.35f
        val radius = size.minDimension * 0.22f * (1f + vol * 0.12f)
        drawCircle(Brush.radialGradient(listOf(c1.copy(alpha = 0.35f * (current[2] + vol * 0.2f)), Color.Transparent),
            center, radius * 1.8f), radius * 1.8f)
        drawCircle(Brush.radialGradient(listOf(c1, c0, c2), center - Offset(radius * 0.3f, radius * 0.35f), radius * 1.6f),
            radius, alpha = (current[3] * pulse).coerceIn(0f, 1f))
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.6f), Color.Transparent),
            center - Offset(radius * 0.34f, radius * 0.4f), radius * 0.45f), radius * 0.45f,
            center - Offset(radius * 0.34f, radius * 0.4f))
    }
}

// 移植自 assistant-ui VoiceOrb 的片元着色器；AGSL 输出预乘透明度，外发光按原 SRC_ALPHA 混合结果换算。
private const val ORB_SHADER = """
uniform float2 resolution;
uniform float time;
uniform float phase;
uniform float amplitude;
uniform float glow;
uniform float brightness;
uniform float pulse;
uniform float saturation;
uniform float3 color0;
uniform float3 color1;
uniform float3 color2;

float3 mod289(float3 x) { return x - floor(x / 289.0) * 289.0; }
float4 mod289(float4 x) { return x - floor(x / 289.0) * 289.0; }
float4 permute(float4 x) { return mod289((x * 34.0 + 1.0) * x); }
float4 taylorInvSqrt(float4 r) { return 1.79284291400159 - 0.85373472095314 * r; }

float snoise(float3 v) {
  const float2 C = float2(1.0 / 6.0, 1.0 / 3.0);
  float3 i = floor(v + dot(v, float3(C.y)));
  float3 x0 = v - i + dot(i, float3(C.x));
  float3 g = step(x0.yzx, x0.xyz);
  float3 l = 1.0 - g;
  float3 i1 = min(g, l.zxy);
  float3 i2 = max(g, l.zxy);
  float3 x1 = x0 - i1 + C.x;
  float3 x2 = x0 - i2 + C.y;
  float3 x3 = x0 - 0.5;
  i = mod289(i);
  float4 p = permute(permute(permute(
    i.z + float4(0.0, i1.z, i2.z, 1.0))
    + i.y + float4(0.0, i1.y, i2.y, 1.0))
    + i.x + float4(0.0, i1.x, i2.x, 1.0));
  float4 j = p - 49.0 * floor(p / 49.0);
  float4 x_ = floor(j / 7.0);
  float4 y_ = floor(j - 7.0 * x_);
  float4 x = (x_ * 2.0 + 0.5) / 7.0 - 1.0;
  float4 y = (y_ * 2.0 + 0.5) / 7.0 - 1.0;
  float4 h = 1.0 - abs(x) - abs(y);
  float4 b0 = float4(x.xy, y.xy);
  float4 b1 = float4(x.zw, y.zw);
  float4 s0 = floor(b0) * 2.0 + 1.0;
  float4 s1 = floor(b1) * 2.0 + 1.0;
  float4 sh = -step(h, float4(0.0));
  float4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
  float4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
  float3 g0 = float3(a0.xy, h.x);
  float3 g1 = float3(a0.zw, h.y);
  float3 g2 = float3(a1.xy, h.z);
  float3 g3 = float3(a1.zw, h.w);
  float4 norm = taylorInvSqrt(float4(dot(g0, g0), dot(g1, g1), dot(g2, g2), dot(g3, g3)));
  g0 *= norm.x; g1 *= norm.y; g2 *= norm.z; g3 *= norm.w;
  float4 m = max(0.6 - float4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
  m = m * m;
  return 42.0 * dot(m * m, float4(dot(g0, x0), dot(g1, x1), dot(g2, x2), dot(g3, x3)));
}

half4 main(float2 fragCoord) {
  float2 uv = fragCoord / resolution * 2.0 - 1.0;
  uv.y = -uv.y;
  float dist = length(uv);
  float t = phase;

  float radius = 0.44;
  float circle = 1.0 - smoothstep(radius - 0.008, radius + 0.008, dist);
  if (circle < 0.001) {
    float glowAlpha = exp(-(dist - radius) * 12.0) * glow * 0.4;
    float3 glowColor = mix(color0, color1, 0.5);
    return half4(half3(glowColor * glowAlpha * glowAlpha), half(glowAlpha));
  }

  float n1 = snoise(float3(uv * 2.0, t * 0.6)) * 0.5 + 0.5;
  float n2 = snoise(float3(uv * 3.5 + 7.0, t * 0.9)) * 0.5 + 0.5;
  float n3 = snoise(float3(uv * 1.5 - 3.0, t * 0.4 + 10.0)) * 0.5 + 0.5;
  float2 distort = float2(
    snoise(float3(uv * 2.0 + 5.0, t * 0.7)),
    snoise(float3(uv * 2.0 + 15.0, t * 0.7))
  ) * amplitude * 2.0;
  float n4 = snoise(float3((uv + distort) * 3.0, t * 0.5)) * 0.5 + 0.5;

  float3 col = mix(color0, color1, n1);
  col = mix(col, color2, n2 * 0.5);
  col = mix(col, color1 * 1.3, n4 * 0.4);
  float vein = pow(n3, 3.0) * amplitude * 6.0;
  col += vein * mix(color1, float3(1.0), 0.3);

  float centerDist = dist / radius;
  col *= 1.0 - centerDist * centerDist * 0.4;
  col += pow(centerDist, 4.0) * 0.6 * mix(color0, float3(1.0), 0.5);

  float2 lightPos = float2(-0.15, -0.18);
  float specDist = length(uv - lightPos);
  col += exp(-specDist * specDist * 30.0) * 0.7;
  col += exp(-length(uv - float2(0.2, 0.25)) * 8.0) * 0.15 * color1;

  float pulseFactor = 1.0 + pulse * sin(time * 3.5) * 0.35;
  float lum = dot(col, float3(0.299, 0.587, 0.114));
  col = mix(float3(lum), col, saturation);
  col *= brightness * pulseFactor;
  col = clamp(col, 0.0, 1.0);
  return half4(half3(col * circle), half(circle));
}
"""
