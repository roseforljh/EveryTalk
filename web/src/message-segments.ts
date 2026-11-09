import { isObject } from './sync-core'
import type { JsonObject } from './types'

/** AI 消息里工具调用前后穿插的可见正文/思考片段。 */
export interface DisplaySegment { type: 'content' | 'reasoning'; text: string }
/** 用户消息里文字与 Skill 标签的顺序结构。 */
export type BodySegment =
  | { type: 'text'; text: string }
  | { type: 'skill'; skillId: string; displayName: string; sourceType: string }

/** 归档回填后用于显示的完整内容。 */
export interface ArchiveDisplay { text: string; reasoning: string; segments?: DisplaySegment[] }

/**
 * 解析 Android 合同里的 displaySegments。
 * 片段可能只带 UTF-16 位置引用；越界或类型未知的片段直接丢弃，由调用方回退到原正文。
 */
export function displaySegments(payload: JsonObject): DisplaySegment[] | undefined {
  if (!Array.isArray(payload.displaySegments)) return undefined
  const segments: DisplaySegment[] = []
  for (const item of payload.displaySegments) {
    if (!isObject(item) || (item.type !== 'content' && item.type !== 'reasoning')) continue
    const type = item.type
    const source = type === 'reasoning' ? payload.reasoning : payload.text
    let text: string | undefined
    if (typeof item.text === 'string') text = item.text
    else if (typeof item.startUtf16 === 'number' && typeof item.lengthUtf16 === 'number' && typeof source === 'string' &&
      Number.isSafeInteger(item.startUtf16) && Number.isSafeInteger(item.lengthUtf16) &&
      item.startUtf16 >= 0 && item.lengthUtf16 >= 0 && item.startUtf16 + item.lengthUtf16 <= source.length) {
      text = source.slice(item.startUtf16, item.startUtf16 + item.lengthUtf16)
    }
    if (text !== undefined) segments.push({ type, text })
  }
  return segments.length ? segments : undefined
}

/** 解析 Android 合同里的 bodySegments；未知类型与空结构按缺省处理。 */
export function bodySegments(payload: JsonObject): BodySegment[] | undefined {
  if (!Array.isArray(payload.bodySegments)) return undefined
  const segments: BodySegment[] = []
  for (const item of payload.bodySegments) {
    if (!isObject(item)) continue
    if (item.type === 'text') segments.push({ type: 'text', text: typeof item.text === 'string' ? item.text : '' })
    else if (item.type === 'skill' && typeof item.skillId === 'string') segments.push({
      type: 'skill', skillId: item.skillId,
      displayName: typeof item.displayName === 'string' ? item.displayName : '',
      sourceType: typeof item.sourceType === 'string' ? item.sourceType : '',
    })
  }
  return segments.length ? segments : undefined
}

/**
 * 从 Android SyncHistoryArchive 中取出可显示的完整正文、思考和有序片段。
 * 归档里的 ExecutionTraceEvent 使用 kotlinx 默认类型判别字段，序列名可能带包名前缀。
 */
export function archiveDisplay(history: JsonObject): ArchiveDisplay | undefined {
  const message = history.message
  if (!isObject(message)) return undefined
  const text = typeof message.text === 'string' ? message.text : ''
  const reasoning = typeof message.reasoning === 'string' ? message.reasoning : ''
  const segments: DisplaySegment[] = []
  if (Array.isArray(message.executionTrace)) for (const event of message.executionTrace) {
    if (!isObject(event) || typeof event.type !== 'string' || typeof event.text !== 'string') continue
    const kind = event.type.slice(event.type.lastIndexOf('.') + 1)
    if (kind === 'Content' || kind === 'Reasoning') segments.push({ type: kind === 'Content' ? 'content' : 'reasoning', text: event.text })
  }
  if (!text && !reasoning && !segments.length) return undefined
  return { text, reasoning, ...(segments.length ? { segments } : {}) }
}
