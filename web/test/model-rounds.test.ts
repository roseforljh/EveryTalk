import { afterEach, describe, expect, test } from 'bun:test'
import { generate, modelRequest, type ModelResult } from '../src/model'
import type { AuthSession } from '../src/types'

const session: AuthSession = { access_token: 't', refresh_token: 'r', expires_at: 0, user: { id: 'u' } }

const stream = (frames: unknown[]): Response => new Response(
  frames.map(frame => `data: ${JSON.stringify(frame)}\n\n`).join('') + 'data: [DONE]\n\n',
  { headers: { 'content-type': 'text/event-stream' } }
)

const originalFetch = globalThis.fetch
afterEach(() => { globalThis.fetch = originalFetch })

describe('工具多轮正文累计', () => {
  test('前几轮的正文和思考不会被下一轮清掉', async () => {
    let round = 0
    globalThis.fetch = (async () => round++ === 0
      ? stream([
        { choices: [{ delta: { content: '先说明。' }, finish_reason: null }] },
        { choices: [{ delta: { reasoning_content: '我想' }, finish_reason: null }] },
        { choices: [{ delta: { tool_calls: [{ index: 0, id: 'call-1', function: { name: 'web_search', arguments: '{"query":"天气"}' } }] }, finish_reason: null }] },
        { choices: [{ delta: {}, finish_reason: 'tool_calls' }] },
      ])
      : stream([
        { choices: [{ delta: { reasoning_content: '再想' }, finish_reason: null }] },
        { choices: [{ delta: { content: '最终回答。' }, finish_reason: null }] },
        { choices: [{ delta: {}, finish_reason: 'stop' }] },
      ])) as unknown as typeof fetch

    const request = modelRequest({ address: 'https://example.com/v1', model: 'm', apiKey: 'k', provider: 'OpenAI' }, [], '')
    const updates: ModelResult[] = []
    const result = await generate(session, request, new AbortController().signal, partial => { updates.push(partial) }, undefined, {
      definitions: [{ name: 'web_search', description: 'search', parameters: { type: 'object', properties: {} } }],
      validate: async () => undefined,
      execute: async () => ({ ok: true }),
    })

    expect(result.text).toBe('先说明。最终回答。')
    expect(result.reasoning).toBe('我想再想')
    // 执行工具时的流式更新也必须保留已经显示的第一轮正文。
    expect(updates.some(update => update.text === '先说明。')).toBe(true)
    expect(updates.at(-1)?.text).toBe('先说明。最终回答。')
  })
})
