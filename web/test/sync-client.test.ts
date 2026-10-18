import 'fake-indexeddb/auto'
import { afterEach, describe, expect, test } from 'bun:test'
import { SyncClient } from '../src/sync'
import type { AuthSession } from '../src/types'

const userId = '44444444-4444-4444-8444-444444444444'
const session = (token = 'token'): AuthSession => ({ access_token: token, refresh_token: 'refresh', expires_at: Date.now() + 60_000, user: { id: userId, email: 'test@example.com' } })
const emptyPull = (cursor = 0) => ({ changes: [], next_cursor: cursor, has_more: false, epoch: 0, secrets_epoch: 0 })
const response = (body: unknown, status = 200, headers: HeadersInit = {}) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
const clients: SyncClient[] = []

afterEach(() => { for (const client of clients.splice(0)) client.close() })

describe('同步网络闭环', () => {
  test('pull、push、pull 后本地队列清空并留下远端记录', async () => {
    const calls: string[] = []
    let pushed = false
    const client = new SyncClient(`https://${crypto.randomUUID()}.sync.test`, async input => {
      const url = new URL(String(input))
      if (url.pathname === '/sync/pull') {
        calls.push('pull')
        return !pushed ? response(emptyPull()) : response({ changes: [{ entity: 'sessions', id: '55555555-5555-4555-8555-555555555555', payload: { clientModifiedAt: 1, title: '新对话' }, deleted: false, server_seq: 1 }], next_cursor: 1, has_more: false, epoch: 0, secrets_epoch: 0 })
      }
      if (url.pathname === '/sync/push') {
        calls.push('push'); pushed = true
        return response({ results: [{ index: 0, entity: 'sessions', id: '55555555-5555-4555-8555-555555555555', status: 'accepted', server_seq: 1 }] })
      }
      throw new Error(`unexpected ${url.pathname}`)
    })
    clients.push(client)
    await client.open(userId)
    await client.saveLocal('sessions', '55555555-5555-4555-8555-555555555555', { clientModifiedAt: 1, title: '新对话' })
    const result = await client.sync(session())
    expect(result.status).toBe('ok')
    expect(result.snapshot.pendingCount).toBe(0)
    expect(result.snapshot.records[0]?.payload.title).toBe('新对话')
    expect(result.snapshot.cursor).toBe(1)
    expect(result.snapshot.records[0]?.server_seq).toBe(1)
    expect(calls).toEqual(['pull', 'push', 'pull'])
  })

  test('401 只调用一次刷新回调后继续同步', async () => {
    let pulls = 0
    const client = new SyncClient(`https://${crypto.randomUUID()}.sync.test`, async input => {
      const path = new URL(String(input)).pathname
      if (path !== '/sync/pull') throw new Error('unexpected request')
      pulls++
      return pulls === 1 ? response({ error: { code: 'UNAUTHORIZED' } }, 401) : response(emptyPull())
    })
    clients.push(client)
    await client.open(userId)
    let refreshes = 0
    const result = await client.sync(session(), async () => { refreshes++; return session('renewed') })
    expect(result.status).toBe('ok')
    expect(refreshes).toBe(1)
    expect(pulls).toBe(2)
  })

  test('附件和密钥请求遇到 401 时复用一次刷新令牌', async () => {
    let calls = 0
    const client = new SyncClient(`https://${crypto.randomUUID()}.sync.test`, async input => {
      calls++
      const path = new URL(String(input)).pathname
      if (calls === 1 || calls === 3) return response({ error: { code: 'UNAUTHORIZED' } }, 401)
      if (path === '/sync/account-key') return response({ dek: 'ok', secrets_epoch: 0, version: 1 })
      return new Response(new Uint8Array([1, 2, 3]), { status: 200 })
    })
    clients.push(client)
    await client.open(userId)
    let refreshes = 0
    const refresh = async () => { refreshes++; return session('renewed') }
    const json = await client.cloudJson(session(), '/sync/account-key', {}, undefined, refresh)
    expect(json).toEqual({ dek: 'ok', secrets_epoch: 0, version: 1 })
    const file = await client.fileRequest(session(), '/files/11111111-1111-4111-8111-111111111111', {}, refresh)
    expect(new Uint8Array(await file.arrayBuffer())).toEqual(new Uint8Array([1, 2, 3]))
    expect(refreshes).toBe(2)
    expect(calls).toBe(4)
  })
})
