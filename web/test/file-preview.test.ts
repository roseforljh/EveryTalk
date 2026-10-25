import 'fake-indexeddb/auto'
import { expect, test } from 'bun:test'
import { filePresentation, readLocalAttachment } from '../src/file-preview'
import { attachmentDownloadKey, type DownloadDirectory, type DownloadPreferences } from '../src/file-downloads'
import { SyncStore } from '../src/sync-store'
import type { StoredRecord } from '../src/types'

const attachment: StoredRecord = { entity: 'attachments', id: 'a', key: 'attachments:a', deleted: false,
  payload: { name: '报告.txt', mime: 'text/plain', sha256: 'version-1' } }

test('同时点击、再次打开及重开数据库只接收一次；版本和账号分别缓存', async () => {
  const namespace = crypto.randomUUID()
  let store = await SyncStore.open(namespace)
  const other = await SyncStore.open(`${namespace}-other`)
  let loads = 0
  const load = async () => { loads++; return new TextEncoder().encode('本地内容') }
  try {
    const files = await Promise.all([readLocalAttachment(store, attachment, load), readLocalAttachment(store, attachment, load)])
    expect(await files[0].text()).toBe('本地内容')
    expect(await files[1].text()).toBe('本地内容')
    await readLocalAttachment(store, attachment, load)
    store.close(); store = await SyncStore.open(namespace)
    expect(await (await readLocalAttachment(store, attachment, load)).text()).toBe('本地内容')
    expect(loads).toBe(1)
    await readLocalAttachment(store, { ...attachment, payload: { ...attachment.payload, sha256: 'version-2' } }, load)
    expect(loads).toBe(2)
    await readLocalAttachment(other, attachment, load)
    expect(loads).toBe(3)
    expect((await store.snapshot()).pending).toEqual([])
    expect((await store.snapshot()).records).toEqual([])
  } finally { store.close(); other.close() }
})

test('已有目录文件优先只读；丢失或无权限时不暗中重新下载', async () => {
  let missing = false, denied = false, loads = 0, cacheReads = 0
  const preferences: DownloadPreferences = {
    id: 'd', enabled: true, since: 1, saved: { [attachmentDownloadKey(attachment)]: '报告.txt' },
    directory: { name: 'AppTalk', queryPermission: async () => denied ? 'denied' : 'granted',
      getFileHandle: async (name: string, options?: { create?: boolean }) => {
        expect(options?.create).not.toBe(true)
        expect(name).toBe('报告.txt')
        if (missing) throw new DOMException('missing', 'NotFoundError')
        return { getFile: async () => new File(['磁盘文件'], name, { type: 'text/plain' }) }
      },
    } as unknown as DownloadDirectory,
  }
  const client = { downloadPreferences: async () => preferences,
    cachedAttachment: async () => { cacheReads++; return undefined }, cacheAttachment: async () => {} }
  const load = async () => { loads++; return new Uint8Array([1]) }
  expect(await (await readLocalAttachment(client, attachment, load)).text()).toBe('磁盘文件')
  expect(await (await readLocalAttachment(client, attachment, load)).text()).toBe('磁盘文件')
  missing = true
  await expect(readLocalAttachment(client, attachment, load)).rejects.toThrow('missing')
  denied = true
  await expect(readLocalAttachment(client, attachment, load)).rejects.toThrow('恢复目录权限')
  expect(loads).toBe(0)
  expect(cacheReads).toBe(0)
})

test('类型配色各异，主动内容仅显示源码，Office 不假装可预览', () => {
  const names = ['a.pdf', 'a.xlsx', 'a.pptx', 'a.zip', 'a.png', 'a.mp3', 'a.mp4', 'a.ts', 'a.docx']
  expect(new Set(names.map(name => filePresentation(name, '').color)).size).toBe(names.length)
  expect(filePresentation('a.csv', '').preview).toBe('text')
  expect(filePresentation('a.xlsx', '').preview).toBeUndefined()
  expect(filePresentation('a.pdf', '').preview).toBe('pdf')
  expect(filePresentation('a.html', 'text/html').preview).toBe('text')
  expect(filePresentation('a.svg', 'image/svg+xml').preview).toBe('text')
  expect(filePresentation('无扩展名', 'image/svg+xml').preview).toBe('text')
})
