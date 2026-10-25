import { afterEach, describe, expect, spyOn, test } from 'bun:test'
import { conversationMemory, searchConversationHistory } from '../src/conversation-memory'
import { generate, modelRequest, type ModelProtocol } from '../src/model'
import type { AuthSession, JsonObject, StoredRecord, SyncEntity } from '../src/types'

const record = (entity: SyncEntity, id: string, payload: JsonObject, deleted = false): StoredRecord => ({ entity, id, key: `${entity}:${id}`, payload, deleted })
const message = (id: string, text: string, timestamp = 100, sessionId = 'earlier', extra: JsonObject = {}): StoredRecord => record('messages', id, { sessionId, sender: 'User', text, timestamp, ...extra })
const switchRecord = (enabled = true, version = 1): StoredRecord => record('user_settings', 'memory-switch', { kind: 'memory_switches', key: 'default', clientModifiedAt: version, value: { memoryEnabled: enabled } })
const records = (): StoredRecord[] => [switchRecord(), record('sessions', 'current', { title: '当前会话' }), record('sessions', 'earlier', { title: '迁移方案' }), message('decision', '迁移方案最终选择分批执行')]
const args = (extra: JsonObject = {}): JsonObject => ({ action: 'search_conversations', ...extra })
const session: AuthSession = { access_token: 'test-token', refresh_token: 'test-refresh', expires_at: 0, user: { id: 'account' } }
const config = { address: 'https://model.example/v1', model: 'test-model', apiKey: 'test-key' }
let fetchMock: ReturnType<typeof spyOn<typeof globalThis, 'fetch'>> | undefined
afterEach(() => { fetchMock?.mockRestore(); fetchMock = undefined })

/** 刻意切开 SSE 帧和 UTF-8 中文字节，覆盖真实网络分块，而不是一次返回完整字符串。 */
function stream(frames: unknown[], done = false): Response {
  const data = frames.map(frame => `data: ${JSON.stringify(frame)}\r\n\r\n`).join('') + (done ? 'data: [DONE]\r\n\r\n' : '')
  const bytes = new TextEncoder().encode(data)
  return new Response(new ReadableStream<Uint8Array>({ start(controller) {
    for (let index = 0; index < bytes.length; index += 17) controller.enqueue(bytes.slice(index, index + 17))
    controller.close()
  } }), { headers: { 'Content-Type': 'text/event-stream' } })
}

