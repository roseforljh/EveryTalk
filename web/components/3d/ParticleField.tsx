'use client'

import { useRef, useMemo } from 'react'
import { useFrame } from '@react-three/fiber'
import * as THREE from 'three'

export default function ParticleField() {
  const particlesRef = useRef<THREE.Points>(null)
  
  const particlesData = useMemo(() => {
    const count = 2000
    const positions = new Float32Array(count * 3)
    const colors = new Float32Array(count * 3)
    const sizes = new Float32Array(count)
    
    const color1 = new THREE.Color('#D4AF37')  // 太极金
    const color2 = new THREE.Color('#C0C0C0')  // 太极银
    const color3 = new THREE.Color('#F5F5DC')  // 太极米白
    
    for (let i = 0; i < count; i++) {
      // Position
      positions[i * 3] = (Math.random() - 0.5) * 50
      positions[i * 3 + 1] = (Math.random() - 0.5) * 50
      positions[i * 3 + 2] = (Math.random() - 0.5) * 30
      
      // Color (mix between three colors)
      const mixFactor = Math.random()
      let color: THREE.Color
      
      if (mixFactor < 0.33) {
        color = color1
      } else if (mixFactor < 0.66) {
        color = color2
      } else {
        color = color3
      }
      
      colors[i * 3] = color.r
      colors[i * 3 + 1] = color.g
      colors[i * 3 + 2] = color.b
      
      // Size
      sizes[i] = Math.random() * 2 + 0.5
    }
    
    return { positions, colors, sizes, count }
  }, [])

  const shaderMaterial = useMemo(() => {
    return new THREE.ShaderMaterial({
      uniforms: {
        uTime: { value: 0 },
        uPixelRatio: { value: Math.min(window.devicePixelRatio, 2) },
      },
      vertexShader: `
        uniform float uTime;
        uniform float uPixelRatio;
        attribute float size;
        varying vec3 vColor;
        
        void main() {
          vColor = color;
          
          vec3 pos = position;
          
          // Add wave motion
          pos.y += sin(uTime * 0.3 + position.x * 0.1) * 0.5;
          pos.x += cos(uTime * 0.2 + position.y * 0.1) * 0.3;
          
          vec4 mvPosition = modelViewMatrix * vec4(pos, 1.0);
          gl_Position = projectionMatrix * mvPosition;
          
          // Size attenuation
          gl_PointSize = size * uPixelRatio * (300.0 / -mvPosition.z);
        }
      `,
      fragmentShader: `
        varying vec3 vColor;
        
        void main() {
          // Create circular particles
          float distanceToCenter = length(gl_PointCoord - vec2(0.5));
          float alpha = 1.0 - smoothstep(0.4, 0.5, distanceToCenter);
          
          if (alpha < 0.01) discard;
          
          vec3 color = vColor;
          float glow = pow(1.0 - distanceToCenter * 2.0, 2.0);
          color += glow * 0.3;
          
          gl_FragColor = vec4(color, alpha * 0.6);
        }
      `,
      transparent: true,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
      vertexColors: true,
    })
  }, [])

  useFrame((state) => {
    if (particlesRef.current) {
      particlesRef.current.rotation.y = state.clock.elapsedTime * 0.05
      const material = particlesRef.current.material as THREE.ShaderMaterial
      material.uniforms.uTime.value = state.clock.elapsedTime
    }
  })

  return (
    <points ref={particlesRef}>
      <bufferGeometry>
        <bufferAttribute
          attach="attributes-position"
          count={particlesData.count}
          array={particlesData.positions}
          itemSize={3}
        />
        <bufferAttribute
          attach="attributes-color"
          count={particlesData.count}
          array={particlesData.colors}
          itemSize={3}
        />
        <bufferAttribute
          attach="attributes-size"
          count={particlesData.count}
          array={particlesData.sizes}
          itemSize={1}
        />
      </bufferGeometry>
      <primitive object={shaderMaterial} attach="material" />
    </points>
  )
}

