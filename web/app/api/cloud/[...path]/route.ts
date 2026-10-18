import type { NextRequest } from 'next/server'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

/**
 * 只中转到部署配置的固定同步服务，解决现有安卓后端没有浏览器 CORS 的问题。
 * 不接收客户端上游地址，不转发 Cookie，也不提供清空云端或密钥重置接口。
 * 用户 JWT 和协议头由原后端逐项验证，Web 服务不持有管理员密钥。
 */
async function proxy(request: NextRequest, context: { params: Promise<{ path: string[] }> }): Promise<Response> {
  const { path } = await context.params
  const route = '/' + path.join('/')
  const allowed = request.method === 'GET'
    ? /^\/(sync\/pull|files\/quota|files\/[0-9a-f-]{36})$/.test(route)
    : request.method === 'POST'
      ? /^\/(sync\/(push|account-key|history-key)|files\/uploads)$/.test(route)
      : request.method === 'PUT' && /^\/files\/uploads\/[0-9a-f-]{36}$/.test(route)
  if (!allowed) return Response.json({ error: { code: 'NOT_FOUND' } }, { status: 404 })
  const authorization = request.headers.get('authorization')
  if (!authorization?.startsWith('Bearer ') || authorization.length > 17000) return Response.json({ error: { code: 'UNAUTHORIZED' } }, { status: 401 })
  if (request.headers.get('x-everytalk-sync-version') !== '0.2.0') return Response.json({ error: { code: 'UNSUPPORTED_SYNC_VERSION' } }, { status: 426 })
  const origin = request.headers.get('origin')
  if (origin && origin !== request.nextUrl.origin) return Response.json({ error: { code: 'INVALID_ORIGIN' } }, { status: 403 })
  let target: URL
  try {
    const base = new URL(process.env.EVERYTALK_SYNC_BASE_URL ?? '')
    if (base.protocol !== 'https:' || base.username || base.password || base.search || base.hash) throw new Error('config')
    target = new URL(base.toString().replace(/\/$/, '') + route + request.nextUrl.search)
  } catch { return Response.json({ error: { code: 'SYNC_NOT_CONFIGURED' } }, { status: 503 }) }
  const headers = new Headers({ authorization })
  for (const name of ['content-type', 'x-everytalk-sync-version', 'x-everytalk-secrets-epoch', 'idempotency-key']) {
    const value = request.headers.get(name)
    if (value) headers.set(name, value)
  }
  try {
    const init: RequestInit & { duplex?: 'half' } = {
      method: request.method, headers, redirect: 'error', cache: 'no-store',
      signal: AbortSignal.any([request.signal, AbortSignal.timeout(90_000)]),
    }
    if (request.method !== 'GET') { init.body = request.body; init.duplex = 'half' }
    const upstream = await fetch(target, init)
    const responseHeaders = new Headers({ 'Cache-Control': 'private,no-store', 'X-Content-Type-Options': 'nosniff' })
    for (const name of ['content-type', 'content-length', 'content-disposition', 'retry-after']) {
      const value = upstream.headers.get(name)
      if (value) responseHeaders.set(name, value)
    }
    return new Response(upstream.body, { status: upstream.status, headers: responseHeaders })
  } catch { return Response.json({ error: { code: 'SYNC_UPSTREAM_UNAVAILABLE' } }, { status: 502, headers: { 'Cache-Control': 'no-store' } }) }
}

export { proxy as GET, proxy as POST, proxy as PUT }
