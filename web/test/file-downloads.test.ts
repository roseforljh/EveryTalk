import 'fake-indexeddb/auto'
import { describe, expect, test } from 'bun:test'
import { automaticDownloads, safeDownloadName, saveReceivedFile, writeDownload, type DownloadDirectory, type DownloadPreferences } from '../src/file-downloads'
import { SyncStore } from '../src/sync-store'
import type { StoredRecord } from '../src/types'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { SettingsPanel } from '../src/SettingsPanel'

/** 用内存目录模拟浏览器文件句柄，验证真实保存流程的提交、撤销和同名处理。 */
function directory() {
  const files = new Map<string, Uint8Array<ArrayBuffer>>()
  let permission: PermissionState = 'granted', failWrite = false, aborted = false
  const handle = {
    name: 'AppTalk', kind: 'directory',
    queryPermission: async () => permission,
    requestPermission: async () => permission,
    getFileHandle: async (name: string, options?: { create?: boolean }) => {
      if (!files.has(name)) {
        if (!options?.create) throw new DOMException('missing', 'NotFoundError')
        files.set(name, new Uint8Array())
      }
      return {
        createWritable: async () => {
          let pending = new Uint8Array()
          return {
            write: async (bytes: Uint8Array<ArrayBuffer>) => { if (failWrite) throw new Error('磁盘已满'); pending = bytes.slice() },
            close: async () => { files.set(name, pending) },
            abort: async () => { aborted = true },
          }
        },
      }
    },
    removeEntry: async (name: string) => { files.delete(name) },
  } as unknown as DownloadDirectory
  return { handle, files, deny: () => { permission = 'denied' }, fail: () => { failWrite = true }, aborted: () => aborted }
}

const record = (entity: StoredRecord['entity'], id: string, payload: Record<string, unknown>): StoredRecord => ({ entity, id, key: `${entity}:${id}`, payload, deleted: false })
const attachment = record('attachments', 'file-1', { name: '报告.txt', messageId: 'ai', role: 'media', fileId: 'cloud-file', sha256: 'version-1' })