describe('历史会话检索', () => {
  test('未开启记忆不提供提示或工具，开启后即使没有记忆条目也能搜索', () => {
    expect(conversationMemory([], 'current', async () => [])).toBeUndefined()
    const disabled = [switchRecord(false)]
    expect(conversationMemory(disabled, 'current', async () => disabled)).toBeUndefined()
    const values = records(), memory = conversationMemory(values, 'current', async () => values)
    expect(memory?.prompt).toContain('先自行调用')
    expect(memory?.prompt).toContain('current')
    expect(memory?.prompt).toContain('timezone')
    expect(memory?.tools.definitions[0]?.name).toBe('everytalk_memory')
  })

  test('按字面关键词和标题搜索，排除当前会话、墓碑和非正文消息', () => {
    const values = records().concat([
      message('literal', '唯一_%关键词'), message('other', '唯一_Z关键词'), message('current-text', '唯一_%关键词', 100, 'current'),
      { ...message('deleted-text', '唯一_%关键词'), deleted: true },
      record('sessions', 'deleted-session', { title: '已删除' }, true), message('in-deleted', '唯一_%关键词', 100, 'deleted-session'),
      record('sessions', 'last_open_chat_v1', {}), message('internal', '唯一_%关键词', 100, 'last_open_chat_v1'),
      message('system', '唯一_%关键词', 100, 'earlier', { sender: 'System' }),
      message('tool', '唯一_%关键词', 100, 'earlier', { sender: 'Tool' }),
      message('error', '唯一_%关键词', 100, 'earlier', { isError: true }),
      message('placeholder', '唯一_%关键词', 100, 'earlier', { isPlaceholderName: true }),
      message('incomplete', '唯一_%关键词', 100, 'earlier', { is_incomplete: true }),
    ])
    const result = searchConversationHistory(values, 'current', args({ query: '唯一_%关键词' }))
    expect(result.messages).toMatchObject([{ message_id: 'literal', conversation_id: 'earlier' }])
    expect(searchConversationHistory(values, 'current', args({ query: '迁移方案' })).messages).toHaveLength(3)
    expect(searchConversationHistory(values, 'current', args({ query: '不存在' })).messages).toEqual([])
    expect(searchConversationHistory(values, 'current', args({ conversation_id: 'current' })).messages).toMatchObject([{ message_id: 'current-text' }])
  })

  test('本地日期包括首尾边界，夏令时结束的 25 小时当天也完整匹配', () => {
    const previous = process.env.TZ
    process.env.TZ = 'America/Los_Angeles'
    try {
      const begin = new Date(2026, 10, 1).getTime(), end = new Date(2026, 10, 2).getTime()
      expect(end - begin).toBe(25 * 60 * 60 * 1000)
      const values = records().slice(0, 3).concat([
        message('before', '方案', begin - 1), message('begin', '方案', begin),
        message('end', '方案', end - 1), message('after', '方案', end),
      ])
      expect(searchConversationHistory(values, 'current', args({ start_date: '2026-11-01', end_date: '2026-11-01' })).messages).toMatchObject([{ message_id: 'end' }, { message_id: 'begin' }])
    } finally { if (previous === undefined) delete process.env.TZ; else process.env.TZ = previous }
  })

  test('拒绝非法日期、未知参数、错误类型和缺少来源的续读', () => {
    for (const invalid of [
      { start_date: '2026-02-30' }, { start_date: '2026-10-25', end_date: '2026-10-24' },
      { start_date: '昨天' }, { query: true }, { offset: '1' }, { offset: -1 }, { offset: 1.5 },
      { content_offset: 1 }, { message_id: 'decision' }, { sql: 'SELECT *' }, { conversation_id: '' },
    ]) expect(() => searchConversationHistory(records(), 'current', args(invalid))).toThrow()
  })

  test('分页不重复且结果限长，长 Unicode 正文能定位关键词并逐段完整读回', () => {
    const text = '😀"\n\t'.repeat(350) + '这是末尾的决定'
    const values = records().slice(0, 3).concat(message('long', text), Array.from({ length: 12 }, (_, index) => message(`page-${index}`, `分页资料 ${index}`, index)))
    expect(searchConversationHistory(values, 'current', args({ query: '末尾的决定' })).messages).toMatchObject([{ content: expect.stringContaining('末尾的决定') }])
    let contentOffset = 0, restored = ''
    for (;;) {
      const result = searchConversationHistory(values, 'current', args({ conversation_id: 'earlier', message_id: 'long', content_offset: contentOffset }))
      expect(Array.from(JSON.stringify(result)).length).toBeLessThanOrEqual(3000)
      const row = (result.messages as JsonObject[])[0]
      restored += String(row.content)
      if (row.next_content_offset === undefined) break
      expect(Number(row.next_content_offset)).toBeGreaterThan(contentOffset)
      contentOffset = Number(row.next_content_offset)
    }
    expect(restored).toBe(text)
    let offset = 0
    const ids: unknown[] = []
    for (;;) {
      const result = searchConversationHistory(values, 'current', args({ query: '分页资料', offset }))
      expect(Array.from(JSON.stringify(result)).length).toBeLessThanOrEqual(3000)
      ids.push(...(result.messages as JsonObject[]).map(row => row.message_id))
      if (result.next_offset === undefined) break
      expect(Number(result.next_offset)).toBeGreaterThan(offset)
      offset = Number(result.next_offset)
    }
    expect(ids).toHaveLength(12)
    expect(new Set(ids).size).toBe(12)
  })

  test('每次搜索重新读取记录，关闭、重新开启或删除当前会话都会撤销旧授权', async () => {
    let values = records()
    const memory = conversationMemory(values, 'current', async () => values)!
    const call = { id: 'call', name: 'everytalk_memory', arguments: args({ query: '迁移' }) }
    expect((await memory.tools.execute(call)).messages).toHaveLength(1)
    values = values.map(value => value.id === 'decision' ? { ...value, deleted: true } : value)
    expect((await memory.tools.execute(call)).messages).toEqual([])
    values[0] = switchRecord(false, 2)
    await expect(memory.tools.execute(call)).rejects.toThrow('记忆已关闭或变更')
    values[0] = switchRecord(true, 3)
    await expect(memory.tools.validate()).rejects.toThrow('记忆已关闭或变更')
    const renewed = conversationMemory(values, 'current', async () => values)!
    values = values.map(value => value.id === 'current' ? { ...value, deleted: true } : value)
    await expect(renewed.tools.validate()).rejects.toThrow('当前会话已被删除')
  })
})

