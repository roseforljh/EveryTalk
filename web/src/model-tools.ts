import { isObject } from './sync-core'
import type { ModelProtocol, ModelResult } from './model'
import type { JsonObject } from './types'

export interface ModelToolCall { id: string; name: string; arguments: JsonObject }
export interface ModelTools {
  definitions: { name: string; description: string; parameters: JsonObject }[]
  /** 每轮发送前和工具执行时重新读取账号记录，关闭记忆后不能沿用旧授权。 */
  validate: () => Promise<void>
  execute: (call: ModelToolCall) => Promise<JsonObject>
}

/** 把同一份本机工具定义转成供应商原生合同，模型仍自行决定是否调用。 */
export function withModelTools(body: JsonObject, protocol: ModelProtocol, definitions: ModelTools['definitions']): JsonObject {
  // 与 Android 保持一致：完整 JSON Schema 必须走 parametersJsonSchema，避免额外字段被 Gemini 的 Schema 子集拒绝。
  if (protocol === 'gemini') return { ...body, tools: [{ functionDeclarations: definitions.map(({ name, description, parameters }) => ({ name, description, parametersJsonSchema: parameters })) }] }
  if (protocol === 'anthropic') return { ...body, tools: definitions.map(({ name, description, parameters }) => ({ name, description, input_schema: parameters })) }
  if (protocol === 'responses') return { ...body, include: [...new Set([...(Array.isArray(body.include) ? body.include : []), 'reasoning.encrypted_content'])], tools: definitions.map(tool => ({ type: 'function', ...tool, strict: false })) }
  return { ...body, tools: definitions.map(tool => ({ type: 'function', function: tool })) }
}

/**
 * 累积一轮模型响应中的工具参数与原始内容块。
 * 供应商的思考签名和 Responses reasoning 项必须原样带回下一轮，不能只重建函数名称和参数。
 */
export class ModelToolTurn {
  private readonly items = new Map<number, JsonObject>()
  private readonly inputJson = new Map<number, string>()
  private readonly geminiParts: JsonObject[] = []

  constructor(private readonly protocol: ModelProtocol) {}

  accept(value: JsonObject): void {
    if (this.protocol === 'openai' && Array.isArray(value.choices)) {
      const choice = value.choices[0]
      const delta = isObject(choice) ? (isObject(choice.delta) ? choice.delta : choice.message) : undefined
      if (isObject(delta) && Array.isArray(delta.tool_calls)) delta.tool_calls.forEach((call, position) => {
        if (!isObject(call) || !isObject(call.function)) return
        const index = typeof call.index === 'number' ? call.index : position
        const previous = this.items.get(index)
        const priorFunction = isObject(previous?.function) ? previous.function : {}
        this.items.set(index, { ...previous, ...call, type: 'function', function: {
          ...priorFunction, ...call.function,
          arguments: String(priorFunction.arguments ?? '') + String(call.function.arguments ?? ''),
        } })
      })
    } else if (this.protocol === 'responses') {
      if ((value.type === 'response.output_item.added' || value.type === 'response.output_item.done') && isObject(value.item) && typeof value.output_index === 'number') this.items.set(value.output_index, value.item)
      if (value.type === 'response.function_call_arguments.delta' && typeof value.output_index === 'number' && typeof value.delta === 'string') {
        const item = this.items.get(value.output_index)
        if (item) this.items.set(value.output_index, { ...item, arguments: String(item.arguments ?? '') + value.delta })
      }
      const output = Array.isArray(value.output) ? value.output : isObject(value.response) && Array.isArray(value.response.output) ? value.response.output : undefined
      if (output) { this.items.clear(); output.forEach((item, index) => { if (isObject(item)) this.items.set(index, item) }) }
    } else if (this.protocol === 'anthropic') {
      if (value.type === 'content_block_start' && typeof value.index === 'number' && isObject(value.content_block)) this.items.set(value.index, { ...value.content_block })
      if (value.type === 'content_block_delta' && typeof value.index === 'number' && isObject(value.delta)) {
        const item = this.items.get(value.index), delta = value.delta
        if (item) {
          for (const key of ['text', 'thinking']) if (typeof delta[key] === 'string') item[key] = String(item[key] ?? '') + delta[key]
          if (typeof delta.signature === 'string') item.signature = delta.signature
          if (typeof delta.partial_json === 'string') this.inputJson.set(value.index, (this.inputJson.get(value.index) ?? '') + delta.partial_json)
        }
      }
      if (Array.isArray(value.content)) value.content.forEach((item, index) => { if (isObject(item)) this.items.set(index, item) })
    } else if (this.protocol === 'gemini' && Array.isArray(value.candidates)) {
      const candidate = value.candidates[0]
      if (isObject(candidate) && isObject(candidate.content) && Array.isArray(candidate.content.parts)) {
        this.geminiParts.push(...candidate.content.parts.filter(isObject))
      }
    }
  }

