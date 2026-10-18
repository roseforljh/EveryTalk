'use client'

import { useEffect, useLayoutEffect, useRef, type ReactNode } from 'react'
import { gsap } from 'gsap'
import * as THREE from 'three'
import { vertex, fragment } from './morph-slider-shaders'

interface MorphItem { image: string }
interface MorphSliderProps {
  items: readonly MorphItem[]
  active: number
  onSelect: (index: number) => void
  label: string
  children: ReactNode
}

// 改编自 React Bits MorphSlider；Copyright (c) 2026 David Haz，许可见设计文档。
/** 受控滑块与分类、箭头共用索引；官方 melt 着色器只在切换时覆盖静态 HoloCard。 */
export default function MorphSlider({ items, active, onSelect, label, children }: MorphSliderProps) {
  const rootRef = useRef<HTMLDivElement>(null)
  const effectRef = useRef<HTMLDivElement>(null)
  const selectRef = useRef<((index: number) => void) | null>(null)
  const dragRef = useRef<{ x: number; y: number } | null>(null)
  useEffect(() => {
    const root = rootRef.current
    const effect = effectRef.current
    if (!root || !effect) return
    let renderer: THREE.WebGLRenderer
    try {
      renderer = new THREE.WebGLRenderer({ alpha: false, antialias: false, powerPreference: 'low-power' })
    } catch {
      return // 下层 HoloCard 的普通图片始终可见，WebGL 失败也能切换和查看原图。
    }
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.25))
    // 每次挂载创建新的画布，避免开发模式的重复挂载复用已释放的 WebGL 上下文。
    const canvas = renderer.domElement
    canvas.className = 'morph-slider-canvas'
    effect.appendChild(canvas)
    const motion = window.matchMedia('(prefers-reduced-motion: reduce)')
    const textures: (THREE.Texture | undefined)[] = items.map(() => undefined)
    const failed = new Set<number>()
    const uniforms = {
      tCurrent: { value: null as THREE.Texture | null },
      tNext: { value: null as THREE.Texture | null },
      uResolution: { value: new THREE.Vector2(1, 1) },
      uCurrentSize: { value: new THREE.Vector2(1, 1) },
      uNextSize: { value: new THREE.Vector2(1, 1) },
      uProgress: { value: 0 }, uDir: { value: 1 }, uMode: { value: 0 },
      uIntensity: { value: 0.38 }, uScale: { value: 2.4 },
      uAberration: { value: 0 }, uDrift: { value: 0 }, uTime: { value: 0 },
      uReduce: { value: 0 }, uPointer: { value: new THREE.Vector2(0.5, 0.5) },
      uOverlay: { value: new THREE.Color(0) },
    }
    const geometry = new THREE.PlaneGeometry(2, 2)
    const material = new THREE.ShaderMaterial({ vertexShader: vertex, fragmentShader: fragment, uniforms, depthTest: false, depthWrite: false })
    const scene = new THREE.Scene()
    scene.add(new THREE.Mesh(geometry, material))
    const camera = new THREE.Camera()
    let current = 0
    let requested = 0
    let disposed = false
    let lost = false
    let tween: gsap.core.Tween | null = null
    const draw = () => {
      if (!disposed && !lost && uniforms.tCurrent.value) renderer.render(scene, camera)
    }
    const dimensions = (texture: THREE.Texture, target: THREE.Vector2) => {
      const image = texture.image as HTMLImageElement
      target.set(image.naturalWidth, image.naturalHeight)
    }
    const hide = () => { delete root.dataset.transitioning }
    const settle = (index: number) => {
      current = index
      uniforms.uProgress.value = 0
      hide()
    }
    const select = (index: number) => {
      if (!Number.isInteger(index) || index < 0 || index >= items.length) return
      requested = index
      // 连续点击只排队最后一次选择，当前过渡完整结束后再开始，避免画面跳回起点。
      if (tween || current === requested) return
      if (motion.matches || lost || failed.has(index) || failed.has(current)) {
        settle(index)
        return
      }
      const from = textures[current]
      const to = textures[index]
      if (!from || !to) return // 纹理未就绪时保留下层图片，加载成功后重新尝试。
      uniforms.tCurrent.value = from
      uniforms.tNext.value = to
      dimensions(from, uniforms.uCurrentSize.value)
      dimensions(to, uniforms.uNextSize.value)
      uniforms.uDir.value = index > current ? 1 : -1
      uniforms.uTime.value = performance.now() / 1000
      uniforms.uProgress.value = 0
      root.dataset.transitioning = 'true'
      draw()
      tween = gsap.to(uniforms.uProgress, {
        value: 1, duration: 0.85, ease: 'power2.inOut',
        onUpdate: draw,
        onComplete: () => {
          current = index
          tween = null
          if (requested !== current) select(requested)
          else settle(current)
        },
      })
    }
    selectRef.current = select
    const resize = () => {
      // 不使用祖先滚动缩放后的 rect，保持画布与宣传卡片的布局尺寸一致。
      renderer.setSize(Math.max(root.clientWidth, 1), Math.max(root.clientHeight, 1), false)
      renderer.getDrawingBufferSize(uniforms.uResolution.value)
      draw()
    }
    const sizeObserver = new ResizeObserver(resize)
    sizeObserver.observe(root)
    resize()
    const loader = new THREE.TextureLoader()
    let loaded = false
    const load = () => {
      if (loaded) return
      loaded = true
      items.forEach((item, index) => {
        loader.load(item.image, texture => {
          if (disposed) { texture.dispose(); return }
          texture.minFilter = THREE.LinearFilter
          texture.magFilter = THREE.LinearFilter
          texture.generateMipmaps = false
          textures[index] = texture
          if (index === current) {
            uniforms.tCurrent.value = texture
            dimensions(texture, uniforms.uCurrentSize.value)
          }
          select(requested)
        }, undefined, () => {
          if (disposed) return
          failed.add(index)
          if (requested === index) select(index)
        })
      })
    }
    // 展示区接近视口才加载五张压缩纹理，不影响首屏资源下载。
    const visibility = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) {
        load()
        visibility.disconnect()
      }
    }, { rootMargin: '240px' })
    visibility.observe(root)
    const preferenceChanged = () => {
      tween?.kill()
      tween = null
      settle(requested)
    }
    const contextLost = () => {
      lost = true
      preferenceChanged()
    }
    motion.addEventListener('change', preferenceChanged)
    canvas.addEventListener('webglcontextlost', contextLost)
    return () => {
      disposed = true
      selectRef.current = null
      tween?.kill()
      sizeObserver.disconnect()
      visibility.disconnect()
      motion.removeEventListener('change', preferenceChanged)
      canvas.removeEventListener('webglcontextlost', contextLost)
      textures.forEach(texture => texture?.dispose())
      material.dispose()
      geometry.dispose()
      renderer.dispose()
      renderer.forceContextLoss()
      canvas.remove()
    }
  }, [items])
  // 索引变化在浏览器绘制前送入过渡层，避免新图片先闪一下再开始形变。
  useLayoutEffect(() => { selectRef.current?.(active) }, [active])
  const step = (direction: number) => onSelect((active + direction + items.length) % items.length)
  return (
    <div
      ref={rootRef}
      className="morph-slider showcase-holo"
      role="group"
      aria-roledescription="carousel"
      aria-label={label}
      tabIndex={0}
      onKeyDown={event => {
        if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
          event.preventDefault()
          step(event.key === 'ArrowRight' ? 1 : -1)
        }
      }}
      onPointerDown={event => {
        if (event.button !== 0) return
        dragRef.current = { x: event.clientX, y: event.clientY }
        event.currentTarget.setPointerCapture(event.pointerId)
      }}
      onPointerUp={event => {
        const start = dragRef.current
        dragRef.current = null
        if (!start) return
        const dx = event.clientX - start.x
        const dy = event.clientY - start.y
        // 横向滑动切图；纵向手势留给浏览器滚动，短距离点击不误触切换。
        if (Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy) * 1.4) step(dx < 0 ? 1 : -1)
      }}
      onPointerCancel={() => { dragRef.current = null }}
    >
      {children}
      <div ref={effectRef} className="morph-slider__effect" aria-hidden="true" />
    </div>
  )
}
