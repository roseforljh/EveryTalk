import { buildSearchRequest, normalizeSearchProvider, parseSearchResponse } from '@/src/search'
import { isObject } from '@/src/sync-core'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

/** 流式读取有上限的 JSON，避免在解析前被超大请求占满函数内存。 */
async function readLimitedJson(request: Request, limit: number): Promise<unknown> {
  const reader = request.body?.getReader()
  if (!reader) throw new Error('EMPTY_BODY')
  const chunks: Uint8Array[] = []
  let size = 0
  try {
    for (;;) {
      const part = await reader.read()
      if (part.done) break
      size += part.value.byteLength
      if (size > limit) throw new Error('BODY_TOO_LARGE')
      chunks.push(part.value)
    }
  } finally { reader.releaseLock() }
  const bytes = new Uint8Array(size)
  let offset = 0
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength }
  return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
}

/** 限制供应商响应大小；搜索结果只需要少量结构化字段。 */
async function readLimitedResponse(response: Response, limit: number): Promise<unknown> {
  const reader = response.body?.getReader()
  if (!reader) throw new Error('EMPTY_RESPONSE')
  const chunks: Uint8Array[] = []
  let size = 0
  try {
    for (;;) {
      const part = await reader.read()
      if (part.done) break
      size += part.value.byteLength
      if (size > limit) throw new Error('RESPONSE_TOO_LARGE')
      chunks.push(part.value)
    }
  } finally { reader.releaseLock() }
  const bytes = new Uint8Array(size)
  let offset = 0
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength }
  return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
}

/** 登录用户的固定供应商搜索代理；密钥只用于本次请求，不写日志、不进入同步数据。 */
export async function POST(request: Request): Promise<Response> {
  const fail = (message: string, status: number) => Response.json({ error: message }, { status, headers: { 'Cache-Control': 'no-store' } })
  if (request.headers.get('origin') !== new URL(request.url).origin) return fail('请求来源无效', 403)
  const authorization = request.headers.get('authorization')
  if (!authorization?.startsWith('Bearer ') || authorization.length > 17000) return fail('请先登录', 401)
  try {
    const auth = await fetch(`${process.env.NEXT_PUBLIC_SUPABASE_URL}/auth/v1/user`, {
      headers: { authorization, apikey: process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY ?? '' },
      cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(15_000),
    })
    if (!auth.ok) return fail(auth.status === 401 || auth.status === 403 ? '登录已失效' : '账号服务暂时不可用', auth.status === 401 || auth.status === 403 ? 401 : 503)
    const user: unknown = await auth.json()
    if (!isObject(user) || typeof user.id !== 'string') return fail('账号验证失败', 401)

    let input: unknown
    try { input = await readLimitedJson(request, 64 * 1024) }
    catch (error) { if (error instanceof Error && error.message === 'BODY_TOO_LARGE') return fail('搜索请求过大', 413); throw error }
    if (!isObject(input) || typeof input.apiKey !== 'string' || typeof input.query !== 'string') return fail('搜索请求格式无效', 400)
    const provider = normalizeSearchProvider(input.provider)
    if (!provider) return fail('不支持的搜索服务商', 400)
    const upstream = buildSearchRequest(provider, input.apiKey, input.query)
    const response = await fetch(upstream.url, {
      method: 'POST', headers: { ...upstream.headers, 'Content-Type': 'application/json' }, body: JSON.stringify(upstream.body),
      cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(30_000),
    })
    if (!response.ok) return fail(`搜索服务返回 HTTP ${response.status}`, 502)
    const result = parseSearchResponse(provider, await readLimitedResponse(response, 2 * 1024 * 1024))
    if (!result.length) return fail('搜索服务未返回可用结果', 502)
    return Response.json({ provider, results: result }, { headers: { 'Cache-Control': 'private,no-store', 'X-Content-Type-Options': 'nosniff' } })
  } catch (error) {
    if (error instanceof SyntaxError) return fail('搜索请求格式无效', 400)
    return fail('无法连接搜索服务', 502)
  }
}
