'use client'

import { useEffect, useState } from 'react'
import { authClient } from './auth'
import type { AuthSession } from './types'
import { LoginPage } from './LoginPage'
import { Workspace } from './Workspace'

/** 登录门禁只挂载一个账号的工作区；退出或换号会销毁同步连接与内存密钥。 */
export function App() {
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
  if (!ready) return <main className="et-app et-boot" role="status">正在打开 EveryTalk…</main>
  return session ? <Workspace key={session.user.id} session={session} onSignOut={signOut} /> : <LoginPage error={error} onLogin={setSession} />
}
