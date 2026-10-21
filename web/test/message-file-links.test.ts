import { describe, expect, test } from 'bun:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MessageFileLink, MessageFileText, messageFileParts } from '../src/MessageFileLink'
import type { StoredRecord } from '../src/types'

const file = (id: string, name: string): StoredRecord => ({ id, key: `attachments:${id}`, entity: 'attachments', deleted: false,
  payload: { name, mime: 'application/octet-stream', mediaId: `android-${id}`, role: 'media' } })
const files = [file('pdf', '报告.pdf'), file('sheet', '预算.xlsx')]

describe('正文文件链接', () => {
  test('文件链接位于原正文位置且匹配安卓原附件ID', () => {
    const parts = messageFileParts('先看[报告](everytalk-file:android-pdf)，这里再看[预算](everytalk-file:android-sheet)，继续说明。', files)
    expect(parts.filter(part => part.attachment).map(part => [part.text, part.attachment!.id])).toEqual([['报告', 'pdf'], ['预算', 'sheet']])
    const html = renderToStaticMarkup(createElement(MessageFileText, { parts, renderFile: (attachment, label) => createElement(MessageFileLink, { attachment, label, onSave: async () => {} }) }))
    expect(html.indexOf('报告')).toBeLessThan(html.indexOf('这里再看'))
    expect(html.indexOf('预算')).toBeLessThan(html.indexOf('继续说明'))
    expect(html).toContain('文件操作：报告.pdf')
    expect(html).not.toContain('everytalk-file:')
  })

  test('旧文件名就地转换，代码块和外部网址保持原文', () => {
    const content = '请看报告.pdf，然后看 `预算.xlsx`。\n```\n报告.pdf\n```\n[报告.pdf](https://example.com/报告.pdf)'
    const parts = messageFileParts(content, files)
    expect(parts.filter(part => part.attachment).map(part => part.attachment!.id)).toEqual(['pdf', 'sheet'])
    expect(parts.map(part => part.text).join('')).toContain('```\n报告.pdf\n```')
    expect(parts.map(part => part.text).join('')).toContain('[报告.pdf](https://example.com/报告.pdf)')
    expect(messageFileParts('报告.pdf.bak', files).some(part => part.attachment)).toBe(false)
  })

  test('外部附件ID不绑定；重名必须显式使用ID；没有正文引用仍可兜底展示', () => {
    const duplicate = [...files, file('other', '报告.pdf')]
    expect(messageFileParts('报告.pdf', duplicate).some(part => part.attachment)).toBe(false)
    expect(messageFileParts('[第二份](everytalk-file:android-other)', duplicate).find(part => part.attachment)?.attachment?.id).toBe('other')
    expect(messageFileParts('[秘密](everytalk-file:foreign)', files).some(part => part.attachment)).toBe(false)
    const html = renderToStaticMarkup(createElement(MessageFileLink, { attachment: files[1], savedPath: 'AppTalk/预算.xlsx', onSave: async () => {} }))
    expect(html).toContain('lucide-file-spreadsheet')
    expect(html).toContain('AppTalk/预算.xlsx')
    expect(html).not.toContain('paperclip')
  })
})
