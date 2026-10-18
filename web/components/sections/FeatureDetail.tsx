'use client'

import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { ArrowLeft, ArrowUpRight, BookOpen, FileCode2 } from 'lucide-react'
import { useEffect } from 'react'
import CodeBlock from '@/components/ui/CodeBlock'
import { useLanguage } from '@/components/ui/LanguageProvider'
import type { FeatureDetail as FeatureDetailData } from '@/lib/feature-details'

const featureOrder: Record<string, string> = {
  'multi-model': '01',
  'search-mcp': '02',
  'image-generation': '03',
  'read-organize': '04',
}

export default function FeatureDetail({ detail }: { detail: FeatureDetailData }) {
  const { language } = useLanguage()
  const router = useRouter()
  const localizedTitle = detail.title[language]
  const copy = (value: { zh: string; en: string }) => value[language]
  const order = featureOrder[detail.slug] ?? '00'

  useEffect(() => {
    // 进入详情页总是从源码页顶部开始，避免沿用上一个页面的滚动位置。
    const frame = window.requestAnimationFrame(() => {
      window.scrollTo({ top: 0, left: 0, behavior: 'auto' })
    })
    return () => window.cancelAnimationFrame(frame)
  }, [detail.slug])

  useEffect(() => {
    document.title = `${localizedTitle} | EveryTalk`
  }, [detail.title, language, localizedTitle])

  const goBack = (event: React.MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault()
    let returnHref = '/features'
    try {
      const raw = sessionStorage.getItem('everytalk-feature-return')
      if (raw) {
        const parsed: unknown = JSON.parse(raw)
        if (
          typeof parsed === 'object' &&
          parsed !== null &&
          'href' in parsed &&
          typeof parsed.href === 'string' &&
          parsed.href.startsWith('/')
        ) {
          returnHref = parsed.href
        }
      }
    } catch {
      /* 存储不可用或内容异常时回到默认功能页。 */
    }
    // 直接回到进入详情页时的地址，避免浏览历史中更早的首页锚点抢走返回目标。
    router.push(returnHref, { scroll: false })
  }

  return (
    <section className="feature-detail site-container">
      <div className="feature-detail-nav">
        <Link href="/features" className="feature-detail-back" onClick={goBack}>
          <ArrowLeft size={15} />
          {language === 'zh' ? '返回功能总览' : 'Back to features'}
        </Link>
        <span className="mono-label">EVERYTALK / {detail.label}</span>
      </div>

      <header className="feature-detail-heading">
        <div className="feature-detail-heading-copy">
          <span className="feature-detail-index">{order} / 04 · SOURCE NOTE</span>
          <h1>{copy(detail.title)}</h1>
          <p>{copy(detail.description)}</p>
          <div className="feature-detail-tags">
            {detail.tags.map((tag) => (
              <span key={tag.zh}>{copy(tag)}</span>
            ))}
          </div>
        </div>
        <div className="feature-detail-mark" aria-hidden="true">
          <FileCode2 size={28} strokeWidth={1.2} />
          <span>{detail.label}</span>
        </div>
      </header>

      <div className="feature-detail-layout">
        <aside className="feature-detail-aside">
          <div className="feature-detail-aside-block">
            <span className="feature-detail-aside-label">
              {language === 'zh' ? '这段代码说明' : 'WHAT THIS SHOWS'}
            </span>
            <h2>{copy(detail.explanationTitle)}</h2>
            <BookOpen size={19} strokeWidth={1.2} />
          </div>
          <p className="feature-detail-aside-note">
            {language === 'zh'
              ? '以下片段来自 EveryTalk Android 工程，页面只展示与这个能力直接相关的部分。'
              : 'These excerpts come from the EveryTalk Android project and focus on the code behind this capability.'}
          </p>
          <Link href="/features" className="feature-detail-overview-link">
            {language === 'zh' ? '浏览其他能力' : 'Browse other capabilities'}
            <ArrowUpRight size={15} />
          </Link>
        </aside>

        <div className="feature-detail-content">
          <CodeBlock
            code={detail.code}
            language={detail.language}
            sourcePath={detail.sourcePath}
            sourceLine={detail.sourceLine}
            uiLanguage={language}
          />
          <div className="feature-explanation">
            <span className="feature-detail-aside-label">
              {language === 'zh' ? 'IMPLEMENTATION NOTES' : 'IMPLEMENTATION NOTES'}
            </span>
            <div className="feature-explanation-grid">
              {detail.explanation.map((item, index) => (
                <article key={item.zh} className="feature-explanation-item">
                  <span>{String(index + 1).padStart(2, '0')}</span>
                  <p>{copy(item)}</p>
                </article>
              ))}
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
