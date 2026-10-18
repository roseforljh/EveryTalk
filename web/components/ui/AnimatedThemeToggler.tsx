'use client'

import { useEffect, useRef, type ComponentPropsWithoutRef } from 'react'
import { Sun } from 'lucide-react'
import { flushSync } from 'react-dom'
import { cn } from '@/lib/utils'
import { useLanguage } from '@/components/ui/LanguageProvider'

// 改编自 Magic UI Animated Theme Toggler，保留圆形 View Transition 揭示算法。
// 来源：https://github.com/magicuidesign/magicui/blob/main/apps/www/registry/magicui/animated-theme-toggler.tsx
// Copyright (c) Magic UI. MIT 许可全文见 BLACK_WHITE_REDESIGN.md。
interface AnimatedThemeTogglerProps extends Omit<
  ComponentPropsWithoutRef<'button'>,
  'onClick'
> {
  theme: 'light' | 'dark'
  onThemeChange: (theme: 'light' | 'dark') => void
  duration?: number
}

/** 只处理切换动画；主题、系统偏好和本地记忆仍由 ThemeProvider 统一管理。 */
export function AnimatedThemeToggler({
  theme,
  onThemeChange,
  duration = 400,
  className,
  ...props
}: AnimatedThemeTogglerProps) {
  const { t } = useLanguage()
  const buttonRef = useRef<HTMLButtonElement>(null)
  const working = useRef(false)
  const activeAnimation = useRef<Animation | null>(null)
  const activeTransition = useRef<ReturnType<
    Document['startViewTransition']
  > | null>(null)

  useEffect(
    () => () => {
      activeTransition.current?.skipTransition()
      activeAnimation.current?.cancel()
    },
    [],
  )

  const toggle = () => {
    const button = buttonRef.current
    const root = document.documentElement
    if (!button || working.current || root.dataset.magicuiThemeVt === 'active')
      return
    const next = theme === 'dark' ? 'light' : 'dark'
    const applyTheme = () => onThemeChange(next)
    // 不支持 View Transitions 或用户要求减少动画时，主题仍然立即切换。
    if (
      typeof document.startViewTransition !== 'function' ||
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
    ) {
      applyTheme()
      return
    }

    const { left, top, width, height } = button.getBoundingClientRect()
    const x = left + width / 2
    const y = top + height / 2
    const viewportWidth = window.innerWidth
    const viewportHeight = window.innerHeight
    const radius = Math.hypot(
      Math.max(x, viewportWidth - x),
      Math.max(y, viewportHeight - y),
    )
    // 与原组件一致使用百分比坐标，避免 Windows 显示缩放导致首帧位置偏移。
    const center = `${(x / viewportWidth) * 100}% ${(y / viewportHeight) * 100}%`
    const radiusPercent =
      (radius / (Math.hypot(viewportWidth, viewportHeight) / Math.SQRT2)) * 100
    const clipPath = [
      `circle(0% at ${center})`,
      `circle(${radiusPercent}% at ${center})`,
    ]

    working.current = true
    root.dataset.magicuiThemeVt = 'active'
    root.style.setProperty(
      '--magicui-theme-toggle-vt-duration',
      `${duration}ms`,
    )
    root.style.setProperty('--magicui-theme-vt-clip-from', clipPath[0])
    const cleanup = () => {
      working.current = false
      delete root.dataset.magicuiThemeVt
      root.style.removeProperty('--magicui-theme-toggle-vt-duration')
      root.style.removeProperty('--magicui-theme-vt-clip-from')
      activeAnimation.current?.cancel()
      activeAnimation.current = null
      activeTransition.current = null
    }

    try {
      const transition = document.startViewTransition(() =>
        flushSync(applyTheme),
      )
      activeTransition.current = transition
      void transition.finished.catch(() => {}).finally(cleanup)
      void transition.ready
        .then(() => {
          activeAnimation.current = root.animate(
            { clipPath },
            {
              duration,
              easing: 'ease-in-out',
              fill: 'forwards',
              pseudoElement: '::view-transition-new(root)',
            },
          )
        })
        .catch(() => {
          // 快照或伪元素动画失败时结束过渡；已经应用的新主题不回滚。
          transition.skipTransition()
        })
    } catch {
      applyTheme()
      cleanup()
    }
  }

  const label = t(theme === 'dark' ? '切换浅色主题' : '切换深色主题')
  return (
    <button
      {...props}
      type="button"
      ref={buttonRef}
      onClick={toggle}
      className={cn('theme-toggle', className)}
      aria-label={label}
      title={label}
    >
      <Sun size={19} aria-hidden="true" />
    </button>
  )
}
