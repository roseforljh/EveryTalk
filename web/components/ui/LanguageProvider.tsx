'use client'

import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from 'react'
import { usePathname } from 'next/navigation'
import { ScrollTrigger } from 'gsap/ScrollTrigger'
import { englishTranslations } from '@/lib/translations'

type Language = 'zh' | 'en'
const LanguageContext = createContext({
  language: 'zh' as Language,
  toggleLanguage: () => {},
  t: (text: string) => text,
})
const pageTitles: Record<string, [string, string]> = {
  '/': ['EveryTalk — 让对话，自由发生', 'EveryTalk — Let your ideas flow'],
  '/features': ['功能与体验 | EveryTalk', 'Features & showcase | EveryTalk'],
  '/download': [
    '下载 Android 应用 | EveryTalk',
    'Download for Android | EveryTalk',
  ],
  '/privacy-policy': ['隐私政策 | EveryTalk', 'Privacy policy | EveryTalk'],
  '/terms-of-service': ['服务条款 | EveryTalk', 'Terms of service | EveryTalk'],
}

/** 全站共享语言状态；刷新和跨页继续使用选择，存储被禁用时仍可即时切换。 */
export default function LanguageProvider({
  children,
}: {
  children: ReactNode
}) {
  const [language, setLanguage] = useState<Language>('zh')
  const pathname = usePathname()
  useEffect(() => {
    try {
      const saved = localStorage.getItem('everytalk-language')
      if (saved === 'zh' || saved === 'en') setLanguage(saved)
    } catch {
      /* 存储不可用时保留中文初始页，手动选择仍在本次访问中生效。 */
    }
  }, [])
  useEffect(() => {
    document.documentElement.lang = language === 'zh' ? 'zh-CN' : 'en'
    const titles = pageTitles[pathname]
    const applyTitle = () => {
      const title = titles?.[language === 'zh' ? 0 : 1]
      if (title && document.title !== title) document.title = title
    }
    applyTitle()
    // Next 的页面元数据可能在客户端挂载后才到达，防止它覆盖当前语言的标签标题。
    const titleObserver = new MutationObserver(applyTitle)
    titleObserver.observe(document.head, {
      childList: true,
      subtree: true,
      characterData: true,
    })
    // 英文长度改变区块高度；等本次 DOM 更新后刷新滚动动画，不改变用户滚动位置。
    const frame = requestAnimationFrame(() => ScrollTrigger.refresh())
    return () => {
      cancelAnimationFrame(frame)
      titleObserver.disconnect()
    }
  }, [language, pathname])
  const toggleLanguage = () => {
    const next = language === 'zh' ? 'en' : 'zh'
    setLanguage(next)
    try {
      localStorage.setItem('everytalk-language', next)
    } catch {
      /* 无存储权限时只更新内存状态。 */
    }
  }
  const t = (text: string): string => {
    if (language === 'zh') return text
    const key = text.trim()
    const translation = englishTranslations[key]
    // 保留 JSX 原来的前后空格，避免英文与内联链接、图标挤在一起。
    return translation === undefined ? text : text.replace(key, translation)
  }
  return (
    <LanguageContext.Provider value={{ language, toggleLanguage, t }}>
      {children}
    </LanguageContext.Provider>
  )
}

export const useLanguage = () => useContext(LanguageContext)

/** 让带服务器元数据的页面也能即时切换文案，不需要把整个页面改成客户端组件。 */
export function LocalizedText({ children }: { children: string }) {
  const { t } = useLanguage()
  return <>{t(children)}</>
}

export function LanguageToggle() {
  const { language, toggleLanguage } = useLanguage()
  return (
    <button
      type="button"
      className="language-toggle"
      onClick={toggleLanguage}
      aria-label={language === 'zh' ? 'Switch to English' : '切换为中文'}
      title={language === 'zh' ? 'Switch to English' : '切换为中文'}
    >
      <span lang={language === 'zh' ? 'en' : 'zh-CN'}>
        {language === 'zh' ? 'EN' : '中'}
      </span>
    </button>
  )
}
