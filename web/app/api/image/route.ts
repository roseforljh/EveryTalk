import { publicOrigin } from '@/lib/utils'
import { modelUpstream, publicImageData } from '@/lib/model-upstream'
import { isObject } from '@/src/sync-core'
import { modalImageDimensions } from '@/src/model'
import type { ImageProtocol } from '@/src/model'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

const MAX_REQUEST_BYTES = 4 * 1024 * 1024
const MAX_RESPONSE_BYTES = 64 * 1024 * 1024
const MODAL_TIMEOUT_MS = 1_800_000
const isProtocol = (value: unknown): value is ImageProtocol => value === 'openai' || value === 'gemini' || value === 'qwen' || value === 'modal'

async function readAtMost(response: Response, maxBytes: number): Promise<string> {
  const reader = response.body?.getReader()
  if (!reader) throw new Error('图片服务响应为空')
  const chunks: Uint8Array[] = []; let size = 0
  try {
    for (;;) {
      const part = await reader.read()
      if (part.done) break
      size += part.value.byteLength
      if (size > maxBytes) { await reader.cancel(); throw new Error('图片服务响应过大') }
      chunks.push(part.value)
    }
  } finally { reader.releaseLock() }
  const bytes = new Uint8Array(size); let offset = 0
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength }
  return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
}

async function imageData(source: unknown, signal: AbortSignal): Promise<string | undefined> {
  if (typeof source !== 'string') return undefined
  if (/^data:image\/[a-z0-9.+-]+;base64,[a-z0-9+/=]+$/i.test(source)) return source
  if (/^https:\/\//i.test(source)) return publicImageData(source, signal)
  return undefined
}

/** 把客户端校验过的单张 data URL 转成 OpenAI images/edits 所需的 multipart 字节。 */
function openAiEditBody(body: Record<string, unknown>): { bytes: Buffer; contentType: string } {
  const model = typeof body.model === 'string' ? body.model.trim() : ''
  const prompt = typeof body.prompt === 'string' ? body.prompt.trim() : ''
  const source = typeof body.image === 'string' ? body.image : ''
  const match = /^data:(image\/[a-z0-9.+-]+);base64,([a-z0-9+/=]+)$/i.exec(source)
  if (!model || model.length > 200 || !prompt || prompt.length > 2000 || !match) throw new Error('OpenAI 图片编辑请求无效')
  const image = Buffer.from(match[2], 'base64')
  if (!image.byteLength || image.byteLength > 3 * 1024 * 1024) throw new Error('参考图片超过 3 MiB 限制')
  const boundary = `----EveryTalkImage${crypto.randomUUID().replace(/-/g, '')}`
  const chunks: Buffer[] = []
  const field = (name: string, value: string): void => {
    chunks.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${value}\r\n`, 'utf8'))
  }
  field('model', model)
  field('prompt', prompt)
  if (typeof body.size === 'string' && body.size.length <= 32) field('size', body.size)
  if (typeof body.quality === 'string' && body.quality.length <= 32) field('quality', body.quality)
  if (typeof body.n === 'number' && Number.isSafeInteger(body.n)) field('n', String(body.n))
  chunks.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="image"; filename="reference.${match[1].split('/')[1].replace(/[^a-z0-9]/gi, '') || 'png'}"\r\nContent-Type: ${match[1]}\r\n\r\n`, 'utf8'))
  chunks.push(image, Buffer.from(`\r\n--${boundary}--\r\n`, 'utf8'))
  return { bytes: Buffer.concat(chunks), contentType: `multipart/form-data; boundary=${boundary}` }
}

