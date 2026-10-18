'use client'

import { useEffect, useRef, type ReactNode } from 'react'
import { gsap } from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'

// 改编自 React Bits ScrollExpand；Copyright (c) 2026 David Haz，许可见设计文档。
/** 用原组件的 smoothstep / clip-path 展开算法，限定在宣传区，不接管整页滚动。 */
export default function ScrollExpand({ children }: { children: ReactNode }) {
  const root = useRef<HTMLDivElement>(null)
  const frame = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const element = root.current
    const panel = frame.current
    if (!element || !panel) return
    gsap.registerPlugin(ScrollTrigger)
    const media = gsap.matchMedia()
    // 复用已有 GSAP 的缓冲时间轴，保留官方 smoothstep，减少滚轮步进造成的边缘跳动。
    media.add('(prefers-reduced-motion: no-preference)', () => {
      // 直接动画化 DOM 属性，matchMedia 才能完整恢复原样式；刷新时重新读取手机展开幅度。
      gsap.fromTo(
        panel,
        {
          clipPath: () => `inset(0 ${window.innerWidth < 700 ? 5 : 14}% round 32px)`,
          scale: 0.93,
        },
        {
          clipPath: 'inset(0 0% round 8px)',
          scale: 1,
          ease: (progress: number) => progress * progress * (3 - 2 * progress),
          scrollTrigger: {
            trigger: element,
            start: 'top 85%',
            end: 'top 20%',
            scrub: 0.45,
            invalidateOnRefresh: true,
          },
        },
      )
    }, root)
    return () => {
      media.revert()
    }
  }, [])
  return (
    <div ref={root} className="scroll-expand">
      <div ref={frame} className="scroll-expand-frame">
        {children}
      </div>
    </div>
  )
}
