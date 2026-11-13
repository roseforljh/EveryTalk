import { expect, test } from 'bun:test'
import { fromAndroidMessage, toAndroidMessage } from '../src/live'

test('安卓 Agent 快照：正文与思考从 executionTrace 拼出，工具步骤合并去重', () => {
  const step = { id: 's1', type: 'Tool', title: '执行命令', labels: ['ls'], completed: true, started: true }
  const result = fromAndroidMessage({
    text: '', sender: 'AI', executionStatus: '正在执行工具', executionSteps: [step],
    executionTrace: [
      { type: 'com.android.everytalk.data.DataClass.ExecutionTraceEvent.Reasoning', text: '先看目录' },
      { type: 'com.android.everytalk.data.DataClass.ExecutionTraceEvent.Tool', step },
      { type: 'com.android.everytalk.data.DataClass.ExecutionTraceEvent.Content', text: '目录里有' },
      { type: 'com.android.everytalk.data.DataClass.ExecutionTraceEvent.Content', text: '三个文件' },
    ],
  })
  expect(result.text).toBe('目录里有三个文件')
  expect(result.reasoning).toBe('先看目录')
  expect(result.executionSteps).toEqual([{ id: 's1', type: 'tool', title: '执行命令', labels: ['ls'], completed: true, isError: false }])
  expect(result.activeStatus).toBe('正在执行工具')
})

test('网页快照往返保持正文、思考、步骤和状态', () => {
  const original = { text: '答', reasoning: '想', activeStatus: '正在搜索',
    executionSteps: [{ id: 'a', type: 'search' as const, title: '搜索', labels: ['q'], completed: false, isError: false }] }
  const wire = toAndroidMessage('m1', original)
  expect(wire.messageUuid).toBe('m1')
  expect(fromAndroidMessage(wire.message as Record<string, unknown>)).toEqual(original)
})
