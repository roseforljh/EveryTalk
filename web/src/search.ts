import { isObject } from './sync-core'

/** Android 端当前支持的外部搜索供应商；Web 端只允许这些固定目标。 */
export type SearchProvider = 'tavily' | 'exa' | 'bocha' | 'serpapi'

export interface SearchResult {
  title: string
  url: string
  snippet: string
}

const providers: SearchProvider[] = ['tavily', 'exa', 'bocha', 'serpapi']

/** 只接受 Android 合同中的供应商 ID，避免把搜索代理变成任意 URL 转发器。 */
export function normalizeSearchProvider(value: unknown): SearchProvider | undefined {
  const candidate = String(value ?? '').trim().toLowerCase()
  return providers.find(provider => provider === candidate)
}

const text = (value: unknown, limit: number): string => typeof value === 'string' ? value.trim().slice(0, limit) : ''

function httpUrl(value: unknown): string {
  if (typeof value !== 'string') return ''
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.toString() : ''
  } catch { return '' }
}

function result(title: unknown, url: unknown, snippet: unknown): SearchResult | undefined {
  const safeUrl = httpUrl(url)
  if (!safeUrl) return undefined
  return { title: text(title, 300) || safeUrl, url: safeUrl, snippet: text(snippet, 2000) }
}

/** 校验消息中恢复的搜索结果，避免把同步内容直接当成链接渲染。 */
export function readSearchResults(value: unknown): SearchResult[] {
  if (!Array.isArray(value)) return []
  return value.flatMap(item => {
    if (!isObject(item)) return []
    const parsed = result(item.title, item.url, item.snippet)
    return parsed ? [parsed] : []
  }).slice(0, 5)
}
