import { describe, expect, test } from 'bun:test'
import { normalizeSearchProvider, readSearchResults } from '../src/search'

describe('联网搜索协议', () => {
  test('只允许 Android 合同中的固定供应商', () => {
    expect(normalizeSearchProvider('TAVILY')).toBe('tavily')
    expect(normalizeSearchProvider('https://attacker.example')).toBeUndefined()
  })


  test('恢复同步结果时丢弃危险链接', () => {
    expect(readSearchResults([
      { title: 'A', url: 'https://example.com/a', snippet: '摘要' },
      { title: '危险协议', url: 'javascript:alert(1)', snippet: '丢弃' },
    ])).toEqual([{ title: 'A', url: 'https://example.com/a', snippet: '摘要' }])
  })
})
