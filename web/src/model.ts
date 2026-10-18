import { isObject } from './sync-core'
import { authenticatedFetch } from './auth'
import type { AuthSession, JsonObject, StoredRecord } from './types'

export interface ModelResult { text: string; reasoning: string; usage?: JsonObject }
export type ModelProtocol = 'openai' | 'anthropic' | 'gemini'
export interface ImageResult { text: string; images: string[] }
export type ImageProtocol = 'openai' | 'gemini' | 'qwen' | 'modal'

/**
 * 浏览器已经读取的图片输入。
 * dataUrl 只用于本次模型请求，不写入同步记录；原始文件仍通过附件同步链路保存。
 */
export interface ModelAttachment { mime: string; dataUrl: string }

export function modelProtocol(config: JsonObject): ModelProtocol {
  const parameters = isObject(config.modelParameters) ? config.modelParameters : {}
  const hint = [parameters.protocolOverride, config.provider, config.model, config.address].map(value => String(value ?? '')).join(' ').toLowerCase()
  return /anthropic|claude/.test(hint) ? 'anthropic' : /gemini|google/.test(hint) ? 'gemini' : 'openai'
}

/**
 * 识别 Android 已有的图片供应商分支。
 * 普通 OpenAI 兼容图片接口继续走 openai；只有明确的编辑模型或 Modal
 * 模型名才进入专用分支，避免把普通聊天模型误当成图片服务。
 */
export function imageProtocol(config: JsonObject): ImageProtocol {
  const hint = [config.provider, config.model, config.address].map(value => String(value ?? '')).join(' ').toLowerCase()
  if (/modal|z[-_ ]?image|image[-_ ]?turbo/.test(hint)) return 'modal'
  if (/qwen.*image.*edit|image.*edit.*qwen|qwen[-_ ]?image[-_ ]?edit/.test(hint)) return 'qwen'
  if (/gemini|google/.test(hint)) return 'gemini'
  return 'openai'
}

/**
 * 按 Android 的 Modal 尺寸合同把配置换成安全的像素宽高。
 * 显式的 `宽x高` 仍然保留，2K/HD 和宽高比组合则使用 Android 同样的映射。
 */
export function modalImageDimensions(imageSize: unknown, aspectRatio: unknown): { width: number; height: number } {
  const sizeText = typeof imageSize === 'string' ? imageSize.trim().toLowerCase() : ''
  const ratioText = typeof aspectRatio === 'string' ? aspectRatio.trim().toUpperCase() : ''
  const explicit = /^(\d{2,4})x(\d{2,4})$/.exec(sizeText)
  const supportedRatios = new Set(['1:1', '16:9', '9:16', '4:3'])
  const ratioInAspect = /^(2K|4K|HD|1K)\s*(1:1|16:9|9:16|4:3)?$/.exec(ratioText)
  const ratio = ratioInAspect?.[2] ?? (supportedRatios.has(ratioText) ? ratioText : '1:1')
  const selectedSize = (ratioInAspect?.[1] ?? sizeText).toLowerCase()
  const size = selectedSize === 'hd' || selectedSize === '1k' || sizeText === '1024x1024' ? 'hd' : '2k'
  let width: number
  let height: number
  if (explicit && !ratioInAspect && !supportedRatios.has(ratioText)) {
    width = Number(explicit[1]); height = Number(explicit[2])
  } else {
    const base = size === 'hd' ? 1024 : 2048
    const dimensions: Record<string, [number, number]> = {
      '1:1': [base, base], '16:9': [base, Math.round(base * 9 / 16)],
      '9:16': [Math.round(base * 9 / 16), base], '4:3': [base, Math.round(base * 3 / 4)],
    }
    ;[width, height] = dimensions[ratio] ?? dimensions['1:1']
    if (size === 'hd' && ratio === '4:3') { width = 1024; height = 1024 }
  }
  return { width: Math.max(256, Math.min(2048, Math.floor(width / 8) * 8)), height: Math.max(256, Math.min(2048, Math.floor(height / 8) * 8)) }
}

