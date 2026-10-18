'use client'

import { useLanguage } from '@/components/ui/LanguageProvider'

import { useState, type CSSProperties } from 'react'
import Image from 'next/image'
import {
  ArrowUpRight,
  CodeXml,
  Layers,
  Pause,
  Play,
  Smartphone,
} from 'lucide-react'
import { Marquee } from '@/components/ui/Marquee'

// 内容依据 Android 依赖配置、官网 package.json 与已接入的动效源码，不列出未采用的框架。
const rows = [
  {
    title: 'App 技术栈',
    label: 'ANDROID',
    icon: Smartphone,
    duration: '36s',
    reverse: false,
    items: [
      {
        name: 'Kotlin',
        logo: 'kotlin.svg',
        logoTone: 'original',
        detail: '原生 Android 开发',
        href: 'https://kotlinlang.org/',
      },
      {
        name: 'Jetpack Compose',
        logo: 'compose.svg',
        logoTone: 'original',
        detail: '声明式界面',
        href: 'https://developer.android.com/compose',
      },
      {
        name: 'Room',
        logo: 'room.svg',
        logoTone: 'original',
        detail: '本地数据存储',
        href: 'https://developer.android.com/training/data-storage/room',
      },
      {
        name: 'Ktor',
        logo: 'ktor.png',
        logoTone: 'original',
        detail: '模型与工具网络请求',
        href: 'https://ktor.io/',
      },
      {
        name: 'Coil',
        logo: 'coil.svg',
        logoTone: 'ink',
        detail: '图片加载',
        href: 'https://coil-kt.github.io/coil/',
      },
      {
        name: 'Koin',
        logo: 'koin.png',
        logoTone: 'original',
        detail: '应用依赖管理',
        href: 'https://insert-koin.io/',
      },
    ],
  },
  {
    title: '官网技术栈',
    label: 'WEB',
    icon: CodeXml,
    duration: '42s',
    reverse: true,
    items: [
      {
        name: 'Next.js',
        logo: 'nextjs.ico',
        logoTone: 'ink',
        detail: '页面与静态生成',
        href: 'https://nextjs.org/',
      },
      {
        name: 'React',
        logo: 'react.svg',
        logoTone: 'original',
        detail: '组件与交互',
        href: 'https://react.dev/',
      },
      {
        name: 'TypeScript',
        logo: 'typescript.png',
        logoTone: 'original',
        detail: '类型检查',
        href: 'https://www.typescriptlang.org/',
      },
      {
        name: 'Tailwind CSS',
        logo: 'tailwind.svg',
        logoTone: 'original',
        detail: '界面样式',
        href: 'https://tailwindcss.com/',
      },
      {
        name: 'Three.js',
        logo: 'threejs.ico',
        logoTone: 'ink',
        detail: '像素雪与三维企鹅',
        href: 'https://threejs.org/',
      },
      {
        name: 'GSAP',
        logo: 'gsap.svg',
        logoTone: 'original',
        detail: '滚动标题动效',
        href: 'https://gsap.com/',
      },
    ],
  },
  {
    title: '开源与参考',
    label: 'OPEN SOURCE',
    icon: Layers,
    duration: '38s',
    reverse: false,
    items: [
      {
        name: 'React Bits',
        logo: 'react-bits.svg',
        logoTone: 'ink',
        detail: '官网背景与滚动动效',
        href: 'https://github.com/DavidHDev/react-bits',
      },
      {
        name: 'Magic UI',
        logo: 'magic-ui.svg',
        logoTone: 'original',
        detail: 'Marquee 循环滚动组件',
        href: 'https://github.com/magicuidesign/magicui',
      },
      {
        name: 'MikePenz Markdown',
        logo: 'markdown.png',
        logoTone: 'original',
        detail: 'App 的 Markdown 渲染',
        href: 'https://github.com/mikepenz/multiplatform-markdown-renderer',
      },
      {
        name: 'MathJax',
        logo: 'mathjax.png',
        logoTone: 'original',
        detail: 'App 的数学公式排版',
        href: 'https://github.com/mathjax/MathJax',
      },
      {
        name: 'MCP Kotlin SDK',
        logo: 'mcp.svg',
        logoTone: 'ink',
        detail: 'App 的 MCP 工具连接',
        href: 'https://github.com/modelcontextprotocol/kotlin-sdk',
      },
    ],
  },
]

/** 卡片沿用网站黑白主题，Logo 保留官方品牌色；暂停按钮同时控制全部动画。 */
export default function TechEcosystem() {
  const { t } = useLanguage()
  const [paused, setPaused] = useState(false)
  return (
    <section
      id="ecosystem"
      className="tech-ecosystem site-container"
      aria-labelledby="ecosystem-title"
    >
      <div className="ecosystem-heading">
        <div>
          <p className="eyebrow">{t('技术栈与参考项目')}</p>
          <h2 id="ecosystem-title">{t('由开源构建。')}</h2>
          <p className="ecosystem-description">
            {t('从原生应用到这个网站，感谢让想法落地的开源项目。')}
          </p>
        </div>
        <button
          type="button"
          className="ecosystem-pause"
          aria-pressed={paused}
          onClick={() => setPaused(!paused)}
        >
          {paused ? <Play size={14} /> : <Pause size={14} />}
          {paused ? t('继续滚动') : t('暂停滚动')}
        </button>
      </div>
      <div className="ecosystem-rows">
        {rows.map(({ title, label, icon: Icon, duration, reverse, items }) => (
          <div className="ecosystem-row" key={label}>
            <h3>
              <Icon size={17} strokeWidth={1.5} />
              <span>{t(title)}</span>
              <span className="mono-label">{label}</span>
            </h3>
            <Marquee
              aria-label={t(title)}
              reverse={reverse}
              pauseOnHover
              paused={paused}
              style={{ '--duration': duration } as CSSProperties}
            >
              {items.map((item) => (
                <a
                  className="ecosystem-card"
                  href={item.href}
                  key={item.name}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  {/* 彩色标识保留品牌色，单色标识由 CSS 适配主题；名称已在卡片显示。 */}
                  <Image
                    src={`/tech-logos/${item.logo}`}
                    alt=""
                    width={40}
                    height={40}
                    loading="eager"
                    unoptimized
                    className="ecosystem-logo"
                    data-tone={item.logoTone}
                  />
                  <span className="ecosystem-card-content">
                    <span className="ecosystem-card-top">
                      <strong>{item.name}</strong>
                      <ArrowUpRight size={14} aria-hidden="true" />
                    </span>
                    <span className="ecosystem-card-detail">
                      {t(item.detail)}
                    </span>
                  </span>
                </a>
              ))}
            </Marquee>
          </div>
        ))}
      </div>
    </section>
  )
}
