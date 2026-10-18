export interface PerformanceSettings {
  enable3D: boolean
  enableParticles: boolean
  enablePostProcessing: boolean
  particleCount: number
  quality: 'low' | 'medium' | 'high'
}

export function getPerformanceSettings(): PerformanceSettings {
  if (typeof window === 'undefined') {
    return {
      enable3D: true,
      enableParticles: true,
      enablePostProcessing: true,
      particleCount: 2000,
      quality: 'high',
    }
  }

  // 检测设备类型
  const isMobile = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(
    navigator.userAgent
  )
  const isTablet = /(tablet|ipad|playbook|silk)|(android(?!.*mobi))/i.test(
    navigator.userAgent
  )

  // 检测内存（如果可用）
  const memory = (navigator as any).deviceMemory
  const lowMemory = memory && memory < 4

  // 检测GPU性能
  const canvas = document.createElement('canvas')
  const gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl')
  let gpuTier: 'low' | 'medium' | 'high' = 'medium'

  if (gl) {
    const debugInfo = (gl as WebGLRenderingContext).getExtension('WEBGL_debug_renderer_info')
    if (debugInfo) {
      const renderer = (gl as WebGLRenderingContext).getParameter(debugInfo.UNMASKED_RENDERER_WEBGL)
      if (renderer.match(/(nvidia|amd|radeon|geforce|rtx)/i)) {
        gpuTier = 'high'
      } else if (renderer.match(/intel/i)) {
        gpuTier = 'medium'
      } else {
        gpuTier = 'low'
      }
    }
  }

  // 移动端设置
  if (isMobile) {
    return {
      enable3D: !lowMemory,
      enableParticles: false,
      enablePostProcessing: false,
      particleCount: 500,
      quality: 'low',
    }
  }

  // 平板设置
  if (isTablet) {
    return {
      enable3D: true,
      enableParticles: true,
      enablePostProcessing: false,
      particleCount: 1000,
      quality: 'medium',
    }
  }

  // 桌面设置 - 根据GPU性能调整
  if (gpuTier === 'low' || lowMemory) {
    return {
      enable3D: true,
      enableParticles: true,
      enablePostProcessing: false,
      particleCount: 1000,
      quality: 'medium',
    }
  }

  if (gpuTier === 'high') {
    return {
      enable3D: true,
      enableParticles: true,
      enablePostProcessing: true,
      particleCount: 2000,
      quality: 'high',
    }
  }

  // 默认中等设置
  return {
    enable3D: true,
    enableParticles: true,
    enablePostProcessing: true,
    particleCount: 1500,
    quality: 'medium',
  }
}

export function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined') return false
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