/** 统一 Android 图片合同的 Gemini inlineData、OpenAI data.url 和 b64_json 返回值。 */
async function parseImages(protocol: Exclude<ImageProtocol, 'modal'>, raw: string, signal: AbortSignal): Promise<{ images: string[]; text?: string }> {
  const value: unknown = JSON.parse(raw)
  if (!isObject(value)) throw new Error('图片服务返回格式无效')
  const images: string[] = []; let text = ''
  if (protocol === 'gemini') {
    const candidates = Array.isArray(value.candidates) ? value.candidates : []
    for (const candidate of candidates) if (isObject(candidate) && isObject(candidate.content) && Array.isArray(candidate.content.parts)) {
      for (const part of candidate.content.parts) if (isObject(part)) {
        if (typeof part.text === 'string') text += part.text
        const inline = isObject(part.inlineData) ? part.inlineData : isObject(part.inline_data) ? part.inline_data : undefined
        if (inline) {
          const data = typeof inline.data === 'string' ? inline.data : ''
          const mime = typeof inline.mimeType === 'string' ? inline.mimeType : typeof inline.mime_type === 'string' ? inline.mime_type : 'image/png'
          if (/^image\/[a-z0-9.+-]+$/i.test(mime) && /^[a-z0-9+/=]+$/i.test(data)) images.push(`data:${mime};base64,${data}`)
        }
      }
    }
  } else if (protocol === 'openai') {
    const items = Array.isArray(value.data) ? value.data : Array.isArray(value.images) ? value.images : []
    for (const item of items) if (isObject(item)) {
      const b64 = typeof item.b64_json === 'string' ? item.b64_json : undefined
      if (b64 && /^[a-z0-9+/=]+$/i.test(b64)) images.push(`data:image/png;base64,${b64}`)
      else {
        const converted = await imageData(item.url, signal)
        if (converted) images.push(converted)
      }
    }
  } else {
    const candidate = value.result ?? value.image_base64 ?? value.image ?? value.data
    const values = Array.isArray(candidate) ? candidate : [candidate]
    for (const item of values) if (typeof item === 'string') {
      if (/^data:image\/[a-z0-9.+-]+;base64,[a-z0-9+/=]+$/i.test(item)) images.push(item)
      else if (/^[a-z0-9+/=]+$/i.test(item)) images.push(`data:image/png;base64,${item}`)
    }
    if (value.status !== undefined && value.status !== 'success') throw new Error('Qwen 图片服务返回失败')
  }
  if (!images.length) throw new Error('图片服务没有返回可保存的图片')
  return { images, ...(text ? { text } : {}) }
}

