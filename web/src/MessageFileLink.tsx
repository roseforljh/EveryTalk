'use client'

import { useState, type ReactNode } from 'react'
import { FileArchive, FileAudio, FileCode, FileImage, FileSpreadsheet, FileText, FileVideo, Presentation } from 'lucide-react'
import type { StoredRecord } from './types'
import { filePresentation } from './file-preview'

type FileTextPart = { text: string; attachment?: StoredRecord }
const escapeRegex = (text: string): string => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/**
 * 网页原正文为纯文本：这里只接管已绑定附件的链接，不扩展成一套 Markdown 渲染器。
 * 代码块、外部 URL、其他消息的附件 ID 保持原文，避免把模型提供的路径当成本机地址。
 */
export function messageFileParts(text: string, attachments: StoredRecord[]): FileTextPart[] {
  const files = attachments.filter(file => file.payload.role !== 'message_archive')
  const names = new Map<string, StoredRecord[]>()
  for (const file of files) {
    const name = String(file.payload.name ?? '').split(/[\\/]/).pop()!
    if (name) names.set(name, [...names.get(name) ?? [], file])
  }
  const uniqueNames = [...names].filter(([, matches]) => matches.length === 1).map(([name]) => name).sort((a, b) => b.length - a.length)
  const pattern = new RegExp('```[^]*?(?:```|$)|~~~[^]*?(?:~~~|$)|`[^`\\n]*`|\\[[^\\]\\n]*\\]\\([^\\s)]*\\)|https?://[^\\s]+|' + (uniqueNames.map(escapeRegex).join('|') || '(?!)'), 'g')
  const result: FileTextPart[] = []
  let offset = 0
  for (const match of text.matchAll(pattern)) {
    result.push({ text: text.slice(offset, match.index) })
    const token = match[0]
    let file: StoredRecord | undefined, label = token
    const link = /^\[([^\]]*)\]\(([^)]*)\)$/.exec(token)
    if (link) {
      const destination = link[2]
      if (destination.startsWith('everytalk-file:')) {
        const id = destination.slice('everytalk-file:'.length)
        const matches = files.filter(item => item.id === id || item.payload.mediaId === id)
        if (matches.length === 1) file = matches[0]
      } else if (!destination.includes(':') || destination.startsWith('sandbox:/')) {
        try {
          const matches = names.get(decodeURIComponent(destination.split('/').pop()!))
          if (matches?.length === 1) file = matches[0]
        } catch { /* 非法转义保留原文。 */ }
      }
      if (file) label = link[1] || String(file.payload.name)
    } else if (!token.startsWith('```') && !token.startsWith('~~~') && !/^https?:/.test(token)) {
      const name = token.startsWith('`') ? token.slice(1, -1) : token
      const previous = text[match.index - 1], next = text[match.index + token.length]
      const matches = names.get(name)
      if (matches?.length === 1 && !/[A-Za-z0-9_.-]/.test(next ?? '') && !/[A-Za-z0-9_-]/.test(previous ?? '')) {
        file = matches[0]; label = name
      }
    }
    result.push({ text: label, attachment: file })
    offset = match.index + token.length
  }
  result.push({ text: text.slice(offset) })
  return result
}

function FileTypeIcon({ attachment }: { attachment: StoredRecord }) {
  const { kind } = filePresentation(String(attachment.payload.name ?? ''), String(attachment.payload.mime ?? ''))
  const Icon = { sheet: FileSpreadsheet, slides: Presentation, archive: FileArchive, image: FileImage,
    audio: FileAudio, video: FileVideo, code: FileCode, pdf: FileText, document: FileText }[kind]
  return <Icon size={17} aria-hidden="true" />
}

/** 文件位置和操作属于该链接，不在聊天顶部固定展示。浏览器只显示它实际具备的操作。 */
export function MessageFileLink({ attachment, label, savedPath, onSave, onOpen, onShare }: {
  attachment: StoredRecord; label?: string; savedPath?: string; onSave: () => Promise<void>; onOpen?: () => Promise<void>; onShare?: () => Promise<void>
}) {
  const [menu, setMenu] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const name = String(attachment.payload.name ?? '文件')
  const run = async (action: () => Promise<void>): Promise<void> => {
    setBusy(true); setError('')
    try { await action() } catch (value) { setError(value instanceof Error ? value.message : '文件操作失败') }
    finally { setBusy(false) }
  }
  const presentation = filePresentation(name, String(attachment.payload.mime ?? ''))
  const preview = presentation.preview ? onOpen : undefined
  return <span className="et-inline-file" style={{ color: presentation.color }}>
    <button type="button" className="et-file-icon" aria-label={`文件操作：${name}`} aria-expanded={menu} onClick={() => setMenu(!menu)}><FileTypeIcon attachment={attachment} /></button>
    <button type="button" className="et-file-name" title={`${savedPath ? `已保存到 ${savedPath}；` : ''}${preview ? '点击预览文件' : '点击查看文件操作'}`} disabled={busy} onClick={() => preview ? void run(preview) : setMenu(true)}>{label || name}</button>
    {menu && <span className="et-file-actions" role="group" aria-label={`${name}的文件操作`}>
      <span>{savedPath ? `已保存：${savedPath}` : '此文件属于当前消息'}</span>
      {preview && <button type="button" disabled={busy} onClick={() => void run(preview)}>预览文件</button>}
      <button type="button" disabled={busy} onClick={() => void run(onSave)}>{savedPath ? '检查已保存文件' : '保存文件'}</button>
      {onShare && <button type="button" disabled={busy} onClick={() => void run(onShare)}>用其他应用打开或分享</button>}
      <small>网页无法直接打开系统文件夹；可从浏览器下载记录或已选目录打开。</small>
      <button type="button" onClick={() => setMenu(false)}>关闭</button>
    </span>}
    {error && <span role="alert" className="et-file-error">{error}</span>}
  </span>
}

export function MessageFileText({ parts, renderFile }: { parts: FileTextPart[]; renderFile: (attachment: StoredRecord, label: string) => ReactNode }) {
  return <>{parts.map((part, index) => <span key={index}>{part.attachment ? renderFile(part.attachment, part.text) : part.text}</span>)}</>
}