  private orderedItems(): JsonObject[] {
    return [...this.items].sort(([a], [b]) => a - b).map(([index, item]) => {
      const input = this.inputJson.get(index)
      return input === undefined ? item : { ...item, input: this.arguments(input) }
    })
  }

  private arguments(value: unknown): JsonObject {
    let parsed: unknown = value
    if (typeof value === 'string') {
      try { parsed = JSON.parse(value) } catch { throw new Error('模型工具参数不是完整 JSON，本轮搜索未执行') }
    }
    if (!isObject(parsed)) throw new Error('模型工具参数必须是对象')
    return parsed
  }

  calls(): ModelToolCall[] {
    const items = this.protocol === 'gemini' ? this.geminiParts : this.orderedItems()
    const calls: ModelToolCall[] = []
    for (const item of items) {
      const fn = this.protocol === 'openai' ? item.function : this.protocol === 'gemini' ? item.functionCall : item
      if (!isObject(fn)) continue
      if (this.protocol === 'responses' && item.type !== 'function_call') continue
      if (this.protocol === 'anthropic' && item.type !== 'tool_use') continue
      const id = this.protocol === 'responses' ? item.call_id : this.protocol === 'gemini' ? fn.id ?? `gemini-${calls.length}` : item.id
      if (typeof id !== 'string' || !id || typeof fn.name !== 'string' || !fn.name) throw new Error('模型工具调用缺少身份信息')
      const args = this.protocol === 'anthropic' ? item.input : this.protocol === 'gemini' ? fn.args ?? {} : fn.arguments
      calls.push({ id, name: fn.name, arguments: this.arguments(args) })
    }
    if (calls.length > 8 || new Set(calls.map(call => call.id)).size !== calls.length) throw new Error('模型工具调用数量或身份无效')
    return calls
  }

  /** 只修改本轮内存请求，不把工具轨迹、历史片段或思考签名写进聊天和同步记录。 */
  continue(body: JsonObject, result: ModelResult, calls: ModelToolCall[], outputs: JsonObject[]): JsonObject {
    const prior = (key: string): unknown[] => Array.isArray(body[key]) ? body[key] : []
    if (this.protocol === 'responses') return { ...body, input: [...prior('input'), ...this.orderedItems(), ...calls.map((call, index) => ({ type: 'function_call_output', call_id: call.id, output: JSON.stringify(outputs[index]) }))] }
    if (this.protocol === 'anthropic') return { ...body, messages: [...prior('messages'), { role: 'assistant', content: this.orderedItems() }, { role: 'user', content: calls.map((call, index) => ({ type: 'tool_result', tool_use_id: call.id, content: JSON.stringify(outputs[index]), is_error: outputs[index]?.ok === false })) }] }
    if (this.protocol === 'gemini') {
      const providerIds = this.geminiParts.flatMap(part => isObject(part.functionCall) ? [part.functionCall.id] : [])
      return { ...body, contents: [...prior('contents'), { role: 'model', parts: this.geminiParts }, { role: 'user', parts: calls.map((call, index) => ({ functionResponse: { name: call.name, response: outputs[index], ...(typeof providerIds[index] === 'string' ? { id: providerIds[index] } : {}) } })) }] }
    }
    return { ...body, messages: [...prior('messages'), { role: 'assistant', content: result.text || null, ...(result.reasoning ? { reasoning_content: result.reasoning } : {}), tool_calls: this.orderedItems().map(({ index: _index, ...item }) => item) }, ...calls.map((call, index) => ({ role: 'tool', tool_call_id: call.id, content: JSON.stringify(outputs[index]) }))] }
  }
}
