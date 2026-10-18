'use client'

import { Component, Suspense, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Canvas, useFrame, useThree } from '@react-three/fiber'
import { MeshTransmissionMaterial } from '@react-three/drei/core/MeshTransmissionMaterial'
import { useGLTF } from '@react-three/drei/core/Gltf'
import { useFBO } from '@react-three/drei/core/Fbo'
import * as THREE from 'three'
import { gsap } from 'gsap'
import { useTheme } from './ThemeProvider'

// 改编自 React Bits FluidGlass 的 Bar、离屏缓冲和 MeshTransmissionMaterial。
// Copyright (c) 2026 David Haz，源码与许可见 BLACK_WHITE_REDESIGN.md。
const MODEL = '/fluid-glass-bar.glb'

/** 三维装饰失败时保留下面的 SVG 玻璃和原生导航，不让模型错误中断页面。 */
class GlassBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false }
  static getDerivedStateFromError() { return { failed: true } }
  render() { return this.state.failed ? null : this.props.children }
}

/** 本地生成中性棚灯环境，反射只使用黑白灰，不下载 HDR 或演示图片。 */
function studioEnvironment(): THREE.DataTexture {
  const width = 256
  const height = 128
  const pixels = new Uint8Array(width * height * 4)
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const u = x / width
      const v = y / height
      const top = Math.exp(-(((v - 0.29) / 0.035) ** 2)) * (0.45 + 0.55 * Math.sin(u * Math.PI) ** 2)
      const side = Math.exp(-(((u - 0.72) / 0.045) ** 2) - ((v - 0.53) / 0.24) ** 2)
      const value = Math.round(Math.min(255, 8 + top * 235 + side * 245))
      const index = (y * width + x) * 4
      pixels[index] = pixels[index + 1] = pixels[index + 2] = value
      pixels[index + 3] = 255
    }
  }
  const texture = new THREE.DataTexture(pixels, width, height)
  texture.mapping = THREE.EquirectangularReflectionMapping
  texture.colorSpace = THREE.SRGBColorSpace
  texture.needsUpdate = true
  return texture
}

function GlassBar({ dark, host }: { dark: boolean; host: React.RefObject<HTMLDivElement | null> }) {
  const { nodes } = useGLTF(MODEL, false, false)
  const viewport = useThree(state => state.viewport)
  const size = useThree(state => state.size)
  const invalidate = useThree(state => state.invalidate)
  const mesh = useRef<THREE.Mesh>(null)
  const geometry = useMemo(() => {
    const source = (nodes.Cube as THREE.Mesh).geometry
    const result = source.clone().rotateX(Math.PI / 2).center()
    result.computeBoundingBox()
    const bounds = result.boundingBox!.getSize(new THREE.Vector3())
    // 模型沿三个轴分别归一化，适配桌面单排与手机双排的同一个 Dock。
    result.scale(1 / bounds.x, 1 / bounds.y, 1 / bounds.z)
    return result
  }, [nodes])
  const environment = useMemo(studioEnvironment, [])
  const background = useMemo(() => {
    const scene = new THREE.Scene()
    scene.background = new THREE.Color(dark ? '#080808' : '#f8f8f8')
    return scene
  }, [dark])
  const buffer = useFBO(Math.min(size.width, 1024), Math.min(size.height, 128), { depthBuffer: false })
  useEffect(() => {
    const dock = host.current?.closest('.dock-bar')
    if (!dock) return
    const glassMaterial = mesh.current?.material
    const preference = window.matchMedia('(hover: hover) and (pointer: fine) and (prefers-reduced-motion: no-preference)')
    const reflect = (x: number, y: number) => {
      if (!(glassMaterial instanceof THREE.MeshPhysicalMaterial)) return
      // 使用时间缓动而非逐帧步进，低帧率设备也会在半秒内收敛并停止绘制。
      gsap.to(glassMaterial.envMapRotation, { x, y, duration: preference.matches ? 0.5 : 0, ease: 'power3.out', overwrite: true, onUpdate: invalidate })
    }
    const move = (event: Event) => {
      if (!preference.matches) return
      const pointer = event as PointerEvent
      const rect = dock.getBoundingClientRect()
      reflect((pointer.clientY - rect.top - rect.height / 2) / rect.height * 0.12, (pointer.clientX - rect.left - rect.width / 2) / rect.width * 0.28)
    }
    const leave = () => reflect(0, 0)
    dock.addEventListener('pointermove', move, { passive: true })
    dock.addEventListener('pointerleave', leave)
    preference.addEventListener('change', leave)
    return () => {
      dock.removeEventListener('pointermove', move)
      dock.removeEventListener('pointerleave', leave)
      preference.removeEventListener('change', leave)
      if (glassMaterial instanceof THREE.MeshPhysicalMaterial) gsap.killTweensOf(glassMaterial.envMapRotation)
    }
  }, [host, invalidate])
  useEffect(() => () => { geometry.dispose(); environment.dispose() }, [geometry, environment])
  useFrame(({ gl, camera }) => {
    // 沿用官方离屏缓冲路径；这里只渲染玻璃外壳，网页背景由下层 SVG 实际折射。
    gl.setRenderTarget(buffer)
    gl.render(background, camera)
    gl.setRenderTarget(null)
    gl.setClearColor(0x000000, 0)
    if (host.current) host.current.dataset.ready = 'true'
  })
  return (
    <mesh ref={mesh} geometry={geometry} scale={[viewport.width, viewport.height, 0.42]}>
      <MeshTransmissionMaterial
        buffer={buffer.texture}
        envMap={environment}
        envMapIntensity={2.4}
        transmission={1}
        roughness={0.025}
        thickness={0.6}
        ior={1.15}
        anisotropy={0.01}
        chromaticAberration={0}
        distortion={0}
        temporalDistortion={0}
        samples={3}
        resolution={128}
        color="#ffffff"
        transparent
        opacity={dark ? 0.62 : 0.45}
      />
    </mesh>
  )
}

/** 官方 Bar 的真实三维透射外壳；按需绘制，不创建新的滚动容器或三维导航文字。 */
export default function FluidGlass() {
  const { dark } = useTheme()
  const host = useRef<HTMLDivElement>(null)
  const [enabled, setEnabled] = useState(false)
  useEffect(() => {
    const probe = document.createElement('canvas').getContext('webgl2')
    if (!probe) return
    probe.getExtension('WEBGL_lose_context')?.loseContext()
    setEnabled(true)
  }, [])
  return (
    <div ref={host} className="fluid-glass" aria-hidden="true">
      {enabled && (
        <GlassBoundary>
          <Canvas
            orthographic
            camera={{ position: [0, 0, 5], zoom: 100, near: 0.1, far: 20 }}
            frameloop="demand"
            dpr={[1, 1.25]}
            gl={{ alpha: true, antialias: true, powerPreference: 'low-power', toneMapping: THREE.NoToneMapping }}
            onCreated={({ gl }) => {
              gl.setClearColor(0x000000, 0)
              gl.domElement.addEventListener('webglcontextlost', () => setEnabled(false), { once: true })
            }}
          >
            <Suspense fallback={null}><GlassBar dark={dark} host={host} /></Suspense>
          </Canvas>
        </GlassBoundary>
      )}
    </div>
  )
}
