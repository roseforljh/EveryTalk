import { resourceParts } from './resources'
import type { StoredRecord } from './types'

/** 浏览器目录权限不属于云端配置；句柄只能存入当前账号的本机 IndexedDB。 */
export interface DownloadDirectory extends FileSystemDirectoryHandle {
  queryPermission(options: { mode: 'readwrite' }): Promise<PermissionState>
  requestPermission(options: { mode: 'readwrite' }): Promise<PermissionState>
}

export interface DownloadPreferences {
  directory: DownloadDirectory
  id: string
  enabled: boolean
  since: number
  saved: Record<string, string>
}

export type DirectoryPickerWindow = Window & {
  showDirectoryPicker?: (options: { id: string; mode: 'readwrite'; startIn: 'downloads' }) => Promise<DownloadDirectory>
}

/** 文件名只取最后一段，并去掉跨平台非法字符；绝不接受来自附件的本地目录。 */
export function safeDownloadName(value: unknown): string {
  const name = (typeof value === 'string' ? value : 'file').split(/[\\/]/).pop()!
    .replace(/[<>:"|?*\x00-\x1f\x7f]/g, '_').replace(/^[. ]+|[. ]+$/g, '').slice(0, 180).replace(/[. ]+$/g, '')
  return !name ? 'file' : /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(name) ? `_${name}` : name
}

export function attachmentDownloadKey(record: StoredRecord): string {
  return JSON.stringify([record.id, record.payload.resourceVersion, record.payload.originalDigest ?? record.payload.sha256])
}

/** 只处理启用后生成的 AI 附件；用户上传、归档、缺块和已删除会话均不能触发自动保存。 */
export function automaticDownloads(records: StoredRecord[], since: number): StoredRecord[] {
  const sessions = new Set(records.filter(r => r.entity === 'sessions' && !r.deleted).map(r => r.id))
  const messages = new Set(records.filter(r => r.entity === 'messages' && !r.deleted && r.payload.sender === 'AI' &&
    Number(r.payload.timestamp) >= since && sessions.has(String(r.payload.sessionId))).map(r => r.id))
  return records.filter(record => {
    const p = record.payload
    if (record.deleted || record.entity !== 'attachments' || !messages.has(String(p.messageId)) ||
      (p.role !== undefined && p.role !== 'media') || (p.chunkCount && p.chunkIndex !== 0)) return false
    try { return resourceParts(records, record).every(part => typeof part.payload.fileId === 'string') }
    catch { return false }
  })
}

/**
 * 调用方持有浏览器跨标签页锁。同名文件使用编号，写入失败中止临时流并清除新建空文件。
 * createWritable 在 close 成功前不会发布新内容，HTML 等文件只保存、不在页面中执行。
 */
export async function writeDownload(directory: DownloadDirectory, suggestedName: unknown, bytes: Uint8Array<ArrayBuffer>): Promise<string> {
  const name = safeDownloadName(suggestedName), dot = name.lastIndexOf('.')
  const stem = dot > 0 ? name.slice(0, dot) : name, extension = dot > 0 ? name.slice(dot) : ''
  for (let index = 0; index < 10_000; index++) {
    const candidate = index === 0 ? name : `${stem} (${index})${extension}`
    try { await directory.getFileHandle(candidate); continue }
    catch (error) {
      if (!(error instanceof DOMException) || error.name !== 'NotFoundError') {
        if (error instanceof DOMException && error.name === 'TypeMismatchError') continue
        throw error
      }
    }
    const file = await directory.getFileHandle(candidate, { create: true })
    let stream: FileSystemWritableFileStream | undefined
    try {
      stream = await file.createWritable()
      await stream.write(bytes)
      await stream.close()
      return candidate
    } catch (error) {
      await stream?.abort().catch(() => undefined)
      await directory.removeEntry(candidate).catch(() => undefined)
      throw error
    }
  }
  throw new Error('同名文件过多，请整理保存目录')
}

/** 锁覆盖权限检查、网络校验、磁盘写入和回执，防止多个标签页同时保存相同附件。 */
export async function saveReceivedFile(
  preferences: { read: () => Promise<DownloadPreferences | undefined>; write: (value: DownloadPreferences) => Promise<void> },
  directoryId: string,
  attachment: StoredRecord,
  loadBytes: () => Promise<Uint8Array<ArrayBuffer>>,
  active: () => boolean,
  automatic = false,
): Promise<string | undefined> {
  const current = await preferences.read()
  if (!active() || !current || current.id !== directoryId || (automatic && !current.enabled)) return
  const key = attachmentDownloadKey(attachment)
  // 自动同步去重；用户主动点击附件仍可重新保存，支持本机副本已被移动或删除的情况。
  if (automatic && Object.hasOwn(current.saved, key)) return `${current.directory.name}/${current.saved[key]}`
  if (await current.directory.queryPermission({ mode: 'readwrite' }) !== 'granted') {
    throw new Error('自动保存目录需要重新授权，请在“设置 → 文件保存”点击“恢复授权”')
  }
  const bytes = await loadBytes()
  if (!active()) return
  const name = await writeDownload(current.directory, attachment.payload.name, bytes)
  // 回执只在完整写入后保存。配额失败会报错，绝不把未落盘的文件标成成功。
  await preferences.write({ ...current, saved: { ...current.saved, [key]: name } })
  return `${current.directory.name}/${name}`
}
