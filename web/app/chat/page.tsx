import { App } from '@/src/app'
import Atmosphere from '@/components/3d/Atmosphere'
import '@/src/styles.css'

export const metadata = { title: 'EveryTalk · 对话', robots: { index: false, follow: false } }

/** 登录门禁和本地数据库在浏览器中初始化，服务端渲染沉浸式背景粒子特效。 */
export default function ChatPage() {
  return (
    <>
      <Atmosphere />
      <App />
    </>
  )
}
