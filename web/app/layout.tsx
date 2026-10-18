import type { Metadata, Viewport } from 'next'
import { Noto_Sans_SC, Space_Grotesk } from 'next/font/google'
import './globals.css'
import ThemeProvider from '@/components/ui/ThemeProvider'
import LanguageProvider from '@/components/ui/LanguageProvider'

const notoSans = Noto_Sans_SC({
  weight: ['400', '500', '600', '700', '800'],
  preload: false,
  variable: '--font-noto',
})
const spaceGrotesk = Space_Grotesk({
  subsets: ['latin'],
  weight: ['500', '600', '700'],
  variable: '--font-space',
})

export const metadata: Metadata = {
  metadataBase: new URL('https://www.everytalk.cc'),
  title: 'EveryTalk — 让对话，自由发生',
  description:
    '开源 Android AI 客户端。连接自己的模型，探索聊天、联网搜索、MCP 与图像创作。',
  keywords: ['EveryTalk', 'AI聊天', 'Android', '开源', '多模型', 'MCP'],
  icons: {
    // 小尺寸图标放大原企鹅并使用圆角浅底；SVG 优先，PNG 兼容不支持 SVG 的浏览器。
    icon: [
      { url: '/favicon-32.png', type: 'image/png', sizes: '32x32' },
      { url: '/favicon.svg', type: 'image/svg+xml', sizes: 'any' },
    ],
    apple: '/everytalk-logo-source.png',
  },
  openGraph: {
    title: 'EveryTalk — 让对话，自由发生',
    description: '模型由你选择。想法不设边界。',
    type: 'website',
    url: 'https://www.everytalk.cc',
    images: [
      {
        url: '/everytalk-logo-source.png',
        width: 512,
        height: 512,
        alt: 'EveryTalk 像素企鹅',
      },
    ],
  },
  twitter: {
    card: 'summary',
    title: 'EveryTalk — 让对话，自由发生',
    images: ['/everytalk-logo-source.png'],
  },
}
export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  maximumScale: 5,
  themeColor: [
    { media: '(prefers-color-scheme: dark)', color: '#000000' },
    { media: '(prefers-color-scheme: light)', color: '#ffffff' },
  ],
}

// 首次绘制前应用上次主题，避免深浅切换闪屏；禁用存储时自动使用系统偏好。
const themeScript = `try{var t=localStorage.getItem('everytalk-theme');document.documentElement.dataset.theme=t==='dark'||t==='light'?t:matchMedia('(prefers-color-scheme:dark)').matches?'dark':'light'}catch(e){document.documentElement.dataset.theme=matchMedia('(prefers-color-scheme:dark)').matches?'dark':'light'}`

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html
      lang="zh-CN"
      data-scroll-behavior="smooth"
      suppressHydrationWarning
      className={`${notoSans.variable} ${spaceGrotesk.variable}`}
    >
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
      </head>
      <body>
        <ThemeProvider>
          <LanguageProvider>
            {children}
          </LanguageProvider>
        </ThemeProvider>
      </body>
    </html>
  )
}
