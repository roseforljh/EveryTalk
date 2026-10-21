'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { attachmentDownloadKey, automaticDownloads, saveReceivedFile, type DirectoryPickerWindow, type DownloadPreferences } from './file-downloads'
import { downloadResource } from './resources'
import type { AuthSession, StoredRecord } from './types'
import type { SyncClient } from './sync'

/** 页面存活时接收新附件；浏览器关闭时不承诺后台下载，重开后凭回执补收。 */
export function useFileDownloads(client: SyncClient | undefined, records: StoredRecord[], session: AuthSession, refreshSession: () => Promise<AuthSession>) {
  const [preferences, setPreferences] = useState<DownloadPreferences>()
  const [status, setStatus] = useState('')
  const [error, setError] = useState('')
  const [supported, setSupported] = useState(false)
  const [savedPaths, setSavedPaths] = useState<Record<string, string>>({})
  const latest = useRef({ records, session, refreshSession })
  latest.current = { records, session, refreshSession }
  const lifetime = useRef<{ alive: boolean; client: SyncClient } | undefined>(undefined)

  const withLock = useCallback(async <T,>(work: () => Promise<T>): Promise<T> => {
    if (!navigator.locks) throw new Error('当前浏览器不支持目录自动保存，请使用桌面版 Chrome 或 Edge')
    return navigator.locks.request('everytalk-file-downloads', work)
  }, [])

  useEffect(() => {
    setSupported(typeof (window as DirectoryPickerWindow).showDirectoryPicker === 'function' && !!navigator.locks)
    setPreferences(undefined); setStatus(''); setError(''); setSavedPaths({})
    if (!client) return
    const current = { alive: true, client }
    lifetime.current = current
    void client.downloadPreferences().then(value => { if (current.alive) {
      setPreferences(value)
      setSavedPaths(Object.fromEntries(Object.entries(value?.saved ?? {}).map(([key, name]) => [key, `${value!.directory.name}/${name}`])))
    } })
      .catch(value => { if (current.alive) setError(value instanceof Error ? value.message : '无法读取保存目录') })
    return () => { current.alive = false; if (lifetime.current === current) lifetime.current = undefined }
  }, [client])

  const save = useCallback(async (attachment: StoredRecord, automatic = false): Promise<boolean> => {
    const current = lifetime.current
    if (!current?.alive || !preferences) return false
    const result = await withLock(() => saveReceivedFile(
      { read: () => current.client.downloadPreferences(), write: value => current.client.saveDownloadPreferences(value) },
      preferences.id, attachment,
      () => downloadResource(current.client, latest.current.session, latest.current.records, attachment, latest.current.refreshSession),
      () => current.alive, automatic,
    ))
    if (current.alive && result) {
      setStatus(`已保存：${result}`)
      const key = attachmentDownloadKey(attachment)
      setSavedPaths(previous => previous[key] === result ? previous : { ...previous, [key]: result })
      if (!automatic) setError('')
    }
    return Boolean(result)
  }, [preferences, withLock])

  // 不因普通记录刷新取消正在写入的文件；回执及跨标签页锁保证重复触发不会重复落盘。
  useEffect(() => {
    const current = lifetime.current
    if (!current?.alive || !preferences?.enabled || !supported) return
    let stopped = false
    void (async () => {
      let failure = ''
      for (const attachment of automaticDownloads(records, preferences.since)) {
        if (stopped || !current.alive) break
        try { await save(attachment, true) }
        catch (value) {
          failure = `文件未自动保存：${String(attachment.payload.name ?? '附件')}：${value instanceof Error ? value.message : '写入失败'}。可点击附件重试。`
        }
      }
      if (current.alive && !stopped) setError(failure)
    })()
    return () => { stopped = true }
  }, [records, preferences, supported, save])

  const choose = async (): Promise<void> => {
    const current = lifetime.current, picker = (window as DirectoryPickerWindow).showDirectoryPicker
    if (!current?.alive || !picker) { setError('此浏览器不支持指定目录，请使用桌面版 Chrome 或 Edge；附件仍可点击下载'); return }
    try {
      // 目录选择必须直接由点击触发，不能先等待网络或数据库读取。
      const directory = await picker.call(window, { id: 'apptalk-downloads', mode: 'readwrite', startIn: 'downloads' })
      if (!current.alive) return
      const value: DownloadPreferences = { directory, id: crypto.randomUUID(), enabled: true, since: Date.now(), saved: {} }
      await withLock(async () => { if (current.alive) await current.client.saveDownloadPreferences(value) })
      if (current.alive) { setPreferences(value); setSavedPaths({}); setStatus(`新 AI 附件将保存到：${directory.name}`); setError('') }
    } catch (value) {
      if (current.alive && !(value instanceof DOMException && value.name === 'AbortError')) setError(value instanceof Error ? value.message : '目录选择失败')
    }
  }

  const authorize = async (): Promise<void> => {
    const current = lifetime.current
    if (!current?.alive || !preferences) return
    try {
      if (await preferences.directory.requestPermission({ mode: 'readwrite' }) !== 'granted') throw new Error('未获得目录写入权限')
      if (current.alive) { setError(''); setPreferences({ ...preferences }) }
    } catch (value) { if (current.alive) setError(value instanceof Error ? value.message : '目录授权失败') }
  }

  const toggle = async (): Promise<void> => {
    const current = lifetime.current
    if (!current?.alive) return
    try {
      await withLock(async () => {
        const previous = await current.client.downloadPreferences()
        if (!previous || !current.alive) return
        const value = { ...previous, enabled: !previous.enabled }
        await current.client.saveDownloadPreferences(value)
        if (current.alive) { setPreferences(value); setError('') }
      })
    } catch (value) { if (current.alive) setError(value instanceof Error ? value.message : '保存设置失败') }
  }

  return { preferences, status, error, supported, choose, authorize, toggle, save, savedPaths }
}
