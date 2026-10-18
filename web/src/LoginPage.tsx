/* eslint-disable @next/next/no-img-element */
'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowRight, GitBranch, Mail, MonitorSmartphone } from 'lucide-react'
import { authClient, configuredAuth } from './auth'
import type { AuthSession } from './types'

/** 使用与安卓相同的账号服务；验证码发送和第三方跳转均捕获失败。 */
export function LoginPage({ onLogin, error: initialError = '' }: { onLogin: (session: AuthSession) => void; error?: string }) {
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [sent, setSent] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(initialError)
  const [cooldown, setCooldown] = useState(0)
  const [oauthProviders, setOauthProviders] = useState<Set<string>>(new Set())
  const [oauthReady, setOauthReady] = useState(false)
  const configured = configuredAuth()
  useEffect(() => { if (!cooldown) return; const id = setTimeout(() => setCooldown(c => c - 1), 1000); return () => clearTimeout(id) }, [cooldown])
  useEffect(() => setError(initialError), [initialError])
  useEffect(() => { void authClient.availableOAuthProviders().then(providers => { setOauthProviders(providers); setOauthReady(true) }) }, [])
  const run = async (action: () => Promise<void>) => {
    setBusy(true); setError('')
    try { await action() } catch (value) { setError(value instanceof Error ? value.message : '操作失败，请稍后重试') }
    finally { setBusy(false) }
  }
  const send = async () => { await authClient.sendEmailCode(email); setSent(true); setCooldown(60) }
  return <main className="et-app et-login">
    <Link className="et-login-brand" href="/" aria-label="返回官网"><img src="/favicon.svg" alt="" width="30" height="30" />EveryTalk</Link>
    <section className="et-login-card" aria-labelledby="login-title">
      <div className="et-login-symbol"><MonitorSmartphone size={26} strokeWidth={1.5} /></div>
      <p className="et-eyebrow">EVERYTALK / WEB</p>
      <h1 id="login-title">让对话，接着发生。</h1>
      <p className="et-muted">登录安卓端使用的账号，继续你的对话。</p>
      {!configured && <p className="et-notice" role="alert">账号服务尚未配置，请查看部署说明。</p>}
      <form onSubmit={event => { event.preventDefault(); void run(async () => { if (!sent) await send(); else onLogin(await authClient.verifyEmailCode(email, code)) }) }}>
        <label>邮箱地址<input type="email" required autoComplete="email" value={email} disabled={busy || sent} onChange={event => setEmail(event.target.value)} placeholder="you@example.com" /></label>
        {sent && <label>邮箱验证码<input required autoFocus autoComplete="one-time-code" inputMode="numeric" value={code} onChange={event => setCode(event.target.value)} placeholder="输入邮件中的验证码" /></label>}
        <button className="et-primary" disabled={busy || !configured}>{busy ? '处理中…' : sent ? '登录' : '发送验证码'}<ArrowRight size={16} /></button>
        {sent && <div className="et-inline"><button type="button" disabled={busy || cooldown > 0} onClick={() => void run(send)}>{cooldown ? `${cooldown} 秒后重新发送` : '重新发送'}</button><button type="button" disabled={busy} onClick={() => { setSent(false); setCode('') }}>更换邮箱</button></div>}
      </form>
      {oauthReady && oauthProviders.size > 0 && <><div className="et-divider">或继续使用</div><div className="et-oauth">{oauthProviders.has('google') && <button disabled={busy} onClick={() => void run(() => authClient.startOAuth('google'))}><Mail size={16} />Google</button>}{oauthProviders.has('github') && <button disabled={busy} onClick={() => void run(() => authClient.startOAuth('github'))}><GitBranch size={16} />GitHub</button>}</div></>}
      {oauthReady && configured && oauthProviders.size === 0 && <p className="et-muted et-oauth-disabled">当前项目未启用第三方登录，请使用邮箱验证码。</p>}
      {error && <p className="et-error" role="alert">{error}</p>}
      <p className="et-login-foot">登录即表示同意 <Link href="/terms-of-service">服务条款</Link> 与 <Link href="/privacy-policy">隐私政策</Link></p>
    </section>
    <p className="et-login-bottom">同一个账号 · 同一段对话 · 每一块屏幕</p>
  </main>
}

