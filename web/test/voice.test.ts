import { describe, expect, test } from 'bun:test'
import { normalizeVoiceLanguage } from '../src/voice'

describe('浏览器语音配置边界', () => {
  test('只接受语言标签，异常值回到中文', () => {
    expect(normalizeVoiceLanguage('en-US')).toBe('en-US')
    expect(normalizeVoiceLanguage(' zh-CN ')).toBe('zh-CN')
    expect(normalizeVoiceLanguage('javascript:alert(1)')).toBe('zh-CN')
    expect(normalizeVoiceLanguage(undefined)).toBe('zh-CN')
  })
})
