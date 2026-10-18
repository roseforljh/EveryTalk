import { describe, expect, test } from 'bun:test'
import { buildSearchRequest, normalizeSearchProvider, parseSearchResponse, readSearchResults } from '../src/search'

describe('联网搜索协议', () => {
  test('只允许 Android 合同中的固定供应商', () => {
    expect(normalizeSearchProvider('TAVILY')).toBe('tavily')
    expect(normalizeSearchProvider('https://attacker.example')).toBeUndefined()
  })

  test('供应商请求不会把任意目标交给调用方', () => {
    const request = buildSearchRequest('tavily', 'tvly-test-key', '最新消息')
    expect(request.url).toBe('https://api.tavily.com/search')
    expect(request.headers.Authorization).toBe('Bearer tvly-test-key')
    expect(request.body).toMatchObject({ query: '最新消息', max_results: 5 })
  })

  test('统一解析并限制外部结果', () => {
    const results = parseSearchResponse('exa', { results: [
      { title: 'A', url: 'https://example.com/a', highlights: ['摘要'] },
      { title: '重复', url: 'https://example.com/a', text: '不应重复' },
      { title: '危险协议', url: 'javascript:alert(1)', text: '丢弃' },
    ] })
    expect(results).toEqual([{ title: 'A', url: 'https://example.com/a', snippet: '摘要' }])
    expect(readSearchResults(results)).toEqual(results)
  })
})
