'use client'

import { useLanguage } from '@/components/ui/LanguageProvider'

import { useEffect, useState } from 'react'
import { ArrowUpRight } from 'lucide-react'

interface Release {
  tag_name: string
  name: string
  published_at: string
  html_url: string
  prerelease: boolean
}
const releasesUrl = 'https://github.com/roseforljh/EveryTalk/releases'

/** GitHub 限流或空响应不阻塞下载；始终保留官方版本入口。 */
export default function VersionHistory() {
  const { t, language } = useLanguage()
  const [releases, setReleases] = useState<Release[]>([])
  const [status, setStatus] = useState<'loading' | 'done' | 'error'>('loading')
  useEffect(() => {
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 10000)
    let active = true
    const load = async () => {
      try {
        const response = await fetch(
          'https://api.github.com/repos/roseforljh/EveryTalk/releases?per_page=3',
          { signal: controller.signal },
        )
        if (!response.ok) throw new Error('版本接口暂不可用')
        const data: unknown = await response.json()
        if (!Array.isArray(data)) throw new Error('版本数据格式错误')
        const valid = data.filter(
          (item): item is Release =>
            typeof item === 'object' &&
            item !== null &&
            typeof item.tag_name === 'string' &&
            typeof item.name === 'string' &&
            typeof item.published_at === 'string' &&
            typeof item.prerelease === 'boolean' &&
            typeof item.html_url === 'string' &&
            item.html_url.startsWith(releasesUrl + '/tag/'),
        )
        if (active) {
          setReleases(valid.slice(0, 3))
          setStatus('done')
        }
      } catch {
        if (active) setStatus('error')
      } finally {
        window.clearTimeout(timeout)
      }
    }
    void load()
    return () => {
      active = false
      controller.abort()
      window.clearTimeout(timeout)
    }
  }, [])
  return (
    <section
      className="release-section section-space site-container"
      aria-labelledby="release-title"
    >
      <p className="eyebrow">{t('持续更新')}</p>
      <h2 id="release-title" className="scroll-reveal">
        {t('版本记录。')}
      </h2>
      <div aria-live="polite">
        {status === 'loading' ? (
          <p>{t('正在读取版本信息…')}</p>
        ) : status === 'error' || !releases.length ? (
          <p>{t('暂时无法读取版本，请直接到 GitHub 查看。')}</p>
        ) : (
          releases.map((release) => (
            <a
              className="release-row"
              key={release.tag_name}
              href={release.html_url}
              target="_blank"
              rel="noopener noreferrer"
            >
              <strong>{release.tag_name}</strong>
              <span>{release.name}</span>
              <time dateTime={release.published_at}>
                {new Date(release.published_at).toLocaleDateString(
                  language === 'zh' ? 'zh-CN' : 'en-US',
                )}
              </time>
              <span>{release.prerelease ? t('预发布') : t('正式版')}</span>
              <ArrowUpRight size={18} />
            </a>
          ))
        )}
      </div>
      <a
        href={releasesUrl}
        target="_blank"
        rel="noopener noreferrer"
        className="action-quiet"
      >
        {t('查看所有版本 ')}
        <ArrowUpRight size={16} />
      </a>
    </section>
  )
}
