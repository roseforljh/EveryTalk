'use client'

import { useLanguage } from '@/components/ui/LanguageProvider'

import dynamic from 'next/dynamic'
import Link from 'next/link'
import { ArrowDown, CodeXml, Download } from 'lucide-react'
import DepthText from '@/components/ui/DepthText'

const PixelPenguin = dynamic(() => import('@/components/3d/PixelPenguin'), {
  ssr: false,
})

export default function Hero() {
  const { t } = useLanguage()
  return (
    <section className="hero site-container" aria-labelledby="hero-title">
      <div className="hero-copy">
        <p className="eyebrow">
          <span className="status-pixel" />
          {t(' 开源 ANDROID AI 客户端')}
        </p>
        <div className="hero-heading">
          <h1 id="hero-title">
            <DepthText text={`${t('让对话，自由发生')}.`} />
          </h1>
          <div className="hero-visual">
            {/* 企鹅跟随标题左侧定位，文案仍以页面中轴居中；窄屏位置由样式调整。 */}
            <PixelPenguin />
          </div>
        </div>
        <p className="hero-description">
          {t('模型由你选择。想法不设边界。')}
          {' '}
          {t('把聊天、搜索和创作，装进口袋。')}
        </p>
        <div className="actions hero-actions">
          <Link href="/#download" className="hero-cta hero-cta-primary">
            <Download size={18} aria-hidden="true" />
            <span>{t('下载 EveryTalk')}</span>
          </Link>
          <a
            href="https://github.com/roseforljh/EveryTalk"
            className="hero-cta hero-cta-secondary"
            target="_blank"
            rel="noopener noreferrer"
          >
            <CodeXml size={18} aria-hidden="true" />
            <span>{t('查看源码')}</span>
          </a>
        </div>
        <div className="hero-facts">
          <span>Android 8.1+</span>
          <span>{t('多模型接入')}</span>
          <span>{t('开源 · MIT')}</span>
        </div>
      </div>
      <a href="#features" className="hero-scroll">
        <ArrowDown size={15} />
        <span>{t('向下探索')}</span>
        <span className="hero-scroll-line" />
      </a>
    </section>
  )
}
