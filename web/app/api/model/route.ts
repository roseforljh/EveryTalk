import { modelUpstream } from '@/lib/model-upstream'
import { isObject } from '@/src/sync-core'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

/** 后端新版本上线后优先复用统一模型入口；旧服务仍返回 501 时回退当前 Vercel 中转。 */
async function backendModelProxy(input: Record<string, unknown>, authorization: string, signal: AbortSignal): Promise<Response | undefined> {
  const configured = process.env.EVERYTALK_SYNC_BASE_URL
  if (!configured) return undefined
  let base: URL
  try {
    base = new URL(configured)
    if ((base.protocol !== 'https:') && !['localhost', '127.0.0.1'].includes(base.hostname)) return undefined
    if (base.username || base.password || base.search || base.hash) return undefined
  } catch { return undefined }
  try {
    const response = await fetch(new URL('/v1/chat', base), {
      method: 'POST', headers: { Authorization: authorization, 'Content-Type': 'application/json' },
      body: JSON.stringify(input), cache: 'no-store', redirect: 'error', signal: AbortSignal.any([signal, AbortSignal.timeout(30_000)]),
    })
    if (response.status === 501) { await response.body?.cancel().catch(() => undefined); return undefined }
    const headers = new Headers({ 'Cache-Control': 'private,no-store', 'X-Content-Type-Options': 'nosniff' })
    const contentType = response.headers.get('content-type')
    if (contentType) headers.set('Content-Type', contentType)
    return new Response(response.body, { status: response.status, headers })
  } catch { return undefined }
}

/** 登录用户的模型流式中转。验证同一 Supabase 账号后才接受请求，不保存 API Key 和消息。 */
export async function POST(request: Request): Promise<Response> {
  const fail = (message: string, status: number) => Response.json({ error: message }, { status, headers: { 'Cache-Control': 'no-store' } })
  if (request.headers.get('origin') !== new URL(request.url).origin) return fail('请求来源无效', 403)
  const authorization = request.headers.get('authorization')
  if (!authorization?.startsWith('Bearer ') || authorization.length > 17000) return fail('请先登录', 401)
  try {
    const auth = await fetch(`${process.env.NEXT_PUBLIC_SUPABASE_URL}/auth/v1/user`, { headers: { authorization, apikey: process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY ?? '' },
      cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(15_000) })
    if (!auth.ok) return fail(auth.status === 401 || auth.status === 403 ? '登录已失效' : '账号服务暂时不可用', auth.status === 401 || auth.status === 403 ? 401 : 503)
    const user: unknown = await auth.json()
    if (!isObject(user) || typeof user.id !== 'string') return fail('账号验证失败', 401)
    const reader = request.body?.getReader()
    if (!reader) return fail('缺少模型请求', 400)
    let size = 0
    const chunks: Uint8Array[] = []
    try { for (;;) { const part = await reader.read(); if (part.done) break; size += part.value.byteLength; if (size > 4 * 1024 * 1024) { await reader.cancel(); return fail('上下文过大，请新建会话', 413) } chunks.push(part.value) } }
    finally { reader.releaseLock() }
    const input: unknown = JSON.parse(Buffer.concat(chunks).toString('utf8'))
    if (!isObject(input) || typeof input.target !== 'string' || typeof input.apiKey !== 'string' || input.apiKey.length > 8192 || /[\r\n]/.test(input.apiKey) || !isObject(input.body)) return fail('模型请求格式无效', 400)
    const protocol = input.protocol
    if (!['openai', 'responses', 'anthropic', 'gemini'].includes(String(protocol))) return fail('暂不支持这个模型协议', 400)
    const target = new URL(input.target)
    const headers: Record<string, string> = protocol === 'anthropic'
      ? { 'x-api-key': input.apiKey, 'anthropic-version': '2023-06-01' }
      : protocol === 'gemini' ? { 'x-goog-api-key': input.apiKey } : { Authorization: `Bearer ${input.apiKey}` }
    const requestInput = { target: input.target, protocol: String(protocol), apiKey: input.apiKey, body: input.body }
    const backend = await backendModelProxy(requestInput, authorization, request.signal)
    if (backend) return backend
    return await modelUpstream(target, headers, JSON.stringify(input.body), AbortSignal.any([request.signal, AbortSignal.timeout(180_000)]))
  } catch { return fail('无法连接模型服务，请检查公网 HTTPS 地址、网络和模型配置', 502) }
}
