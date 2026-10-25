import { type ClassValue, clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function detectGPUTier(): 'high' | 'medium' | 'low' {
  if (typeof window === 'undefined') return 'high'
  
  const canvas = document.createElement('canvas')
  const gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl')
  
  if (!gl) return 'low'
  
  const debugInfo = (gl as WebGLRenderingContext).getExtension('WEBGL_debug_renderer_info')
  if (!debugInfo) return 'medium'
  
  const renderer = (gl as WebGLRenderingContext).getParameter(debugInfo.UNMASKED_RENDERER_WEBGL)
  
  // 简单的GPU性能检测
  if (renderer.match(/(nvidia|amd|radeon)/i)) return 'high'
  if (renderer.match(/intel/i)) return 'medium'
  
  return 'medium'
}

export function isMobile(): boolean {
  if (typeof window === 'undefined') return false
  return window.innerWidth < 768
}

export function smoothScroll(target: HTMLElement | null) {
  if (!target) return
  
  target.scrollIntoView({
    behavior: 'smooth',
    block: 'start',
  })
}

export function lerp(start: number, end: number, factor: number): number {
  return start + (end - start) * factor
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max)
}

export function randomRange(min: number, max: number): number {
  return Math.random() * (max - min) + min
}


/**
 * 站点对外的源，用于接口的同源校验。自托管时 Next 看到的是容器内地址，
 * 以反代覆盖写入的 X-Forwarded-Host/Proto 为准（Caddy、Vercel 都会写）；没有反代头时退回请求地址。
 */
export function publicOrigin(request: Request): string {
  const url = new URL(request.url)
  const host = request.headers.get('x-forwarded-host')?.split(',')[0].trim() || request.headers.get('host') || url.host
  const proto = request.headers.get('x-forwarded-proto')?.split(',')[0].trim() || url.protocol.slice(0, -1)
  return `${proto}://${host}`
}
