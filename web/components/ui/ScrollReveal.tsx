'use client'

import { useEffect, useRef } from 'react'
import { gsap } from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'

// 改编自 React Bits ScrollReveal；Copyright (c) 2026 David Haz，许可见设计文档。
/** 中文按字符渐显；只清理自身 GSAP 上下文，不影响页面其他滚动动画。 */
export default function ScrollReveal({
  children,
  className = '',
}: {
  children: string
  className?: string
}) {
  const host = useRef<HTMLHeadingElement>(null)
  // 英文按词而不是逐字拆开，让手写字体在词内保留自然连接和字距。
  const segments = /[\u4e00-\u9fff]/.test(children)
    ? Array.from(children)
    : (children.match(/\S+\s*|\s+/g) ?? [])
  useEffect(() => {
    gsap.registerPlugin(ScrollTrigger)
    const media = gsap.matchMedia()
    media.add(
      '(prefers-reduced-motion: no-preference)',
      () => {
        const words = host.current?.querySelectorAll('.reveal-word')
        if (!words) return
        gsap.fromTo(
          words,
          { opacity: 0.35, y: 10 },
          {
            opacity: 1,
            y: 0,
            stagger: 0.045,
            ease: 'none',
            scrollTrigger: {
              trigger: host.current,
              start: 'top 88%',
              end: 'top 48%',
              // 让文字在滚轮停下后用短暂缓冲收敛，避免与瞬时位移硬绑定。
              scrub: 0.4,
            },
          },
        )
      },
      host,
    )
    return () => media.revert()
  }, [children])
  return (
    <h2
      ref={host}
      className={`scroll-reveal ${className}`}
      aria-label={children}
    >
      <span aria-hidden="true">
        {segments.map((word, index) => (
          <span className="reveal-word" key={index}>
            {word.replace(/ /g, '\u00a0')}
          </span>
        ))}
      </span>
    </h2>
  )
}
