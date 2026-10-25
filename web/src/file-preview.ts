import { attachmentDownloadKey, safeDownloadName } from './file-downloads'
import { downloadResource } from './resources'
import type { SyncClient } from './sync'
import type { AuthSession, StoredRecord } from './types'

export function filePresentation(name: string, mime: string) {
  mime = mime.split(';')[0].trim().toLowerCase()
  const extension = name.split('.').pop()!.toLowerCase()
  if (extension === 'pdf' || mime === 'application/pdf') return { kind: 'pdf', color: 'light-dark(#c62828,#ff8585)', preview: 'pdf' } as const
  if (/^(csv|tsv|xls|xlsx|ods)$/.test(extension)) return { kind: 'sheet', color: 'light-dark(#167347,#6ed5a0)', preview: /^(csv|tsv)$/.test(extension) ? 'text' : undefined } as const
  if (/^(ppt|pptx|odp)$/.test(extension)) return { kind: 'slides', color: 'light-dark(#b44e16,#ffad79)', preview: undefined } as const
  if (/^(zip|rar|7z|gz|tar)$/.test(extension)) return { kind: 'archive', color: 'light-dark(#8a6510,#e3c46c)', preview: undefined } as const
  // HTML/SVG/XML 仅显示源码，不能在本站执行附件脚本。
  if (/^(json|js|ts|tsx|jsx|py|kt|java|html|htm|svg|xml|css|sql|sh|yaml|yml|rs|go|c|cpp)$/.test(extension) || ['image/svg+xml', 'application/xhtml+xml', 'application/xml', 'application/json'].includes(mime)) return { kind: 'code', color: 'light-dark(#087b75,#6bd6d0)', preview: 'text' } as const
  if (mime.startsWith('image/') || /^(png|jpg|jpeg|webp|gif|bmp)$/.test(extension)) return { kind: 'image', color: 'light-dark(#7843b5,#c5a2ff)', preview: 'image' } as const
  if (mime.startsWith('audio/') || /^(mp3|wav|flac|ogg|m4a)$/.test(extension)) return { kind: 'audio', color: 'light-dark(#ab3674,#f39bc6)', preview: 'audio' } as const
  if (mime.startsWith('video/') || /^(mp4|webm|mov|mkv)$/.test(extension)) return { kind: 'video', color: 'light-dark(#6550b4,#b8acff)', preview: 'video' } as const
  return { kind: 'document', color: 'light-dark(#2468b2,#87bbff)', preview: mime.startsWith('text/') || /^(txt|md|log)$/.test(extension) ? 'text' : undefined } as const
}

type LocalFiles = Pick<SyncClient, 'downloadPreferences' | 'cachedAttachment' | 'cacheAttachment'>
const pending = new WeakMap<LocalFiles, Map<string, Promise<File>>>()

/** 已保存目录优先，其次账号本地缓存，最后才接收一次；重复点击共用读取，绝不创建下载副本。 */
export function readLocalAttachment(client: LocalFiles, attachment: StoredRecord, load: () => Promise<Uint8Array<ArrayBuffer>>): Promise<File> {
  const key = attachmentDownloadKey(attachment)
  let requests = pending.get(client)
  if (!requests) { requests = new Map(); pending.set(client, requests) }
  const existing = requests.get(key)
  if (existing) return existing
  const request = (async () => {
    const preferences = await client.downloadPreferences()
    const savedName = preferences?.saved[key]
    if (preferences && savedName) {
      if (await preferences.directory.queryPermission({ mode: 'readwrite' }) !== 'granted') throw new Error('请在文件保存设置中恢复目录权限')
      // 已保存回执失效时明确报错，不悄悄重新下载已删除或移动的文件。
      return (await preferences.directory.getFileHandle(savedName)).getFile()
    }
    let blob = await client.cachedAttachment(key)
    if (!blob) {
      const bytes = await load()
      blob = new Blob([bytes], { type: String(attachment.payload.mime ?? 'application/octet-stream') })
      await client.cacheAttachment(key, blob)
    }
    return new File([blob], safeDownloadName(attachment.payload.name), { type: blob.type })
  })().finally(() => { requests.delete(key) })
  requests.set(key, request)
  return request
}

export function receiveFileForPreview(client: SyncClient, session: AuthSession, records: StoredRecord[], attachment: StoredRecord, refresh: () => Promise<AuthSession>): Promise<File> {
  return readLocalAttachment(client, attachment, () => downloadResource(client, session, records, attachment, refresh))
}
