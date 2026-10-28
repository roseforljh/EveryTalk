/* eslint-disable @next/next/no-img-element */
'use client'

import Link from 'next/link'
import { useCallback, useEffect, useState } from 'react'
import { LogOut, RefreshCw } from 'lucide-react'
import { authClient, authenticatedFetch, apiBaseUrl } from './auth'
import { LoginPage } from './LoginPage'
import type { AuthSession } from './types'

type Day = { day: string; requests: number; errors: number; bytes: number; active_users: number; model_runs: number; signups: number; logins: number }
type Stats = {
  generated_at: string; days: number; active_model_runs: number; daily: Day[]
  kinds: Array<{ kind: string; requests: number; errors: number; bytes: number }>
  top_users: Array<{ user_id: string; email: string; requests: number; bytes: number; model_runs: number }>
  users: { configured: boolean; total: number; providers: Record<string, number>
    recent: Array<{ id: string; email: string; created_at: string; last_sign_in_at: string | null; provider: string }> }
}

const RANGES = [7, 30, 90]
const KIND_LABELS: Record<string, string> = {
  model_run: '模型任务完成', 'POST /v1/chat/runs': '创建模型任务', 'GET /v1/chat/runs/:runId/stream': '模型输出续接',
  'DELETE /v1/chat/runs/:runId': '取消模型任务', 'POST /v1/chat': '网页模型中转', 'POST /sync/push': '同步上传',
  'GET /sync/pull': '同步拉取', 'POST /files/uploads': '附件预约', 'PUT /files/uploads/:uploadId': '附件上传',
  'GET /files/:fileId': '附件下载', 'GET /files/quota': '附件配额', 'GET /admin/stats': '监控面板',
}
const count = (value: number) => value.toLocaleString('zh-CN')
const bytes = (value: number) => value < 1024 ? `${value} B` : value < 1048576 ? `${(value / 1024).toFixed(1)} KB` :
  value < 1073741824 ? `${(value / 1048576).toFixed(1)} MB` : `${(value / 1073741824).toFixed(2)} GB`
const time = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '从未'
const sum = (days: Day[], key: keyof Omit<Day, 'day'>) => days.reduce((total, day) => total + day[key], 0)

/** 单序列柱状图：标题即图例，悬停显示当天数值，明细见下方表格。 */
function BarChart({ title, days, value }: { title: string; days: Day[]; value: (day: Day) => number }) {
  const [hover, setHover] = useState<number | null>(null)
  const max = Math.max(1, ...days.map(value))
  const width = 640, height = 160, gap = 2
  const bar = width / days.length
  const active = hover === null ? null : days[hover]
  return (
    <section className="et-admin-panel">
      <div className="et-inline"><h2>{title}</h2><span className="et-muted">{active ? `${active.day} · ${count(value(active))}` : `峰值 ${count(max)}`}</span></div>
      <svg viewBox={`0 0 ${width} ${height + 18}`} role="img" aria-label={title} onMouseLeave={() => setHover(null)}>
        <line x1="0" x2={width} y1={height} y2={height} className="et-admin-axis" />
        {days.map((day, index) => {
          const h = value(day) / max * (height - 8)
          return (
            <g key={day.day} onMouseEnter={() => setHover(index)}>
              <rect x={index * bar} y="0" width={bar} height={height} fill="transparent" />
              {h > 0 && <rect x={index * bar + gap / 2} y={height - h} width={Math.max(1, bar - gap)} height={h} rx={Math.min(4, (bar - gap) / 2)}
                className={hover === index ? 'et-admin-bar active' : 'et-admin-bar'} />}
            </g>
          )
        })}
        <text x="0" y={height + 14} className="et-admin-tick">{days[0]?.day.slice(5)}</text>
        <text x={width} y={height + 14} textAnchor="end" className="et-admin-tick">{days.at(-1)?.day.slice(5)}</text>
      </svg>
    </section>
  )
}

