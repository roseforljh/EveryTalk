import { isObject } from './sync-core'

/** Android 端当前支持的外部搜索供应商；Web 端只允许这些固定目标。 */
export type SearchProvider = 'tavily' | 'exa' | 'bocha' | 'serpapi'

export interface SearchResult {
  title: string
  url: string
  snippet: string
}

export interface SearchRequest {
  url: string
  headers: Record<string, string>
  body: Record<string, unknown>
}

const endpoints: Record<SearchProvider, string> = {
  tavily: 'https://api.tavily.com/search',
  exa: 'https://api.exa.ai/search',
  bocha: 'https://api.bochaai.com/v1/web-search',
  serpapi: 'https://serpapi.com/search.json',
}

/** 只接受 Android 合同中的供应商 ID，避免把搜索代理变成任意 URL 转发器。 */
export function normalizeSearchProvider(value: unknown): SearchProvider | undefined {
  const candidate = String(value ?? '').trim().toLowerCase()
  return candidate in endpoints ? candidate as SearchProvider : undefined
}

/** 生成供应商请求；API Key 只存在于当前请求内，不进入同步记录。 */
export function buildSearchRequest(provider: SearchProvider, apiKey: string, query: string): SearchRequest {
  const key = apiKey.trim()
  const text = query.trim()
  if (!key || key.length > 8192 || /[\r\n]/.test(key)) throw new Error('搜索 API Key 无效')
  if (!text || text.length > 2000) throw new Error('搜索内容不能为空或过长')
  if (provider === 'tavily') return {
    url: endpoints[provider], headers: { Authorization: `Bearer ${key}` },
    body: { query: text, search_depth: 'basic', max_results: 5, include_answer: false, include_images: false },
  }
  if (provider === 'exa') return {
    url: endpoints[provider], headers: { 'x-api-key': key },
    body: { query: text, type: 'auto', numResults: 5, contents: { highlights: { maxCharacters: 1200 } } },
  }
  if (provider === 'bocha') return {
    url: endpoints[provider], headers: { Authorization: `Bearer ${key}` },
    body: { query: text, freshness: 'oneYear', summary: true, count: 5 },
  }
  return {
    url: endpoints[provider], headers: {},
    body: { q: text, api_key: key, engine: 'google', num: 5, hl: 'zh-cn', google_domain: 'google.com' },
  }
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

/** 解析四种供应商的结果并统一成消息可同步的最小结构。 */
export function parseSearchResponse(provider: SearchProvider, body: unknown): SearchResult[] {
  if (!isObject(body)) return []
  const items: unknown[] = provider === 'tavily' || provider === 'exa'
    ? Array.isArray(body.results) ? body.results : []
    : provider === 'bocha'
      ? isObject(body.data) && isObject(body.data.webPages) && Array.isArray(body.data.webPages.value) ? body.data.webPages.value : []
      : Array.isArray(body.organic_results) ? body.organic_results : []
  const results: SearchResult[] = []
  for (const item of items) {
    if (!isObject(item)) continue
    const candidate = provider === 'tavily'
      ? result(item.title, item.url, item.content)
      : provider === 'exa'
        ? result(item.title, item.url, item.text ?? (Array.isArray(item.highlights) ? item.highlights[0] : item.summary))
        : provider === 'bocha'
          ? result(item.name, item.url, item.snippet)
          : result(item.title, item.link, item.snippet)
    if (candidate && !results.some(existing => existing.url === candidate.url)) results.push(candidate)
    if (results.length >= 5) break
  }
  return results
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
