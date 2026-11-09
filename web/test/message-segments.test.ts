import { describe, expect, test } from 'bun:test'
import { archiveDisplay, bodySegments, displaySegments } from '../src/message-segments'

describe('同步消息片段解析', () => {
  test('displaySegments 优先 text，越界引用按 UTF-16 下标丢弃', () => {
    const payload = {
      text: '第一段第二段',
      reasoning: '思考一思考二',
      displaySegments: [
        { type: 'reasoning', startUtf16: 0, lengthUtf16: 3 },
        { type: 'content', startUtf16: 0, lengthUtf16: 3 },
        { type: 'content', text: '工具后补充' },
        { type: 'content', startUtf16: 10, lengthUtf16: 2 },
        { type: 'tool' },
      ],
    }
    expect(displaySegments(payload)).toEqual([
      { type: 'reasoning', text: '思考一' },
      { type: 'content', text: '第一段' },
      { type: 'content', text: '工具后补充' },
    ])
    expect(displaySegments({ text: '只有正文' })).toBeUndefined()
    // JS 字符串下标本身就是 UTF-16，与 Android String.substring 一致。
    expect(displaySegments({ text: '😀正文', displaySegments: [{ type: 'content', startUtf16: 2, lengthUtf16: 2 }] }))
      .toEqual([{ type: 'content', text: '正文' }])
  })

  test('bodySegments 保留文字与 Skill 标签顺序', () => {
    expect(bodySegments({ bodySegments: [
      { type: 'text', text: '用 ' },
      { type: 'skill', skillId: 's1', displayName: '翻译', sourceType: 'LOCAL_IMPORT', contentHash: 'h' },
      { type: 'text', text: ' 翻译这句话' },
      { type: 'unknown' },
    ] })).toEqual([
      { type: 'text', text: '用 ' },
      { type: 'skill', skillId: 's1', displayName: '翻译', sourceType: 'LOCAL_IMPORT' },
      { type: 'text', text: ' 翻译这句话' },
    ])
    expect(bodySegments({})).toBeUndefined()
  })

  test('归档解析完整正文、思考与穿插片段', () => {
    expect(archiveDisplay({ message: {
      id: 'm', conversationId: 's', text: '全文', reasoning: '全思考',
      executionTrace: [
        { type: 'com.android.everytalk.data.DataClass.ExecutionTraceEvent.Reasoning', text: '先想' },
        { type: 'com.android.everytalk.data.DataClass.ExecutionTraceEvent.Tool', step: {} },
        { type: 'com.android.everytalk.data.DataClass.ExecutionTraceEvent.Content', text: '再说' },
        { type: 'Content', text: '最后' },
      ],
    } })).toEqual({
      text: '全文', reasoning: '全思考',
      segments: [{ type: 'reasoning', text: '先想' }, { type: 'content', text: '再说' }, { type: 'content', text: '最后' }],
    })
    expect(archiveDisplay({ message: { text: '' } })).toBeUndefined()
    expect(archiveDisplay({})).toBeUndefined()
  })
})
