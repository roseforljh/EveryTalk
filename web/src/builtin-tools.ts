import { authenticatedFetch } from './auth'
import { isObject } from './sync-core'
import { readSearchResults, type SearchResult } from './search'
import type { ModelTools } from './model-tools'
import type { AuthSession, JsonObject } from './types'

/** 通过同源登录代理执行一次搜索，搜索密钥只在内存和当前请求中存在。 */
export async function requestWebSearch(session: AuthSession, provider: string, apiKey: string, query: string, signal: AbortSignal, refreshSession?: () => Promise<AuthSession>): Promise<SearchResult[]> {
  const response = await authenticatedFetch(session, '/api/search', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider, apiKey, query }), signal, cache: 'no-store',
  }, refreshSession)
  const body: unknown = await response.json().catch(() => undefined)
  if (!response.ok) throw new Error(isObject(body) && typeof body.error === 'string' ? body.error : `联网搜索失败（HTTP ${response.status}）`)
  const results = isObject(body) ? readSearchResults(body.results) : []
  if (!results.length) throw new Error('联网搜索没有返回可用结果')
  return results
}

/** 当前浏览器本地时间，字段与安卓 get_current_datetime 相同。 */
export function currentDateTime(now = new Date()): JsonObject {
  const pad = (value: number) => String(value).padStart(2, '0')
  const offset = -now.getTimezoneOffset()
  const date = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
  const time = `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
  return {
    datetime: `${date}T${time}${offset >= 0 ? '+' : '-'}${pad(Math.floor(Math.abs(offset) / 60))}:${pad(Math.abs(offset) % 60)}`,
    date, time, local_time: `${date} ${time}`, hour: now.getHours(), minute: now.getMinutes(), second: now.getSeconds(),
    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone, timestamp_ms: now.getTime(),
  }
}

/**
 * 安卓 MessageSender 每轮注入的内建工具：webfetch、get_current_datetime，
 * 以及未走原生搜索且已选外部服务商时的 web_search。名称、参数和返回字段保持一致。
 */
export function builtinTools(options: {
  session: () => AuthSession; refreshSession: () => Promise<AuthSession>; signal: AbortSignal
  search?: { provider: string; apiKey: string; onResults: (results: SearchResult[]) => void }
}): ModelTools {
  const definitions: ModelTools['definitions'] = [
    { name: 'webfetch', description: '读取网页正文。描述网页内容需以成功返回的正文为依据。', parameters: { type: 'object', properties: {
      url: { type: 'string', description: 'HTTP or HTTPS URL to fetch.' },
      max_chars: { type: 'integer', description: 'Maximum characters to return.' },
    }, required: ['url'] } },
    { name: 'get_current_datetime', description: 'Read the device’s current local date, time, timezone and Unix timestamp when needed.', parameters: { type: 'object', properties: {} } },
  ]
  if (options.search) definitions.push({ name: 'web_search', description: "Search the web and return results. Use when the question needs up-to-date information you don't have.", parameters: { type: 'object', properties: {
    query: { type: 'string', description: 'The search query to look up.' },
  }, required: ['query'] } })
  return {
    definitions,
    validate: async () => undefined,
    execute: async call => {
      if (call.name === 'get_current_datetime') return currentDateTime()
      if (call.name === 'web_search' && options.search) {
        const query = typeof call.arguments.query === 'string' ? call.arguments.query.trim() : ''
        if (!query) return { error: 'query is required' }
        try {
          const results = await requestWebSearch(options.session(), options.search.provider, options.search.apiKey, query.slice(0, 2000), options.signal, options.refreshSession)
          options.search.onResults(results)
          return { query, results: results.map(item => ({ title: item.title, url: item.url, snippet: item.snippet })) }
        } catch (error) {
          if (options.signal.aborted) throw error
          return { error: error instanceof Error ? error.message : '联网搜索失败' }
        }
      }
      if (call.name === 'webfetch') {
        const url = typeof call.arguments.url === 'string' ? call.arguments.url : ''
        const maxChars = typeof call.arguments.max_chars === 'number' ? call.arguments.max_chars : undefined
        const response = await authenticatedFetch(options.session(), '/api/webfetch', {
          method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url, maxChars }), signal: options.signal, cache: 'no-store',
        }, options.refreshSession)
        const body: unknown = await response.json().catch(() => undefined)
        if (isObject(body) && typeof body.ok === 'boolean') return body
        return { ok: false, requestedUrl: url, truncated: false, error: isObject(body) && typeof body.error === 'string' ? body.error : `WebFetch 返回 HTTP ${response.status}` }
      }
      throw new Error(`未知的模型工具：${call.name}`)
    },
  }
}
