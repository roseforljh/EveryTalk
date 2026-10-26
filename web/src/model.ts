import { isObject } from './sync-core'
import { authenticatedFetch } from './auth'
import type { AuthSession, JsonObject, StoredRecord } from './types'
import { modelCapabilities, parameterProtocol } from './model-capabilities'
import { ModelToolTurn, withModelTools, type ModelTools } from './model-tools'

export interface ModelResult { text: string; reasoning: string; usage?: JsonObject }
export type ModelProtocol = 'openai' | 'responses' | 'anthropic' | 'gemini'
export interface ImageResult { text: string; images: string[] }
export type ImageProtocol = 'openai' | 'gemini' | 'qwen' | 'modal' | 'unsupported'

/**
 * 浏览器已经读取的图片输入。
 * dataUrl 只用于本次模型请求，不写入同步记录；原始文件仍通过附件同步链路保存。
 */
export interface ModelAttachment { mime: string; dataUrl: string }

export function modelProtocol(config: JsonObject): ModelProtocol {
  const protocol = parameterProtocol(config)
  return protocol === 'CODEX' ? 'responses' : protocol === 'ANTHROPIC' ? 'anthropic' : protocol === 'GEMINI' ? 'gemini' : 'openai'
}

/**
 * 识别 Android 已有的图片供应商分支。
 * 普通 OpenAI 兼容图片接口继续走 openai；只有明确的编辑模型或 Modal
 * 模型名才进入专用分支，避免把普通聊天模型误当成图片服务。
 */
