'use client'

import {
  Children,
  cloneElement,
  isValidElement,
  useRef,
  type ReactNode,
} from 'react'
import {
  motion,
  useMotionValue,
  useReducedMotion,
  useSpring,
  useTransform,
  type HTMLMotionProps,
  type MotionValue,
} from 'framer-motion'
import { cn } from '@/lib/utils'

// 改编自 Magic UI Dock，保留距离映射与弹簧放大机制；复用现有 Framer Motion。
// 来源：https://github.com/magicuidesign/magicui/blob/main/apps/www/registry/magicui/dock.tsx
// Copyright (c) Magic UI. MIT 许可全文见 BLACK_WHITE_REDESIGN.md。
interface DockProps extends HTMLMotionProps<'div'> {
  children: ReactNode
  iconSize?: number
  iconMagnification?: number
  iconDistance?: number
}

/** 将同一个鼠标位置传给每个图标，让靠近鼠标的相邻图标一起放大。 */
export function Dock({
  children,
  className,
  iconSize = 44,
  iconMagnification = 62,
  iconDistance = 120,
  ...props
}: DockProps) {
  const mouseX = useMotionValue(Infinity)
  const reduceMotion = useReducedMotion()
  return (
    <motion.div
      {...props}
      className={cn('dock-bar', className)}
      // 使用 clientX 与视口坐标比较，避免横向滚动后 pageX 与 bounds.x 不一致。
      onMouseMove={(event) => mouseX.set(event.clientX)}
      onMouseLeave={() => mouseX.set(Infinity)}
    >
      {Children.map(children, (child) =>
        isValidElement<DockIconProps>(child) && child.type === DockIcon
          ? cloneElement(child, {
              mouseX,
              size: iconSize,
              magnification: iconMagnification,
              distance: iconDistance,
              disableMagnification: Boolean(reduceMotion),
            })
          : child,
      )}
    </motion.div>
  )
}

interface DockIconProps extends HTMLMotionProps<'div'> {
  children: ReactNode
  size?: number
  magnification?: number
  distance?: number
  mouseX?: MotionValue<number>
  disableMagnification?: boolean
}

/** 先按距离求目标尺寸，再用弹簧平滑跟随；减少动画时直接使用固定尺寸。 */
export function DockIcon({
  children,
  className,
  size = 44,
  magnification = 62,
  distance = 120,
  mouseX,
  disableMagnification = false,
  ...props
}: DockIconProps) {
  const ref = useRef<HTMLDivElement>(null)
  const defaultMouseX = useMotionValue(Infinity)
  const distanceCalc = useTransform(mouseX ?? defaultMouseX, (value) => {
    const bounds = ref.current?.getBoundingClientRect()
    return bounds ? value - bounds.x - bounds.width / 2 : Infinity
  })
  const targetSize = useTransform(
    distanceCalc,
    [-distance, 0, distance],
    [size, magnification, size],
  )
  const scaleSize = useSpring(targetSize, {
    mass: 0.1,
    stiffness: 150,
    damping: 12,
  })
  return (
    <motion.div
      {...props}
      ref={ref}
      className={cn('dock-item', className)}
      style={{
        width: disableMagnification ? size : scaleSize,
        height: disableMagnification ? size : scaleSize,
      }}
    >
      {children}
    </motion.div>
  )
}
