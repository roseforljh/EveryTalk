import { parseModelsDevCatalog, type CatalogModel } from '@/src/model-capabilities'

export const runtime = 'nodejs'
let cached: { models: CatalogModel[]; expires: number } | undefined
let pending: Promise<CatalogModel[]> | undefined

/** 所有人共享公开目录缓存；固定上游地址，不接受用户 URL 或凭据。失败时保留上次成功目录。 */
export async function GET(): Promise<Response> {
  try {
    if (!cached || cached.expires < Date.now()) {
      pending ??= (async () => {
        const response = await fetch('https://models.dev/api.json', { signal: AbortSignal.timeout(12_000), redirect: 'error', headers: { 'User-Agent': 'EveryTalk/1.0' } })
        if (!response.ok) throw new Error('models.dev 暂时不可用')
        const reader = response.body?.getReader()
        if (!reader) throw new Error('模型目录为空')
        const chunks: Uint8Array[] = []; let size = 0
        try {
          for (;;) {
            const part = await reader.read(); if (part.done) break
            size += part.value.byteLength
            if (size > 12 * 1024 * 1024) throw new Error('模型目录超过大小限制')
            chunks.push(part.value)
          }
        } finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
        const models = parseModelsDevCatalog(JSON.parse(Buffer.concat(chunks).toString('utf8')))
        if (!models.length) throw new Error('模型目录格式无效')
        cached = { models, expires: Date.now() + 3_600_000 }
        return models
      })().finally(() => { pending = undefined })
      await pending
    }
  } catch {
    if (!cached) return Response.json({ error: '模型目录暂时不可用，可手动配置后重试' }, { status: 503 })
  }
  return Response.json({ models: cached!.models, source: 'models.dev', stale: cached!.expires < Date.now() }, { headers: { 'Cache-Control': 'public, max-age=300' } })
}
