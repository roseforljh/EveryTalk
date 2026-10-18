'use client'

import { useEffect, useRef } from 'react'
import { usePathname } from 'next/navigation'
import Lenis from 'lenis'
import { ScrollTrigger } from 'gsap/ScrollTrigger'
import { gsap } from 'gsap'

/**
 * 用 Lenis 给桌面滚轮增加轻微惯性，让区块移动连续；触摸、键盘和减少动画仍走浏览器原生滚动。
 * 这个组件只负责滚动时间轴，不接管锚点内容或业务状态。
 */
export default function SmoothScroll() {
  const pathname = usePathname()
  const lenisRef = useRef<Lenis | null>(null)

  useEffect(() => {
    const motion = window.matchMedia('(prefers-reduced-motion: reduce)')
    const pointer = window.matchMedia('(hover: hover) and (pointer: fine)')
    // Next 客户端路由和浏览器历史都可能尝试恢复旧滚动位置；页面自己管理
    // 详情页置顶与功能页返回位置，避免两套恢复机制互相覆盖。
    const previousScrollRestoration = window.history.scrollRestoration
    window.history.scrollRestoration = 'manual'
    gsap.registerPlugin(ScrollTrigger)
    let lenis: Lenis | null = null
    let frame = 0
    let anchorFrame = 0
    let disposed = false
    const initialHash = window.location.hash
    const updateScrollTrigger = () => ScrollTrigger.update()
    const raf = (time: number) => {
      lenis?.raf(time)
      frame = requestAnimationFrame(raf)
    }
    const stop = () => {
      cancelAnimationFrame(frame)
      frame = 0
      lenis?.off('scroll', updateScrollTrigger)
      lenis?.destroy()
      lenis = null
      lenisRef.current = null
    }
    const updatePreference = () => {
      stop()
      // 触摸设备使用原生惯性滚动，不在后台再维护一套桌面滚轮时间轴。
      if (motion.matches || !pointer.matches) return
      lenis = new Lenis({
        autoRaf: false,
        // 当前 Lenis 会读取 CSS scroll-padding-top，避免再次添加偏移造成双倍留白。
        anchors: true,
        lerp: 0.085,
        smoothWheel: true,
        syncTouch: false,
      })
      lenisRef.current = lenis
      lenis.on('scroll', updateScrollTrigger)
      frame = requestAnimationFrame(raf)
    }
    // 顶栏 Logo 和其他需要回顶的入口共用这个事件，确保 Lenis 与原生滚动一致。
    const scrollToTop = () => {
      if (lenis) {
        lenis.scrollTo(0, { immediate: true })
      } else {
        window.scrollTo({ top: 0, left: 0, behavior: 'auto' })
      }
    }
    window.addEventListener('everytalk:scroll-to-top', scrollToTop)
    // 访问期间修改系统偏好时也销毁实例，立即恢复原生滚动。
    motion.addEventListener('change', updatePreference)
    pointer.addEventListener('change', updatePreference)
    updatePreference()

    // 从子页面带锚点进入时，浏览器可能先定位、再被字体加载或 ScrollTrigger 刷新打断。
    // 只在首次加载完成后对齐当前锚点；普通滚轮和后续页面内导航不经过这里。
    const alignInitialAnchor = () => {
      void document.fonts.ready.then(() => {
        if (disposed) return
        anchorFrame = requestAnimationFrame(() => {
          if (window.location.hash !== initialHash) return
          let id: string
          try {
            id = decodeURIComponent(initialHash.slice(1))
          } catch {
            return
          }
          const target = document.getElementById(id)
          if (!target) return
          ScrollTrigger.refresh()
          if (lenis) {
            lenis.resize()
            lenis.scrollTo(target, { immediate: true })
          } else {
            target.scrollIntoView({ behavior: 'instant', block: 'start' })
          }
        })
      })
    }
    if (initialHash) {
      if (document.readyState === 'complete') alignInitialAnchor()
      else window.addEventListener('load', alignInitialAnchor, { once: true })
    }

    return () => {
      disposed = true
      cancelAnimationFrame(anchorFrame)
      window.removeEventListener('load', alignInitialAnchor)
      window.removeEventListener('everytalk:scroll-to-top', scrollToTop)
      motion.removeEventListener('change', updatePreference)
      pointer.removeEventListener('change', updatePreference)
      window.history.scrollRestoration = previousScrollRestoration
      stop()
    }
  }, [])

  useEffect(() => {
    let shouldReset = false
    try {
      const isFeatureDetailEntry =
        sessionStorage.getItem('everytalk-feature-detail-entry') === '1'
      const isHeaderHomeEntry = sessionStorage.getItem('everytalk-scroll-top') === '1'
      shouldReset = isFeatureDetailEntry || isHeaderHomeEntry
      if (isFeatureDetailEntry) {
        sessionStorage.removeItem('everytalk-feature-detail-entry')
      }
      if (isHeaderHomeEntry) {
        sessionStorage.removeItem('everytalk-scroll-top')
      }
    } catch {
      shouldReset = true
    }
    if (!shouldReset) return

    let secondFrame = 0
    const firstFrame = window.requestAnimationFrame(() => {
      secondFrame = window.requestAnimationFrame(() => {
        if (lenisRef.current) {
          lenisRef.current.scrollTo(0, { immediate: true })
        } else {
          window.scrollTo({ top: 0, left: 0, behavior: 'auto' })
        }
      })
    })
    return () => {
      window.cancelAnimationFrame(firstFrame)
      if (secondFrame) window.cancelAnimationFrame(secondFrame)
    }
  }, [pathname])

  return null
}
