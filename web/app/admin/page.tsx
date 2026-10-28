import { AdminApp } from '@/src/AdminDashboard'
import '@/src/styles.css'

export const metadata = { title: 'EveryTalk · 监控面板', robots: { index: false, follow: false } }

/** 只有后端 ADMIN_USER_IDS 中的账号能读到数据，其余账号会看到无权限提示。 */
export default function AdminPage() { return <AdminApp /> }