export function imageProtocol(config: JsonObject): ImageProtocol {
  const model = String(config.model ?? '')
  if (['默认', 'default', 'builtin'].includes(String(config.provider ?? '').toLowerCase())) {
    if (model === 'qwen-image-edit-modal') return 'qwen'
    if (model === 'z-image-turbo-modal') return 'modal'
    if (model === 'Kwai-Kolors/Kolors') return 'openai'
  }
  const endpoint = String(config.address ?? '').trim().replace(/#$/, '').toLowerCase()
  if (endpoint.includes('modal.run')) return /qwen.*edit/i.test(model) ? 'qwen' : 'modal'
  if (/\/images\/(generations|edits)(?:[/?]|$)/.test(endpoint)) return 'openai'
  if (/\/v1beta(?:[/?]|$)/.test(endpoint) || endpoint.includes('generativelanguage.googleapis.com')) return 'gemini'
  const protocol = parameterProtocol(config)
  return protocol === 'GEMINI' ? 'gemini' : protocol === 'OPENAI_COMPATIBLE' ? 'openai' : 'unsupported'
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
  const knownSuffixes = ['/v1/images/generations', '/images/generations', '/v1/images/edits', '/images/edits', '/v1/chat/completions', '/chat/completions', '/v1/responses', '/responses', '/v1/completions', '/completions', '/v1/messages', '/messages', '/v1beta/models', '/v1beta']
  const base = geminiOperation === 'streamgeneratecontent' || geminiOperation === 'generatecontent'
    ? knownBase.slice(0, geminiIndex).replace(/\/+$/, '')
    : (knownSuffixes.find(suffix => lower.endsWith(suffix)) ? knownBase.slice(0, -knownSuffixes.find(suffix => lower.endsWith(suffix))!.length) : knownBase).replace(/\/+$/, '')
  let endpoint: string
  if (protocol === 'gemini') {
    const encodedModel = encodeURIComponent(model.trim().replace(/^models\//, ''))
    endpoint = lower.endsWith('/v1beta/models') ? `${base}/${encodedModel}:streamGenerateContent` : `${base}${lower.endsWith('/v1beta') ? '' : '/v1beta'}/models/${encodedModel}:streamGenerateContent`
  } else {
    const path = protocol === 'anthropic' ? 'messages' : protocol === 'responses' ? 'responses' : 'chat/completions'
    const hasVersion = /^v\d+$/.test(base.slice(base.lastIndexOf('/') + 1))
    endpoint = `${base}${hasVersion ? '' : '/v1'}/${path}`
  }
  if (protocol === 'gemini' && !/[?&]alt=/.test(query)) return `${endpoint}${query ? `${query}&alt=sse` : '?alt=sse'}`
  return endpoint + query
}

/** 复用安卓地址与协议覆盖值；已包含完整路径时不重复追加。 */
export function modelRequest(config: JsonObject, messages: StoredRecord[], system: string, attachments: ModelAttachment[] = []): { target: string; protocol: ModelProtocol; apiKey: string; body: JsonObject } {
  const protocol = modelProtocol(config)
  const capability = modelCapabilities(config)
  const parameters = isObject(config.modelParameters) ? config.modelParameters : {}
  const model = String(config.model ?? '')
  // 安卓地址留空时回退到 GOOGLE_API_BASE_URL / DEFAULT_OPENAI_API_BASE_URL，两者都是公开官方地址。
  const address = String(config.address ?? '').trim() || (protocol === 'gemini' ? 'https://generativelanguage.googleapis.com' : protocol === 'anthropic' ? '' : 'https://api.openai.com')
  if (!address || !model || typeof config.apiKey !== 'string' || !config.apiKey.trim()) throw new Error('请先配置模型地址、模型名和 API Key')
  const history = messages.filter(m => !m.deleted && !m.payload.isError).map(m => ({ role: m.payload.sender === 'User' ? 'user' : 'assistant', content: String(m.payload.text ?? '') }))
  const images = attachments.filter(item => /^image\/[a-z0-9.+-]+$/i.test(item.mime) && /^data:image\/[a-z0-9.+-]+;base64,[a-z0-9+/=]+$/i.test(item.dataUrl))
  // 明确配置过输入能力时必须校验，不能悄悄丢弃不被模型支持的附件。
  if (images.length && !capability.input.includes('image') && (parameters.inputModalities || parameters.resolvedCapability)) throw new Error('当前模型未启用图片输入能力')
  const lastUser = history.findLastIndex(item => item.role === 'user')
  const openAiHistory = history.map((item, index) => index === lastUser && images.length > 0
    ? { ...item, content: [{ type: 'text', text: item.content }, ...images.map(image => ({ type: 'image_url', image_url: { url: image.dataUrl } }))] }
    : item)
  const anthropicHistory = history.map((item, index) => index === lastUser && images.length > 0
    ? { ...item, content: [{ type: 'text', text: item.content }, ...images.map(image => { const [, value] = image.dataUrl.split(',', 2); return { type: 'image', source: { type: 'base64', media_type: image.mime, data: value } } })] }
    : item)
  const target = resolveModelEndpoint(protocol, address, model)
  let body: JsonObject
  // 与安卓 DEFAULT_MAX_OUTPUT_TOKENS 一致，未配置时也发送上限。
  const outputLimit = typeof config.outputLimit === 'number' && config.outputLimit > 0 ? config.outputLimit : 4096
  // 采样参数统一使用服务端默认值，旧配置中的温度和 Top P 不再进入任何协议请求。
  const thinking = capability.reasoning === false ? undefined : thinkingConfig(protocol, parameters, model)
  if (protocol === 'responses') {
    body = { model, stream: true, input: history.map((item, index) => ({ role: item.role, content: [{ type: item.role === 'assistant' ? 'output_text' : 'input_text', text: item.content }, ...(index === lastUser ? images.map(image => ({ type: 'input_image', image_url: image.dataUrl })) : [])] })), ...(system ? { instructions: system } : {}), max_output_tokens: outputLimit,
      ...(thinking ? { reasoning: { effort: thinking.effort, summary: 'auto' } } : {}) }
  } else if (protocol === 'anthropic') {
    // 安卓 AnthropicDirectClient：程度模式用 adaptive，预算模式仅在 1024 ≤ 预算 < 上限时启用。
    const budget = thinking?.mode === 'BUDGET' && thinking.budget >= 1024 && thinking.budget < outputLimit ? thinking.budget : undefined
    body = { model, stream: true, messages: anthropicHistory, max_tokens: outputLimit, ...(system ? { system: [{ type: 'text', text: system, cache_control: { type: 'ephemeral' } }] } : {}),
      ...(thinking?.mode === 'EFFORT' ? { thinking: { type: 'adaptive' }, output_config: { effort: thinking.effort } } : budget ? { thinking: { type: 'enabled', budget_tokens: budget } } : {}) }
  } else if (protocol === 'gemini') {
    body = { contents: history.map((m, index) => ({ role: m.role === 'assistant' ? 'model' : 'user', parts: [{ text: m.content }, ...(index === lastUser ? images.map(image => { const [, data] = image.dataUrl.split(',', 2); return { inline_data: { mime_type: image.mime, data } } }) : [])] })),
      ...(system ? { systemInstruction: { parts: [{ text: system }] } } : {}), generationConfig: { maxOutputTokens: outputLimit, ...(thinking?.gemini ? { thinkingConfig: thinking.gemini } : {}) }, safetySettings: geminiSafetySettings }
  } else {
    body = { model, stream: true, stream_options: { include_usage: true }, messages: [...(system ? [{ role: 'system', content: system }] : []), ...openAiHistory],
      [openAiMaxTokensField(String(config.provider ?? ''), target)]: outputLimit, ...openAiCustomParameters(parameters) }
  }
  return { target, protocol, apiKey: config.apiKey, body }
}

/** 安卓 WebSearchSupport：Gemini 用原生 google_search，Qwen 兼容接口用 enable_search，其余走外部搜索工具。 */
export function nativeWebSearch(config: JsonObject): 'gemini' | 'qwen' | undefined {
  const model = String(config.model ?? '').toLowerCase()
  const protocol = modelProtocol(config)
  if (protocol === 'gemini' && (model.split('/').pop() ?? '').startsWith('gemini')) return 'gemini'
  if (protocol === 'openai' && model.includes('qwen')) return 'qwen'
  return undefined
}

/** 开启联网且模型支持原生搜索时，按安卓 GeminiDirectClient / OpenAIDirectClient 写入请求体。 */
export function withNativeWebSearch(request: ReturnType<typeof modelRequest>, config: JsonObject): ReturnType<typeof modelRequest> {
  const native = nativeWebSearch(config)
  if (native === 'gemini') return { ...request, body: { ...request.body, tools: [{ google_search: {} }] } }
  if (native === 'qwen') {
    const search = { enable_search: true, search_options: { forced_search: true, search_strategy: 'max' } }
    return { ...request, body: { ...request.body, ...search, extra_body: search } }
  }
  return request
}

/** 与安卓 reasoningBudgetForEffort 一致。 */
function reasoningBudgetForEffort(effort: string): number {
  return ({ none: 0, minimal: 0, low: 1024, medium: 8192, high: 24576, xhigh: 32768, max: 32768 } as Record<string, number>)[effort] ?? 8192
}

const thinkingLevels: Record<ModelProtocol, string[]> = {
  responses: ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'],
  anthropic: ['none', 'low', 'medium', 'high', 'max'],
  gemini: ['none', 'minimal', 'low', 'medium', 'high'],
  openai: ['none', 'low', 'medium', 'high', 'xhigh', 'max'],
}

/** 安卓 ModelParameters.toThinkingConfig 的协议转换；兼容接口不走这里，思考程度由自定义参数发送。 */
function thinkingConfig(protocol: ModelProtocol, parameters: JsonObject, model: string): { mode: string; effort: string; budget: number; gemini?: JsonObject } | undefined {
  if (protocol === 'openai') return undefined
  const raw = String(parameters.reasoningEffort ?? '').trim().toLowerCase()
  const effort = thinkingLevels[protocol].includes(raw) ? raw : 'medium'
  const mode = String(parameters.reasoningMode ?? 'EFFORT')
  const budget = typeof parameters.thoughtBudget === 'number' ? parameters.thoughtBudget : 2048
  if (protocol === 'responses') return mode === 'DISABLED' ? { mode: 'EFFORT', effort: 'none', budget } : { mode: 'EFFORT', effort, budget }
  if (protocol === 'anthropic') return mode === 'DISABLED' || effort === 'none' ? undefined : { mode, effort, budget }
  const level = model.toLowerCase().includes('gemini-3')
  const geminiMode = effort === 'none' ? 'DISABLED' : mode
  const gemini = geminiMode === 'DISABLED' ? { includeThoughts: false, ...(level ? { thinkingLevel: 'minimal' } : { thinkingBudget: 0 }) }
    : geminiMode === 'BUDGET' ? { includeThoughts: true, thinkingBudget: budget }
    : { includeThoughts: true, ...(level ? { thinkingLevel: effort } : { thinkingBudget: reasoningBudgetForEffort(effort) }) }
  return { mode: geminiMode, effort, budget, gemini }
}

/** 与安卓 PiOpenAIChatMessageAdapter.maxTokensField 一致：少数兼容服务仍只认 max_tokens。 */
export function openAiMaxTokensField(provider: string, endpoint: string): string {
  const id = provider.toLowerCase(), url = endpoint.toLowerCase()
  const legacy = ['zai', 'zai-coding-cn', 'together', 'moonshotai', 'moonshotai-cn', 'cloudflare-ai-gateway', 'nvidia', 'ant-ling', 'deepseek'].includes(id) ||
    ['chutes.ai', 'api.z.ai', 'open.bigmodel.cn', 'api.together.ai', 'api.together.xyz', 'api.moonshot.', 'gateway.ai.cloudflare.com', 'integrate.api.nvidia.com', 'api.ant-ling.com', 'deepseek.com'].some(host => url.includes(host))
  return legacy ? 'max_tokens' : 'max_completion_tokens'
}

const reservedParameterNames = new Set(['model', 'messages', 'input', 'stream', 'tools', 'tool_choice', 'previous_response_id', 'max_tokens', 'max_completion_tokens', 'max_output_tokens'])

/** 与安卓 openAICompatibleRequestParameters 一致：未保存时默认 reasoning_effort=medium，按类型校验后平铺进请求体。 */
export function openAiCustomParameters(parameters: JsonObject): JsonObject {
  const resolved = isObject(parameters.resolvedCapability) ? parameters.resolvedCapability : {}
  const list = Array.isArray(parameters.customParameters) ? parameters.customParameters.filter(isObject) : [{ name: 'reasoning_effort', value: 'medium', type: 'STRING', enabled: true }]
  const enabled = list.filter(item => {
    const name = String(item.name ?? '').trim().toLowerCase()
    return item.enabled !== false && !['temperature', 'top_p', 'topp'].includes(name) && !(resolved.supportsReasoning === false && name === 'reasoning_effort')
  })
  const result: JsonObject = {}
  const seen = new Set<string>()
  for (const item of enabled) {
    const name = String(item.name ?? '').trim()
    if (!name) throw new Error('参数名不能为空')
    if (seen.has(name.toLowerCase())) throw new Error(`参数名不能重复：${name.toLowerCase()}`)
    if (reservedParameterNames.has(name.toLowerCase())) throw new Error(`参数 ${name} 由应用管理，不能覆盖`)
    seen.add(name.toLowerCase())
    const value = String(item.value ?? '')
    if (item.type === 'NUMBER') {
      const parsed: unknown = (() => { try { return JSON.parse(value.trim()) } catch { return undefined } })()
      if (typeof parsed !== 'number') throw new Error(`参数 ${name} 需要填写有效数字`)
      result[name] = parsed
    } else if (item.type === 'BOOLEAN') {
      const flag = value.trim().toLowerCase()
      if (flag !== 'true' && flag !== 'false') throw new Error(`参数 ${name} 需要填写 true 或 false`)
      result[name] = flag === 'true'
    } else if (item.type === 'JSON') {
      try { result[name] = JSON.parse(value) } catch { throw new Error(`参数 ${name} 不是有效 JSON`) }
    } else result[name] = value
  }
  return result
}

/** 生成图片只使用 Android 已有的 Gemini 和 OpenAI 兼容 JSON 合同。 */
export function imageRequest(config: JsonObject, prompt: string, attachments: ModelAttachment[] = []): { target: string; protocol: ImageProtocol; apiKey: string; body: JsonObject; builtinModel?: string } {
  const parameters = isObject(config.modelParameters) ? config.modelParameters : {}
  config = { ...config, aspectRatio: parameters.imageAspectRatio ?? config.aspectRatio, quality: parameters.imageQuality ?? config.quality, batchSize: parameters.imageBatchSize ?? config.batchSize }
  const builtin = ['默认', 'default'].includes(String(config.provider ?? '').trim().toLowerCase()) &&
    ['z-image-turbo-modal', 'qwen-image-edit-modal', 'Kwai-Kolors/Kolors'].includes(String(config.model))
  if (builtin) {
    // 只传模型身份，服务端再解析固定服务和密钥，客户端不能指定内置服务的目的地址。
    const request = imageRequest({ ...config, provider: 'builtin', address: 'https://builtin.invalid', apiKey: 'builtin' }, prompt, attachments)
    return { ...request, target: '', apiKey: '', builtinModel: String(config.model) }
  }
  const inferenceSteps = config.inferenceSteps ?? config.numInferenceSteps
  const protocol = imageProtocol(config)
  if (protocol === 'unsupported') throw new Error('当前 API 协议未接入图片生成，请在 API 配置中选择支持图片生成的接口。')
  const model = String(config.model ?? ''), provider = String(config.provider ?? '').toLowerCase()
  // 与安卓 ApiClient 的 isSeedream / isSiliconFlow 判断一致。
  const seedream = provider.includes('seedream') || /doubao|seedream/i.test(model)
  const siliconFlow = provider.includes('silicon') || String(config.address ?? '').includes('siliconflow.cn')
  // 地址留空时与安卓相同：Seedream 用火山方舟，Gemini 用官方地址，其余用 OpenAI 官方地址。
  const address = String(config.address ?? '').trim() || (seedream ? 'https://ark.cn-beijing.volces.com/api/v3' : protocol === 'gemini' ? 'https://generativelanguage.googleapis.com' : protocol === 'openai' ? 'https://api.openai.com' : '')
  const apiKey = typeof config.apiKey === 'string' ? config.apiKey.trim() : ''
  // Modal Z-Image 无需密钥；Qwen 编辑使用 x-api-key。
  if (!address || !model || (!apiKey && protocol !== 'modal')) throw new Error('请先配置图片模型地址、模型名和 API Key')
  // Modal/Qwen 地址可以逗号分隔多个，服务端按顺序重试。
  const exactAddress = address.split(',').map(item => item.trim().replace(/#$/, '').replace(/\/+$/, '')).filter(Boolean).join(',')
  if (protocol === 'modal') {
    const body: JsonObject = { prompt, numInferenceSteps: Number.isSafeInteger(inferenceSteps) ? inferenceSteps : 4 }
    if (typeof config.imageSize === 'string' && config.imageSize.trim()) body.imageSize = config.imageSize.trim()
    if (typeof config.aspectRatio === 'string' && config.aspectRatio.trim()) body.aspectRatio = config.aspectRatio.trim()
    return { protocol, apiKey, target: exactAddress, body }
  }
  if (protocol === 'qwen') {
    const image = attachments[0]
    if (!image) throw new Error('Qwen 图像编辑需要至少一张参考图片')
    const [, data] = image.dataUrl.split(',', 2)
    return { protocol, apiKey, target: exactAddress, body: {
      image_base64: data, prompt, steps: Number.isSafeInteger(inferenceSteps) ? inferenceSteps : 30,
      guidance_scale: typeof config.guidanceScale === 'number' ? config.guidanceScale : 7.5,
    } }
  }
  if (protocol === 'gemini') {
    const target = new URL(resolveModelEndpoint('gemini', address, model).replace(':streamGenerateContent', ':generateContent'))
    if (target.searchParams.get('alt') === 'sse') target.searchParams.delete('alt')
    const parts: JsonObject[] = [{ text: prompt }, ...attachments.map(image => { const [, data] = image.dataUrl.split(',', 2); return { inlineData: { mimeType: image.mime, data } } })]
    const generationConfig: JsonObject = { responseModalities: ['IMAGE', 'TEXT'] }
    const aspectRatio = typeof config.aspectRatio === 'string' && /^(?:1:1|2:3|3:2|3:4|4:3|4:5|5:4|9:16|16:9|21:9)$/.test(config.aspectRatio) ? config.aspectRatio : undefined
    const configuredImageSize = config.geminiImageSize ?? config.imageSize
    // 与安卓 isGemini3ProImage 一致，其他 Gemini 图片模型收到 imageSize 会报 400。
    const imageSize = typeof configuredImageSize === 'string' && /^(?:1K|2K|4K)$/i.test(configuredImageSize) && model.toLowerCase().includes('gemini-3-pro-image') ? configuredImageSize.toUpperCase() : undefined
    if (aspectRatio || imageSize) generationConfig.imageConfig = { ...(aspectRatio ? { aspectRatio } : {}), ...(imageSize ? { imageSize } : {}) }
    return { protocol, apiKey, target: target.toString(), body: { contents: [{ role: 'user', parts }], generationConfig, safetySettings: geminiSafetySettings } }
  }
  const size = requestImageSize(config, model)
  if (seedream) {
    // 安卓 Seedream 始终走 generations，参考图作为 image 数组发送。
    const target = resolveModelEndpoint('openai', address, model).replace(/\/chat\/completions(?=\?|$)/, '/images/generations')
    return { protocol, apiKey, target, body: { model, prompt, response_format: 'url', watermark: config.watermark === true,
      size: seedreamSize(size && size !== '1024x1024' ? size : '2K', typeof config.aspectRatio === 'string' ? config.aspectRatio : ''),
      ...(attachments.length ? { image: attachments.map(image => image.dataUrl) } : {}) } }
  }
  if (attachments.length > 1) throw new Error('OpenAI 图像编辑目前只支持一张参考图片')
  const edit = attachments.length === 1
  const target = resolveModelEndpoint('openai', address, model).replace(/\/chat\/completions(?=\?|$)/, edit ? '/images/edits' : '/images/generations')
  const body: JsonObject = { model, prompt, ...(edit ? { image: attachments[0]?.dataUrl } : {}) }
  if (size) body[siliconFlow ? 'image_size' : 'size'] = size
  if (typeof config.batchSize === 'number') body[siliconFlow ? 'batch_size' : 'n'] = config.batchSize
  if (siliconFlow && typeof inferenceSteps === 'number') body.num_inference_steps = inferenceSteps
  if (siliconFlow && typeof config.guidanceScale === 'number') body.guidance_scale = config.guidanceScale
  if (typeof config.quality === 'string' && config.quality.trim()) body.quality = config.quality.trim()
  return { protocol, apiKey, target, body }
}

// 与安卓 AiContentSafetyPolicy.geminiSafetySettings 一致，聊天和生图共用。
const geminiSafetySettings = ['HARM_CATEGORY_HARASSMENT', 'HARM_CATEGORY_HATE_SPEECH', 'HARM_CATEGORY_SEXUALLY_EXPLICIT', 'HARM_CATEGORY_DANGEROUS_CONTENT'].map(category => ({ category, threshold: 'BLOCK_MEDIUM_AND_ABOVE' }))

// 以下尺寸表与安卓 ImageRatio.DEFAULT_RATIOS / ImageGenCapabilities 保持一致。
const ratioPixels: Record<string, string> = { '1:1': '1024x1024', '2:3': '832x1248', '3:2': '1248x832', '3:4': '864x1184', '4:3': '1184x864', '1:2': '720x1440', '4:5': '896x1152', '5:4': '1152x896', '9:16': '768x1344', '16:9': '1344x768', '21:9': '1536x672' }
const qwenSizes: Record<string, string> = { '1:1': '1328x1328', '16:9': '1664x928', '9:16': '928x1664', '4:3': '1472x1140', '3:4': '1140x1472', '3:2': '1584x1056', '2:3': '1056x1584' }
const kolorsSizes: Record<string, string> = { '1:1': '1024x1024', '3:4': '960x1280', '1:2': '720x1440', '9:16': '720x1280' }
const gptImageSizes: Record<string, string> = { '1:1': '1024x1024', '3:2': '1536x1024', '2:3': '1024x1536', '16:9': '2048x1152', '9:16': '1152x2048', '4:3': '2048x1536', '3:4': '1536x2048' }
const seedreamSizes: Record<'2K' | '4K', Record<string, string>> = {
  '2K': { '1:1': '2048x2048', '4:3': '2304x1728', '3:4': '1728x2304', '16:9': '2560x1440', '9:16': '1440x2560', '3:2': '2496x1664', '2:3': '1664x2496', '21:9': '3024x1296' },
  '4K': { '1:1': '4096x4096', '4:3': '4608x3456', '3:4': '3456x4608', '16:9': '5120x2880', '9:16': '2880x5120', '3:2': '4992x3328', '2:3': '3328x4992', '21:9': '6048x2592' },
}

/** 安卓 ImageGenCapabilities.detectFamily + resolveRequestImageSize：按比例换算各家族实际尺寸。 */
export function requestImageSize(config: JsonObject, model: string): string | undefined {
  let family = 'unknown'
  for (const value of [model, config.provider, config.address]) {
    const name = String(value ?? '').toLowerCase()
    family = /z-image-turbo|z_image_turbo/.test(name) ? 'modal' : /gemini|google/.test(name) ? 'gemini' : /doubao|seedream|volces/.test(name) ? 'seedream'
      : name.includes('qwen') ? 'qwen' : /kolors|kwai|siliconflow/.test(name) ? 'kolors' : /gpt-image|gpt_image|chatgpt-image/.test(name) ? 'gpt-image' : 'unknown'
    if (family !== 'unknown') break
  }
  const configured = typeof config.imageSize === 'string' ? config.imageSize.trim() : ''
  const fallback = configured && !configured.includes('<') ? configured : '1024x1024'
  const ratio = typeof config.aspectRatio === 'string' && ratioPixels[config.aspectRatio.trim()] ? config.aspectRatio.trim() : undefined
  if (family === 'kolors') return (ratio && kolorsSizes[ratio]) || fallback
  if (family === 'qwen') return model.toLowerCase().includes('edit') ? undefined : (ratio && qwenSizes[ratio]) || '1328x1328'
  if (family === 'gpt-image') return ratio ? gptImageSizes[ratio] ?? fallback : 'auto'
  if (family === 'unknown') return ratio ? ratioPixels[ratio] : fallback
  return fallback
}

/** 安卓 mapSeedreamSize：无比例时保留足够大的自定义尺寸，否则按清晰度和比例查推荐尺寸。 */
export function seedreamSize(size: string, aspectRatio: string): string {
  const normalized = size.trim().toLowerCase()
  const [width = 0, height = 0] = normalized.split('x').map(Number)
  const dimensions = normalized.split('x').length === 2 && width > 0 && height > 0
  if (dimensions && !aspectRatio.trim() && width * height >= 3_686_400) return normalized
  const quality = normalized === '4k' || (dimensions && width * height > 2048 * 2048) ? '4K' : '2K'
  return seedreamSizes[quality][aspectRatio.trim()] ?? quality
}

/** 图片接口返回已落地的数据 URL；正文和图片都在成功后才进入同步记录。 */
export async function generateImage(session: AuthSession, request: ReturnType<typeof imageRequest>, signal: AbortSignal, refreshSession?: () => Promise<AuthSession>): Promise<ImageResult> {
  const response = await authenticatedFetch(session, '/api/image', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request), signal, cache: 'no-store' }, refreshSession)
  const value: unknown = await response.json().catch(() => undefined)
  // Modal/Qwen 长任务以 200 保活响应，失败时正文携带 error。
  if (!response.ok || !isObject(value) || typeof value.error === 'string') throw new Error(isObject(value) && typeof value.error === 'string' ? value.error : `图片生成失败（HTTP ${response.status}）`)
  const images = Array.isArray(value.images) ? value.images.filter((item): item is string => typeof item === 'string' && /^data:image\/[a-z0-9.+-]+;base64,[a-z0-9+/=]+$/i.test(item)) : []
  if (!images.length) throw new Error('图片服务没有返回有效图片')
  return { images, text: typeof value.text === 'string' ? value.text : '' }
}

/** 有界的原生工具循环：仅开启记忆时注册工具，历史片段和调用轨迹始终留在本轮内存。 */
export async function generate(session: AuthSession, request: ReturnType<typeof modelRequest>, signal: AbortSignal, onDelta: (result: ModelResult) => void, refreshSession?: () => Promise<AuthSession>, tools?: ModelTools): Promise<ModelResult> {
  let body = tools ? withModelTools(request.body, request.protocol, tools.definitions) : request.body
  let reasoning = ''
  for (let round = 0; round < 8; round++) {
    signal.throwIfAborted()
    await tools?.validate()
    const turn = new ModelToolTurn(request.protocol)
    const result = await generateTurn(session, { ...request, body }, signal, partial => onDelta({ ...partial, reasoning: reasoning + partial.reasoning }), refreshSession, turn)
    signal.throwIfAborted()
    await tools?.validate()
    const calls = turn.calls()
    if (!calls.length) return { ...result, reasoning: reasoning + result.reasoning }
    if (!tools) throw new Error('本轮没有授权使用模型工具')
    if (round === 7) throw new Error('工具调用轮数过多，请把问题说得更具体')
    const outputs: JsonObject[] = []
    for (const call of calls) { signal.throwIfAborted(); outputs.push(await tools.execute(call)) }
    body = turn.continue(body, result, calls, outputs)
    reasoning += result.reasoning
    onDelta({ text: '', reasoning })
  }
  throw new Error('模型工具循环未完成')
}

/** SSE 支持跨网络块、CRLF 和最后一帧；草稿仅在内存中，成功定稿后才能入同步队列。 */
async function generateTurn(session: AuthSession, request: ReturnType<typeof modelRequest>, signal: AbortSignal, onDelta: (result: ModelResult) => void, refreshSession: (() => Promise<AuthSession>) | undefined, toolTurn: ModelToolTurn): Promise<ModelResult> {
  const response = await authenticatedFetch(session, '/api/model', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request), signal, cache: 'no-store' }, refreshSession)
  if (!response.ok) throw new Error(`模型请求失败（HTTP ${response.status}），请检查密钥、余额和模型名称`)
  const result: ModelResult = { text: '', reasoning: '' }
  let ended = false
  function accept(value: unknown): void {
    if (!isObject(value)) return
    if (value.error || value.type === 'error') throw new Error('模型服务返回错误，本次草稿没有同步')
    if (value.stop_reason === 'max_tokens' || value.stop_reason === 'refusal') throw new Error('模型回复未完整结束，本轮工具未执行')
    toolTurn.accept(value)
    if (value.type === 'response.output_text.delta' && typeof value.delta === 'string') result.text += value.delta
    if (value.type === 'response.reasoning_summary_text.delta' && typeof value.delta === 'string') result.reasoning += value.delta
    if (value.type === 'response.failed' || value.type === 'response.incomplete' || value.status === 'incomplete' || value.status === 'failed') throw new Error('模型回复未完成，本次草稿没有同步')
    if (Array.isArray(value.choices)) {
      for (const choice of value.choices.slice(0, 1)) if (isObject(choice)) {
        if (choice.finish_reason === 'length' || choice.finish_reason === 'content_filter') throw new Error('模型回复未完整结束，本轮工具未执行')
        if (choice.finish_reason === 'stop' || choice.finish_reason === 'tool_calls') ended = true
        const delta = isObject(choice.delta) ? choice.delta : isObject(choice.message) ? choice.message : {}
        if (typeof delta.content === 'string') result.text += delta.content
        if (typeof delta.reasoning_content === 'string') result.reasoning += delta.reasoning_content
      }
    }
    if (isObject(value.delta)) {
      if (value.delta.stop_reason === 'max_tokens' || value.delta.stop_reason === 'refusal') throw new Error('模型回复未完整结束，本轮工具未执行')
      if (typeof value.delta.text === 'string') result.text += value.delta.text
      if (typeof value.delta.thinking === 'string') result.reasoning += value.delta.thinking
    }
    if (value.type === 'content_block_start' && isObject(value.content_block)) {
      if (typeof value.content_block.text === 'string') result.text += value.content_block.text
      if (typeof value.content_block.thinking === 'string') result.reasoning += value.content_block.thinking
    }
    if (Array.isArray(value.content)) for (const part of value.content) if (isObject(part) && typeof part.text === 'string') result.text += part.text
    if (Array.isArray(value.candidates)) for (const candidate of value.candidates.slice(0, 1)) if (isObject(candidate)) {
      if (candidate.finishReason && candidate.finishReason !== 'STOP') throw new Error('模型回复未完整结束，本轮工具未执行')
      if (!isObject(candidate.content) || !Array.isArray(candidate.content.parts)) continue
      for (const part of candidate.content.parts) if (isObject(part) && typeof part.text === 'string') { if (part.thought) result.reasoning += part.text; else result.text += part.text }
    }
    // Responses 既可能返回 SSE，也可能由兼容服务返回完整 JSON；完整 output 只作为没有增量时的补充。
    const output = Array.isArray(value.output) ? value.output : isObject(value.response) && Array.isArray(value.response.output) ? value.response.output : []
    if (!result.text) for (const item of output) if (isObject(item) && item.type === 'message' && Array.isArray(item.content)) {
      for (const part of item.content) if (isObject(part) && part.type === 'output_text' && typeof part.text === 'string') result.text += part.text
    }
    if (isObject(value.usage)) result.usage = value.usage
    onDelta({ ...result })
  }
  if (!response.headers.get('content-type')?.includes('text/event-stream')) {
    accept(await response.json())
    if (!result.text.trim() && !toolTurn.calls().length) throw new Error('模型没有返回正文')
    return result
  }
  const reader = response.body?.getReader()
  if (!reader) throw new Error('模型响应为空')
  const decoder = new TextDecoder()
  let buffer = '', received = 0
  const frame = (raw: string) => {
    const data = raw.split('\n').filter(l => l.startsWith('data:')).map(l => l.slice(5).trimStart()).join('\n')
    if (!data) return
    if (data.trim() === '[DONE]') { ended = true; return }
    const parsed: unknown = JSON.parse(data)
    if (isObject(parsed) && (parsed.type === 'response.completed' || parsed.type === 'message_stop' || (Array.isArray(parsed.candidates) && parsed.candidates.some(c => isObject(c) && c.finishReason)))) ended = true
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
  if (!result.text.trim() && !toolTurn.calls().length) throw new Error('模型没有返回正文')
  return result
}
