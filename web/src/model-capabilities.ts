import { isObject } from './sync-core'
import type { JsonObject } from './types'

// 与 QoneAgent 的 CapabilityEditor 一致；PDF 保留在目录元数据中，不单列为可编辑能力。
export const modalityLabels: Record<string, string> = { text: '文本', image: '图片', video: '视频', audio: '音频' }
export type ParameterProtocol = 'OPENAI_COMPATIBLE' | 'CODEX' | 'ANTHROPIC' | 'GEMINI'
export interface CatalogModel {
  id: string; name: string; provider: string; input: string[]; output: string[]
  reasoning?: boolean; temperature?: boolean; toolCall?: boolean
  context?: number; maxOutput?: number; efforts: string[]
}

/** models.dev 的 MIME 模态归并到界面能力；未知模态保留，不误报为文本。 */
export function normalizeModalities(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return [...new Set(value.filter((item): item is string => typeof item === 'string').map(item => {
    const normalized = item.trim().toLowerCase()
    return normalized === 'application/pdf' ? 'pdf' : /^(image|audio|video)\//.test(normalized) ? normalized.split('/')[0] : normalized
  }).filter(Boolean))]
}

/** 显式协议覆盖优先于模型名称，避免代理中的 Claude/Gemini 被错误改成原生接口。 */
export function parameterProtocol(config: JsonObject): ParameterProtocol {
  const parameters = isObject(config.modelParameters) ? config.modelParameters : {}
  const explicit = String(parameters.protocolOverride ?? parameters.apiProtocolOverride ?? config.channel ?? '').trim().toLowerCase()
  if (/codex|responses/.test(explicit)) return 'CODEX'
  if (/anthropic/.test(explicit)) return 'ANTHROPIC'
  if (/gemini/.test(explicit)) return 'GEMINI'
  if (/openai/.test(explicit)) return 'OPENAI_COMPATIBLE'
  const hint = `${config.provider ?? ''} ${config.model ?? ''} ${config.address ?? ''}`.toLowerCase()
  return /anthropic|claude/.test(hint) ? 'ANTHROPIC' : /gemini|google/.test(hint) ? 'GEMINI' : 'OPENAI_COMPATIBLE'
}

/** 目录只提供模型规格；用户手动覆盖独立保存，刷新目录不会覆盖它。 */
export function modelCapabilities(config: JsonObject): { input: string[]; output: string[]; reasoning?: boolean; temperature?: boolean } {
  const parameters = isObject(config.modelParameters) ? config.modelParameters : {}
  const resolved = isObject(parameters.resolvedCapability) ? parameters.resolvedCapability : {}
  const input = normalizeModalities(parameters.inputModalities ?? resolved.inputModalities)
  const output = normalizeModalities(parameters.outputModalities ?? resolved.outputModalities)
  return {
    input: input.length ? input : ['text'], output: output.length ? output : config.imageConfig === true ? ['image'] : ['text'],
    // 思考设置常驻，旧的手动能力开关不再生效；仍尊重目录明确声明的不支持。
    reasoning: typeof resolved.supportsReasoning === 'boolean' ? resolved.supportsReasoning : undefined,
    temperature: typeof resolved.supportsTemperature === 'boolean' ? resolved.supportsTemperature : undefined,
  }
}

/** 解析固定的 models.dev 公开目录，仅保留参数面板需要的字段，不收集用户配置。 */
/** 优先精确 ID 和对应供应商，只有唯一模型身份时才回退到去掉供应商前缀的 ID。 */
export function findCatalogModel(catalog: CatalogModel[], model: string, provider = ''): CatalogModel | undefined {
  const id = model.trim().toLowerCase().replace(/^models\//, '')
  if (!id) return undefined
  let matches = catalog.filter(item => item.id.toLowerCase() === id)
  if (!matches.length) {
    matches = catalog.filter(item => item.id.toLowerCase().split('/').at(-1) === id.split('/').at(-1))
    if (new Set(matches.map(item => item.id.toLowerCase())).size > 1) return undefined
  }
  return matches.find(item => item.provider.toLowerCase() === provider.trim().toLowerCase()) ?? matches.sort((a, b) => a.provider.localeCompare(b.provider))[0]
}

export function withCatalogModel(config: JsonObject, model: CatalogModel): JsonObject {
  const parameters = isObject(config.modelParameters) ? config.modelParameters : {}
  return { ...config, outputLimit: Math.min(model.maxOutput ?? 4096, (model.context ?? 128000) - 1),
    modelParameters: { ...parameters, contextLimit: model.context ?? parameters.contextLimit ?? 128000, resolvedCapability: {
      modelId: String(config.model ?? model.id), endpointIdentity: String(config.address ?? ''),
      contextWindowTokens: model.context ?? 128000, maxOutputTokens: model.maxOutput ?? 4096,
      contextWindowSource: 'COMMUNITY_CATALOG', maxOutputSource: 'COMMUNITY_CATALOG', modalitiesSource: 'COMMUNITY_CATALOG',
      inputModalities: model.input, outputModalities: model.output, reasoningEfforts: model.efforts,
      supportsReasoning: model.reasoning ?? null,
      ...(model.temperature !== undefined ? { supportsTemperature: model.temperature } : {}),
      ...(model.toolCall !== undefined ? { supportsToolCalls: model.toolCall } : {}),
    } },
  }
}

/** 更换模型时清除旧目录事实，手动覆盖和接口配置继续保留，避免新模型继承旧模型能力。 */
export function withModelId(config: JsonObject, id: string, catalog: CatalogModel[]): JsonObject {
  const parameters = isObject(config.modelParameters) ? config.modelParameters : {}
  const next = { ...config, model: id, modelParameters: { ...parameters, resolvedCapability: null } }
  const matched = findCatalogModel(catalog, id, String(config.provider ?? ''))
  return matched ? withCatalogModel(next, matched) : next
}

/** 仅列出当前适配器实际会发送的图片参数，切换接口时隐藏字段但保留已填值。 */
export function imageParameterKeys(format: string): string[] {
  switch (format) {
    case 'gemini': return ['imageSize', 'aspectRatio']
    case 'qwen': return ['inferenceSteps', 'guidanceScale']
    case 'modal': return ['imageSize', 'aspectRatio', 'inferenceSteps', 'guidanceScale']
    case 'unsupported': return []
    default: return ['imageSize', 'quality', 'batchSize']
  }
}
