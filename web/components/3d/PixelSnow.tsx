'use client'

import { useEffect, useRef } from 'react'
import * as THREE from 'three'
import { useTheme } from '@/components/ui/ThemeProvider'
import { vertexShader, fragmentShader } from './pixel-snow-shaders'

/** React Bits Pixel Snow 原始 shader 的本地适配：低分辨率渲染、主题反色和按需暂停。 */
export default function PixelSnow() {
  const host = useRef<HTMLDivElement>(null)
  const materialRef = useRef<THREE.ShaderMaterial | null>(null)
  const { dark } = useTheme()

  useEffect(() => {
    const element = host.current
    if (!element) return
    let renderer: THREE.WebGLRenderer
    try {
      renderer = new THREE.WebGLRenderer({
        alpha: true,
        antialias: false,
        powerPreference: 'low-power',
        depth: false,
      })
    } catch {
      return /* WebGL 不可用时保留 CSS 静态像素背景。 */
    }
    renderer.setPixelRatio(1)
    element.appendChild(renderer.domElement)
    const scene = new THREE.Scene()
    const camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1)
    const material = new THREE.ShaderMaterial({
      vertexShader,
      fragmentShader,
      transparent: true,
      uniforms: {
        uTime: { value: 12 },
        uResolution: { value: new THREE.Vector2() },
        uFlakeSize: { value: 0.012 },
        uMinFlakeSize: { value: 1.25 },
        uPixelResolution: { value: 280 },
        uSpeed: { value: 0.35 },
        uDepthFade: { value: 7 },
        uFarPlane: { value: 14 },
        uColor: { value: new THREE.Vector3(1, 1, 1) },
        uBrightness: { value: 0.8 },
        uGamma: { value: 0.4545 },
        uDensity: { value: 0.14 },
        uVariant: { value: 0 },
        uDirection: { value: (125 * Math.PI) / 180 },
      },
    })
    materialRef.current = material
    const geometry = new THREE.PlaneGeometry(2, 2)
    scene.add(new THREE.Mesh(geometry, material))
    const media = window.matchMedia('(prefers-reduced-motion: reduce)')
    let visible = true
    let frame = 0
    let last = 0
    const resize = () => {
      const width = Math.max(1, element.clientWidth)
      const height = Math.max(1, element.clientHeight)
      // 像素雪无需随屏幕分辨率增长；限制到 480×320 内，避免每个全屏像素都执行光线步进。
      const scale = Math.min(0.5, 480 / width, 320 / height)
      renderer.setSize(Math.round(width * scale), Math.round(height * scale), false)
      renderer.getDrawingBufferSize(material.uniforms.uResolution.value)
    }
    // 30fps 足以表现慢速雪；不可见和减少动画时不保留空转 RAF。
    const render = (now: number) => {
      frame = 0
      if (now - last > 32 || media.matches) {
        if (!media.matches)
          material.uniforms.uTime.value += Math.min((now - last) / 1000, 0.05)
        last = now
        renderer.render(scene, camera)
      }
      if (visible && !document.hidden && !media.matches)
        frame = requestAnimationFrame(render)
    }
    const resume = () => {
      cancelAnimationFrame(frame)
      frame = 0
      if (visible && !document.hidden) {
        last = 0
        frame = requestAnimationFrame(render)
      }
    }
    const observer = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting
      resume()
    })
    const sizeObserver = new ResizeObserver(() => {
      resize()
      resume()
    })
    observer.observe(element)
    sizeObserver.observe(element)
    document.addEventListener('visibilitychange', resume)
    media.addEventListener('change', resume)
    // 主题变化时也绘制静态帧，减少动画模式下不会留下旧主题。
    const themeObserver = new MutationObserver(resume)
    themeObserver.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme'],
    })
    resize()
    resume()
    return () => {
      cancelAnimationFrame(frame)
      observer.disconnect()
      sizeObserver.disconnect()
      themeObserver.disconnect()
      document.removeEventListener('visibilitychange', resume)
      media.removeEventListener('change', resume)
      geometry.dispose()
      material.dispose()
      renderer.dispose()
      renderer.forceContextLoss()
      renderer.domElement.remove()
      materialRef.current = null
    }
  }, [])

  useEffect(() => {
    materialRef.current?.uniforms.uColor.value.setScalar(dark ? 1 : 0)
  }, [dark])

  return <div ref={host} className="pixel-snow" aria-hidden="true" />
}
