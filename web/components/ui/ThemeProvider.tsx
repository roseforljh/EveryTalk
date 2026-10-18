'use client'

import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { AnimatedThemeToggler } from './AnimatedThemeToggler'

type Theme = 'system' | 'light' | 'dark'
const ThemeContext = createContext({
  mode: 'system' as Theme,
  dark: true,
  setMode: (_mode: Theme) => {},
})

/** 优先读取用户选择；系统模式持续监听系统主题，存储不可用时仍可切换。 */
export default function ThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setMode] = useState<Theme>('system')
  const [dark, setDark] = useState(true)
  const modeRef = useRef<Theme>('system')

  useEffect(() => {
    try {
      const saved = localStorage.getItem('everytalk-theme')
      if (saved === 'light' || saved === 'dark') modeRef.current = saved
    } catch {
      /* 浏览器禁止存储时继续跟随系统。 */
    }
    setMode(modeRef.current)
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const apply = () => {
      const nextDark =
        modeRef.current === 'system'
          ? media.matches
          : modeRef.current === 'dark'
      document.documentElement.dataset.theme = nextDark ? 'dark' : 'light'
      setDark(nextDark)
    }
    apply()
    media.addEventListener('change', apply)
    return () => media.removeEventListener('change', apply)
  }, [])

  const choose = (next: Theme) => {
    const nextDark =
      next === 'system'
        ? window.matchMedia('(prefers-color-scheme: dark)').matches
        : next === 'dark'
    modeRef.current = next
    // 在 View Transition 快照回调内同步应用 DOM 与 React 状态，雪和企鹅也一起反色。
    document.documentElement.dataset.theme = nextDark ? 'dark' : 'light'
    setMode(next)
    setDark(nextDark)
    try {
      localStorage.setItem('everytalk-theme', next)
    } catch {
      /* 仅本次访问生效。 */
    }
  }

  return (
    <ThemeContext.Provider value={{ mode, dark, setMode: choose }}>
      {children}
    </ThemeContext.Provider>
  )
}

export const useTheme = () => useContext(ThemeContext)

/** 只显示一个太阳按钮，切换日夜；首次访问仍自动跟随系统主题。 */
export function ThemeToggle() {
  const { dark, setMode } = useTheme()
  return (
    <AnimatedThemeToggler
      theme={dark ? 'dark' : 'light'}
      onThemeChange={setMode}
    />
  )
}
