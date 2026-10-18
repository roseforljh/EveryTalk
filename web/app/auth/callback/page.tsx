import { App } from '@/src/app'
import '@/src/styles.css'

export const metadata = { title: 'EveryTalk · 完成登录', robots: { index: false, follow: false } }
// 回调页必须由服务器稳定提供，避免 Vercel 构建器把带授权参数的入口误判为静态页面。
export const dynamic = 'force-dynamic'
export default function AuthCallback() { return <App /> }
