'use client'

import { useEffect, useRef } from 'react'
import * as THREE from 'three'
import { createElasticMeshPhysics } from './elastic-mesh-physics'
import { VERT, FRAG } from './elastic-mesh-shaders'

// 改编自 React Bits ElasticMesh；保留官方物理与 shader，复用现有 Three.js。
// Copyright (c) 2026 David Haz. MIT + Commons Clause 许可全文见 BLACK_WHITE_REDESIGN.md。
// 来源：https://github.com/DavidHDev/react-bits/blob/main/src/ts-default/Animations/ElasticMesh/ElasticMesh.tsx
const DIST = 4.6
const FIT = 0.82
const TILT = 14
const STEP = 1 / 120
const MAX_SUBSTEPS = 5

/** 下载区的装饰背景；监听父区块的鼠标，文字和链接不随网格变形。 */
export default function ElasticMesh({
  className = '',
}: {
  className?: string
}) {
  const host = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const element = host.current
    const eventSource = element?.parentElement
    if (!element || !eventSource) return
    let visible = false
    let attempted = false
    let resume = () => {}
    let stop = () => {}
    let release: (() => void) | undefined

    /** 首次进入视口才申请 GPU；创建失败时保留 CSS 静态网格。 */
    const initialize = (): (() => void) | undefined => {
      let renderer: THREE.WebGLRenderer
      try {
        renderer = new THREE.WebGLRenderer({
          alpha: true,
          antialias: true,
          depth: false,
          powerPreference: 'low-power',
        })
      } catch {
        return
      }
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5))
      renderer.setClearColor(0, 0)
      element.appendChild(renderer.domElement)

      const physics = createElasticMeshPhysics()
      const geometry = new THREE.BufferGeometry()
      geometry.setAttribute('aGrid', new THREE.BufferAttribute(physics.grid, 2))
      geometry.setAttribute('uv', new THREE.BufferAttribute(physics.uv, 2))
      geometry.setAttribute(
        'aOffset',
        new THREE.BufferAttribute(physics.offsets, 3),
      )
      geometry.setAttribute(
        'aNormal',
        new THREE.BufferAttribute(physics.normals, 3),
      )
      geometry.setIndex(new THREE.BufferAttribute(physics.indices, 1))
      const material = new THREE.ShaderMaterial({
        vertexShader: VERT,
        fragmentShader: FRAG,
        transparent: true,
        depthTest: false,
        depthWrite: false,
        side: THREE.DoubleSide,
        toneMapped: false,
        uniforms: {
          tMap: { value: null },
          uHasImage: { value: 0 },
          uColor1: { value: new THREE.Vector3() },
          uColor2: { value: new THREE.Vector3() },
          uHighlight: { value: new THREE.Vector3(1, 1, 1) },
          uGrid: { value: 1 },
          uGridDensity: { value: 26 },
          uGridOpacity: { value: 0.38 },
          uGridColor: { value: new THREE.Vector3() },
          uShading: { value: 0.55 },
          uRes: { value: new THREE.Vector2(1, 1) },
          uRadius: { value: 24 },
          uAspect: { value: 1 },
          uTilt: { value: (TILT * Math.PI) / 180 },
          uDist: { value: DIST },
          uFit: { value: FIT },
        },
      })
      const mesh = new THREE.Mesh(geometry, material)
      // 顶点由 aGrid 与 aOffset 定位，没有 Three.js 默认的 position 属性。
      mesh.frustumCulled = false
      const scene = new THREE.Scene()
      scene.add(mesh)
      const camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1)
      const motion = window.matchMedia('(prefers-reduced-motion: reduce)')
      const pointer = { x: 0, y: 0, tx: 0, ty: 0, active: false }
      let aspect = 1
      let raf = 0
      let last = 0
      let elapsed = 0
      let alive = true
      let lost = false

      const frame = (now: number): void => {
        raf = 0
        if (!alive || lost || !visible || document.hidden) return
        // 从统一主题读取黑白色阶；静止状态下也响应主题切换。
        const dark = document.documentElement.dataset.theme === 'dark'
        material.uniforms.uColor1.value.setScalar(dark ? 0.055 : 0.9)
        material.uniforms.uColor2.value.setScalar(dark ? 0.09 : 0.96)
        material.uniforms.uGridColor.value.setScalar(dark ? 0.55 : 0.34)
        const dt = Math.min(0.05, Math.max(0, (now - last) / 1000))
        last = now
        const lerp = 1 - Math.exp(-Math.max(dt, 0.0001) / 0.06)
        pointer.x += (pointer.tx - pointer.x) * lerp
        pointer.y += (pointer.ty - pointer.y) * lerp
        if (motion.matches) {
          physics.reset()
          pointer.active = false
          elapsed = 0
        } else {
          // 沿用官方 120Hz 固定步长，限制补算量，避免切回后台后突然跳动。
          elapsed += dt
          let steps = 0
          while (elapsed >= STEP && steps < MAX_SUBSTEPS) {
            physics.step(pointer)
            elapsed -= STEP
            steps++
          }
          if (elapsed > STEP) elapsed = 0
        }
        const energy = physics.commit()
        geometry.attributes.aOffset.needsUpdate = true
        geometry.attributes.aNormal.needsUpdate = true
        renderer.render(scene, camera)
        element.dataset.ready = 'true'

        // 悬停达到平衡、离开完成回弹后停止渲染，不让局部装饰持续占用 GPU。
        const moving =
          energy.maxVelocity > 0.00005 ||
          (!pointer.active && energy.maxOffset > 0.0005) ||
          (pointer.active &&
            Math.abs(pointer.x - pointer.tx) +
              Math.abs(pointer.y - pointer.ty) >
              0.001)
        if (!motion.matches && moving) raf = requestAnimationFrame(frame)
      }
      stop = () => {
        cancelAnimationFrame(raf)
        raf = 0
        pointer.active = false
      }
      resume = () => {
        if (raf || !alive || lost || !visible || document.hidden) return
        last = performance.now()
        raf = requestAnimationFrame(frame)
      }

      /** 把鼠标坐标反投影到倾斜平面，沿用官方透视与倾角计算。 */
      const move = (event: PointerEvent): void => {
        if (event.pointerType === 'touch' || motion.matches || !visible) return
        const rect = element.getBoundingClientRect()
        if (!rect.width || !rect.height) return
        const clipX = ((event.clientX - rect.left) / rect.width) * 2 - 1
        const clipY = 1 - ((event.clientY - rect.top) / rect.height) * 2
        const tilt = (TILT * Math.PI) / 180
        const a = clipY / (Math.cos(tilt) * FIT * DIST)
        const py = (a * DIST) / (1 + a * Math.sin(tilt))
        const perspective = DIST / (DIST - py * Math.sin(tilt))
        pointer.tx = (clipX * aspect) / (perspective * FIT)
        pointer.ty = py
        pointer.active = true
        resume()
      }
      const leave = (): void => {
        if (!pointer.active) return
        pointer.active = false
        resume()
      }
      const resize = (): void => {
        const width = Math.max(1, element.clientWidth)
        const height = Math.max(1, element.clientHeight)
        aspect = width / height
        renderer.setSize(width, height)
        physics.setAspect(aspect)
        material.uniforms.uAspect.value = aspect
        material.uniforms.uRes.value.set(width, height)
        resume()
      }
      const preferenceChanged = (): void => {
        stop()
        resume()
      }
      const contextLost = (): void => {
        lost = true
        stop()
        delete element.dataset.ready
      }
      const sizeObserver = new ResizeObserver(resize)
      sizeObserver.observe(element)
      const themeObserver = new MutationObserver(resume)
      themeObserver.observe(document.documentElement, {
        attributes: true,
        attributeFilter: ['data-theme'],
      })
      eventSource.addEventListener('pointermove', move, { passive: true })
      eventSource.addEventListener('pointerenter', move, { passive: true })
      eventSource.addEventListener('pointerleave', leave)
      motion.addEventListener('change', preferenceChanged)
      document.addEventListener('visibilitychange', preferenceChanged)
      renderer.domElement.addEventListener('webglcontextlost', contextLost)
      resize()

      return () => {
        alive = false
        stop()
        sizeObserver.disconnect()
        themeObserver.disconnect()
        eventSource.removeEventListener('pointermove', move)
        eventSource.removeEventListener('pointerenter', move)
        eventSource.removeEventListener('pointerleave', leave)
        motion.removeEventListener('change', preferenceChanged)
        document.removeEventListener('visibilitychange', preferenceChanged)
        renderer.domElement.removeEventListener('webglcontextlost', contextLost)
        geometry.dispose()
        material.dispose()
        renderer.dispose()
        renderer.forceContextLoss()
        renderer.domElement.remove()
        delete element.dataset.ready
      }
    }

    const visibility = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting
      if (visible && !attempted) {
        attempted = true
        release = initialize()
      }
      if (visible) resume()
      else stop()
    })
    visibility.observe(element)
    return () => {
      visibility.disconnect()
      release?.()
    }
  }, [])

  return (
    <div
      ref={host}
      className={`elastic-mesh ${className}`}
      aria-hidden="true"
    />
  )
}
