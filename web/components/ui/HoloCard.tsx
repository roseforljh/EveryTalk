'use client'

import { useEffect, useRef, useState } from 'react'
import type { CSSProperties, PointerEvent as ReactPointerEvent } from 'react'

import Image from 'next/image'
import { VERTEX, FRAGMENT } from './holo-card-shaders'

// 改编自 React Bits HoloCard，沿用官方 WebGL 光箔与弹簧倾斜。
// Copyright (c) 2026 David Haz. 许可全文见 BLACK_WHITE_REDESIGN.md。
// 来源：https://github.com/DavidHDev/react-bits/blob/main/src/ts-default/Components/HoloCard/HoloCard.tsx

type HoloPreset =
  | 'bursts'
  | 'stars'
  | 'shards'
  | 'cosmos'
  | 'rainbow'
  | 'swirl'
  | 'glitter'
  | 'gold'

export interface HoloCardProps {
  image?: string
  alt?: string
  preset?: HoloPreset
  intensity?: number
  scale?: number
  edgeSparkle?: number
  frame?: number
  glare?: number
  foilColor?: string
  width?: number
  radius?: number
  tiltMax?: number
  hoverScale?: number
  idle?: boolean
  shadow?: boolean
  className?: string
  style?: CSSProperties
}

interface Settings {
  preset: number
  intensity: number
  scale: number
  edgeSparkle: number
  frame: number
  glare: number
  foil: number[]
  radius: number
  tiltMax: number
  hoverScale: number
  idle: boolean
}

const PRESETS: HoloPreset[] = [
  'bursts',
  'stars',
  'shards',
  'cosmos',
  'rainbow',
  'swirl',
  'glitter',
  'gold',
]
const PERSPECTIVE = 1100
const CARD_RATIO = 9 / 16

const clamp = (value: number, min: number, max: number) =>
  Math.min(max, Math.max(min, value))

const parseColor = (value: string): number[] => {
  const fallback = [0.78, 0.8, 0.84]
  if (typeof document === 'undefined') return fallback
  const ctx = document.createElement('canvas').getContext('2d')
  if (!ctx) return fallback
  ctx.fillStyle = '#000000'
  ctx.fillStyle = value
  const resolved = ctx.fillStyle
  if (!resolved.startsWith('#') || resolved.length !== 7) return fallback
  return [1, 3, 5].map((i) =>
    Math.pow(parseInt(resolved.slice(i, i + 2), 16) / 255, 2.2),
  )
}

const compile = (
  gl: WebGL2RenderingContext,
  type: number,
  source: string,
): WebGLShader | null => {
  const shader = gl.createShader(type)
  if (!shader) return null
  gl.shaderSource(shader, source)
  gl.compileShader(shader)
  if (gl.getShaderParameter(shader, gl.COMPILE_STATUS)) return shader
  gl.deleteShader(shader)
  return null
}

