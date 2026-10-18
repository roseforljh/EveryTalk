import { afterEach, expect, spyOn, test } from 'bun:test'
import { AuthClient, authConfig, authenticatedFetch } from '../src/auth'
import type { AuthSession } from '../src/types'

const originalConfig = { ...authConfig }
let fetchMock: ReturnType<typeof spyOn<typeof globalThis, 'fetch'>> | undefined

afterEach(() => {
  fetchMock?.mockRestore()
  Object.assign(authConfig, originalConfig)
})

test('登录入口读取 Supabase 真实布尔配置，并忽略未启用的提供商', async () => {
  Object.assign(authConfig, { url: 'https://auth.example.com', key: 'sb_publishable_test' })
  fetchMock = spyOn(globalThis, 'fetch').mockResolvedValue(Response.json({ external: { google: true, github: true, email: true } }))
  expect([...await new AuthClient().availableOAuthProviders()]).toEqual(['google', 'github'])

  fetchMock.mockResolvedValue(Response.json({ external: { google: false, github: 'true', email: true } }))
  expect([...await new AuthClient().availableOAuthProviders()]).toEqual([])

  // 错误页即使包含相同字段，也不能当作已启用的登录方式。
  fetchMock.mockResolvedValue(Response.json({ external: { google: true } }, { status: 503 }))
  expect([...await new AuthClient().availableOAuthProviders()]).toEqual([])
})

test('同源登录请求在 401 后只用同一账号的新令牌重试一次', async () => {
  const session = { access_token: 'old', refresh_token: 'refresh', expires_at: 0, user: { id: '44444444-4444-4444-8444-444444444444' } } satisfies AuthSession
  fetchMock = spyOn(globalThis, 'fetch').mockResolvedValueOnce(Response.json({ error: 'expired' }, { status: 401 })).mockResolvedValueOnce(Response.json({ ok: true }))
  let refreshes = 0
  const response = await authenticatedFetch(session, '/api/model', { method: 'POST' }, async () => {
    refreshes++
    return { ...session, access_token: 'new' }
  })
  expect(response.status).toBe(200)
  expect(fetchMock).toHaveBeenCalledTimes(2)
  expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get('Authorization')).toBe('Bearer old')
  expect(new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get('Authorization')).toBe('Bearer new')
  expect(refreshes).toBe(1)
})
