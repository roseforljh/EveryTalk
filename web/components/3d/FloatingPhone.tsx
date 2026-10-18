'use client'

import { useRef } from 'react'
import { useFrame } from '@react-three/fiber'
import { RoundedBox, MeshDistortMaterial } from '@react-three/drei'
import * as THREE from 'three'

export default function FloatingPhone({ mousePosition }: { mousePosition: { x: number; y: number } }) {
  const phoneRef = useRef<THREE.Group>(null)
  const screenRef = useRef<THREE.Mesh>(null)
  
  useFrame((state) => {
    if (phoneRef.current) {
      // Auto rotation
      phoneRef.current.rotation.y = state.clock.elapsedTime * 0.3
      
      // Floating animation
      phoneRef.current.position.y = Math.sin(state.clock.elapsedTime * 0.5) * 0.3
      
      // Mouse follow effect
      phoneRef.current.rotation.x = THREE.MathUtils.lerp(
        phoneRef.current.rotation.x,
        mousePosition.y * 0.3,
        0.05
      )
      
      phoneRef.current.rotation.y = THREE.MathUtils.lerp(
        phoneRef.current.rotation.y,
        mousePosition.x * 0.3 + state.clock.elapsedTime * 0.2,
        0.05
      )
    }
    
    // Screen glow effect
    if (screenRef.current) {
      const material = screenRef.current.material as THREE.MeshStandardMaterial
      material.emissiveIntensity = Math.sin(state.clock.elapsedTime * 2) * 0.2 + 0.8
    }
  })

  return (
    <group ref={phoneRef} position={[0, 0, 0]}>
      {/* Phone body */}
      <RoundedBox
        args={[2, 4, 0.2]}
        radius={0.2}
        smoothness={10}
        castShadow
      >
        <meshStandardMaterial
          color="#1a1a24"
          metalness={0.8}
          roughness={0.2}
        />
      </RoundedBox>
      
      {/* Screen - 太极金色 */}
      <RoundedBox
        ref={screenRef}
        args={[1.8, 3.6, 0.1]}
        radius={0.15}
        smoothness={10}
        position={[0, 0, 0.11]}
        castShadow
      >
        <meshStandardMaterial
          color="#D4AF37"
          emissive="#D4AF37"
          emissiveIntensity={0.8}
          metalness={0.5}
          roughness={0.3}
        />
      </RoundedBox>
      
      {/* Camera notch */}
      <mesh position={[0, 1.6, 0.15]}>
        <cylinderGeometry args={[0.08, 0.08, 0.05, 32]} />
        <meshStandardMaterial
          color="#0a0a0f"
          metalness={0.9}
          roughness={0.1}
        />
      </mesh>
      
      {/* Glowing orbs around phone - 太极主题 */}
      {[...Array(5)].map((_, i) => {
        const angle = (i / 5) * Math.PI * 2
        const radius = 3.5
        return (
          <mesh
            key={i}
            position={[
              Math.cos(angle) * radius,
              Math.sin(angle) * radius,
              0,
            ]}
          >
            <sphereGeometry args={[0.15, 32, 32]} />
            <MeshDistortMaterial
              color={i % 3 === 0 ? '#D4AF37' : i % 3 === 1 ? '#C0C0C0' : '#F5F5DC'}
              speed={2}
              distort={0.3}
              radius={1}
              emissive={i % 3 === 0 ? '#D4AF37' : i % 3 === 1 ? '#C0C0C0' : '#F5F5DC'}
              emissiveIntensity={1}
            />
          </mesh>
        )
      })}
      
      {/* Point lights for glow - 太极金色 */}
      <pointLight position={[0, 0, 1]} intensity={1} color="#D4AF37" distance={5} />
    </group>
  )
}