const protocols: { protocol: ModelProtocol; config: JsonObject }[] = [
  { protocol: 'openai', config: { ...config, channel: 'OpenAI兼容' } },
  { protocol: 'responses', config: { ...config, modelParameters: { protocolOverride: 'CODEX' } } },
  { protocol: 'anthropic', config: { ...config, channel: 'Anthropic' } },
  { protocol: 'gemini', config: { ...config, channel: 'Gemini' } },
]
const searchArguments = JSON.stringify(args({ query: '迁移' }))
const responseCall = { type: 'function_call', id: 'item-1', call_id: 'search-1', name: 'everytalk_memory', arguments: searchArguments }
const responseReasoning = { type: 'reasoning', id: 'reasoning-1', summary: [], encrypted_content: 'opaque-reasoning' }

function toolFrames(protocol: ModelProtocol): unknown[] {
  if (protocol === 'openai') return [
    { choices: [{ delta: { reasoning_content: '定位来源', tool_calls: [{ index: 0, id: 'search-1', type: 'function', function: { name: 'everytalk_memory', arguments: searchArguments.slice(0, 18) } }] } }] },
    { choices: [{ delta: { tool_calls: [{ index: 0, function: { arguments: searchArguments.slice(18) } }] }, finish_reason: 'tool_calls' }] },
  ]
  if (protocol === 'responses') return [
    { type: 'response.output_item.done', output_index: 0, item: responseReasoning },
    { type: 'response.output_item.added', output_index: 1, item: { ...responseCall, arguments: '' } },
    { type: 'response.function_call_arguments.delta', output_index: 1, delta: searchArguments },
    { type: 'response.output_item.done', output_index: 1, item: responseCall },
    { type: 'response.completed', response: { status: 'completed', output: [responseReasoning, responseCall] } },
  ]
  if (protocol === 'anthropic') return [
    { type: 'content_block_start', index: 0, content_block: { type: 'thinking', thinking: '' } },
    { type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: '定位来源' } },
    { type: 'content_block_delta', index: 0, delta: { type: 'signature_delta', signature: 'opaque-signature' } },
    { type: 'content_block_start', index: 1, content_block: { type: 'tool_use', id: 'search-1', name: 'everytalk_memory', input: {} } },
    { type: 'content_block_delta', index: 1, delta: { type: 'input_json_delta', partial_json: searchArguments.slice(0, 18) } },
    { type: 'content_block_delta', index: 1, delta: { type: 'input_json_delta', partial_json: searchArguments.slice(18) } },
    { type: 'message_delta', delta: { stop_reason: 'tool_use' } }, { type: 'message_stop' },
  ]
  return [{ candidates: [{ content: { role: 'model', parts: [
    { text: '定位来源', thought: true, thoughtSignature: 'thought-signature' },
    { functionCall: { id: 'search-1', name: 'everytalk_memory', args: args({ query: '迁移' }) }, thoughtSignature: 'call-signature' },
  ] }, finishReason: 'STOP' }] }]
}

function answerFrames(protocol: ModelProtocol): unknown[] {
  if (protocol === 'openai') return [{ choices: [{ delta: { content: '之前最终选择分批执行。' }, finish_reason: 'stop' }] }]
  if (protocol === 'responses') return [{ type: 'response.output_text.delta', delta: '之前最终选择分批执行。' }, { type: 'response.completed' }]
  if (protocol === 'anthropic') return [{ type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: '之前最终选择分批执行。' } }, { type: 'message_stop' }]
  return [{ candidates: [{ content: { role: 'model', parts: [{ text: '之前最终选择分批执行。' }] }, finishReason: 'STOP' }] }]
}

