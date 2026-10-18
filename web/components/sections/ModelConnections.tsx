'use client'

import { useLanguage } from '@/components/ui/LanguageProvider'

import { useEffect, useRef, useState } from 'react'
import Image from 'next/image'
import { Globe, Image as ImageIcon, Pause, Play } from 'lucide-react'
import { useInView } from 'framer-motion'
import { AnimatedBeam } from '@/components/ui/AnimatedBeam'

/** 把真实支持的模型与能力放到官方示例的七节点布局中，展示连接关系。 */
export default function ModelConnections() {
  const { t } = useLanguage()
  const container = useRef<HTMLDivElement>(null)
  const hub = useRef<HTMLDivElement>(null)
  const openai = useRef<HTMLDivElement>(null)
  const gemini = useRef<HTMLDivElement>(null)
  const claude = useRef<HTMLDivElement>(null)
  const mcp = useRef<HTMLDivElement>(null)
  const search = useRef<HTMLDivElement>(null)
  const image = useRef<HTMLDivElement>(null)
  const visible = useInView(container, { amount: 0.1 })
  const [paused, setPaused] = useState(false)
  const [reducedMotion, setReducedMotion] = useState(false)
  const [documentVisible, setDocumentVisible] = useState(true)

  useEffect(() => {
    const media = window.matchMedia('(prefers-reduced-motion: reduce)')
    setReducedMotion(media.matches)
    const preferenceChanged = (event: MediaQueryListEvent) =>
      setReducedMotion(event.matches)
    const visibilityChanged = () => setDocumentVisible(!document.hidden)
    visibilityChanged()
    media.addEventListener('change', preferenceChanged)
    document.addEventListener('visibilitychange', visibilityChanged)
    return () => {
      media.removeEventListener('change', preferenceChanged)
      document.removeEventListener('visibilitychange', visibilityChanged)
    }
  }, [])

  const active = visible && documentVisible && !paused && !reducedMotion
  const beams = [
    { ref: openai, curvature: -120, delay: 0 },
    { ref: gemini, curvature: 0, delay: 0.3 },
    { ref: claude, curvature: 120, delay: 0.6 },
    { ref: mcp, curvature: -120, delay: 1.2 },
    { ref: search, curvature: 0, delay: 1.5 },
    { ref: image, curvature: 120, delay: 1.8 },
  ]

  return (
    <figure className="connection-panel">
      <div className="connection-heading" aria-hidden="true">
        <span>{t('你选择的模型')}</span>
        <span>{t('对话中的能力')}</span>
      </div>
      <div
        ref={container}
        className="connection-map"
        role="img"
        aria-label={t(
          'OpenAI、Gemini 和 Claude 通过 EveryTalk 连接 MCP 工具、联网搜索和图像生成',
        )}
        data-animation={
          reducedMotion ? 'reduced' : active ? 'running' : 'paused'
        }
      >
        <div className="connection-node connection-openai">
          <div className="connection-circle" ref={openai}>
            <Image
              src="/tech-logos/openai.svg"
              width={30}
              height={30}
              alt=""
              className="ecosystem-logo"
              data-tone="ink"
              unoptimized
            />
          </div>
          <span>OpenAI</span>
        </div>
        <div className="connection-node connection-gemini">
          <div className="connection-circle" ref={gemini}>
            <Image
              src="/tech-logos/gemini.png"
              width={30}
              height={30}
              alt=""
              className="ecosystem-logo"
              unoptimized
            />
          </div>
          <span>Gemini</span>
        </div>
        <div className="connection-node connection-claude">
          <div className="connection-circle" ref={claude}>
            <Image
              src="/tech-logos/claude.png"
              width={30}
              height={30}
              alt=""
              className="ecosystem-logo"
              unoptimized
            />
          </div>
          <span>Claude</span>
        </div>
        <div className="connection-node connection-hub">
          <div className="connection-circle" ref={hub}>
            <Image
              src="/everytalk-logo-source.png"
              width={60}
              height={60}
              alt=""
              className="brand-image"
              unoptimized
            />
          </div>
          <span>EveryTalk.</span>
        </div>
        <div className="connection-node connection-mcp">
          <div className="connection-circle" ref={mcp}>
            <Image
              src="/tech-logos/mcp.svg"
              width={30}
              height={30}
              alt=""
              className="ecosystem-logo"
              data-tone="ink"
              unoptimized
            />
          </div>
          <span>{t('MCP 工具')}</span>
        </div>
        <div className="connection-node connection-search">
          <div className="connection-circle" ref={search}>
            <Globe size={30} strokeWidth={1.5} />
          </div>
          <span>{t('联网搜索')}</span>
        </div>
        <div className="connection-node connection-image">
          <div className="connection-circle" ref={image}>
            <ImageIcon size={30} strokeWidth={1.5} />
          </div>
          <span>{t('图像生成')}</span>
        </div>
        {beams.map(({ ref, curvature, delay }, index) => (
          <AnimatedBeam
            key={index}
            containerRef={container}
            fromRef={ref}
            toRef={hub}
            curvature={curvature}
            delay={delay}
            paused={!active}
            reducedMotion={reducedMotion}
          />
        ))}
      </div>
      <figcaption className="connection-caption">
        <p>{t('把你选择的模型，连接到搜索、工具与图像创作。')}</p>
        {!reducedMotion && (
          <button
            type="button"
            aria-pressed={paused}
            onClick={() => setPaused(!paused)}
          >
            {paused ? <Play size={13} /> : <Pause size={13} />}
            {paused ? t('继续动画') : t('暂停动画')}
          </button>
        )}
      </figcaption>
    </figure>
  )
}