/** 原图始终保留，画布仅在纹理就绪后覆盖；参数控制光箔强度和倾斜幅度。 */
export default function HoloCard({
  image,
  alt = '',
  preset = 'bursts',
  intensity = 0.85,
  scale = 1,
  edgeSparkle = 0.8,
  frame = 4,
  glare = 0.5,
  foilColor = '#e2e6ec',
  width = 320,
  radius = 14,
  tiltMax = 14,
  hoverScale = 1.04,
  idle = false,
  shadow = true,
  className = '',
  style,
}: HoloCardProps) {
  const rootRef = useRef<HTMLDivElement>(null)
  const rotorRef = useRef<HTMLDivElement>(null)
  const shadowRef = useRef<HTMLSpanElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const settingsRef = useRef<Settings | null>(null)
  const pointerRef = useRef({ x: 0.5, y: 0.5, inside: false })
  const wakeRef = useRef<(() => void) | null>(null)
  const loadRef = useRef<((src?: string) => void) | null>(null)
  const [ratio, setRatio] = useState(CARD_RATIO)
  const [ready, setReady] = useState(false)

  settingsRef.current = {
    preset: Math.max(0, PRESETS.indexOf(preset)),
    intensity: clamp(intensity, 0, 1),
    scale: clamp(scale, 0.25, 4),
    edgeSparkle: clamp(edgeSparkle, 0, 1),
    frame: clamp(frame, 0, 20) / 100,
    glare: clamp(glare, 0, 1),
    foil: parseColor(foilColor),
    radius: Math.max(0, radius),
    tiltMax: clamp(tiltMax, 0, 45),
    hoverScale: clamp(hoverScale, 0.8, 1.3),
    idle,
  }

  useEffect(() => {
    wakeRef.current?.()
  })

  useEffect(() => {
    loadRef.current?.(image)
  }, [image])

  useEffect(() => {
    const root = rootRef.current
    const rotor = rotorRef.current
    const canvas = canvasRef.current
    if (!root || !rotor || !canvas) return undefined

    // 访问中改变系统偏好也会立即停止动画；手机触摸不触发倾斜，保留纵向滑动。
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)')
    // 获取上下文失败时保留下面的原图，不让整块截图变空白。
    let gl: WebGL2RenderingContext | null = null
    try {
      gl = canvas.getContext('webgl2', {
        alpha: true,
        premultipliedAlpha: true,
        antialias: false,
        powerPreference: 'low-power',
      })
    } catch {
      /* 浏览器禁用 WebGL 时继续使用静态图片。 */
    }
    let program: WebGLProgram | null = null
    let texture: WebGLTexture | null = null
    let buffer: WebGLBuffer | null = null
    const uniforms: Record<string, WebGLUniformLocation | null> = {}
    let textureReady = false

    if (gl) {
      const vertex = compile(gl, gl.VERTEX_SHADER, VERTEX)
      const fragment = compile(gl, gl.FRAGMENT_SHADER, FRAGMENT)
      if (!vertex || !fragment) {
        gl.deleteShader(vertex)
        gl.deleteShader(fragment)
      }
      if (vertex && fragment) {
        program = gl.createProgram()
        gl.attachShader(program, vertex)
        gl.attachShader(program, fragment)
        gl.bindAttribLocation(program, 0, 'position')
        gl.linkProgram(program)
        gl.deleteShader(vertex)
        gl.deleteShader(fragment)
        if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
          gl.deleteProgram(program)
          program = null
        }
      }
      if (program) {
        buffer = gl.createBuffer()
        gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
        gl.bufferData(
          gl.ARRAY_BUFFER,
          new Float32Array([-1, -1, 3, -1, -1, 3]),
          gl.STATIC_DRAW,
        )
        gl.enableVertexAttribArray(0)
        gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0)
        texture = gl.createTexture()
        gl.bindTexture(gl.TEXTURE_2D, texture)
        gl.texParameteri(
          gl.TEXTURE_2D,
          gl.TEXTURE_MIN_FILTER,
          gl.LINEAR_MIPMAP_LINEAR,
        )
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
        gl.texImage2D(
          gl.TEXTURE_2D,
          0,
          gl.RGBA,
          1,
          1,
          0,
          gl.RGBA,
          gl.UNSIGNED_BYTE,
          new Uint8Array([0, 0, 0, 0]),
        )
        for (const name of [
          'uArt',
          'uSize',
          'uAspect',
          'uTilt',
          'uLight',
          'uDistance',
          'uPreset',
          'uIntensity',
          'uScale',
          'uEdge',
          'uFrame',
          'uRadius',
          'uGlare',
          'uFoil',
          'uReady',
        ]) {
          uniforms[name] = gl.getUniformLocation(program, name)
        }
      }
    }

    const state = {
      tiltX: 0,
      tiltY: 0,
      tiltVX: 0,
      tiltVY: 0,
      lightX: 0.36,
      lightY: 0.26,
      lightVX: 0,
      lightVY: 0,
      lift: 1,
      liftV: 0,
      clock: Math.random() * 40,
    }
    let raf = 0
    let last = 0
    let visible = true
    let alive = true
    let calm = 0
    let loadVersion = 0

    const draw = () => {
      if (!gl || !program || gl.isContextLost()) return
      const s = settingsRef.current!
      const w = canvas.width
      const h = canvas.height
      if (!w || !h) return
      gl.viewport(0, 0, w, h)
      gl.clearColor(0, 0, 0, 0)
      gl.clear(gl.COLOR_BUFFER_BIT)
      gl.useProgram(program)
      gl.activeTexture(gl.TEXTURE0)
      gl.bindTexture(gl.TEXTURE_2D, texture)
      gl.uniform1i(uniforms.uArt, 0)
      gl.uniform2f(uniforms.uSize, w, h)
      gl.uniform1f(uniforms.uAspect, h / w)
      gl.uniform2f(
        uniforms.uTilt,
        (state.tiltX * Math.PI) / 180,
        (state.tiltY * Math.PI) / 180,
      )
      gl.uniform2f(uniforms.uLight, state.lightX, state.lightY)
      gl.uniform1f(
        uniforms.uDistance,
        PERSPECTIVE / Math.max(1, canvas.clientWidth),
      )
      gl.uniform1i(uniforms.uPreset, s.preset)
      gl.uniform1f(uniforms.uIntensity, s.intensity)
      gl.uniform1f(uniforms.uScale, s.scale)
      gl.uniform1f(uniforms.uEdge, s.edgeSparkle)
      gl.uniform1f(uniforms.uFrame, s.frame)
      gl.uniform1f(uniforms.uRadius, s.radius / Math.max(1, canvas.clientWidth))
      gl.uniform1f(uniforms.uGlare, s.glare)
      gl.uniform3f(uniforms.uFoil, s.foil[0], s.foil[1], s.foil[2])
      gl.uniform1f(uniforms.uReady, textureReady ? 1 : 0)
      gl.drawArrays(gl.TRIANGLES, 0, 3)
    }

    const spring = (
      value: number,
      velocity: number,
      target: number,
      stiffness: number,
      damping: number,
      dt: number,
    ): [number, number] => {
      const next =
        velocity + ((target - value) * stiffness - velocity * damping) * dt
      return [value + next * dt, next]
    }

    const tick = (now: number) => {
      raf = 0
      if (!alive) return
      const dt = Math.min(1 / 30, Math.max(1 / 240, (now - last) / 1000))
      last = now
      const s = settingsRef.current!
      const pointer = pointerRef.current
      let targetX = 0
      let targetY = 0
      let lightX = 0.36
      let lightY = 0.26
      let lift = 1
      const drifting = s.idle && !reduce.matches && !pointer.inside
      if (pointer.inside && !reduce.matches) {
        targetX = (0.5 - pointer.y) * 2 * s.tiltMax
        targetY = (pointer.x - 0.5) * 2 * s.tiltMax
        lightX = pointer.x
        lightY = pointer.y
        lift = s.hoverScale
      } else if (drifting) {
        state.clock += dt
        const t = state.clock
        targetX = Math.sin(t * 0.7) * s.tiltMax * 0.28
        targetY = Math.sin(t * 0.53 + 1.2) * s.tiltMax * 0.4
        lightX = 0.5 + Math.sin(t * 0.41) * 0.42
        lightY = 0.38 + Math.sin(t * 0.33 + 0.6) * 0.3
      }
      const steps = Math.ceil(dt / (1 / 240))
      const h = dt / steps
      for (let i = 0; i < steps; i++) {
        ;[state.tiltX, state.tiltVX] = spring(
          state.tiltX,
          state.tiltVX,
          targetX,
          150,
          16,
          h,
        )
        ;[state.tiltY, state.tiltVY] = spring(
          state.tiltY,
          state.tiltVY,
          targetY,
          150,
          16,
          h,
        )
        ;[state.lightX, state.lightVX] = spring(
          state.lightX,
          state.lightVX,
          lightX,
          220,
          30,
          h,
        )
        ;[state.lightY, state.lightVY] = spring(
          state.lightY,
          state.lightVY,
          lightY,
          220,
          30,
          h,
        )
        ;[state.lift, state.liftV] = spring(
          state.lift,
          state.liftV,
          lift,
          320,
          30,
          h,
        )
      }
      // 弹簧积分沿用官方实现；减少动画时直接归位，避免残留倾斜。
      if (reduce.matches) {
        state.tiltX =
          state.tiltY =
          state.tiltVX =
          state.tiltVY =
          state.liftV =
            0
        state.lift = 1
        state.lightX = 0.36
        state.lightY = 0.26
      }
      rotor.style.transform = `perspective(${PERSPECTIVE}px) scale(${state.lift}) rotateX(${state.tiltX}deg) rotateY(${state.tiltY}deg)`
      if (shadowRef.current) {
        shadowRef.current.style.transform = `translate(${-state.tiltY * 0.7}px, ${state.tiltX * 0.7}px) scale(${0.9 + (state.lift - 1) * 1.5})`
      }
      draw()
      const motion =
        Math.abs(state.tiltVX) +
        Math.abs(state.tiltVY) +
        Math.abs(state.liftV) * 40 +
        (Math.abs(state.lightVX) + Math.abs(state.lightVY)) * 60 +
        Math.abs(state.tiltX - targetX) +
        Math.abs(state.tiltY - targetY)
      calm = motion > 0.02 ? 0 : calm + dt
      // 指针停住后只保留静态帧；离屏、后台或减少动画时不空转。
      if (
        !visible ||
        document.hidden ||
        reduce.matches ||
        (!drifting && calm > 0.3)
      )
        return
      raf = requestAnimationFrame(tick)
    }

    const wake = () => {
      if (raf || !alive || !visible || document.hidden) return
      calm = 0
      last = performance.now()
      raf = requestAnimationFrame(tick)
    }
    wakeRef.current = wake

    const resize = () => {
      // 光箔是装饰层，1.5 倍足够；避免高像素密度手机用四倍像素反复计算光箔。
      const dpr = Math.min(window.devicePixelRatio || 1, 1.5)
      const w = Math.max(1, Math.round(canvas.clientWidth * dpr))
      const h = Math.max(1, Math.round(canvas.clientHeight * dpr))
      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w
        canvas.height = h
      }
      draw()
      wake()
    }

    // 每次换图增加版本，旧图片即使晚加载完成也不能覆盖当前选择。
    loadRef.current = (src?: string) => {
      const version = ++loadVersion
      textureReady = false
      setReady(false)
      if (!src) return
      const img = new window.Image()
      img.crossOrigin = 'anonymous'
      img.decoding = 'async'
      img.onload = () => {
        if (!alive || version !== loadVersion) return
        if (img.naturalWidth && img.naturalHeight)
          setRatio(img.naturalWidth / img.naturalHeight)
        if (!gl || !program) return
        try {
          gl.bindTexture(gl.TEXTURE_2D, texture)
          gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false)
          gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false)
          gl.texImage2D(
            gl.TEXTURE_2D,
            0,
            gl.RGBA,
            gl.RGBA,
            gl.UNSIGNED_BYTE,
            img,
          )
          gl.generateMipmap(gl.TEXTURE_2D)
          textureReady = true
          setReady(true)
          draw()
          wake()
        } catch {
          textureReady = false
        }
      }
      img.src = src
    }
    loadRef.current(image)

    const resizeObserver = new ResizeObserver(resize)
    resizeObserver.observe(canvas)
    const visibility = new IntersectionObserver((entries) => {
      visible = entries.some((entry) => entry.isIntersecting)
      if (visible) wake()
      else {
        cancelAnimationFrame(raf)
        raf = 0
      }
    })
    visibility.observe(root)
    const onVisibility = () => {
      cancelAnimationFrame(raf)
      raf = 0
      wake()
    }
    const onContextLost = () => {
      // GPU 上下文丢失后隐藏画布，继续显示当前原图。
      cancelAnimationFrame(raf)
      raf = 0
      textureReady = false
      setReady(false)
    }
    reduce.addEventListener('change', onVisibility)
    document.addEventListener('visibilitychange', onVisibility)
    canvas.addEventListener('webglcontextlost', onContextLost)
    resize()

    return () => {
      alive = false
      cancelAnimationFrame(raf)
      wakeRef.current = null
      loadRef.current = null
      // 卸载时停止回调、忽略在途图片，并释放 GPU 资源。
      ++loadVersion
      reduce.removeEventListener('change', onVisibility)
      document.removeEventListener('visibilitychange', onVisibility)
      canvas.removeEventListener('webglcontextlost', onContextLost)
      resizeObserver.disconnect()
      visibility.disconnect()
      if (gl) {
        gl.deleteTexture(texture)
        gl.deleteBuffer(buffer)
        gl.deleteProgram(program)
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const track = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (e.pointerType === 'touch') return
    const rect = e.currentTarget.getBoundingClientRect()
    pointerRef.current = {
      x: clamp((e.clientX - rect.left) / rect.width, 0, 1),
      y: clamp((e.clientY - rect.top) / rect.height, 0, 1),
      inside: true,
    }
    wakeRef.current?.()
  }

  const leave = () => {
    pointerRef.current = { ...pointerRef.current, inside: false }
    wakeRef.current?.()
  }

  return (
    <div
      ref={rootRef}
      className={`holo-card${className ? ` ${className}` : ''}`}
      role="img"
      aria-label={alt || undefined}
      data-ready={ready ? '' : undefined}
      onPointerEnter={track}
      onPointerMove={track}
      onPointerDown={track}
      onPointerLeave={leave}
      onPointerCancel={leave}
      style={
        {
          '--hc-w': `${width}px`,
          '--hc-ratio': ratio,
          '--hc-radius': `${radius}px`,
          ...style,
        } as CSSProperties
      }
    >
      {shadow ? (
        <span
          ref={shadowRef}
          className="holo-card__shadow"
          aria-hidden="true"
        />
      ) : null}
      <div ref={rotorRef} className="holo-card__rotor">
        <div className="holo-card__face holo-card__face--front">
          {image ? (
            <Image
              src={image}
              alt=""
              fill
              unoptimized
              sizes="(max-width: 700px) 85vw, 430px"
              draggable={false}
            />
          ) : null}
          <canvas ref={canvasRef} aria-hidden="true" />
        </div>
      </div>
    </div>
  )
}
