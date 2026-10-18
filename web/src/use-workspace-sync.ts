'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { authClient, SessionExpiredError } from './auth'
import { AccountCrypto } from './crypto'
import { SyncClient, type SyncSnapshot } from './sync'
import type { AuthSession } from './types'

export interface WorkspaceRuntime { client: SyncClient; crypto: AccountCrypto; alive: boolean; ready: boolean; channel?: BroadcastChannel }
const empty: SyncSnapshot = { records: [], cursor: 0, rejected: 0, retryableRejected: 0, pendingCount: 0 }

/** 生命周期只跟账号绑定。刷新令牌不会重开数据库，StrictMode 清理后使用全新连接。 */
export function useWorkspaceSync(session: AuthSession, onExpired: () => void) {
  const sessionRef = useRef(session)
  sessionRef.current = session
  const expiredRef = useRef(onExpired)
  expiredRef.current = onExpired
  const runtime = useRef<WorkspaceRuntime | undefined>(undefined)
  const [snapshot, setSnapshot] = useState<SyncSnapshot>(empty)
  const [status, setStatus] = useState('正在打开')
  const [error, setError] = useState('')
  const [ready, setReady] = useState(false)
  const refreshing = useRef<Promise<void> | undefined>(undefined)

  const refresh = useCallback(async (): Promise<void> => {
    const current = runtime.current
    if (!current?.alive || !current.ready) return
    if (refreshing.current) return refreshing.current
    const operation = (async () => {
      setStatus('同步中')
      try {
        const result = await current.client.sync(sessionRef.current, async () => {
          const renewed = await authClient.refresh(sessionRef.current)
          sessionRef.current = renewed
          return renewed
        })
        if (!current.alive) return
        setSnapshot(result.snapshot)
        const names = { ok: '已同步', disabled: '服务未配置', paused: '同步已暂停', deferred: '等待重试', pending: '仍有待同步内容' }
        setStatus(names[result.status]); setError('')
      } catch (value) {
        if (!current.alive) return
        if (value instanceof SessionExpiredError) { expiredRef.current(); return }
        setStatus(navigator.onLine ? '同步失败' : '离线保存')
        setError(value instanceof Error ? value.message : '同步暂时失败，本地内容已保留')
        try { const local = await current.client.snapshot(); if (current.alive) setSnapshot(local) } catch { /* 退出关闭后不再更新页面。 */ }
      }
    })()
    refreshing.current = operation
    await operation.finally(() => { if (refreshing.current === operation) refreshing.current = undefined })
  }, [])

  const changed = useCallback(async (): Promise<void> => {
    const current = runtime.current
    if (!current?.alive || !current.ready) throw new Error('工作区尚未准备好')
    const local = await current.client.snapshot()
    if (!current.alive) return
    setSnapshot(local); current.channel?.postMessage('changed'); void refresh()
  }, [refresh])

  useEffect(() => {
    const current: WorkspaceRuntime = { client: new SyncClient(), crypto: new AccountCrypto(session.user.id), alive: true, ready: false }
    runtime.current = current
    setReady(false); setSnapshot(empty)
    if (typeof BroadcastChannel !== 'undefined') {
      current.channel = new BroadcastChannel(`everytalk:${session.user.id}`)
      current.channel.onmessage = () => { void refresh() }
    }
    void current.client.open(session.user.id).then(async () => {
      if (!current.alive) return
      current.ready = true
      const local = await current.client.snapshot()
      if (!current.alive) return
      setSnapshot(local); setReady(true); await refresh()
    }).catch(value => { if (current.alive) { setError(value instanceof Error ? value.message : '无法打开浏览器数据库'); setStatus('无法打开') } })
    const interval = window.setInterval(() => { if (document.visibilityState === 'visible') void refresh() }, 20_000)
    const online = () => { void refresh() }
    const visible = () => { if (document.visibilityState === 'visible') void refresh() }
    window.addEventListener('online', online); document.addEventListener('visibilitychange', visible)
    return () => {
      current.alive = false; current.client.close(); current.crypto.close(); current.channel?.close()
      window.clearInterval(interval); window.removeEventListener('online', online); document.removeEventListener('visibilitychange', visible)
      refreshing.current = undefined
      if (runtime.current === current) runtime.current = undefined
    }
  }, [session.user.id, refresh])
  return { runtime, sessionRef, snapshot, status, error, setError, ready, refresh, changed }
}
