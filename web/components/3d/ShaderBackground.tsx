'use client'

import { useRef, useMemo } from 'react'
import { useFrame } from '@react-three/fiber'
import { ShaderMaterial } from 'three'
import * as THREE from 'three'

export default function ShaderBackground() {
  const meshRef = useRef<THREE.Mesh>(null)
  
  const shaderMaterial = useMemo(() => {
    return new ShaderMaterial({
      uniforms: {
        uTime: { value: 0 },
        uResolution: { value: new THREE.Vector2(window.innerWidth, window.innerHeight) },
        uColor1: { value: new THREE.Color('#D4AF37') },  // 太极金
        uColor2: { value: new THREE.Color('#C0C0C0') },  // 太极银
        uColor3: { value: new THREE.Color('#F5F5DC') },  // 太极米白
      },
      vertexShader: `
        varying vec2 vUv;
        void main() {
          vUv = uv;
          gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
        }
      `,
      fragmentShader: `
        uniform float uTime;
        uniform vec2 uResolution;
        uniform vec3 uColor1;
        uniform vec3 uColor2;
        uniform vec3 uColor3;
        varying vec2 vUv;

        // Noise function
        float noise(vec2 p) {
          return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
        }

        float smoothNoise(vec2 p) {
          vec2 i = floor(p);
          vec2 f = fract(p);
          f = f * f * (3.0 - 2.0 * f);
          
          float a = noise(i);
          float b = noise(i + vec2(1.0, 0.0));
          float c = noise(i + vec2(0.0, 1.0));
          float d = noise(i + vec2(1.0, 1.0));
          
          return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
        }

        float fbm(vec2 p) {
          float value = 0.0;
          float amplitude = 0.5;
          float frequency = 1.0;
          
          for(int i = 0; i < 5; i++) {
            value += amplitude * smoothNoise(p * frequency);
            frequency *= 2.0;
            amplitude *= 0.5;
          }
          
          return value;
        }

        void main() {
          vec2 uv = vUv;
          vec2 p = uv * 2.0 - 1.0;
          p.x *= uResolution.x / uResolution.y;
          
          // Create flowing noise pattern
          float n1 = fbm(p * 2.0 + uTime * 0.1);
          float n2 = fbm(p * 3.0 - uTime * 0.15 + vec2(5.2, 1.3));
          float n3 = fbm(p * 1.5 + uTime * 0.05 + vec2(2.8, 9.1));
          
          // Create color gradient
          vec3 color = mix(uColor1, uColor2, n1);
          color = mix(color, uColor3, n2 * 0.5);
          
          // Add depth with noise
          float depth = n3 * 0.3 + 0.7;
          color *= depth;
          
          // Add subtle vignette
          float dist = length(p);
          float vignette = smoothstep(1.5, 0.5, dist);
          color *= vignette * 0.3 + 0.3;
          
          // 降低整体亮度，更黑
          color *= 0.4;
          
          gl_FragColor = vec4(color, 0.2);
        }
      `,
      transparent: true,
      side: THREE.DoubleSide,
    })
  }, [])

  useFrame((state) => {
    if (meshRef.current) {
      const material = meshRef.current.material as ShaderMaterial
      material.uniforms.uTime.value = state.clock.elapsedTime
    }
  })

  return (
    <mesh ref={meshRef} position={[0, 0, -5]}>
      <planeGeometry args={[50, 50]} />
      <primitive object={shaderMaterial} attach="material" />
    </mesh>
  )
}