/** 登录用户的图片生成中转；只保留图片字节，不保存 API Key、供应商 URL 或请求正文。 */
export async function POST(request: Request): Promise<Response> {
  const fail = (message: string, status: number) => Response.json({ error: message }, { status, headers: { 'Cache-Control': 'no-store' } })
  if (request.headers.get('origin') !== publicOrigin(request)) return fail('请求来源无效', 403)
  const authorization = request.headers.get('authorization')
  if (!authorization?.startsWith('Bearer ') || authorization.length > 17000) return fail('请先登录', 401)
  try {
    const auth = await fetch(`${process.env.NEXT_PUBLIC_SUPABASE_URL}/auth/v1/user`, { headers: { authorization, apikey: process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY ?? '' }, cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(15_000) })
    if (!auth.ok) return fail(auth.status === 401 || auth.status === 403 ? '登录已失效' : '账号服务暂时不可用', auth.status === 401 || auth.status === 403 ? 401 : 503)
    const body = await request.arrayBuffer()
    if (body.byteLength > MAX_REQUEST_BYTES) return fail('图片请求过大', 413)
    const input: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(body))
    if (!isObject(input) || !isProtocol(input.protocol) || typeof input.target !== 'string' || typeof input.apiKey !== 'string' || input.apiKey.length > 8192 || /[\r\n]/.test(input.apiKey) || !isObject(input.body)) return fail('图片请求格式无效', 400)
    // 内置模型复用安卓同名服务端环境变量，严格覆盖客户端传来的协议、地址和密钥。
    if (input.builtinModel !== undefined) {
      if (input.builtinModel === 'z-image-turbo-modal') {
        input.protocol = 'modal'; input.target = process.env.VITE_API_URLS ?? ''
      } else if (input.builtinModel === 'qwen-image-edit-modal') {
        input.protocol = 'qwen'; input.target = process.env.QWEN_EDIT_API_URLS ?? ''; input.apiKey = process.env.QWEN_EDIT_API_SECRET ?? ''
      } else if (input.builtinModel === 'Kwai-Kolors/Kolors') {
        input.protocol = 'openai'; input.target = process.env.SILICONFLOW_IMAGE_API_URL ?? 'https://api.siliconflow.cn/v1/images/generations'
        input.apiKey = process.env.SILICONFLOW_API_KEY ?? ''; input.body.model = 'Kwai-Kolors/Kolors'
        if (typeof input.body.size === 'string') { input.body.image_size = input.body.size; delete input.body.size }
        if (input.body.image) return fail('Kolors 不支持参考图编辑，请选择 Qwen Image Edit', 422)
        if (!input.apiKey) return fail('服务端尚未配置默认 Kolors 服务', 503)
      } else return fail('内置模型无效', 400)
      if (!String(input.target).split(',').some(item => item.trim())) return fail('服务端尚未配置此默认图片服务', 503)
    }
    // 修改过的未知输入必须重新缩窄类型，后面的请求只能使用这四种图片协议。
    if (!isProtocol(input.protocol) || typeof input.target !== 'string' || typeof input.apiKey !== 'string') return fail('图片配置无效', 400)
    if (input.protocol === 'modal' || input.protocol === 'qwen') {
      // 与安卓一致：多个 Modal 地址按顺序重试，单次最长等待 30 分钟（冷启动和长任务）。
      const urls = input.target.split(',').map(item => item.trim()).filter(Boolean).slice(0, 8)
      const protocol = input.protocol, apiKey = input.apiKey, payload = input.body
      const prompt = typeof payload.prompt === 'string' ? payload.prompt : ''
      if (!prompt || prompt.length > 2000) return fail('图片提示词无效', 422)
      const run = async (): Promise<Record<string, unknown>> => {
        let lastError = '图片服务没有可用地址'
        for (const url of urls) {
          request.signal.throwIfAborted()
          try {
            const target = new URL(url)
            if (protocol === 'modal') {
              // Modal Z-Image-Turbo 使用 GET 查询参数并直接返回图片字节，
              // 复用固定公网地址和大小校验，避免把任意 URL 代理给浏览器。
              const { width, height } = modalImageDimensions(payload.imageSize, payload.aspectRatio)
              target.searchParams.set('prompt', prompt)
              target.searchParams.set('width', String(width)); target.searchParams.set('height', String(height))
              target.searchParams.set('steps', String(Number.isSafeInteger(payload.numInferenceSteps) ? payload.numInferenceSteps : 4))
              return { images: [await publicImageData(target.toString(), request.signal, MAX_RESPONSE_BYTES, MODAL_TIMEOUT_MS)] }
            }
            const upstream = await modelUpstream(target, { 'x-api-key': apiKey }, JSON.stringify(payload), request.signal, 'application/json', MODAL_TIMEOUT_MS)
            if (!upstream.ok) { await upstream.body?.cancel().catch(() => undefined); lastError = `图片供应商请求失败（HTTP ${upstream.status}）`; continue }
            return await parseImages('qwen', await readAtMost(upstream, MAX_RESPONSE_BYTES), request.signal)
          } catch (error) {
            if (request.signal.aborted) throw error
            lastError = error instanceof Error ? error.message : '无法连接图片服务'
          }
        }
        return { error: lastError }
      }
      // Cloudflare 代理 100 秒无数据会断开；JSON 前置空白合法，每 15 秒写一个空格保活。
      const encoder = new TextEncoder()
      let timer: ReturnType<typeof setInterval> | undefined
      const stream = new ReadableStream<Uint8Array>({ start(controller) {
        timer = setInterval(() => { try { controller.enqueue(encoder.encode(' ')) } catch { clearInterval(timer) } }, 15_000)
        // start 不等待生成结束，保活空格才能在生成期间立即发出。
        void (async () => {
          try { controller.enqueue(encoder.encode(JSON.stringify(await run()))) }
          catch (error) { try { controller.enqueue(encoder.encode(JSON.stringify({ error: error instanceof Error ? error.message : '图片生成已取消' }))) } catch { /* 浏览器已断开 */ } }
          finally { clearInterval(timer); try { controller.close() } catch { /* 已取消 */ } }
        })()
      }, cancel() { clearInterval(timer) } })
      return new Response(stream, { headers: { 'Content-Type': 'application/json', 'Cache-Control': 'private,no-store', 'X-Content-Type-Options': 'nosniff', 'X-Accel-Buffering': 'no' } })
    }
    const target = new URL(input.target)
    const headers: Record<string, string> = input.protocol === 'gemini' ? { 'x-goog-api-key': input.apiKey } : { Authorization: `Bearer ${input.apiKey}` }
    const edit = input.protocol === 'openai' && typeof input.body.image === 'string'
    const multipart = edit ? openAiEditBody(input.body) : undefined
    const upstream = await modelUpstream(target, headers, multipart?.bytes ?? JSON.stringify(input.body), AbortSignal.any([request.signal, AbortSignal.timeout(180_000)]), multipart?.contentType)
    if (!upstream.ok) {
      await upstream.body?.cancel().catch(() => undefined)
      return fail(`图片供应商请求失败（HTTP ${upstream.status}）`, upstream.status >= 400 && upstream.status < 600 ? upstream.status : 502)
    }
    const result = await parseImages(input.protocol, await readAtMost(upstream, MAX_RESPONSE_BYTES), request.signal)
    return Response.json(result, { headers: { 'Cache-Control': 'private,no-store', 'X-Content-Type-Options': 'nosniff' } })
  } catch (error) { return fail(error instanceof Error ? error.message : '无法连接图片服务', 502) }
}