describe('客户端固定目录保存', () => {
  test('不接受目录穿越、控制字符及 Windows 设备名', () => {
    expect(safeDownloadName('../../报告.txt')).toBe('报告.txt')
    expect(safeDownloadName('C:\\private\\report.txt')).toBe('report.txt')
    expect(safeDownloadName('..')).toBe('file')
    expect(safeDownloadName('CON.txt')).toBe('_CON.txt')
    expect(safeDownloadName('报告\u0000.txt')).toBe('报告_.txt')
  })

  test('同名保留两份；写入失败中止并清理空文件', async () => {
    const dir = directory()
    await writeDownload(dir.handle, '报告.txt', new Uint8Array([1, 2]))
    expect(await writeDownload(dir.handle, '报告.txt', new Uint8Array([3]))).toBe('报告 (1).txt')
    expect([...dir.files.get('报告.txt')!]).toEqual([1, 2])
    dir.fail()
    await expect(writeDownload(dir.handle, '报告.txt', new Uint8Array([4]))).rejects.toThrow('磁盘已满')
    expect(dir.aborted()).toBe(true)
    expect(dir.files.size).toBe(2)
  })

  test('重复同步不重复下载；权限失效和退出账号都不写入、不登记成功', async () => {
    const dir = directory()
    let value: DownloadPreferences = { directory: dir.handle, id: 'directory-1', enabled: true, since: 1, saved: {} }
    const store = { read: async () => value, write: async (next: DownloadPreferences) => { value = next } }
    let requests = 0
    const load = async () => { requests++; return new Uint8Array([1]) }
    await saveReceivedFile(store, 'directory-1', attachment, load, () => true, true)
    expect(await saveReceivedFile(store, 'directory-1', attachment, load, () => true, true)).toBe('AppTalk/报告.txt')
    expect(requests).toBe(1)
    await saveReceivedFile(store, 'directory-1', attachment, load, () => true)
    await saveReceivedFile(store, 'directory-1', attachment, load, () => true)
    expect(requests).toBe(1)
    expect(dir.files.size).toBe(1)
    const next = { ...attachment, id: 'file-2' }
    dir.deny()
    await expect(saveReceivedFile(store, 'directory-1', next, load, () => true)).rejects.toThrow('重新授权')
    expect(await saveReceivedFile(store, 'directory-1', next, load, () => false)).toBeUndefined()
    expect(Object.keys(value.saved)).toHaveLength(1)
    expect(requests).toBe(1)
  })

  test('已有自动保存回执时，手动点击仍能重新保存被删除的副本', async () => {
    const dir = directory()
    let value: DownloadPreferences = { directory: dir.handle, id: 'd', enabled: true, since: 1, saved: {} }
    const store = { read: async () => value, write: async (next: DownloadPreferences) => { value = next } }
    const load = async () => new Uint8Array([1])
    await saveReceivedFile(store, 'd', attachment, load, () => true, true)
    dir.files.clear()
    await saveReceivedFile(store, 'd', attachment, load, () => true)
    expect([...dir.files.get('报告.txt')!]).toEqual([1])
  })

  test('网络等待时退出页面以及磁盘失败均不留下成功回执', async () => {
    const dir = directory()
    let active = true
    const value: DownloadPreferences = { directory: dir.handle, id: 'd', enabled: true, since: 1, saved: {} }
    let writes = 0
    const store = { read: async () => value, write: async () => { writes++ } }
    await saveReceivedFile(store, 'd', attachment, async () => { active = false; return new Uint8Array([1]) }, () => active)
    expect(dir.files.size).toBe(0)
    active = true; dir.fail()
    await expect(saveReceivedFile(store, 'd', attachment, async () => new Uint8Array([1]), () => active)).rejects.toThrow('磁盘已满')
    expect(writes).toBe(0)
    expect(dir.files.size).toBe(0)
  })

  test('暂停自动保存及目录已更换时不读取远端文件', async () => {
    const dir = directory()
    const value: DownloadPreferences = { directory: dir.handle, id: 'new', enabled: false, since: 1, saved: {} }
    const store = { read: async () => value, write: async () => {} }
    const load = async (): Promise<Uint8Array<ArrayBuffer>> => { throw new Error('不能请求网络') }
    expect(await saveReceivedFile(store, 'old', attachment, load, () => true, true)).toBeUndefined()
    expect(await saveReceivedFile(store, 'new', attachment, load, () => true, true)).toBeUndefined()
    expect(dir.files.size).toBe(0)
  })

  test('只自动接收新 AI 完整附件，不保存用户上传和历史归档', () => {
    const session = record('sessions', 'session', {})
    const message = record('messages', 'ai', { sessionId: 'session', sender: 'AI', timestamp: 100 })
    const user = record('messages', 'user', { sessionId: 'session', sender: 'User', timestamp: 100 })
    const records = [session, message, user, attachment,
      { ...attachment, id: 'uploaded', payload: { ...attachment.payload, messageId: 'user' } },
      { ...attachment, id: 'archive', payload: { ...attachment.payload, role: 'message_archive' } },
      { ...attachment, id: 'incomplete', payload: { ...attachment.payload, chunkCount: 2, chunkIndex: 0, resourceVersion: 'v2' } },
    ]
    expect(automaticDownloads(records, 100).map(r => r.id)).toEqual(['file-1'])
    expect(automaticDownloads(records, 101)).toEqual([])
    session.deleted = true
    expect(automaticDownloads(records, 0)).toEqual([])
  })

  test('目录和回执持久化且账号隔离，不进入云端同步队列', async () => {
    const namespace = crypto.randomUUID()
    let store = await SyncStore.open(namespace)
    const other = await SyncStore.open(`${namespace}-other`)
    try {
      // fake-indexeddb 不实现原生可克隆句柄；使用纯数据替身验证持久化边界。
      const value: DownloadPreferences = { directory: { name: 'AppTalk', kind: 'directory' } as DownloadDirectory, id: 'd', enabled: true, since: 123, saved: { file: 'report.txt' } }
      await store.saveDownloadPreferences(value)
      store.close(); store = await SyncStore.open(namespace)
      expect(await store.downloadPreferences()).toEqual(value)
      expect(await other.downloadPreferences()).toBeUndefined()
      expect((await store.snapshot()).pending).toEqual([])
      expect((await store.snapshot()).records).toEqual([])
    } finally { store.close(); other.close() }
  })

  test('错误入口直接打开文件保存页，包含当前目录与恢复授权入口', () => {
    const noop = async () => {}
    const html = renderToStaticMarkup(createElement(SettingsPanel, {
      records: [], onClose: () => {}, saveSetting: noop, saveMemory: noop, error: '', reportError: () => {}, initialTab: 'files',
      downloads: {
        preferences: { directory: directory().handle, id: 'd', enabled: true, since: 1, saved: {} },
        status: '', error: '等待目录授权', supported: true, choose: noop, authorize: noop, toggle: noop, save: async () => true, savedPaths: {},
      },
    }))
    expect(html).toContain('AI 文件自动保存')
    expect(html).toContain('当前目录：AppTalk')
    expect(html).toContain('恢复授权')
    expect(html).toContain('暂停自动保存')
    expect(html).toContain('等待目录授权')
  })
})
