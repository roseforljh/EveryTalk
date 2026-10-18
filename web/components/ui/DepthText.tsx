'use client'

import { useEffect, useRef } from 'react'
import { gsap } from 'gsap'

// 改编自 React Bits DepthText 的图层、颜色混合与指针倾斜算法。
// Copyright (c) 2026 David Haz，许可见设计文档。
/** 只用于首屏标题；装饰层对读屏隐藏，英文词内字形仍然相连。 */
export default function DepthText({ text }: { text: string }) {
  const rootRef = useRef<HTMLSpanElement>(null)
  const stageRef = useRef<HTMLSpanElement>(null)
  useEffect(() => {
    const root = rootRef.current
    const stage = stageRef.current
    if (!root || !stage) return
    const media = gsap.matchMedia()
    media.add('(hover: hover) and (pointer: fine) and (prefers-reduced-motion: no-preference)', () => {
      // 复用 GSAP 缓动，在指针停下后结束动画，避免原版自动旋转一直占用主线程。
      const base = { x: -3, y: 4 }
      gsap.set(stage, { rotationX: base.x, rotationY: base.y })
      const move = (event: PointerEvent) => {
        const rect = root.getBoundingClientRect()
        const clamp = (value: number) => Math.max(-1, Math.min(1, value))
        const x = clamp((event.clientX - rect.left - rect.width / 2) / (rect.width * 0.8))
        const y = clamp((event.clientY - rect.top - rect.height / 2) / (rect.height * 0.8))
        gsap.to(stage, { rotationX: base.x - y * 5, rotationY: base.y + x * 5, duration: 0.55, ease: 'power3.out', overwrite: true })
      }
      const leave = () => gsap.to(stage, { rotationX: base.x, rotationY: base.y, duration: 0.65, ease: 'power3.out', overwrite: true })
      root.addEventListener('pointermove', move, { passive: true })
      root.addEventListener('pointerleave', leave)
      return () => {
        root.removeEventListener('pointermove', move)
        root.removeEventListener('pointerleave', leave)
        gsap.killTweensOf(stage)
      }
    }, root)
    return () => media.revert()
  }, [])
  return (
    <span ref={rootRef} className="depth-text">
      <span ref={stageRef} className="depth-text__stage">
        {Array.from({ length: 20 }, (_, layer) => {
          const index = 20 - layer
          // 沿用官方二次颜色混合曲线，厚度层只使用当前主题的黑白灰。
          const faceMix = Math.round((1 - (index / 20) ** 2) * 72 + 4)
          return (
            <span
              aria-hidden="true"
              className="depth-text__layer"
              key={index}
              style={{ color: `color-mix(in srgb, var(--fg) ${faceMix}%, var(--line))`, transform: `translateZ(${-index * 0.8}px)` }}
            >{text}</span>
          )
        })}
        <span className="depth-text__face">{text}</span>
      </span>
    </span>
  )
}
