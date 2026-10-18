'use client'

import { useLanguage } from '@/components/ui/LanguageProvider'

import Image from 'next/image'
import Link from 'next/link'
import { ArrowUpRight } from 'lucide-react'

export default function Footer() {
  const { t } = useLanguage()
  return (
    <footer className="site-footer site-container">
      <div className="footer-top">
        <Link href="/" className="brand">
          <Image
            src="/everytalk-logo-source.png"
            width={36}
            height={36}
            alt=""
            className="brand-image"
          />
          <span>EveryTalk.</span>
        </Link>
        <p>{t('保持开放。继续对话。')}</p>
        <a
          href="https://github.com/roseforljh/EveryTalk"
          target="_blank"
          rel="noopener noreferrer"
        >
          {t('在 GitHub 一起构建 ')}
          <ArrowUpRight size={15} />
        </a>
      </div>
      <div className="footer-bottom">
        <span>
          © {new Date().getFullYear()}
          {t(' EveryTalk · 开源 Android AI 客户端')}
        </span>
        <div>
          <Link href="/privacy-policy">{t('隐私政策')}</Link>
          <Link href="/terms-of-service">{t('服务条款')}</Link>
          <a
            href="https://github.com/roseforljh/EveryTalk/blob/main/LICENSE.md"
            target="_blank"
            rel="noopener noreferrer"
          >
            MIT License
          </a>
        </div>
      </div>
    </footer>
  )
}
