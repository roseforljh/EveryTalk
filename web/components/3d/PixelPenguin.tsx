'use client'

import { useLanguage } from '@/components/ui/LanguageProvider'

import { useEffect, useRef, useState } from 'react'
import Image from 'next/image'
import { ArrowUpRight } from 'lucide-react'
import * as THREE from 'three'
import { vertex, maskFragment, viewFragment } from './dither-veil-shaders'

interface Voxel {
  x: number
  y: number
  z: number
}

const HOLD = 1.6
const BURST_SECONDS = 1.2
const MAX_BURSTS = 4

/** 采样原始 Logo 的深色区域，分层实例化小方块，保留品牌轮廓。 */
export default function PixelPenguin() {
  const { t } = useLanguage()
  const host = useRef<HTMLDivElement>(null)
  const reveal = useRef<(() => void) | null>(null)
  const [ready, setReady] = useState(false)
  const [reducedMotion, setReducedMotion] = useState(false)

  useEffect(() => {
    const element = host.current
    if (!element) return
    const motion = window.matchMedia('(prefers-reduced-motion: reduce)')
    // 用同一个事件状态控制绘制和按钮，避免每帧查询媒体偏好导致通知与画面不同步。
    let reduce = motion.matches
    setReducedMotion(reduce)
    let renderer: THREE.WebGLRenderer
    try {
      renderer = new THREE.WebGLRenderer({
        alpha: true,
        antialias: true,
        powerPreference: 'low-power',
      })
    } catch {
      return /* 保留下层的原始 Logo，WebGL 失败也能识别品牌。 */
    }
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5))
    renderer.setClearColor(0x000000, 0)
    element.appendChild(renderer.domElement)
    const scene = new THREE.Scene()
    const camera = new THREE.PerspectiveCamera(38, 1, 0.1, 50)
    camera.position.set(0, 0, 7.8)
    const group = new THREE.Group()
    scene.add(group)
    scene.add(new THREE.AmbientLight(0xffffff, 1.4))
    const light = new THREE.DirectionalLight(0xffffff, 3)
    light.position.set(-3, 5, 7)
    scene.add(light)
    const geometry = new THREE.BoxGeometry(0.035, 0.035, 0.065)
    const material = new THREE.MeshStandardMaterial({
      color: 0xffffff,
      roughness: 0.45,
      metalness: 0.2,
    })

    // 先把真实三维企鹅绘到透明纹理，再用官方 Dither Veil 做屏幕空间揭幕。
    const imageTarget = new THREE.WebGLRenderTarget(2, 2)
    const floatMask = renderer.extensions.has('EXT_color_buffer_float')
    const masks = [0, 1].map(
      () =>
        new THREE.WebGLRenderTarget(2, 2, {
          type: floatMask ? THREE.HalfFloatType : THREE.UnsignedByteType,
          depthBuffer: false,
          stencilBuffer: false,
          minFilter: THREE.LinearFilter,
          magFilter: THREE.LinearFilter,
        }),
    )
    let maskIndex = 0
    const fullscreen = new THREE.BufferGeometry()
    fullscreen.setAttribute(
      'position',
      new THREE.Float32BufferAttribute([-1, -1, 0, 3, -1, 0, -1, 3, 0], 3),
    )
    fullscreen.setAttribute(
      'uv',
      new THREE.Float32BufferAttribute([0, 0, 2, 0, 0, 2], 2),
    )
    const maskUniforms = {
      tPrev: { value: masks[0].texture },
      uSize: { value: new THREE.Vector2(1, 1) },
      uFrom: { value: new THREE.Vector2() },
      uTo: { value: new THREE.Vector2() },
      uRadius: { value: 150 },
      uSoftness: { value: 0.6 },
      uStrength: { value: 0 },
      uFade: { value: 1 },
      uHold: { value: HOLD },
    }
    const viewUniforms = {
      tImage: { value: imageTarget.texture },
      tMask: { value: masks[0].texture },
      // 本页选用官方 Bayer 模式，不启用需要 CPU 扩散或噪声纹理的其他预设。
      tNoise: { value: imageTarget.texture },
      tDiffused: { value: imageTarget.texture },
      uResolution: { value: new THREE.Vector2(1, 1) },
      uCover: { value: new THREE.Vector2(1, 1) },
      uLod: { value: 0 },
      uCell: { value: 6 },
      uPattern: { value: 0 },
      // 待机像素幕保持灰白；鼠标揭幕后由 tImage 直接显示实例的七彩原画。
      uPalette: { value: 0 },
      uLevels: { value: 4 },
      uInk: { value: new THREE.Vector3(0, 0, 0) },
      uPaper: { value: new THREE.Vector3(1, 1, 1) },
      uRimColor: { value: new THREE.Vector3(0.75, 0.75, 0.75) },
      uRim: { value: 0.08 },
      uContrast: { value: 1.05 },
      uBrightness: { value: 0 },
      uReverse: { value: 0 },
      uIntro: { value: 1 },
      uHold: { value: HOLD },
      uMatte: { value: new THREE.Vector3(0, 0, 0) },
      uKey: { value: 0 },
      uSize: { value: new THREE.Vector2(1, 1) },
      uBursts: { value: new Float32Array(MAX_BURSTS * 4) },
      uBurstWidth: { value: 100 },
    }
    const maskMaterial = new THREE.RawShaderMaterial({
      glslVersion: THREE.GLSL3,
      vertexShader: vertex,
      fragmentShader: maskFragment,
      uniforms: maskUniforms,
      depthTest: false,
      depthWrite: false,
    })
    const viewMaterial = new THREE.RawShaderMaterial({
      glslVersion: THREE.GLSL3,
      vertexShader: vertex,
      fragmentShader: viewFragment,
      uniforms: viewUniforms,
      depthTest: false,
      depthWrite: false,
      transparent: true,
    })
    const maskScene = new THREE.Scene()
    const viewScene = new THREE.Scene()
    const maskQuad = new THREE.Mesh(fullscreen, maskMaterial)
    const viewQuad = new THREE.Mesh(fullscreen, viewMaterial)
    maskQuad.frustumCulled = viewQuad.frustumCulled = false
    maskScene.add(maskQuad)
    viewScene.add(viewQuad)
    const screenCamera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1)

    const pixels: Voxel[] = []
    let mesh: THREE.InstancedMesh | undefined
    let disposed = false
    let visible = true
    let raf = 0
    let previous = 0
    // 三维原画保持固定透视，仅在颜色、尺寸或模型变化时重绘；后处理复用这张纹理。
    let imageDirty = true
    let settling = 0
    let lost = false
    let width = 1
    let height = 1
    let presence = 0
    const pointer = { x: 0, y: 0, inside: false, fresh: true, placed: false }
    const brush = { x: 0, y: 0, px: 0, py: 0 }
    const bursts: { x: number; y: number; start: number }[] = []
    const dummy = new THREE.Object3D()

    /** 原组件的笔刷跟随、轨迹衰减和点击波纹，只改变后处理，不移动任何方块。 */
    const render = (now: number) => {
      raf = 0
      if (disposed || lost || !visible || document.hidden) return
      const dt = Math.min((now - previous) / 1000 || 0.016, 0.05)
      previous = now
      group.rotation.set(-0.08, -0.22, -0.025)
      if (imageDirty) {
        renderer.setRenderTarget(imageTarget)
        renderer.render(scene, camera)
        imageDirty = false
      }
      settling = Math.max(0, settling - dt)

      presence +=
        ((pointer.inside ? 1 : 0) - presence) * (1 - Math.exp(-dt / 0.16))
      if (pointer.fresh) {
        brush.x = brush.px = pointer.x
        brush.y = brush.py = pointer.y
        pointer.fresh = false
      } else {
        const follow = 1 - Math.exp(-dt / 0.035)
        brush.x += (pointer.x - brush.x) * follow
        brush.y += (pointer.y - brush.y) * follow
      }
      if (reduce) {
        presence = 0
        bursts.length = 0
      }
      const radius = Math.min(width, height) * 0.3
      maskUniforms.tPrev.value = masks[maskIndex].texture
      maskUniforms.uFrom.value.set(brush.px, brush.py)
      maskUniforms.uTo.value.set(brush.x, brush.y)
      maskUniforms.uRadius.value = radius * (0.45 + 0.55 * presence)
      maskUniforms.uStrength.value = presence
      maskUniforms.uFade.value = reduce
        ? 1
        : Math.max(dt, floatMask ? 0 : 1.5 / 255)
      const nextMask = 1 - maskIndex
      renderer.setRenderTarget(masks[nextMask])
      renderer.render(maskScene, screenCamera)
      maskIndex = nextMask
      brush.px = brush.x
      brush.py = brush.y

      const burstData = viewUniforms.uBursts.value
      burstData.fill(0)
      viewUniforms.uBurstWidth.value = Math.max(60, radius * 0.9)
      for (let index = bursts.length - 1; index >= 0; index--)
        if ((now - bursts[index].start) / 1000 >= BURST_SECONDS)
          bursts.splice(index, 1)
      bursts.forEach((burst, index) => {
        const progress = Math.max(0, (now - burst.start) / 1000 / BURST_SECONDS)
        const reach =
          Math.hypot(
            Math.max(burst.x, width - burst.x),
            Math.max(burst.y, height - burst.y),
          ) + viewUniforms.uBurstWidth.value
        burstData[index * 4] = burst.x
        burstData[index * 4 + 1] = burst.y
        burstData[index * 4 + 2] = reach * Math.sin((progress * Math.PI) / 2)
        burstData[index * 4 + 3] = 1 - progress * progress * progress
      })
      viewUniforms.tMask.value = masks[maskIndex].texture
      renderer.setRenderTarget(null)
      renderer.render(viewScene, screenCamera)
      // 无人操作时冻结灰白帧；笔刷停住或离开后等轨迹完全收敛再停，保留七彩揭幕和波纹。
      if (visible && !document.hidden && !reduce && (settling > 0 || bursts.length))
        raf = requestAnimationFrame(render)
    }
    const resume = () => {
      // 鼠标事件只唤醒停下的循环，不取消已排队的帧，保证连续笔刷的时间步长稳定。
      if (disposed || lost || !visible || document.hidden) {
        cancelAnimationFrame(raf)
        raf = 0
      } else if (!raf) {
        previous = performance.now()
        raf = requestAnimationFrame(render)
      }
    }
    const resize = () => {
      width = element.clientWidth
      height = element.clientHeight
      if (!width || !height) return
      renderer.setSize(width, height)
      camera.aspect = width / height
      camera.updateProjectionMatrix()
      renderer.getDrawingBufferSize(viewUniforms.uResolution.value)
      imageTarget.setSize(
        viewUniforms.uResolution.value.x,
        viewUniforms.uResolution.value.y,
      )
      viewUniforms.uSize.value.set(width, height)
      viewUniforms.uCell.value = Math.max(
        1,
        Math.round(4 * renderer.getPixelRatio()),
      )
      maskUniforms.uSize.value.set(width, height)
      masks.forEach((target) => {
        target.setSize(
          Math.max(2, Math.round(width * 0.5)),
          Math.max(2, Math.round(height * 0.5)),
        )
        renderer.setRenderTarget(target)
        renderer.clear()
      })
      renderer.setRenderTarget(null)
      maskIndex = 0
      imageDirty = true
      if (!pointer.placed) {
        pointer.x = width / 2
        pointer.y = height / 2
      }
      resume()
    }
    const move = (event: PointerEvent) => {
      if (event.pointerType === 'touch' || reduce) return
      const rect = element.getBoundingClientRect()
      pointer.x = event.clientX - rect.left
      pointer.y = event.clientY - rect.top
      pointer.placed = true
      if (!pointer.inside) pointer.fresh = true
      pointer.inside = true
      settling = 1.4
      resume()
    }
    const leave = () => {
      pointer.inside = false
      settling = 1.4
      resume()
    }
    const burstAt = (x: number, y: number) => {
      if (!mesh || reduce) return
      bursts.push({ x, y, start: performance.now() })
      settling = 1.4
      if (bursts.length > MAX_BURSTS) bursts.shift()
      resume()
    }
    const down = (event: PointerEvent) => {
      // 手机滚动不触发效果；手机与键盘通过独立按钮触发同一扩散波纹。
      if (event.pointerType === 'touch' || event.button !== 0) return
      move(event)
      burstAt(pointer.x, pointer.y)
    }
    reveal.current = () => burstAt(width / 2, height / 2)
    const source = new window.Image()
    source.onload = () => {
      if (disposed || lost) return
      const canvas = document.createElement('canvas')
      canvas.width = canvas.height = 96
      const ctx = canvas.getContext('2d', { willReadFrequently: true })
      if (!ctx) return
      ctx.drawImage(source, 0, 0, 96, 96)
      const data = ctx.getImageData(0, 0, 96, 96).data
      for (let y = 0; y < 96; y++) {
        for (let x = 0; x < 96; x++) {
          const offset = (y * 96 + x) * 4
          if (data[offset + 3] < 128 || data[offset] > 120) continue
          for (let layer = 0; layer < 3; layer++) {
            pixels.push({
              x: (x - 47.5) * 0.062,
              y: (47.5 - y) * 0.062,
              z: -layer * 0.085,
            })
          }
        }
      }
      mesh = new THREE.InstancedMesh(geometry, material, pixels.length)
      let minY = Infinity
      let maxY = -Infinity
      for (const pixel of pixels) {
        minY = Math.min(minY, pixel.y)
        maxY = Math.max(maxY, pixel.y)
      }
      const span = Math.max(maxY - minY, 0.062)
      const color = new THREE.Color()
      // 沿实际轮廓从头到脚走过红、橙、黄、绿、青、蓝、紫；同高度的三层方块同色。
      // 位置和颜色都只上传一次。后续悬停仍由像素幕后处理负责，不移动或逐帧重染方块。
      pixels.forEach((pixel, index) => {
        dummy.position.set(pixel.x, pixel.y, pixel.z)
        dummy.updateMatrix()
        mesh?.setMatrixAt(index, dummy.matrix)
        const hue = ((maxY - pixel.y) / span) * 0.78
        color.setHSL(hue, 0.92, 0.52, THREE.SRGBColorSpace)
        mesh?.setColorAt(index, color)
      })
      mesh.instanceMatrix.needsUpdate = true
      if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true
      group.add(mesh)
      imageDirty = true
      element.dataset.ready = 'true'
      setReady(true)
      resume()
    }
    source.src = '/everytalk-logo-source.png'
    const observer = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting
      resume()
    })
    observer.observe(element)
    const sizeObserver = new ResizeObserver(resize)
    sizeObserver.observe(element)
    // 深色保持鲜亮，浅色稍压亮度以凸显轮廓；统一材质乘色保留原七彩，不重传实例数据。
    const updateTheme = () => {
      const dark = document.documentElement.dataset.theme === 'dark'
      material.color.setScalar(dark ? 1 : 0.72)
      // 灰白幕在黑底为银白，在白底为深灰；只改变幕的明暗，不给揭幕原画染色。
      viewUniforms.uPaper.value.setScalar(dark ? 1 : 0.18)
      viewUniforms.uRimColor.value.setScalar(dark ? 0.75 : 0.25)
      imageDirty = true
      resume()
    }
    const themeObserver = new MutationObserver(updateTheme)
    themeObserver.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme'],
    })
    element.addEventListener('pointermove', move, { passive: true })
    element.addEventListener('pointerenter', move, { passive: true })
    element.addEventListener('pointerdown', down, { passive: true })
    element.addEventListener('pointerleave', leave)
    element.addEventListener('pointercancel', leave)
    const preferenceChanged = (event: MediaQueryListEvent) => {
      reduce = event.matches
      setReducedMotion(reduce)
      pointer.inside = false
      settling = 1.4
      resume()
    }
    const contextLost = () => {
      lost = true
      cancelAnimationFrame(raf)
      raf = 0
      delete element.dataset.ready
      setReady(false)
    }
    renderer.domElement.addEventListener('webglcontextlost', contextLost)
    document.addEventListener('visibilitychange', resume)
    motion.addEventListener('change', preferenceChanged)
    updateTheme()
    resize()
    return () => {
      disposed = true
      cancelAnimationFrame(raf)
      source.onload = null
      reveal.current = null
      observer.disconnect()
      sizeObserver.disconnect()
      themeObserver.disconnect()
      element.removeEventListener('pointermove', move)
      element.removeEventListener('pointerenter', move)
      element.removeEventListener('pointerdown', down)
      element.removeEventListener('pointerleave', leave)
      element.removeEventListener('pointercancel', leave)
      renderer.domElement.removeEventListener('webglcontextlost', contextLost)
      document.removeEventListener('visibilitychange', resume)
      motion.removeEventListener('change', preferenceChanged)
      mesh?.dispose()
      geometry.dispose()
      material.dispose()
      fullscreen.dispose()
      maskMaterial.dispose()
      viewMaterial.dispose()
      imageTarget.dispose()
      masks.forEach((target) => target.dispose())
      renderer.dispose()
      renderer.forceContextLoss()
      renderer.domElement.remove()
      delete element.dataset.ready
    }
  }, [])

  return (
    <div className="penguin-mark">
      <div
        ref={host}
        className="penguin-canvas"
        role="img"
        aria-label={t('EveryTalk 七彩三维像素企鹅，鼠标移动时揭开像素幕')}
      >
        <Image
          src="/everytalk-logo-source.png"
          alt=""
          fill
          className="penguin-fallback brand-image"
          sizes="(max-width: 700px) 128px, (max-width: 1050px) 18vw, (max-width: 1455px) 22vw, 320px"
          preload
        />
      </div>
      <div className="penguin-caption">
        <span>{t('一只企鹅，无限可能。')}</span>
        <button
          type="button"
          disabled={!ready || reducedMotion}
          onClick={() => reveal.current?.()}
        >
          {t('像素揭幕 ')}
          <ArrowUpRight size={14} />
        </button>
      </div>
    </div>
  )
}
