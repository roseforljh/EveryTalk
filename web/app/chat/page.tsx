import { App } from '@/src/app'
import '@/src/styles.css'

export const metadata = { title: 'EveryTalk · 对话', robots: { index: false, follow: false } }

/** 登录门禁和本地数据库在浏览器中初始化，服务端不会渲染任何聊天内容。 */
export default function ChatPage() { return <App /> }
