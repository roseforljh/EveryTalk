'use client'

import { useEffect, useRef, type ButtonHTMLAttributes } from 'react'
import * as THREE from 'three'

// 改编自 React Bits SpecularButton，沿用官方圆角 SDF、对称高光与角度缓动。
// Copyright (c) 2026 David Haz，许可见设计文档；渲染器复用已有 Three.js。
const vertex = `in vec2 position;
void main() {
  gl_Position = vec4(position, 0.0, 1.0);
}
`
const fragment = `precision highp float;

uniform vec2 uCenter;
uniform vec2 uHalfSize;
uniform float uRadius;
uniform float uAngle;
uniform float uPx;
uniform vec3 uLineColor;
uniform vec3 uBaseColor;
uniform float uIntensity;
uniform float uShineSize;
uniform float uShineFade;
uniform float uThickness;
uniform float uBaseWidth;

out vec4 fragColor;

float sdRoundedRect(vec2 p, vec2 b, float r) {
  vec2 q = abs(p) - b + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float shapeSDF(vec2 p) { return sdRoundedRect(p, uHalfSize, uRadius); }

float gaussianLine(float d, float sigma) {
  float x = d / (sigma + 1e-6);
  float k = mix(1.0, 1.6, smoothstep(0.0, 1.5, x));
  return exp(-k * x * x);
}

void main() {
  vec2 p = gl_FragCoord.xy - uCenter;
  float d = shapeSDF(p);
  vec2 L = vec2(cos(uAngle), sin(uAngle));

  // Dark base stroke hugging the edge for a sense of thickness
  float base = (1.0 - smoothstep(0.0, uBaseWidth, abs(d))) * 0.45;

  // Symmetric specular: the edges facing toward/away from the light both
  // catch a streak. The angular window (size + fade) is measured with an
  // elliptical normal so it varies continuously along straight edges.
  vec2 nEll = normalize(p / (uHalfSize * uHalfSize) + 1e-6);
  float phi = acos(clamp(abs(dot(nEll, L)), 0.0, 1.0));
  float rim = 1.0 - smoothstep(uShineSize - uShineFade, uShineSize + uShineFade + 1e-4, phi);
  float line = gaussianLine(d, uThickness);
  float edgeClamp = 1.0 - smoothstep(0.5 * uPx, 3.0 * uPx, abs(d));
  float hi = line * rim * edgeClamp * uIntensity;

  vec3 col = uBaseColor * base + uLineColor * hi;
  float a = clamp(base + hi, 0.0, 1.0);
  fragColor = vec4(col, a);
}
`

