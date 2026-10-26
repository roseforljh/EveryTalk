import { publicOrigin } from '@/lib/utils'
import { isObject } from '@/src/sync-core'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

const MAX_FETCH_RESPONSE_BYTES = 1024 * 1024

/**
 * 与安卓 WebFetchService 相同：通过 Defuddle 服务读取网页正文。
 * WEBFETCH_BASE_URL / WEBFETCH_API_KEY 只在服务端读取，浏览器拿不到密钥。
 */
export async function POST(request: Request): Promise<Response> {
  const fail = (message: string, status: number) => Response.json({ error: message }, { status, headers: { 'Cache-Control': 'no-store' } })
  if (request.headers.get('origin') !== publicOrigin(request)) return fail('请求来源无效', 403)
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

    const text = await request.text()
    if (text.length > 16 * 1024) return fail('网页读取请求过大', 413)
    const input: unknown = JSON.parse(text)
    if (!isObject(input) || typeof input.url !== 'string') return fail('网页读取请求格式无效', 400)
    const url = input.url.trim()
    const maxChars = Number.isSafeInteger(input.maxChars) && Number(input.maxChars) > 0 ? Math.min(Number(input.maxChars), 200_000) : 12_000
    const result = (body: Record<string, unknown>) => Response.json({ requestedUrl: url, maxContentChars: maxChars, ...body }, { headers: { 'Cache-Control': 'private,no-store' } })
    const base = process.env.WEBFETCH_BASE_URL?.trim().replace(/\/+$/, '') ?? ''
    const apiKey = process.env.WEBFETCH_API_KEY?.trim() ?? ''
    if (!base) return result({ ok: false, truncated: false, error: '未配置 WEBFETCH_BASE_URL' })
    if (!apiKey) return result({ ok: false, truncated: false, error: '未配置 WEBFETCH_API_KEY' })
    let valid = false
    try { const parsed = new URL(url); valid = (parsed.protocol === 'http:' || parsed.protocol === 'https:') && Boolean(parsed.hostname) } catch { /* 无效地址 */ }
    if (!valid || url.length > 4096) return result({ ok: false, truncated: false, error: 'URL 无效，仅支持 http/https 网页地址' })

    const response = await fetch(`${base}/api/parse`, {
      method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'x-api-key': apiKey },
      body: JSON.stringify({ url, defuddleOptions: { markdown: true } }), cache: 'no-store', signal: AbortSignal.any([request.signal, AbortSignal.timeout(30_000)]),
    })
    const declared = Number(response.headers.get('content-length') ?? 0)
    if (declared > MAX_FETCH_RESPONSE_BYTES) { await response.body?.cancel(); return result({ ok: false, truncated: false, statusCode: response.status, error: 'WebFetch 返回内容过大' }) }
    const raw = await response.text()
    if (raw.length > MAX_FETCH_RESPONSE_BYTES) return result({ ok: false, truncated: false, statusCode: response.status, error: 'WebFetch 返回内容过大' })
    const value: unknown = (() => { try { return JSON.parse(raw) } catch { return undefined } })()
    if (!response.ok) {
      const detail = isObject(value) && typeof value.error === 'string' && value.error ? `: ${value.error}` : ''
      return result({ ok: false, truncated: false, statusCode: response.status, error: `WebFetch 返回 HTTP ${response.status}${detail}` })
    }
    const parsed = isObject(value) && isObject(value.result) ? value.result : undefined
    if (!parsed) return result({ ok: false, truncated: false, statusCode: response.status, error: 'WebFetch 返回格式无效' })
    const content = [parsed.contentMarkdown, parsed.content].find((item): item is string => typeof item === 'string' && item.trim() !== '')?.replaceAll('�', '')
    if (!content) return result({ ok: false, truncated: false, statusCode: response.status, error: 'WebFetch 返回空内容' })
    const truncated = content.length > maxChars
    const finalContent = truncated ? content.slice(0, maxChars).trimEnd() : content
    const heading = finalContent.split('\n').find(line => line.trim())
    const title = typeof parsed.title === 'string' && parsed.title.trim() ? parsed.title : heading?.startsWith('# ') ? heading.slice(2).trim() : undefined
    return result({ ok: true, finalUrl: url, ...(title ? { title } : {}), content: finalContent, truncated, ...(truncated ? { truncationReason: 'content_truncated' } : {}), statusCode: response.status })
  } catch (error) {
    if (error instanceof SyntaxError) return fail('网页读取请求格式无效', 400)
    return fail(`WebFetch 请求失败: ${error instanceof Error ? error.message : '未知错误'}`, 502)
  }
}