describe('模型自主检索循环', () => {
  for (const item of protocols) test(`${item.protocol} 流式工具调用返回真实历史并保留原生续接字段`, async () => {
    const values = records(), before = structuredClone(values)
    const memory = conversationMemory(values, 'current', async () => values)!
    const requests: JsonObject[] = []
    fetchMock = spyOn(globalThis, 'fetch').mockImplementation(Object.assign(async (_url: Parameters<typeof fetch>[0], init?: Parameters<typeof fetch>[1]) => {
      const request = JSON.parse(String(init?.body)) as { body: JsonObject }
      requests.push(request.body)
      return requests.length === 1 ? stream(toolFrames(item.protocol), item.protocol === 'openai') : stream(answerFrames(item.protocol), item.protocol === 'openai')
    }, { preconnect: fetch.preconnect }))
    const result = await generate(session, modelRequest(item.config, [message('question', '之前的迁移方案最后选了什么？', 200, 'current')], memory.prompt), new AbortController().signal, () => {}, undefined, memory.tools)
    expect(result.text).toBe('之前最终选择分批执行。')
    expect(requests).toHaveLength(2)
    if (item.protocol === 'gemini') expect(requests[0].tools).toMatchObject([{ functionDeclarations: [{ parametersJsonSchema: { additionalProperties: false } }] }])
    expect(JSON.stringify(requests[1])).toContain('迁移方案最终选择分批执行')
    expect(JSON.stringify(requests[1])).toContain('search-1')
    if (item.protocol === 'responses') expect(requests[1].input).toContainEqual(responseReasoning)
    if (item.protocol === 'anthropic') expect(JSON.stringify(requests[1])).toContain('opaque-signature')
    if (item.protocol === 'gemini') { expect(JSON.stringify(requests[1])).toContain('thought-signature'); expect(JSON.stringify(requests[1])).toContain('call-signature') }
    if (item.protocol === 'openai') expect(JSON.stringify(requests[1])).toContain('reasoning_content')
    expect(values).toEqual(before)
  })

  for (const item of protocols) test(`${item.protocol} 兼容返回 JSON 的工具调用和最终正文`, async () => {
    const values = records(), memory = conversationMemory(values, 'current', async () => values)!
    const first: JsonObject = item.protocol === 'openai' ? { choices: [{ message: { tool_calls: [{ id: 'call', type: 'function', function: { name: 'everytalk_memory', arguments: searchArguments } }] }, finish_reason: 'tool_calls' }] }
      : item.protocol === 'responses' ? { output: [responseReasoning, responseCall], status: 'completed' }
        : item.protocol === 'anthropic' ? { content: [{ type: 'tool_use', id: 'call', name: 'everytalk_memory', input: args({ query: '迁移' }) }], stop_reason: 'tool_use' }
          : { candidates: [{ content: { parts: [{ functionCall: { name: 'everytalk_memory', args: args({ query: '迁移' }) }, thoughtSignature: 'signature' }] }, finishReason: 'STOP' }] }
    const final: JsonObject = item.protocol === 'openai' ? { choices: [{ message: { content: '分批执行' }, finish_reason: 'stop' }] }
      : item.protocol === 'responses' ? { output: [{ type: 'message', role: 'assistant', content: [{ type: 'output_text', text: '分批执行' }] }], status: 'completed' }
        : item.protocol === 'anthropic' ? { content: [{ type: 'text', text: '分批执行' }], stop_reason: 'end_turn' }
          : { candidates: [{ content: { parts: [{ text: '分批执行' }] }, finishReason: 'STOP' }] }
    fetchMock = spyOn(globalThis, 'fetch').mockResolvedValueOnce(Response.json(first)).mockResolvedValueOnce(Response.json(final))
    expect((await generate(session, modelRequest(item.config, [], memory.prompt), new AbortController().signal, () => {}, undefined, memory.tools)).text).toBe('分批执行')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  test('记忆关闭时只发送普通聊天，未暴露历史搜索能力', async () => {
    const values = records(); values[0] = switchRecord(false)
    const memory = conversationMemory(values, 'current', async () => values)
    fetchMock = spyOn(globalThis, 'fetch').mockResolvedValue(stream(answerFrames('openai'), true))
    await generate(session, modelRequest(config, [], memory?.prompt ?? ''), new AbortController().signal, () => {}, undefined, memory?.tools)
    const request = JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))
    expect(request.body.tools).toBeUndefined()
    expect(JSON.stringify(request)).not.toContain('search_conversations')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  test('多个交错的工具调用按各自 ID 返回结果，错误参数只返回本机校验错误', async () => {
    const values = records(), memory = conversationMemory(values, 'current', async () => values)!
    fetchMock = spyOn(globalThis, 'fetch').mockResolvedValueOnce(stream([
      { choices: [{ delta: { tool_calls: [
        { index: 1, id: 'bad-call', type: 'function', function: { name: 'everytalk_memory', arguments: '{"action":"search_' } },
        { index: 0, id: 'valid-call', type: 'function', function: { name: 'everytalk_memory', arguments: searchArguments.slice(0, 18) } },
      ] } }] },
      { choices: [{ delta: { tool_calls: [
        { index: 0, function: { arguments: searchArguments.slice(18) } },
        { index: 1, function: { arguments: 'conversations","start_date":"2026-02-30"}' } },
      ] }, finish_reason: 'tool_calls' }] },
    ], true)).mockResolvedValueOnce(stream(answerFrames('openai'), true))
    await generate(session, modelRequest(config, [], memory.prompt), new AbortController().signal, () => {}, undefined, memory.tools)
    const request = JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body)) as { body: { messages: JsonObject[] } }
    const results = request.body.messages.filter(value => value.role === 'tool')
    expect(results.map(value => value.tool_call_id)).toEqual(['valid-call', 'bad-call'])
    expect(JSON.parse(String(results[0].content)).messages).toHaveLength(1)
    expect(JSON.parse(String(results[1].content))).toEqual({ ok: false, error: 'start_date 日期无效' })
  })

  test('网络等待期间关闭记忆会停止工具与下一次发送', async () => {
    const values = records(), memory = conversationMemory(values, 'current', async () => values)!
    const execute = spyOn(memory.tools, 'execute')
    fetchMock = spyOn(globalThis, 'fetch').mockImplementation(Object.assign(async () => {
      values[0] = switchRecord(false, 2)
      return stream(toolFrames('openai'), true)
    }, { preconnect: fetch.preconnect }))
    await expect(generate(session, modelRequest(config, [], memory.prompt), new AbortController().signal, () => {}, undefined, memory.tools)).rejects.toThrow('记忆已关闭或变更')
    expect(execute).not.toHaveBeenCalled()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  test('工具参数完整但 SSE 提前断流时不执行搜索', async () => {
    const values = records(), memory = conversationMemory(values, 'current', async () => values)!
    const execute = spyOn(memory.tools, 'execute')
    fetchMock = spyOn(globalThis, 'fetch').mockResolvedValue(stream([{ choices: [{ delta: { tool_calls: [{ index: 0, id: 'call', function: { name: 'everytalk_memory', arguments: searchArguments } }] } }] }]))
    await expect(generate(session, modelRequest(config, [], memory.prompt), new AbortController().signal, () => {}, undefined, memory.tools)).rejects.toThrow('提前中断')
    expect(execute).not.toHaveBeenCalled()
  })

  test('供应商截断与非法 JSON 参数不会执行工具', async () => {
    for (const finish of ['length', 'content_filter']) {
      const values = records(), memory = conversationMemory(values, 'current', async () => values)!
      const execute = spyOn(memory.tools, 'execute')
      fetchMock = spyOn(globalThis, 'fetch').mockResolvedValue(stream([{ choices: [{ delta: { tool_calls: [{ index: 0, id: 'call', function: { name: 'everytalk_memory', arguments: searchArguments } }] }, finish_reason: finish }] }], true))
      await expect(generate(session, modelRequest(config, [], ''), new AbortController().signal, () => {}, undefined, memory.tools)).rejects.toThrow('未完整结束')
      expect(execute).not.toHaveBeenCalled(); fetchMock.mockRestore()
    }
    const values = records(), memory = conversationMemory(values, 'current', async () => values)!
    fetchMock = spyOn(globalThis, 'fetch').mockResolvedValue(stream([{ choices: [{ delta: { tool_calls: [{ index: 0, id: 'call', function: { name: 'everytalk_memory', arguments: '{' } }] }, finish_reason: 'tool_calls' }] }], true))
    await expect(generate(session, modelRequest(config, [], ''), new AbortController().signal, () => {}, undefined, memory.tools)).rejects.toThrow('不是完整 JSON')
  })

  test('搜索循环有轮数上限，取消后也不会再请求模型', async () => {
    const values = records(), memory = conversationMemory(values, 'current', async () => values)!
    fetchMock = spyOn(globalThis, 'fetch').mockImplementation(Object.assign(async () => stream(toolFrames('openai'), true), { preconnect: fetch.preconnect }))
    await expect(generate(session, modelRequest(config, [], ''), new AbortController().signal, () => {}, undefined, memory.tools)).rejects.toThrow('轮数过多')
    expect(fetchMock).toHaveBeenCalledTimes(8)
    const controller = new AbortController(); controller.abort()
    await expect(generate(session, modelRequest(config, [], ''), controller.signal, () => {}, undefined, memory.tools)).rejects.toThrow()
    expect(fetchMock).toHaveBeenCalledTimes(8)
  })
})
