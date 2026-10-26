import { publicOrigin } from '@/lib/utils'
import { isObject } from '@/src/sync-core'
import { reportCategories } from '@/src/report'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

const clean = (value: unknown, max: number): string => typeof value === 'string' ? value.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '').trim().slice(0, max) : ''

/**
 * 与安卓 AiContentReportRepository 相同的举报合同；AI_CONTENT_REPORT_URL 只在服务端读取，且必须是 HTTPS。
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
    let endpoint: URL
    try { endpoint = new URL(process.env.AI_CONTENT_REPORT_URL?.trim() ?? '') } catch { return fail('服务端尚未配置举报地址', 503) }
    if (endpoint.protocol !== 'https:') return fail('服务端尚未配置举报地址', 503)
    const text = await request.text()
    if (text.length > 32 * 1024) return fail('举报内容过大', 413)
    const input: unknown = JSON.parse(text)
    if (!isObject(input) || typeof input.messageId !== 'string' || !reportCategories.some(([id]) => id === input.category)) return fail('举报格式无效', 400)
    const payload = {
      reportId: crypto.randomUUID(), messageId: clean(input.messageId, 200), category: input.category,
      details: clean(input.details, 500), messageText: clean(input.messageText, 4000),
      imageCount: Number.isSafeInteger(input.imageCount) ? Math.max(0, Math.min(100, Number(input.imageCount))) : 0,
      isImageGeneration: input.isImageGeneration === true,
      modelName: clean(input.modelName, 200) || null, providerName: clean(input.providerName, 200) || null,
      appVersion: 'web', platform: 'web', createdAtEpochMillis: Date.now(),
    }
    const response = await fetch(endpoint, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'User-Agent': 'EveryTalk/web Web', 'X-EveryTalk-Report-Version': '1' },
      body: JSON.stringify(payload), cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(15_000),
    })
    await response.body?.cancel().catch(() => undefined)
    if (!response.ok) return fail(`举报服务返回 HTTP ${response.status}`, 502)
    return Response.json({ ok: true }, { headers: { 'Cache-Control': 'no-store' } })
  } catch (error) {
    if (error instanceof SyntaxError) return fail('举报格式无效', 400)
    return fail('无法连接举报服务', 502)
  }
}
