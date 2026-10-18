'use client'

import { useEffect, useId, useState, type RefObject } from 'react'
import { motion, useAnimationControls } from 'framer-motion'
import { cn } from '@/lib/utils'

// 改编自 Magic UI Animated Beam，保留曲线路径与移动线性渐变的脉冲算法。
// 来源：https://github.com/magicuidesign/magicui/blob/main/apps/www/registry/magicui/animated-beam.tsx
// Copyright (c) Magic UI. MIT 许可全文见 BLACK_WHITE_REDESIGN.md。
interface AnimatedBeamProps {
  containerRef: RefObject<HTMLElement | null>
  fromRef: RefObject<HTMLElement | null>
  toRef: RefObject<HTMLElement | null>
  curvature?: number
  reverse?: boolean
  delay?: number
  duration?: number
  paused?: boolean
  reducedMotion?: boolean
  className?: string
}

/** 根据真实节点中心绘制曲线，移动渐变产生光束；暂停和减少动画不影响静态连线。 */
export function AnimatedBeam({
  containerRef,
  fromRef,
  toRef,
  curvature = 0,
  reverse = false,
  delay = 0,
  duration = 3,
  paused = false,
  reducedMotion = false,
  className,
}: AnimatedBeamProps) {
  const id = useId()
  const controls = useAnimationControls()
  const [geometry, setGeometry] = useState({ path: '', width: 0, height: 0 })

  useEffect(() => {
    let frame = 0
    const updatePath = () => {
      frame = 0
      const container = containerRef.current
      const from = fromRef.current
      const to = toRef.current
      if (!container || !from || !to) return
      const box = container.getBoundingClientRect()
      if (!box.width || !box.height) return
      const start = from.getBoundingClientRect()
      const end = to.getBoundingClientRect()
      const startX = start.left - box.left + start.width / 2
      const startY = start.top - box.top + start.height / 2
      const endX = end.left - box.left + end.width / 2
      const endY = end.top - box.top + end.height / 2
      // 官方的二次贝塞尔曲线：中间行是直线，上下行向中心弯曲。
      const path = `M ${startX},${startY} Q ${(startX + endX) / 2},${startY - curvature} ${endX},${endY}`
      setGeometry({ path, width: box.width, height: box.height })
    }
    const schedule = () => {
      if (!frame) frame = requestAnimationFrame(updatePath)
    }
    const observer = new ResizeObserver(schedule)
    // 同时观察节点与容器，响应手机布局变化和图标尺寸变化。
    for (const ref of [containerRef, fromRef, toRef])
      if (ref.current) observer.observe(ref.current)
    schedule()
    return () => {
      observer.disconnect()
      cancelAnimationFrame(frame)
    }
  }, [containerRef, fromRef, toRef, curvature])

  useEffect(() => {
    if (paused || reducedMotion || !geometry.path) {
      controls.stop()
      return
    }
    void controls.start({
      x1: reverse ? ['90%', '-10%'] : ['10%', '110%'],
      x2: reverse ? ['100%', '0%'] : ['0%', '100%'],
      y1: ['0%', '0%'],
      y2: ['0%', '0%'],
      transition: {
        delay,
        duration,
        // 保留官方移动渐变，改为匀速，让流动光束在黑白主题中更容易看清。
        ease: 'linear',
        repeat: Infinity,
        repeatDelay: 0.4,
      },
    })
    return () => controls.stop()
  }, [controls, paused, reducedMotion, reverse, delay, duration, geometry.path])

  if (!geometry.path) return null
  return (
    <svg
      className={cn('animated-beam', className)}
      fill="none"
      width={geometry.width}
      height={geometry.height}
      viewBox={`0 0 ${geometry.width} ${geometry.height}`}
      aria-hidden="true"
    >
      <path
        className="animated-beam-track"
        d={geometry.path}
        stroke="var(--fg)"
        strokeWidth={1.5}
        strokeOpacity={0.2}
        strokeLinecap="round"
      />
      {!reducedMotion && (
        <path
          className="animated-beam-pulse"
          d={geometry.path}
          stroke={`url(#${id})`}
          strokeWidth={2.5}
          strokeLinecap="round"
        />
      )}
      <defs>
        <motion.linearGradient
          id={id}
          gradientUnits="userSpaceOnUse"
          initial={{ x1: '0%', x2: '0%', y1: '0%', y2: '0%' }}
          animate={controls}
        >
          <stop stopColor="var(--fg)" stopOpacity={0} />
          <stop stopColor="var(--fg)" />
          <stop offset="32.5%" stopColor="var(--muted)" />
          <stop offset="100%" stopColor="var(--muted)" stopOpacity={0} />
        </motion.linearGradient>
      </defs>
    </svg>
  )
}