function Dashboard({ session, onSignOut }: { session: AuthSession; onSignOut: () => void }) {
  const [range, setRange] = useState(30)
  const [stats, setStats] = useState<Stats | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const response = await authenticatedFetch(session, `${apiBaseUrl}/admin/stats?days=${range}`,
        { cache: 'no-store', headers: { 'X-EveryTalk-Sync-Version': '0.2.0' } }, () => authClient.refresh(session))
      if (response.status === 403) throw new Error('当前账号不是管理员。请把它的用户 ID 加入后端 ADMIN_USER_IDS。')
      if (!response.ok) throw new Error(`监控数据读取失败（HTTP ${response.status}）`)
      setStats(await response.json() as Stats)
    } catch (value) { setError(value instanceof Error ? value.message : '监控数据读取失败') }
    finally { setLoading(false) }
  }, [session, range])
  useEffect(() => { void load() }, [load])
  useEffect(() => { const timer = setInterval(() => void load(), 60_000); return () => clearInterval(timer) }, [load])

  const days = stats?.daily ?? []
  const requests = sum(days, 'requests')
  const errors = sum(days, 'errors')
  const today = days.at(-1)
  const tiles = stats ? [
    ['总用户', stats.users.configured ? count(stats.users.total) : '未配置'],
    [`${range} 天新注册`, stats.users.configured ? count(sum(days, 'signups')) : '未配置'],
    ['今日活跃用户', count(today?.active_users ?? 0)],
    [`${range} 天请求`, count(requests)],
    [`${range} 天模型任务`, count(sum(days, 'model_runs'))],
    ['错误率', requests ? `${(errors / requests * 100).toFixed(2)}%` : '0%'],
    ['正在生成', count(stats.active_model_runs)],
  ] : []

  return (
    <main className="et-app et-admin">
      <header className="et-admin-head">
        <Link href="/" className="et-brand"><img src="/favicon.svg" width="25" height="25" alt="" />EveryTalk<span>Admin</span></Link>
        <div className="et-header-actions">
          <div className="et-admin-range" role="group" aria-label="时间范围">
            {RANGES.map(value => <button key={value} className={value === range ? 'selected' : ''} onClick={() => setRange(value)}>{value} 天</button>)}
          </div>
          <button className="et-outline" onClick={() => void load()} disabled={loading} aria-label="刷新"><RefreshCw size={14} />{loading ? '刷新中' : '刷新'}</button>
          <button onClick={onSignOut} aria-label="退出登录"><LogOut size={14} /></button>
        </div>
      </header>
      <div className="et-admin-body">
        <p className="et-eyebrow">MONITOR</p>
        <h1>后端监控</h1>
        <p className="et-muted">{stats ? `数据时间 ${time(stats.generated_at)} · 每分钟自动刷新 · 请求计数约 30 秒落库一次` : '正在读取…'}</p>
        {error && <p className="et-notice et-error">{error}</p>}
        {stats && !stats.users.configured && <p className="et-notice">后端未配置 SUPABASE_SERVICE_ROLE_KEY，注册与登录数据不可用。</p>}
        {stats && <>
          <div className="et-admin-tiles">{tiles.map(([label, value]) => <div key={label} className="et-admin-tile"><span className="et-muted">{label}</span><strong>{value}</strong></div>)}</div>
          <div className="et-admin-grid">
            <BarChart title="每日请求量" days={days} value={day => day.requests} />
            <BarChart title="每日活跃用户" days={days} value={day => day.active_users} />
            <BarChart title="每日新注册" days={days} value={day => day.signups} />
            <BarChart title="每日模型任务" days={days} value={day => day.model_runs} />
          </div>
          <section className="et-admin-panel">
            <h2>每日明细</h2>
            <div className="et-admin-table"><table>
              <thead><tr><th>日期</th><th>请求</th><th>错误</th><th>活跃用户</th><th>模型任务</th><th>新注册</th><th>最后登录在当天</th><th>模型输出</th></tr></thead>
              <tbody>{[...days].reverse().map(day => <tr key={day.day}><td>{day.day}</td><td>{count(day.requests)}</td><td>{count(day.errors)}</td>
                <td>{count(day.active_users)}</td><td>{count(day.model_runs)}</td><td>{count(day.signups)}</td><td>{count(day.logins)}</td><td>{bytes(day.bytes)}</td></tr>)}</tbody>
            </table></div>
          </section>
          <div className="et-admin-grid">
            <section className="et-admin-panel">
              <h2>接口请求分布</h2>
              <div className="et-admin-table"><table>
                <thead><tr><th>接口</th><th>请求</th><th>错误</th><th>数据量</th></tr></thead>
                <tbody>{stats.kinds.map(item => <tr key={item.kind}><td title={item.kind}>{KIND_LABELS[item.kind] ?? item.kind}</td>
                  <td>{count(item.requests)}</td><td>{count(item.errors)}</td><td>{item.bytes ? bytes(item.bytes) : '-'}</td></tr>)}</tbody>
              </table></div>
            </section>
            <section className="et-admin-panel">
              <h2>用量最高用户</h2>
              <div className="et-admin-table"><table>
                <thead><tr><th>用户</th><th>请求</th><th>模型任务</th><th>模型输出</th></tr></thead>
                <tbody>{stats.top_users.map(user => <tr key={user.user_id}><td title={user.user_id}>{user.email || user.user_id.slice(0, 8)}</td>
                  <td>{count(user.requests)}</td><td>{count(user.model_runs)}</td><td>{bytes(user.bytes)}</td></tr>)}</tbody>
              </table></div>
            </section>
          </div>
          {stats.users.configured && <section className="et-admin-panel">
            <div className="et-inline"><h2>最新注册</h2><span className="et-muted">{Object.entries(stats.users.providers).map(([name, value]) => `${name} ${value}`).join(' · ')}</span></div>
            <div className="et-admin-table"><table>
              <thead><tr><th>邮箱</th><th>登录方式</th><th>注册时间</th><th>最近登录</th></tr></thead>
              <tbody>{stats.users.recent.map(user => <tr key={user.id}><td title={user.id}>{user.email || user.id.slice(0, 8)}</td><td>{user.provider}</td>
                <td>{time(user.created_at)}</td><td>{time(user.last_sign_in_at)}</td></tr>)}</tbody>
            </table></div>
          </section>}
        </>}
      </div>
    </main>
  )
}

/** 登录门禁与聊天页共用同一套 Supabase 会话。 */
export function AdminApp() {
  const [session, setSession] = useState<AuthSession | null>(null)
  const [ready, setReady] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    let alive = true
    let unsubscribe: (() => void) | undefined
    void authClient.initialize().then(value => {
      if (!alive) return
      setSession(value)
      unsubscribe = authClient.subscribe(next => { if (alive) setSession(next) })
    }).catch(value => { if (alive) setError(value instanceof Error ? value.message : '无法恢复登录') })
      .finally(() => { if (alive) setReady(true) })
    return () => { alive = false; unsubscribe?.() }
  }, [])
  const signOut = async () => {
    setSession(null)
    try { await authClient.signOut() } catch (value) { setError(value instanceof Error ? value.message : '退出失败') }
  }
  if (!ready) return <main className="et-app et-boot" role="status">正在打开监控面板…</main>
  return session ? <Dashboard key={session.user.id} session={session} onSignOut={signOut} /> : <LoginPage error={error} onLogin={setSession} />
}
