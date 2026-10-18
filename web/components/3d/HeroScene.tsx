'use client'

import { useState, Suspense, useEffect } from 'react'
import { Canvas } from '@react-three/fiber'
import { PerspectiveCamera, Environment } from '@react-three/drei'
import { EffectComposer, Bloom } from '@react-three/postprocessing'
import ShaderBackground from './ShaderBackground'
import ParticleField from './ParticleField'
import FloatingPhone from './FloatingPhone'
import { getPerformanceSettings, prefersReducedMotion } from '@/lib/performance'

export default function HeroScene() {
  const [mousePosition, setMousePosition] = useState({ x: 0, y: 0 })
  const [settings, setSettings] = useState(() => getPerformanceSettings())
  const [reducedMotion, setReducedMotion] = useState(false)

  useEffect(() => {
    setSettings(getPerformanceSettings())
    setReducedMotion(prefersReducedMotion())
  }, [])

  const handleMouseMove = (e: React.MouseEvent<HTMLDivElement>) => {
    if (reducedMotion) return
    const rect = e.currentTarget.getBoundingClientRect()
    const x = ((e.clientX - rect.left) / rect.width) * 2 - 1
    const y = -((e.clientY - rect.top) / rect.height) * 2 + 1
    setMousePosition({ x, y })
  }

  // 如果设备不支持3D或用户偏好减少动画，显示静态背景
  if (!settings.enable3D || reducedMotion) {
    return (
      <div className="absolute inset-0 -z-10 bg-black">
        <div className="absolute inset-0 opacity-5">
          <div className="absolute top-1/4 left-1/4 w-96 h-96 bg-white rounded-full blur-3xl" />
          <div className="absolute bottom-1/4 right-1/4 w-96 h-96 bg-gray-200 rounded-full blur-3xl" />
        </div>
      </div>
    )
  }

  return (
    <div
      className="absolute inset-0 -z-10"
      onMouseMove={handleMouseMove}
    >
      <Canvas
        dpr={[1, settings.quality === 'high' ? 2 : 1]}
        performance={{ min: 0.5 }}
      >
        <PerspectiveCamera makeDefault position={[0, 0, 10]} fov={75} />
        
        {/* Lights */}
        <ambientLight intensity={0.5} />
        <directionalLight position={[10, 10, 5]} intensity={1} />
        <pointLight position={[-10, -10, -5]} intensity={0.5} color="#C0C0C0" />
        
        <Suspense fallback={null}>
          {/* Background */}
          <ShaderBackground />
          
          {/* Particles - 根据性能设置决定是否渲染 */}
          {settings.enableParticles && <ParticleField />}
          
          {/* Main 3D object */}
          <FloatingPhone mousePosition={mousePosition} />
          
          {/* Environment */}
          <Environment preset="city" />
          
          {/* Post-processing effects - 根据性能设置决定是否启用 */}
          {settings.enablePostProcessing && (
            <EffectComposer>
              <Bloom
                intensity={0.6}
                luminanceThreshold={0.3}
                luminanceSmoothing={0.9}
                mipmapBlur
              />
            </EffectComposer>
          )}
        </Suspense>
      </Canvas>
    </div>
  )
}

