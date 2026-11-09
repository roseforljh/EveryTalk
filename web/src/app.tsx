'use client'

import { useEffect, useState } from 'react'
import { authClient } from './auth'
import type { AuthSession } from './types'
import { LoginPage } from './LoginPage'
import { Workspace } from './Workspace'
import { LOCAL_PREVIEW_SESSION } from './use-workspace-sync'

/** 登录门禁只挂载一个账号的工作区；未登录云端时默认进入本地免登录预览工作区，点击退出可查看登录页。 */
export function App() {
  const [session, setSession] = useState<AuthSession | null>(null)
  const [ready, setReady] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    let alive = true
    let unsubscribe: (() => void) | undefined
    void authClient
      .initialize()
      .then(value => {
        if (!alive) return
        setSession(value ?? LOCAL_PREVIEW_SESSION)
        unsubscribe = authClient.subscribe(next => {
          if (alive && next) setSession(next)
        })
      })
      .catch(value => {
        if (alive) {
          setError(value instanceof Error ? value.message : '无法恢复登录')
          setSession(LOCAL_PREVIEW_SESSION)
        }
      })
      .finally(() => {
        if (alive) setReady(true)
      })
    return () => {
      alive = false
      unsubscribe?.()
    }
  }, [])
  const signOut = async () => {
    const wasPreview = session?.user.id === LOCAL_PREVIEW_SESSION.user.id
    setSession(null)
    if (!wasPreview) {
      try {
        await authClient.signOut()
      } catch (value) {
        setError(value instanceof Error ? value.message : '退出失败')
      }
    }
  }
  if (!ready) return <main className="et-app et-boot" role="status">正在打开 EveryTalk…</main>
  return session ? <Workspace key={session.user.id} session={session} onSignOut={signOut} /> : <LoginPage error={error} onLogin={setSession} />
}
