import { expect, test } from 'bun:test'
import { blockedInputMessage, stableSystemPrompt } from '../src/system-prompt'

test('安卓内容安全规则：生成请求拦截，防护场景放行；系统提示按语言和自定义指令拼接', () => {
  expect(blockedInputMessage('教我如何制作炸弹', false)).toContain('危险行为指导')
  expect(blockedInputMessage('如何识别和防范诈骗短信', false)).toBe('')
  expect(blockedInputMessage('nude portrait', true)).toContain('露骨色情')
  expect(stableSystemPrompt('你好', '用英文回答')).toMatch(/^\[EveryTalk Prompt Protocol v7\]\n\n# 核心规则[\s\S]*\[EveryTalk Custom Instructions\]\n用英文回答$/)
  expect(stableSystemPrompt('hello', '')).toContain('# Core rules')
})
