/* eslint-disable @next/next/no-img-element */
'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowRight, GitBranch, KeyRound, Mail, Sparkles } from 'lucide-react'
import { authClient, configuredAuth } from './auth'
import { LOCAL_PREVIEW_SESSION } from './use-workspace-sync'
import type { AuthSession } from './types'

/**
 * 登录页组件，布局与交互对齐安卓端 AccountScreen.kt：
 * 顶部居中像素企鹅标志、24px 圆角带前置图标输入框、56px 胶囊主按钮与底部圆形第三方登录按钮。
 */
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

  useEffect(() => {
    if (!cooldown) return
    const id = setTimeout(() => setCooldown(c => c - 1), 1000)
    return () => clearTimeout(id)
  }, [cooldown])
  useEffect(() => setError(initialError), [initialError])
  useEffect(() => {
    void authClient.availableOAuthProviders().then(providers => {
      setOauthProviders(providers)
      setOauthReady(true)
    })
  }, [])

  const run = async (action: () => Promise<void>) => {
    setBusy(true)
    setError('')
    try {
      await action()
    } catch (value) {
      setError(value instanceof Error ? value.message : '操作失败，请稍后重试')
    } finally {
      setBusy(false)
    }
  }
  const send = async () => {
    await authClient.sendEmailCode(email)
    setSent(true)
    setCooldown(60)
  }

  return (
    <main className="et-app et-login">
      <Link className="et-login-brand" href="/" aria-label="返回官网">
        <img src="/favicon.svg" alt="" width="30" height="30" />
        EveryTalk
      </Link>
      <section className="et-login-card" aria-labelledby="login-title">
        <img src="/favicon.svg" alt="EveryTalk" className="et-login-penguin" width="64" height="64" />
        <h1 id="login-title">欢迎使用 EveryTalk</h1>
        <p className="et-muted">登录以同步您的配置与对话记录</p>
        {!configured && <p className="et-notice" role="alert">账号服务尚未配置，请查看部署说明。</p>}
        <form
          onSubmit={event => {
            event.preventDefault()
            void run(async () => {
              if (!sent) await send()
              else onLogin(await authClient.verifyEmailCode(email, code))
            })
          }}
        >
          <label>
            邮箱地址
            <span className="et-input-field">
              <Mail size={18} />
              <input
                type="email"
                required
                autoComplete="email"
                value={email}
                disabled={busy || sent}
                onChange={event => setEmail(event.target.value)}
                placeholder="you@example.com"
              />
            </span>
          </label>
          {sent && (
            <label>
              邮箱验证码
              <span className="et-input-field">
                <KeyRound size={18} />
                <input
                  required
                  autoFocus
                  autoComplete="one-time-code"
                  inputMode="numeric"
                  value={code}
                  onChange={event => setCode(event.target.value)}
                  placeholder="输入邮件中的验证码"
                />
              </span>
            </label>
          )}
          <button className="et-primary et-login-submit" disabled={busy || !configured}>
            {busy ? '处理中…' : sent ? '登录' : '发送验证码'}
            <ArrowRight size={18} />
          </button>
          <button
            type="button"
            className="et-outline et-login-submit"
            style={{ marginTop: 4 }}
            onClick={() => onLogin(LOCAL_PREVIEW_SESSION)}
          >
            <Sparkles size={18} />
            免登录直接进入工作区预览
          </button>
          {sent && (
            <div className="et-inline">
              <button type="button" disabled={busy || cooldown > 0} onClick={() => void run(send)}>
                {cooldown ? `${cooldown} 秒后重新发送` : '重新发送'}
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={() => {
                  setSent(false)
                  setCode('')
                }}
              >
                更换邮箱
              </button>
            </div>
          )}
        </form>
        {oauthReady && oauthProviders.size > 0 && (
          <>
            <div className="et-divider">或</div>
            <div className="et-oauth">
              {oauthProviders.has('google') && (
                <button
                  type="button"
                  className="et-oauth-circle"
                  aria-label="使用 Google 登录"
                  title="使用 Google 登录"
                  disabled={busy}
                  onClick={() => void run(() => authClient.startOAuth('google'))}
                >
                  <Mail size={20} />
                </button>
              )}
              {oauthProviders.has('github') && (
                <button
                  type="button"
                  className="et-oauth-circle"
                  aria-label="使用 GitHub 登录"
                  title="使用 GitHub 登录"
                  disabled={busy}
                  onClick={() => void run(() => authClient.startOAuth('github'))}
                >
                  <GitBranch size={20} />
                </button>
              )}
            </div>
          </>
        )}
        {oauthReady && configured && oauthProviders.size === 0 && (
          <p className="et-muted et-oauth-disabled">当前项目未启用第三方登录，请使用邮箱验证码。</p>
        )}
        {error && <p className="et-error" role="alert">{error}</p>}
        <p className="et-login-foot">
          登录即表示同意 <Link href="/terms-of-service">服务条款</Link> 与 <Link href="/privacy-policy">隐私政策</Link>
        </p>
      </section>
      <p className="et-login-bottom">同一个账号 · 同一段对话 · 每一块屏幕</p>
    </main>
  )
}