/** 分类按钮保留原生语义；高光按需创建，鼠标移开并收敛后停止绘制。 */
export default function SpecularButton({
  children,
  className = '',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement>) {
  const buttonRef = useRef<HTMLButtonElement>(null)
  const effectRef = useRef<HTMLSpanElement>(null)
  useEffect(() => {
    const button = buttonRef.current
    const effect = effectRef.current
    if (!button || !effect) return
    const motion = window.matchMedia('(prefers-reduced-motion: reduce)')
    let renderer: THREE.WebGLRenderer | null = null
    let geometry: THREE.BufferGeometry | null = null
    let material: THREE.RawShaderMaterial | null = null
    let scene: THREE.Scene | null = null
    let observer: ResizeObserver | null = null
    let raf = 0
    let angle = 2.4
    let targetAngle = angle
    let bright = 0
    let targetBright = 0
    let last = 0
    let failed = false
    const camera = new THREE.Camera()
    const uniforms = {
      uCenter: { value: new THREE.Vector2() },
      uHalfSize: { value: new THREE.Vector2() },
      uRadius: { value: 14 },
      uAngle: { value: angle },
      uPx: { value: 1 },
      uLineColor: { value: new THREE.Color() },
      uBaseColor: { value: new THREE.Color() },
      uIntensity: { value: 0 },
      uShineSize: { value: 10 * Math.PI / 180 },
      uShineFade: { value: 40 * Math.PI / 180 },
      uThickness: { value: 1.2 },
      uBaseWidth: { value: 1 },
    }

    const draw = () => {
      if (!renderer || !scene) return
      const style = getComputedStyle(button)
      // 每次绘制读取主题变量，切换深浅主题后不留下旧主题的白色边缘。
      uniforms.uLineColor.value.set(style.color)
      uniforms.uBaseColor.value.set(style.borderTopColor)
      uniforms.uAngle.value = angle
      uniforms.uIntensity.value = bright * 1.4
      renderer.render(scene, camera)
    }
    const tick = (now: number) => {
      raf = 0
      const dt = Math.min((now - last) / 1000, 0.05)
      last = now
      const diff = ((targetAngle - angle + Math.PI * 3) % (Math.PI * 2)) - Math.PI
      angle += diff * (1 - Math.exp(-dt * 7))
      bright += (targetBright - bright) * (1 - Math.exp(-dt * 8))
      draw()
      if (Math.abs(diff) > 0.001 || Math.abs(targetBright - bright) > 0.001)
        raf = requestAnimationFrame(tick)
    }
    const wake = () => {
      if (!raf && !motion.matches) {
        last = performance.now()
        raf = requestAnimationFrame(tick)
      }
    }
    const resize = () => {
      if (!renderer) return
      // 使用布局尺寸，不读取 ScrollExpand 缩放后的视觉尺寸，避免高光边框偏移。
      const width = button.offsetWidth
      const height = button.offsetHeight
      renderer.setSize(width + 40, height + 40, false)
      uniforms.uCenter.value.set(20 + width / 2, 20 + height / 2)
      uniforms.uHalfSize.value.set(width / 2, height / 2)
      uniforms.uRadius.value = Math.min(14, height / 2)
      draw()
    }
    const initialize = () => {
      if (renderer || failed || motion.matches) return
      try {
        renderer = new THREE.WebGLRenderer({ alpha: true, premultipliedAlpha: true, powerPreference: 'low-power' })
        renderer.setPixelRatio(1)
        geometry = new THREE.BufferGeometry()
        geometry.setAttribute('position', new THREE.Float32BufferAttribute([-1, -1, 0, 3, -1, 0, -1, 3, 0], 3))
        material = new THREE.RawShaderMaterial({
          vertexShader: vertex, fragmentShader: fragment, uniforms,
          glslVersion: THREE.GLSL3, transparent: true, depthTest: false, depthWrite: false,
          blending: THREE.CustomBlending, blendSrc: THREE.OneFactor, blendDst: THREE.OneMinusSrcAlphaFactor,
        })
        scene = new THREE.Scene()
        scene.add(new THREE.Mesh(geometry, material))
        effect.appendChild(renderer.domElement)
        observer = new ResizeObserver(resize)
        observer.observe(button)
        resize()
      } catch {
        failed = true
        renderer?.dispose()
        renderer?.domElement.remove()
        renderer = null
        // WebGL 不可用时仍保留 CSS 边框、选中样式与可点击按钮。
      }
    }
    const enter = () => {
      initialize()
      targetBright = 1
      wake()
    }
    const move = (event: PointerEvent) => {
      const rect = button.getBoundingClientRect()
      const nx = (event.clientX - rect.left - rect.width / 2) / (rect.width / 2)
      const ny = (rect.top + rect.height / 2 - event.clientY) / (rect.height / 2)
      targetAngle = Math.atan2(2 / rect.height, -2 / rect.width) + nx * 0.3 + ny * 0.15
      enter()
    }
    const leave = () => {
      targetBright = button.matches(':focus-visible') ? 1 : 0
      wake()
    }
    const preferenceChanged = () => {
      cancelAnimationFrame(raf)
      raf = 0
      bright = targetBright = 0
      draw()
    }
    button.addEventListener('pointerenter', enter)
    button.addEventListener('pointermove', move)
    button.addEventListener('pointerleave', leave)
    button.addEventListener('focus', enter)
    button.addEventListener('blur', leave)
    motion.addEventListener('change', preferenceChanged)
    return () => {
      cancelAnimationFrame(raf)
      observer?.disconnect()
      button.removeEventListener('pointerenter', enter)
      button.removeEventListener('pointermove', move)
      button.removeEventListener('pointerleave', leave)
      button.removeEventListener('focus', enter)
      button.removeEventListener('blur', leave)
      motion.removeEventListener('change', preferenceChanged)
      geometry?.dispose()
      material?.dispose()
      renderer?.dispose()
      renderer?.forceContextLoss()
      renderer?.domElement.remove()
    }
  }, [])
  return (
    <button ref={buttonRef} type="button" className={`specular-button ${className}`} {...props}>
      <span ref={effectRef} className="specular-button__fx" aria-hidden="true" />
      <span className="specular-button__label">{children}</span>
    </button>
  )
}
