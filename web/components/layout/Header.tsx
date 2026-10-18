'use client'

import Image from 'next/image'
import Link from 'next/link'
import dynamic from 'next/dynamic'
import { useEffect, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { ThemeToggle } from '@/components/ui/ThemeProvider'
import {
  CodeXml,
  Download,
  GalleryHorizontal,
  House,
  MessageSquare,
  Layers,
  SquareTerminal,
} from 'lucide-react'
import { Dock, DockIcon } from '@/components/ui/Dock'
import GlassSurface from '@/components/ui/GlassSurface'
import { LanguageToggle, useLanguage } from '@/components/ui/LanguageProvider'

// 导航先显示并可操作，三维玻璃随后加载，不把模型下载作为导航可用的前提。
const FluidGlass = dynamic(() => import('@/components/ui/FluidGlass'), { ssr: false })

const links = [
  { label: '首页', href: '/#main-content', icon: House },
  { label: '功能', href: '/#features', icon: Layers },
  { label: '技术', href: '/#ecosystem', icon: SquareTerminal },
  { label: '体验', href: '/#showcase', icon: GalleryHorizontal },
  { label: '下载', href: '/#download', icon: Download },
  { label: '网页版', href: '/chat', icon: MessageSquare },
]

/** 品牌、原 Dock 导航和设置合并为一个顶部玻璃容器，手机在容器内分为两排。 */
export default function Header() {
  const { t } = useLanguage()
  const router = useRouter()
  const [compact, setCompact] = useState(false)
  const lastScrollY = useRef(0)
  const frame = useRef<number | null>(null)

  /** 用一帧合并连续滚轮事件；向下收起、向上展开，回到页面顶部强制展开。 */
  useEffect(() => {
    lastScrollY.current = window.scrollY

    const updateHeader = () => {
      const currentScrollY = window.scrollY
      const delta = currentScrollY - lastScrollY.current

      if (currentScrollY <= 24) {
        setCompact(false)
      } else if (Math.abs(delta) >= 5) {
        setCompact(delta > 0)
      }

      lastScrollY.current = currentScrollY
      frame.current = null
    }

    const onScroll = () => {
      if (frame.current !== null) return
      frame.current = window.requestAnimationFrame(updateHeader)
    }

    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScroll)
      if (frame.current !== null) window.cancelAnimationFrame(frame.current)
    }
  }, [])

  /**
   * Logo 始终回到首页最顶部。
   * 先通知 Lenis 立即清零，再进行路由切换，避免详情页的旧滚动位置被带到首页。
   */
  const goHome = (event: React.MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault()
    try {
      sessionStorage.setItem('everytalk-scroll-top', '1')
    } catch {
      /* 存储不可用时仍先执行即时回顶。 */
    }
    window.dispatchEvent(new Event('everytalk:scroll-to-top'))
    router.push('/', { scroll: false })
  }

  return (
    <header className="site-header" data-compact={compact}>
      <nav aria-label={t('主导航')}>
        <Dock
          className={`header-inner${compact ? ' header-compact' : ''}`}
          iconSize={compact ? 36 : 44}
          iconMagnification={compact ? 44 : 54}
          layout="size"
          transition={{
            layout: {
              duration: 0.56,
              ease: [0.16, 1, 0.3, 1],
            },
          }}
        >
          {/* SVG 折射真实页面，Fluid Glass 提供三维曲面与棚灯反光；内容保持清楚。 */}
          <div className="dock-glass" aria-hidden="true">
            <GlassSurface
              width="100%"
              height="100%"
              borderRadius={18}
              backgroundOpacity={0.06}
              distortionScale={-48}
              displace={0.2}
              lens
            />
            <FluidGlass />
          </div>
          <Link href="/" className="brand" aria-label={t('EveryTalk 首页')} onClick={goHome}>
            <Image
              src="/everytalk-logo-source.png"
              width={42}
              height={42}
              alt=""
              className="brand-image"
              preload
            />
            <span>
              EveryTalk<span className="brand-period">.</span>
            </span>
          </Link>
          {links.map(({ label, href, icon: Icon }) => (
            <DockIcon key={href}>
              <a href={href} className="dock-link" aria-label={t(label)}>
                <Icon aria-hidden="true" strokeWidth={1.6} />
                <span className="dock-label" aria-hidden="true">
                  {t(label)}
                </span>
              </a>
            </DockIcon>
          ))}
          <div className="dock-divider" aria-hidden="true" />
          <DockIcon>
            <a
              href="https://github.com/roseforljh/EveryTalk"
              className="dock-link"
              aria-label={t('查看源码（在新标签页打开）')}
              target="_blank"
              rel="noopener noreferrer"
            >
              <CodeXml aria-hidden="true" strokeWidth={1.6} />
              <span className="dock-label" aria-hidden="true">
                {t('源码')}
              </span>
            </a>
          </DockIcon>
          <div className="header-actions">
            <ThemeToggle />
            <LanguageToggle />
          </div>
        </Dock>
      </nav>
    </header>
  )
}
