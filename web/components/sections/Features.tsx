'use client'

import { useLanguage } from '@/components/ui/LanguageProvider'

import { useEffect } from 'react'
import {
  ArrowUpRight,
  SlidersHorizontal,
  Globe,
  Image as ImageIcon,
  MessageSquareText,
} from 'lucide-react'
import ScrollReveal from '@/components/ui/ScrollReveal'
import ModelConnections from '@/components/sections/ModelConnections'
import Link from 'next/link'

const capabilities = [
  {
    icon: SlidersHorizontal,
    title: '模型，自己选。',
    label: 'MULTI-MODEL',
    slug: 'multi-model',
    description:
      '连接 OpenAI、Gemini、Claude 与兼容接口。服务地址、模型和参数，由你设置。',
    tags: ['自由接入', '参数控制'],
  },
  {
    icon: Globe,
    title: '对话，连接世界。',
    label: 'SEARCH & MCP',
    slug: 'search-mcp',
    description:
      '联网搜索、图片与附件输入，以及 MCP 工具。需要更多信息时，让对话向外延伸。',
    tags: ['联网搜索', 'MCP 扩展'],
  },
  {
    icon: ImageIcon,
    title: '想法，变成画面。',
    label: 'IMAGE GENERATION',
    slug: 'image-generation',
    description: '从文字描述到图像创作，在同一个应用里切换聊天与图像生成。',
    tags: ['图像生成', '多模态输入'],
  },
  {
    icon: MessageSquareText,
    title: '内容，好好呈现。',
    label: 'READ & ORGANIZE',
    slug: 'read-organize',
    description:
      'Markdown、代码和公式清晰排版。分组与管理会话，找回值得继续的那次讨论。',
    tags: ['Markdown / 公式', '会话管理'],
  },
]

export default function Features() {
  const { t } = useLanguage()

  useEffect(() => {
    let saved: { href: string; scrollY: number } | null = null
    try {
      const value = sessionStorage.getItem('everytalk-feature-return')
      if (value) saved = JSON.parse(value) as { href: string; scrollY: number }
    } catch {
      saved = null
    }
    if (!saved) return
    const currentHref = `${window.location.pathname}${window.location.search}${window.location.hash}`
    if (saved.href !== currentHref) return

    const frame = window.requestAnimationFrame(() => {
      window.requestAnimationFrame(() => {
        window.scrollTo({ top: saved?.scrollY ?? 0, left: 0, behavior: 'auto' })
        try {
          sessionStorage.removeItem('everytalk-feature-return')
        } catch {
          /* 存储不可用时只恢复本次浏览位置。 */
        }
      })
    })
    return () => window.cancelAnimationFrame(frame)
  }, [])

  const rememberFeatureReturn = (event: React.MouseEvent<HTMLAnchorElement>) => {
    if (
      event.button !== 0 ||
      event.metaKey ||
      event.ctrlKey ||
      event.shiftKey ||
      event.altKey
    ) {
      return
    }
    try {
      sessionStorage.setItem(
        'everytalk-feature-return',
        JSON.stringify({
          href: `${window.location.pathname}${window.location.search}${window.location.hash}`,
          scrollY: window.scrollY,
        }),
      )
      sessionStorage.setItem('everytalk-feature-detail-entry', '1')
    } catch {
      /* 存储不可用时仍允许正常进入详情页。 */
    }
  }

  return (
    <section
      id="features"
      className="capabilities section-space site-container"
    >
      <div className="section-heading">
        <p className="eyebrow">{t('你的日常，不止问答')}</p>
        <ScrollReveal>{t('一个入口，更多可能。')}</ScrollReveal>
        <p>{t('从一个问题出发，到你想做的下一件事。')}</p>
      </div>
      <ModelConnections />
      <div className="capability-grid">
        {capabilities.map(({ icon: Icon, title, label, slug, description, tags }) => (
          <Link
            key={label}
            href={`/features/${slug}`}
            className="capability"
            onClick={rememberFeatureReturn}
            aria-label={`${t(title)} — ${t('查看关键源码')}`}
          >
            <div className="capability-top">
              <Icon size={24} strokeWidth={1.4} />
              <span className="mono-label">{label}</span>
              <ArrowUpRight size={16} />
            </div>
            <h3>{t(title)}</h3>
            <p>{t(description)}</p>
            <div className="capability-tags">
              {tags.map((tag) => (
                <span key={tag}>{t(tag)}</span>
              ))}
            </div>
            <span className="capability-open">
              {t('查看关键源码')}
              <ArrowUpRight size={14} />
            </span>
          </Link>
        ))}
      </div>
      <p className="capability-note">
        {t('模型与工具的可用范围，取决于你连接的服务。')}
      </p>
    </section>
  )
}