/** 与安卓 LlmEndpointResolver 对齐：保留查询参数，识别已填写的完整接口地址。 */
export function resolveModelEndpoint(protocol: ModelProtocol, rawAddress: string, model: string): string {
  const raw = rawAddress.trim()
  if (!raw) return ''
  const exact = raw.endsWith('#')
  const withoutMarker = exact ? raw.slice(0, -1).trim() : raw
  const queryIndex = withoutMarker.indexOf('?')
  const rawPath = queryIndex >= 0 ? withoutMarker.slice(0, queryIndex) : withoutMarker
  const query = queryIndex >= 0 ? withoutMarker.slice(queryIndex) : ''
  const address = (rawPath.startsWith('http://') || rawPath.startsWith('https://') ? rawPath : `https://${rawPath}`).replace(/\/+$/, '')
  if (exact) return address + query
  const knownBase = address.toLowerCase().includes('open.bigmodel.cn') && new URL(address).pathname === '/'
    ? `${address}/api/paas/v4` : address
  const lower = knownBase.toLowerCase()
  const geminiIndex = lower.lastIndexOf('/v1beta/models/')
  const geminiOperation = geminiIndex >= 0 ? lower.slice(geminiIndex).split(':')[1] : ''
  const knownSuffixes = ['/v1/chat/completions', '/chat/completions', '/v1/responses', '/responses', '/v1/completions', '/completions', '/v1/messages', '/messages', '/v1beta/models', '/v1beta']
  const base = geminiOperation === 'streamgeneratecontent' || geminiOperation === 'generatecontent'
    ? knownBase.slice(0, geminiIndex).replace(/\/+$/, '')
    : (knownSuffixes.find(suffix => lower.endsWith(suffix)) ? knownBase.slice(0, -knownSuffixes.find(suffix => lower.endsWith(suffix))!.length) : knownBase).replace(/\/+$/, '')
  let endpoint: string
  if (protocol === 'gemini') {
    const encodedModel = encodeURIComponent(model.trim().replace(/^models\//, ''))
    endpoint = lower.endsWith('/v1beta/models') ? `${base}/${encodedModel}:streamGenerateContent` : `${base}${lower.endsWith('/v1beta') ? '' : '/v1beta'}/models/${encodedModel}:streamGenerateContent`
  } else {
    const path = protocol === 'anthropic' ? 'messages' : 'chat/completions'
    const hasVersion = /^v\d+$/.test(base.slice(base.lastIndexOf('/') + 1))
    endpoint = `${base}${hasVersion ? '' : '/v1'}/${path}`
  }
  if (protocol === 'gemini' && !/[?&]alt=/.test(query)) return `${endpoint}${query ? `${query}&alt=sse` : '?alt=sse'}`
  return endpoint + query
}

/** 复用安卓地址与协议覆盖值；已包含完整路径时不重复追加。 */
export function modelRequest(config: JsonObject, messages: StoredRecord[], system: string, attachments: ModelAttachment[] = []): { target: string; protocol: ModelProtocol; apiKey: string; body: JsonObject } {
  const protocol = modelProtocol(config)
  const model = String(config.model ?? '')
  const address = String(config.address ?? '')
  if (!address || !model || typeof config.apiKey !== 'string' || !config.apiKey.trim()) throw new Error('请先配置模型地址、模型名和 API Key')
  const history = messages.filter(m => !m.deleted && !m.payload.isError).map(m => ({ role: m.payload.sender === 'User' ? 'user' : 'assistant', content: String(m.payload.text ?? '') }))
  const images = attachments.filter(item => /^image\/[a-z0-9.+-]+$/i.test(item.mime) && /^data:image\/[a-z0-9.+-]+;base64,[a-z0-9+/=]+$/i.test(item.dataUrl))
  const lastUser = history.findLastIndex(item => item.role === 'user')
  const openAiHistory = history.map((item, index) => index === lastUser && images.length > 0
    ? { ...item, content: [{ type: 'text', text: item.content }, ...images.map(image => ({ type: 'image_url', image_url: { url: image.dataUrl } }))] }
    : item)
  const anthropicHistory = history.map((item, index) => index === lastUser && images.length > 0
    ? { ...item, content: [{ type: 'text', text: item.content }, ...images.map(image => { const [, value] = image.dataUrl.split(',', 2); return { type: 'image', source: { type: 'base64', media_type: image.mime, data: value } } })] }
    : item)
  let target: string, body: JsonObject
  const outputLimit = typeof config.outputLimit === 'number' ? config.outputLimit : undefined
  if (protocol === 'anthropic') {
    target = resolveModelEndpoint(protocol, address, model)
    body = { model, stream: true, messages: anthropicHistory, max_tokens: outputLimit ?? 4096, ...(system ? { system } : {}) }
  } else if (protocol === 'gemini') {
    target = resolveModelEndpoint(protocol, address, model)
    body = { contents: history.map((m, index) => ({ role: m.role === 'assistant' ? 'model' : 'user', parts: [{ text: m.content }, ...(index === lastUser ? images.map(image => { const [, data] = image.dataUrl.split(',', 2); return { inline_data: { mime_type: image.mime, data } } }) : [])] })),
      ...(system ? { systemInstruction: { parts: [{ text: system }] } } : {}), generationConfig: { ...(outputLimit ? { maxOutputTokens: outputLimit } : {}), ...(typeof config.temperature === 'number' ? { temperature: config.temperature } : {}) } }
  } else {
    target = resolveModelEndpoint(protocol, address, model)
    body = { model, stream: true, messages: [...(system ? [{ role: 'system', content: system }] : []), ...openAiHistory], ...(outputLimit ? { max_tokens: outputLimit } : {}) }
  }
  if (protocol !== 'gemini') {
    if (typeof config.temperature === 'number') body.temperature = config.temperature
    if (typeof config.topP === 'number') body.top_p = config.topP
  }
  return { target, protocol, apiKey: config.apiKey, body }
}

/** 生成图片只使用 Android 已有的 Gemini 和 OpenAI 兼容 JSON 合同。 */
export function imageRequest(config: JsonObject, prompt: string, attachments: ModelAttachment[] = []): { target: string; protocol: ImageProtocol; apiKey: string; body: JsonObject } {
  const protocol = imageProtocol(config)
  const model = String(config.model ?? ''), address = String(config.address ?? '')
  if (!address || !model || typeof config.apiKey !== 'string' || !config.apiKey.trim()) throw new Error('请先配置图片模型地址、模型名和 API Key')
  const exactAddress = address.trim().replace(/#$/, '').replace(/\/+$/, '')
  if (protocol === 'modal') {
    const body: JsonObject = { prompt }
    if (typeof config.imageSize === 'string' && config.imageSize.trim()) body.imageSize = config.imageSize.trim()
    if (typeof config.aspectRatio === 'string' && config.aspectRatio.trim()) body.aspectRatio = config.aspectRatio.trim()
    if (Number.isSafeInteger(config.numInferenceSteps)) body.numInferenceSteps = config.numInferenceSteps
    if (typeof config.guidanceScale === 'number') body.guidanceScale = config.guidanceScale
    return { protocol, apiKey: config.apiKey, target: exactAddress, body }
  }
  if (protocol === 'qwen') {
    const image = attachments[0]
    if (!image) throw new Error('Qwen 图像编辑需要至少一张参考图片')
    const [, data] = image.dataUrl.split(',', 2)
    return { protocol, apiKey: config.apiKey, target: exactAddress, body: {
      image_base64: data, prompt, ...(Number.isSafeInteger(config.numInferenceSteps) ? { num_inference_steps: config.numInferenceSteps } : {}),
      ...(typeof config.guidanceScale === 'number' ? { guidance_scale: config.guidanceScale } : {}),
    } }
  }
  if (protocol === 'gemini') {
    const base = exactAddress.replace(/\/v1beta\/models\/.*$/i, '').replace(/\/v1\/.*$/i, '')
    const parts: JsonObject[] = [{ text: prompt }, ...attachments.map(image => { const [, data] = image.dataUrl.split(',', 2); return { inlineData: { mimeType: image.mime, data } } })]
    const generationConfig: JsonObject = { responseModalities: ['IMAGE', 'TEXT'] }
    const aspectRatio = typeof config.aspectRatio === 'string' && /^(?:1:1|2:3|3:2|3:4|4:3|4:5|5:4|9:16|16:9|21:9)$/.test(config.aspectRatio) ? config.aspectRatio : undefined
    const configuredImageSize = config.geminiImageSize ?? config.imageSize
    const imageSize = typeof configuredImageSize === 'string' && /^(?:1K|2K|4K)$/i.test(configuredImageSize) && /gemini-3.*image/i.test(model) ? configuredImageSize.toUpperCase() : undefined
    if (aspectRatio || imageSize) generationConfig.imageConfig = { ...(aspectRatio ? { aspectRatio } : {}), ...(imageSize ? { imageSize } : {}) }
    return { protocol, apiKey: config.apiKey, target: `${base}/v1beta/models/${encodeURIComponent(model.replace(/^models\//, ''))}:generateContent`, body: { contents: [{ role: 'user', parts }], generationConfig } }
  }
  if (attachments.length > 1) throw new Error('OpenAI 图像编辑目前只支持一张参考图片')
  const edit = attachments.length === 1
  const target = edit
    ? /\/images\/edits$/i.test(exactAddress) ? exactAddress : `${exactAddress.replace(/\/v1(?:\/images\/generations)?$/i, '')}/v1/images/edits`
    : /\/images\/generations$/i.test(exactAddress) ? exactAddress : `${exactAddress.replace(/\/v1$/i, '')}/v1/images/generations`
  const providerHint = [config.provider, config.model, config.address].map(value => String(value ?? '')).join(' ').toLowerCase()
  const siliconFlow = /siliconflow|硅基流动/.test(providerHint)
  const seedream = /seedream|doubao|豆包|即梦/.test(providerHint)
  if (attachments.length && !seedream && !edit) throw new Error('当前图片接口不支持参考图编辑')
  const body: JsonObject = { model, prompt, ...(edit ? { image: attachments[0]?.dataUrl } : {}) }
  if (typeof config.imageSize === 'string' && config.imageSize.trim()) body[siliconFlow ? 'image_size' : 'size'] = config.imageSize.trim()
  if (typeof config.batchSize === 'number') body[siliconFlow ? 'batch_size' : 'n'] = config.batchSize
  if (typeof config.numInferenceSteps === 'number') body.num_inference_steps = config.numInferenceSteps
  if (typeof config.guidanceScale === 'number') body.guidance_scale = config.guidanceScale
  if (typeof config.quality === 'string' && config.quality.trim()) body.quality = config.quality.trim()
  if (seedream) { body.response_format = 'url'; body.watermark = config.watermark === true }
  if (seedream && attachments.length) body.image = attachments.map(image => image.dataUrl)
  return { protocol, apiKey: config.apiKey, target, body }
}

/** 图片接口返回已落地的数据 URL；正文和图片都在成功后才进入同步记录。 */
export async function generateImage(session: AuthSession, request: ReturnType<typeof imageRequest>, signal: AbortSignal, refreshSession?: () => Promise<AuthSession>): Promise<ImageResult> {
  const response = await authenticatedFetch(session, '/api/image', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request), signal, cache: 'no-store' }, refreshSession)
  const value: unknown = await response.json().catch(() => undefined)
  if (!response.ok || !isObject(value)) throw new Error(isObject(value) && typeof value.error === 'string' ? value.error : `图片生成失败（HTTP ${response.status}）`)
  const images = Array.isArray(value.images) ? value.images.filter((item): item is string => typeof item === 'string' && /^data:image\/[a-z0-9.+-]+;base64,[a-z0-9+/=]+$/i.test(item)) : []
  if (!images.length) throw new Error('图片服务没有返回有效图片')
  return { images, text: typeof value.text === 'string' ? value.text : '' }
}

/** SSE 支持跨网络块、CRLF 和最后一帧；草稿仅在内存中，成功定稿后才能入同步队列。 */
export async function generate(session: AuthSession, request: ReturnType<typeof modelRequest>, signal: AbortSignal, onDelta: (result: ModelResult) => void, refreshSession?: () => Promise<AuthSession>): Promise<ModelResult> {
  const response = await authenticatedFetch(session, '/api/model', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request), signal, cache: 'no-store' }, refreshSession)
  if (!response.ok) throw new Error(`模型请求失败（HTTP ${response.status}），请检查密钥、余额和模型名称`)
  const result: ModelResult = { text: '', reasoning: '' }
  function accept(value: unknown): void {
    if (!isObject(value)) return
    if (value.error || value.type === 'error') throw new Error('模型服务返回错误，本次草稿没有同步')
    if (Array.isArray(value.choices)) {
      for (const choice of value.choices) if (isObject(choice)) {
        const delta = isObject(choice.delta) ? choice.delta : isObject(choice.message) ? choice.message : {}
        if (typeof delta.content === 'string') result.text += delta.content
        if (typeof delta.reasoning_content === 'string') result.reasoning += delta.reasoning_content
      }
    }
    if (isObject(value.delta)) {
      if (typeof value.delta.text === 'string') result.text += value.delta.text
      if (typeof value.delta.thinking === 'string') result.reasoning += value.delta.thinking
    }
    if (Array.isArray(value.content)) for (const part of value.content) if (isObject(part) && typeof part.text === 'string') result.text += part.text
    if (Array.isArray(value.candidates)) for (const candidate of value.candidates) if (isObject(candidate) && isObject(candidate.content) && Array.isArray(candidate.content.parts)) {
      for (const part of candidate.content.parts) if (isObject(part) && typeof part.text === 'string') { if (part.thought) result.reasoning += part.text; else result.text += part.text }
    }
    if (isObject(value.usage)) result.usage = value.usage
    onDelta({ ...result })
  }
  if (!response.headers.get('content-type')?.includes('text/event-stream')) { accept(await response.json()); if (!result.text) throw new Error('模型没有返回正文'); return result }
  const reader = response.body?.getReader()
  if (!reader) throw new Error('模型响应为空')
  const decoder = new TextDecoder()
  let buffer = '', received = 0
  let ended = false
  const frame = (raw: string) => {
    const data = raw.split('\n').filter(l => l.startsWith('data:')).map(l => l.slice(5).trimStart()).join('\n')
    if (!data) return
    if (data.trim() === '[DONE]') { ended = true; return }
    const parsed: unknown = JSON.parse(data)
    if (isObject(parsed) && (parsed.type === 'message_stop' || (Array.isArray(parsed.candidates) && parsed.candidates.some(c => isObject(c) && c.finishReason)))) ended = true
    accept(parsed)
  }
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) { buffer += decoder.decode(); if (buffer.trim()) frame(buffer); break }
      received += value.length
      if (received > 8 * 1024 * 1024) throw new Error('模型回复过大，已停止接收')
      buffer += decoder.decode(value, { stream: true }).replace(/\r/g, '')
      let end: number
      while ((end = buffer.indexOf('\n\n')) >= 0) { frame(buffer.slice(0, end)); buffer = buffer.slice(end + 2) }
    }
  } finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
  if (!ended) throw new Error('模型连接提前中断，未将不完整回复上传')
  if (!result.text.trim()) throw new Error('模型没有返回正文')
  return result
}
