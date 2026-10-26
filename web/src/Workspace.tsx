/* eslint-disable @next/next/no-img-element */
'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Link from 'next/link'
import { ArrowUp, Check, ChevronDown, Copy, FileDown, Flag, LogOut, Menu, MessageSquare, Mic, MoreHorizontal, Paperclip, Pin, Plus, RefreshCw, Search, Settings, Square, Trash2, Volume2, X } from 'lucide-react'
import { ThemeToggle } from '@/components/ui/ThemeProvider'
import { builtinModels, isBuiltinModel, withBuiltinModels } from './builtin-models'
import { SettingsPanel } from './SettingsPanel'
import { ModelIcon } from './ModelIcon'
import { authClient, authenticatedFetch } from './auth'
import { useWorkspaceSync } from './use-workspace-sync'
import { useFileDownloads } from './use-file-downloads'
import { MessageFileLink, MessageFileText, messageFileParts } from './MessageFileLink'
import { MessageFilePreview } from './MessageFilePreview'
import { receiveFileForPreview } from './file-preview'
import { attachmentDownloadKey } from './file-downloads'
import { generate, generateImage, imageRequest, modelRequest, nativeWebSearch, withNativeWebSearch, type ModelAttachment, type ModelResult } from './model'
import { readHistory, uploadResource } from './resources'
import { isObject } from './sync-core'
import { syncKey, type AuthSession, type JsonObject, type StoredRecord, type SyncEntity } from './types'
import type { SyncClient } from './sync'
import { getSpeechRecognition, normalizeVoiceLanguage, speakText, type BrowserSpeechRecognition } from './voice'
import { normalizeSearchProvider, readSearchResults, type SearchResult } from './search'
import { conversationMemory } from './conversation-memory'
import { builtinTools } from './builtin-tools'
import { combineTools } from './model-tools'
import { blockedInputMessage, stableSystemPrompt } from './system-prompt'
import { reportCategories } from './report'

const str = (value: unknown, fallback = ''): string => typeof value === 'string' ? value : fallback
const valueOf = (record?: StoredRecord): JsonObject => record && isObject(record.payload.value) ? record.payload.value : {}
const recordOf = (entity: SyncEntity, id: string, payload: JsonObject, deleted = false): StoredRecord => ({ key: syncKey(entity, id), entity, id, payload, deleted })
const updated = (old?: StoredRecord): number => Math.max(Date.now(), Number(old?.payload.clientModifiedAt ?? 0) + 1)
const MAX_MODEL_IMAGE_BYTES = 3 * 1024 * 1024
const MAX_GENERATED_IMAGE_BYTES = 32 * 1024 * 1024

/** 把图片接口返回的 data URL 变成附件文件，正文只保存稳定文字和附件元数据。 */
function dataUrlFile(dataUrl: string, index: number): File {
  const match = /^data:(image\/[a-z0-9.+-]+);base64,([a-z0-9+/=]+)$/i.exec(dataUrl)
  if (!match) throw new Error('图片返回格式无效')
  const binary = atob(match[2]); const bytes = new Uint8Array(binary.length)
  for (let offset = 0; offset < binary.length; offset++) bytes[offset] = binary.charCodeAt(offset)
  if (bytes.length < 1 || bytes.length > MAX_GENERATED_IMAGE_BYTES) throw new Error('生成图片超过 32 MiB 限制')
  return new File([bytes], `generated-${index + 1}.${match[1].split('/')[1].replace('jpeg', 'jpg')}`, { type: match[1] })
}

/** 图片缩略图与文件预览共享本地缓存，消息刷新时不再次请求远端文件。 */
function SyncedImage({ attachment, records, client, session, refreshSession }: { attachment: StoredRecord; records: StoredRecord[]; client: SyncClient; session: AuthSession; refreshSession: () => Promise<AuthSession> }) {
  const [source, setSource] = useState('')
  useEffect(() => {
    let cancelled = false; let objectUrl = ''
    void receiveFileForPreview(client, session, records, attachment, refreshSession).then(file => {
      if (cancelled) return
      objectUrl = URL.createObjectURL(file)
      setSource(objectUrl)
    }).catch(() => undefined)
    return () => { cancelled = true; if (objectUrl) URL.revokeObjectURL(objectUrl) }
  }, [attachment, client, records, session, refreshSession])
  return source ? <img src={source} alt={str(attachment.payload.name, '图片')} /> : <span className="et-image-loading">加载图片…</span>
}

/**
 * 只把小图片转成模型需要的 data URL。
 * 原文件已经由附件上传保存，非图片和超出本次模型预算的文件继续保留为可下载附件。
 */
async function modelAttachments(files: File[]): Promise<ModelAttachment[]> {
  let total = 0
  const result: ModelAttachment[] = []
  for (const file of files) {
    if (!file.type.startsWith('image/') || file.size < 1 || total + file.size > MAX_MODEL_IMAGE_BYTES) continue
    const bytes = new Uint8Array(await file.arrayBuffer())
    let binary = ''
    for (let offset = 0; offset < bytes.length; offset += 0x8000) binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000))
    result.push({ mime: file.type, dataUrl: `data:${file.type};base64,${btoa(binary)}` })
    total += file.size
  }
  return result
}

/** Qone 的中性侧栏与中央对话布局。数据操作统一走账号同步事务，流式草稿不进入云端。 */
export function Workspace({ session, onSignOut }: { session: AuthSession; onSignOut: () => Promise<void> }) {
  const sync = useWorkspaceSync(session, () => { void onSignOut() })
  const { snapshot, ready, runtime, sessionRef, changed, refresh, setError } = sync
  const [selected, setSelected] = useState<string>()
  const [draft, setDraft] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const [query, setQuery] = useState('')
  const [mobile, setMobile] = useState(false)
  const [settings, setSettings] = useState<'models' | 'files'>()
  const [menu, setMenu] = useState(false)
  const [rename, setRename] = useState(false)
  const [title, setTitle] = useState('')
  const [group, setGroup] = useState('')
  const [groupFilter, setGroupFilter] = useState('')
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const [streaming, setStreaming] = useState<ModelResult & { sessionId: string }>()
  const [listening, setListening] = useState(false)
  const [speakingMessage, setSpeakingMessage] = useState('')
  const controller = useRef<AbortController | undefined>(undefined)
  const recognition = useRef<BrowserSpeechRecognition | undefined>(undefined)
  const voiceBase = useRef('')
  const [history, setHistory] = useState<{ id: string; value: JsonObject }>()
  const [previewFile, setPreviewFile] = useState<File>()
  useEffect(() => { setPreviewFile(undefined) }, [session.user.id])
  const [copied, setCopied] = useState('')
  const [fileSharing, setFileSharing] = useState(false)
  const [report, setReport] = useState<{ message: StoredRecord; category: string; details: string; sending: boolean }>()
  // 与安卓本地举报记录一样，同一条消息只举报一次；按账号保存在本机。
  const reportedKey = `everytalk-web-reported:${session.user.id}`
  const [reported, setReported] = useState<string[]>([])
  useEffect(() => { try { const saved: unknown = JSON.parse(localStorage.getItem(reportedKey) ?? '[]'); setReported(Array.isArray(saved) ? saved.filter((id): id is string => typeof id === 'string') : []) } catch { setReported([]) } }, [reportedKey])
  const messagesEnd = useRef<HTMLDivElement>(null)
  const input = useRef<HTMLTextAreaElement>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const liveRecords = useRef(snapshot.records)
  liveRecords.current = snapshot.records
  const records = useMemo(() => withBuiltinModels(snapshot.records), [snapshot.records])
  const imageClient = runtime.current?.client
  const settingsRows = useMemo(() => records.filter(r => r.entity === 'user_settings' && !r.deleted), [records])
  const setting = (kind: string, key: string) => settingsRows.find(r => r.payload.kind === kind && r.payload.key === key)
  const models = settingsRows.filter(r => r.payload.kind === 'api_configs')
  const pinned = new Set(settingsRows.filter(r => r.payload.kind === 'pinned_items').map(r => str(r.payload.key)))
  const memberships = settingsRows.filter(r => r.payload.kind === 'group_members')
  const groups = [...new Set(memberships.map(r => str(valueOf(r).groupName)).filter(Boolean))]
  const sessions = useMemo(() => records.filter(r => r.entity === 'sessions' && !r.deleted)
    .sort((a, b) => Number(b.payload.lastModifiedTimestamp ?? 0) - Number(a.payload.lastModifiedTimestamp ?? 0)), [records])
  const sortedSessions = [...sessions].sort((a, b) => Number(pinned.has(b.id)) - Number(pinned.has(a.id)))
  const activeId = selected && sessions.some(s => s.id === selected) ? selected : sessions[0]?.id
  const active = sessions.find(s => s.id === activeId)
  const messages = useMemo(() => records.filter(r => r.entity === 'messages' && !r.deleted && r.payload.sessionId === activeId)
    .sort((a, b) => Number(a.payload.timestamp) - Number(b.payload.timestamp)), [records, activeId])
  const conversation = setting('conversation_settings', activeId ?? '')
  const conversationOptions = valueOf(conversation)
  const webSearchEnabled = conversationOptions.webSearchEnabled === true
  const searchSetting = setting('selected_models', 'web_search')
  const searchConfigs = settingsRows.filter(record => record.payload.kind === 'web_search_configs')
  const selectedSearchId = str(valueOf(searchSetting).providerId)
  const selectedSearch = searchConfigs.find(record => str(record.payload.key) === selectedSearchId) ?? searchConfigs[0]
  // 旧图片会话仅在尚未保存逐会话模型时沿用原图片默认选择。
  const imageSession = active?.payload.isImageGeneration === true
  const preferredModel = str(valueOf(conversation).modelId, str(valueOf(setting('selected_models', imageSession ? 'image' : 'text')).configId))
  // 没有选择时按会话类型取第一个同类模型，避免新账号只有内置生图模型时文字会话默认进入生图。
  const selectedModel = models.find(m => m.payload.key === preferredModel) ?? models.find(m => (valueOf(m).imageConfig === true) === imageSession) ?? models[0]
  const imageMode = valueOf(selectedModel).imageConfig === true
  const modelLabel = str(valueOf(selectedModel).name, str(valueOf(selectedModel).model, '选择模型'))
  const voiceSetting = settingsRows.find(record => record.payload.kind === 'voice_configs')
  const voiceLanguage = normalizeVoiceLanguage(valueOf(voiceSetting).language)
  const name = str(session.user.user_metadata?.everytalk_name, str(session.user.user_metadata?.full_name, session.user.email?.split('@')[0] ?? 'EveryTalk 用户'))

  useEffect(() => () => { controller.current?.abort(); recognition.current?.stop(); window.speechSynthesis?.cancel() }, [])
  useEffect(() => { setFileSharing(typeof navigator.share === 'function' && typeof navigator.canShare === 'function') }, [])
  useEffect(() => { messagesEnd.current?.scrollIntoView({ block: 'end', behavior: 'instant' }) }, [messages.length, streaming?.text, activeId])
  useEffect(() => { setRename(false); setMenu(false); setHistory(undefined) }, [activeId])

  const run = async (work: () => Promise<void>): Promise<void> => {
    try { await work() } catch (value) { if (runtime.current?.alive) setError(value instanceof Error ? value.message : '操作失败') }
  }
  const currentRuntime = () => {
    const current = runtime.current
    if (!current?.ready || !current.alive) throw new Error('工作区尚未准备好')
    return current
  }
  /** 资源和密钥请求共用同一刷新出口；刷新后只替换内存令牌，不重建工作区。 */
  const refreshSession = useCallback(async (): Promise<AuthSession> => {
    const renewed = await authClient.refresh(sessionRef.current)
    sessionRef.current = renewed
    return renewed
  }, [sessionRef])
  const downloads = useFileDownloads(ready ? imageClient : undefined, records, session, refreshSession)
  const toggleVoiceInput = (): void => {
    if (listening) { recognition.current?.stop(); return }
    const current = getSpeechRecognition(window)
    if (!current) { setError('当前浏览器不支持语音输入，请使用最新版 Chrome 或 Edge'); return }
    voiceBase.current = draft.trim() ? `${draft.trim()} ` : ''
    current.lang = voiceLanguage; current.continuous = false; current.interimResults = true
    current.onresult = event => {
      let transcript = ''
      for (let index = 0; index < event.results.length; index++) transcript += event.results[index]?.[0]?.transcript ?? ''
      setDraft(`${voiceBase.current}${transcript}`)
    }
    current.onerror = event => { setListening(false); recognition.current = undefined; if (event.error !== 'aborted') setError(`语音输入失败：${event.error || '浏览器未返回原因'}`) }
    current.onend = () => { setListening(false); recognition.current = undefined }
    recognition.current = current; setError(''); setListening(true)
    try { current.start() } catch { setListening(false); recognition.current = undefined; setError('语音输入无法启动，请检查浏览器麦克风权限') }
  }
  const readAloud = (messageId: string, text: string): void => {
    if (!window.speechSynthesis) { setError('当前浏览器不支持语音朗读'); return }
    if (speakingMessage === messageId) { window.speechSynthesis.cancel(); setSpeakingMessage(''); return }
    window.speechSynthesis.cancel()
    if (speakText(window, text, voiceLanguage, () => setSpeakingMessage(current => current === messageId ? '' : current))) setSpeakingMessage(messageId)
  }
  const saveSetting = async (kind: string, key: string, value: JsonObject, old?: StoredRecord, deleted = false): Promise<void> => {
    if (!key) throw new Error('配置标识不能为空')
    const previous = old ?? liveRecords.current.find(r => r.entity === 'user_settings' && r.payload.kind === kind && r.payload.key === key)
    if (kind === 'api_configs' && (isBuiltinModel(value, key) || (previous && isBuiltinModel(valueOf(previous), key)))) throw new Error('内置默认配置不可修改或删除')
    const current = currentRuntime()
    if (!deleted) {
      await current.crypto.unlock(current.client, sessionRef.current, snapshot.secretsEpoch ?? 0, refreshSession)
      const encoded = await current.crypto.encodeSettings(value, kind, key)
      if (!isObject(encoded)) throw new Error('配置格式无效')
      value = encoded
    }
    const existing = old ?? liveRecords.current.find(r => r.entity === 'user_settings' && r.payload.kind === kind && r.payload.key === key)
    await current.client.saveLocal('user_settings', existing?.id ?? crypto.randomUUID(), { clientModifiedAt: updated(existing), kind, key, value }, deleted)
    await changed()
  }
  const saveMemory = async (content: string, old?: StoredRecord, deleted = false): Promise<void> => {
    if (!content.trim() && !deleted) throw new Error('记忆不能为空')
    const now = updated(old)
    await currentRuntime().client.saveLocal('memories', old?.id ?? crypto.randomUUID(), { ...old?.payload, content: content.trim(), category: old?.payload.category ?? 'OTHER', origin: old?.payload.origin ?? 'MANUAL', enabled: old?.payload.enabled ?? true, createdAt: old?.payload.createdAt ?? now, updatedAt: now, clientModifiedAt: now }, deleted)
    await changed()
  }
  const newSession = async (): Promise<StoredRecord> => {
    const id = crypto.randomUUID(), now = Date.now()
    // 会话归属跟随新建时选中的模型类型，与安卓文字/生图列表一致。
    const record = recordOf('sessions', id, { clientModifiedAt: now, title: '新对话', creationTimestamp: now, lastModifiedTimestamp: now, isImageGeneration: imageMode })
    await currentRuntime().client.saveRecords([record])
    if (selectedModel) await saveSetting('conversation_settings', id, { modelId: str(selectedModel.payload.key) })
    await changed()
    setSelected(id); setMobile(false); setDraft(''); setFiles([])
    return record
  }
  const chooseModel = async (configId: string): Promise<void> => {
    if (activeId) await saveSetting('conversation_settings', activeId, { ...valueOf(conversation), modelId: configId }, conversation)
    else {
      // 没有会话时更新对应类型的全局默认（安卓 selected_models/text 或 image）。
      const scope = valueOf(models.find(m => m.payload.key === configId)).imageConfig === true ? 'image' : 'text'
      await saveSetting('selected_models', scope, { ...valueOf(setting('selected_models', scope)), configId }, setting('selected_models', scope))
    }
  }

  const toggleWebSearch = async (): Promise<void> => {
    if (!activeId) { setError('请先创建对话再开启联网搜索'); return }
    await saveSetting('conversation_settings', activeId, { ...conversationOptions, webSearchEnabled: !webSearchEnabled }, conversation)
  }

  const reply = async (chat: StoredRecord, context: StoredRecord[], filesForModel: File[] = []): Promise<void> => {
    const current = currentRuntime()
    const chosen = selectedModel
    if (!chosen) { setSettings('models'); throw new Error('消息已保存。请添加或同步模型后，点击「继续生成」') }
    if (!navigator.onLine) throw new Error('消息已离线保存，联网后可继续生成回复')
    const state = await current.client.snapshot()
    if (!isBuiltinModel(valueOf(chosen), str(chosen.payload.key))) await current.crypto.unlock(current.client, sessionRef.current, state.secretsEpoch ?? 0, refreshSession)
    const config = isBuiltinModel(valueOf(chosen), str(chosen.payload.key))
      ? builtinModels.find(model => model.model === valueOf(chosen).model)
      : await current.crypto.decodeSettings(valueOf(chosen), 'api_configs', str(chosen.payload.key))
    if (!isObject(config)) throw new Error('模型配置无效')
    const chatSetting = liveRecords.current.find(r => r.entity === 'user_settings' && !r.deleted && r.payload.kind === 'conversation_settings' && r.payload.key === chat.id)
    const options = valueOf(chatSetting)
    const lastUserText = str([...context].reverse().find(message => message.payload.sender === 'User')?.payload.text)
    // 安卓固定前缀 + 会话自定义指令，记忆资料追加在后。
    let system = stableSystemPrompt(lastUserText, options.promptEnabled === true ? str(options.systemPrompt) : '')
    const memory = conversationMemory(state.records, chat.id, async () => {
      if (!current.alive) throw new Error('当前账号工作区已关闭')
      return (await current.client.snapshot()).records
    }, lastUserText)
    if (memory) system += `\n\n${memory.prompt}`
    controller.current = new AbortController()
    const abort = controller.current
    setStreaming({ text: '', reasoning: '', sessionId: chat.id })
    try {
      if (valueOf(chosen).imageConfig === true) {
        const prompt = str([...context].reverse().find(message => message.payload.sender === 'User')?.payload.text).trim()
        if (!prompt) throw new Error('图片提示词不能为空')
        const result = await generateImage(sessionRef.current, imageRequest(config, prompt, await modelAttachments(filesForModel)), abort.signal, refreshSession)
        if (!current.alive || abort.signal.aborted) return
        const fresh = await current.client.snapshot(), storedChat = fresh.records.find(r => r.entity === 'sessions' && r.id === chat.id)
        if (storedChat?.deleted || context.some(m => fresh.records.some(r => r.id === m.id && r.entity === 'messages' && r.deleted))) throw new Error('这段对话已在另一端删除，图片未保存')
        const now = updated(storedChat ?? chat), id = crypto.randomUUID(), resources: StoredRecord[] = []
        for (const [index, dataUrl] of result.images.entries()) resources.push(...await uploadResource(current.client, sessionRef.current, dataUrlFile(dataUrl, index), chat.id, id, refreshSession))
        const text = result.text || '已生成图片'
        await current.client.saveRecords([recordOf('messages', id, { sessionId: chat.id, text, sender: 'AI', timestamp: now, isError: false, outputType: 'image', modelName: str(config.model), providerName: str(config.provider), executionFinishedAt: now }), recordOf('sessions', chat.id, { ...(storedChat ?? chat).payload, clientModifiedAt: now, lastModifiedTimestamp: now }), ...resources])
        await changed()
        return
      }
      let webSearchResults: SearchResult[] = []
      let search: Parameters<typeof builtinTools>[0]['search']
      // 安卓 WebSearchSupport：支持原生搜索的模型直接开启原生搜索，其余模型改由 web_search 工具按需检索。
      const nativeSearch = options.webSearchEnabled === true && nativeWebSearch(config) !== undefined
      if (options.webSearchEnabled === true && !nativeSearch) {
        if (!selectedSearch) throw new Error('请先在设置的“联网搜索”中配置服务商')
        const searchConfig = await current.crypto.decodeSettings(valueOf(selectedSearch), 'web_search_configs', str(selectedSearch.payload.key))
        if (!isObject(searchConfig) || typeof searchConfig.apiKey !== 'string') throw new Error('联网搜索配置无效，请重新保存 API Key')
        const provider = normalizeSearchProvider(searchConfig.providerId ?? selectedSearch.payload.key)
        if (!provider) throw new Error('联网搜索服务商不受支持')
        search = { provider, apiKey: searchConfig.apiKey, onResults: results => {
          webSearchResults = [...webSearchResults, ...results.filter(item => !webSearchResults.some(old => old.url === item.url))]
        } }
      }
      const tools = combineTools(builtinTools({ session: () => sessionRef.current, refreshSession, signal: abort.signal, search }), memory?.tools)
      const request = modelRequest(config, context, system, await modelAttachments(filesForModel))
      const result = await generate(sessionRef.current, nativeSearch ? withNativeWebSearch(request, config) : request, abort.signal, partial => { if (current.alive) setStreaming({ ...partial, sessionId: chat.id }) }, refreshSession, tools)
      if (!current.alive || abort.signal.aborted) return
      const fresh = await current.client.snapshot()
      const storedChat = fresh.records.find(r => r.entity === 'sessions' && r.id === chat.id)
      if (storedChat?.deleted || context.some(m => fresh.records.some(r => r.id === m.id && r.entity === 'messages' && r.deleted))) throw new Error('这段对话已在另一端删除，回复未保存')
      const now = updated(storedChat ?? chat), id = crypto.randomUUID()
      await current.client.saveRecords([
        recordOf('messages', id, { sessionId: chat.id, text: result.text, sender: 'AI', timestamp: now, isError: false, outputType: 'general', modelName: str(config.model), providerName: str(config.provider), ...(result.reasoning ? { reasoning: result.reasoning } : {}), ...(webSearchResults.length ? { webSearchResults } : {}), executionFinishedAt: now }),
        recordOf('sessions', chat.id, { ...(storedChat ?? chat).payload, clientModifiedAt: now, lastModifiedTimestamp: now }),
      ])
      await changed()
    } finally { if (controller.current === abort) controller.current = undefined; if (current.alive) setStreaming(undefined) }
  }

  const send = async (): Promise<void> => {
    if (busyRef.current || (!draft.trim() && !files.length)) return
    // 安卓 AiContentSafetyPolicy：命中拦截规则时不保存、不发送，输入保留在编辑框。
    const blocked = blockedInputMessage(draft.trim(), imageMode)
    if (blocked) { setError(blocked); return }
    busyRef.current = true; setBusy(true); setError('')
    const content = draft.trim(), pendingFiles = files
    try {
      const chat = active ?? await newSession(), current = currentRuntime()
      const now = updated(chat), id = crypto.randomUUID()
      const user = recordOf('messages', id, { sessionId: chat.id, text: content || pendingFiles.map(f => f.name).join('\n'), sender: 'User', timestamp: now, isError: false, outputType: 'general' })
      const resources: StoredRecord[] = []
      for (const file of pendingFiles) resources.push(...await uploadResource(current.client, sessionRef.current, file, chat.id, id, refreshSession))
      if (!current.alive) return
      await current.client.saveRecords([user, recordOf('sessions', chat.id, { ...chat.payload, clientModifiedAt: now, lastModifiedTimestamp: now, title: str(chat.payload.title) === '新对话' ? (content || pendingFiles[0]?.name || '新对话').slice(0, 40) : chat.payload.title }), ...resources])
      setDraft(''); setFiles([]); await changed()
      const context = liveRecords.current.filter(r => r.entity === 'messages' && !r.deleted && r.payload.sessionId === chat.id && r.id !== id).sort((a, b) => Number(a.payload.timestamp) - Number(b.payload.timestamp))
      await reply(chat, [...context, user], pendingFiles)
    } catch (value) {
      if (runtime.current?.alive && !(value instanceof DOMException && value.name === 'AbortError')) setError(value instanceof Error ? value.message : '发送失败，未保存的输入仍在编辑框中')
    } finally { busyRef.current = false; if (runtime.current?.alive) { setBusy(false); input.current?.focus() } }
  }
  const continueReply = async () => {
    if (!active || busyRef.current) return
    busyRef.current = true; setBusy(true)
    try { await run(() => reply(active, messages)) } finally { busyRef.current = false; if (runtime.current?.alive) setBusy(false) }
  }
  const download = async (attachment: StoredRecord) => {
    const current = currentRuntime()
    if (attachment.payload.role === 'message_archive') {
      const value = await readHistory(current.client, current.crypto, sessionRef.current, records, attachment, refreshSession)
      if (current.alive) setHistory({ id: attachment.id, value })
      return
    }
    if (downloads.preferences && downloads.supported) {
      await downloads.authorize()
      if (await downloads.save(attachment)) return
      throw new Error('保存目录已变化，请重新点击附件')
    }
    const localFile = await receiveFileForPreview(current.client, sessionRef.current, records, attachment, refreshSession)
    if (!current.alive) return
    // 使用下载链接，HTML/SVG 等主动内容不会在本站上下文执行。
    const url = URL.createObjectURL(localFile)
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = str(attachment.payload.name, 'file'); anchor.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 10_000)
  }
  const exportLocal = () => {
    const url = URL.createObjectURL(new Blob([JSON.stringify({ exportedAt: new Date().toISOString(), records }, null, 2)], { type: 'application/json' }))
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'everytalk-web-backup.json'; anchor.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  const shareFile = async (attachment: StoredRecord): Promise<void> => {
    const current = currentRuntime()
    const file = await receiveFileForPreview(current.client, sessionRef.current, records, attachment, refreshSession)
    if (!current.alive) return
    if (!navigator.canShare?.({ files: [file] })) throw new Error('当前浏览器不能分享此类文件，请先保存后使用系统打开')
    try { await navigator.share({ files: [file], title: file.name }) }
    catch (value) {
      if (value instanceof DOMException && value.name === 'AbortError') return
      throw new Error('系统未允许此次文件分享，请保存后从本地打开')
    }
  }
  const previewAttachment = async (attachment: StoredRecord): Promise<void> => {
    const current = currentRuntime()
    const file = await receiveFileForPreview(current.client, sessionRef.current, records, attachment, refreshSession)
    if (current.alive) setPreviewFile(file)
  }
  const sendReport = async (): Promise<void> => {
    if (!report || report.sending) return
    const message = report.message
    setReport({ ...report, sending: true })
    try {
      const imageCount = records.filter(r => r.entity === 'attachments' && !r.deleted && r.payload.messageId === message.id && str(r.payload.kind) === 'image' && (!r.payload.chunkCount || r.payload.chunkIndex === 0)).length
      const response = await authenticatedFetch(sessionRef.current, '/api/report', { method: 'POST', headers: { 'Content-Type': 'application/json' }, cache: 'no-store', body: JSON.stringify({
        messageId: message.id, category: report.category, details: report.details, messageText: str(message.payload.text), imageCount,
        isImageGeneration: message.payload.outputType === 'image', modelName: str(message.payload.modelName), providerName: str(message.payload.providerName),
      }) }, refreshSession)
      const body: unknown = await response.json().catch(() => undefined)
      if (!response.ok) throw new Error(isObject(body) && typeof body.error === 'string' ? body.error : `举报失败（HTTP ${response.status}）`)
      const next = [...new Set([...reported, message.id])].slice(-500)
      setReported(next); localStorage.setItem(reportedKey, JSON.stringify(next)); setReport(undefined)
    } catch (value) { setReport(current => current && { ...current, sending: false }); throw value }
  }
  const removeSession = async () => {
    if (!active) return
    await currentRuntime().client.saveLocal('sessions', active.id, { ...active.payload, clientModifiedAt: updated(active) }, true)
    await changed(); setSelected(undefined); setMenu(false)
  }
  const filteredSessions = sortedSessions.filter(item => (!groupFilter || memberships.some(r => valueOf(r).conversationId === item.id && valueOf(r).groupName === groupFilter)) &&
    (!query || str(item.payload.title).toLowerCase().includes(query.toLowerCase()) || records.some(m => m.entity === 'messages' && !m.deleted && m.payload.sessionId === item.id && str(m.payload.text).toLowerCase().includes(query.toLowerCase()))))

  return <main className="et-app et-workspace">
    {previewFile && <MessageFilePreview file={previewFile} onClose={() => setPreviewFile(undefined)} />}
    {mobile && <button className="et-nav-scrim" aria-label="关闭导航" onClick={() => setMobile(false)} />}
    <aside className={`et-sidebar ${mobile ? 'is-open' : ''}`} aria-label="会话导航">
      <div className="et-sidebar-head"><Link href="/" className="et-brand"><img src="/favicon.svg" width="25" height="25" alt="" />EveryTalk<span>Web</span></Link><button className="et-mobile-only" aria-label="关闭导航" onClick={() => setMobile(false)}><X size={18} /></button></div>
      <button className="et-new-chat" disabled={!ready || busy} onClick={() => void run(async () => { await newSession() })}><Plus size={17} />新对话<kbd>＋</kbd></button>
      <label className="et-search"><Search size={15} /><input aria-label="搜索对话" placeholder="搜索对话" value={query} onChange={e => setQuery(e.target.value)} /></label>
      {groups.length > 0 && <select className="et-group-filter" aria-label="筛选分组" value={groupFilter} onChange={e => setGroupFilter(e.target.value)}><option value="">全部对话</option>{groups.map(g => <option key={g}>{g}</option>)}</select>}
      <div className="et-sidebar-label">对话<span>{sessions.length}</span></div>
      <nav className="et-sessions">{filteredSessions.map(item => <button key={item.id} className={`et-session ${item.id === activeId ? 'active' : ''}`} onClick={() => { setSelected(item.id); setMobile(false) }}><MessageSquare size={14} /><span>{str(item.payload.title, '未命名对话') || '未命名对话'}</span>{pinned.has(item.id) && <Pin size={12} />}</button>)}{!filteredSessions.length && <p className="et-empty-list">{query ? '没有匹配的对话' : ready ? '创建对话，或等待安卓端同步' : '正在读取对话…'}</p>}</nav>
      <div className="et-sidebar-bottom"><button className="et-settings-button" onClick={() => setSettings('models')}><Settings size={16} />模型与设置</button><div className="et-account"><span className="et-avatar">{name.slice(0, 1).toUpperCase()}</span><div><strong>{name}</strong><small>{session.user.email}</small></div><button aria-label="退出登录" title="退出当前浏览器" onClick={() => void onSignOut()}><LogOut size={15} /></button></div></div>
    </aside>
    <section className="et-chat">
      <header className="et-chat-header"><div className="et-header-title"><button className="et-mobile-only" aria-label="打开导航" onClick={() => setMobile(true)}><Menu size={19} /></button>{rename ? <form onSubmit={e => { e.preventDefault(); if (active) void run(async () => { await currentRuntime().client.saveLocal('sessions', active.id, { ...active.payload, title: title.trim() || '未命名对话', clientModifiedAt: updated(active) }); await changed(); setRename(false) }) }}><input autoFocus aria-label="会话标题" value={title} onChange={e => setTitle(e.target.value)} onKeyDown={e => { if (e.key === 'Escape') setRename(false) }} /><button aria-label="保存标题"><Check size={16} /></button></form> : <h1>{str(active?.payload.title, 'EveryTalk') || '未命名对话'}</h1>}</div><div className="et-header-actions"><button className="et-sync-status" onClick={() => void refresh()} title="立即同步"><RefreshCw size={13} className={sync.status === '同步中' ? 'et-spin' : ''} /><span>{sync.status}</span>{snapshot.pendingCount > 0 && <small>{snapshot.pendingCount}</small>}</button><ThemeToggle />{active && <div className="et-menu-wrap"><button aria-label="会话操作" onClick={() => setMenu(!menu)}><MoreHorizontal size={19} /></button>{menu && <div className="et-menu"><button onClick={() => { setTitle(str(active.payload.title)); setRename(true); setMenu(false) }}>重命名</button><button onClick={() => void run(async () => { await saveSetting('pinned_items', active.id, {}, setting('pinned_items', active.id), pinned.has(active.id)); setMenu(false) })}>{pinned.has(active.id) ? '取消置顶' : '置顶会话'}</button><form onSubmit={e => { e.preventDefault(); void run(async () => { if (!group.trim()) return; const encoded = btoa(unescape(encodeURIComponent(group.trim()))).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_'); await saveSetting('group_members', `${encoded}.${active.id}`, { groupName: group.trim(), conversationId: active.id }); setGroup(''); setMenu(false) }) }}><input aria-label="分组名称" placeholder="添加到分组" value={group} onChange={e => setGroup(e.target.value)} /><button>添加</button></form><button className="et-danger" disabled={busy} onClick={() => void run(removeSession)}><Trash2 size={13} />删除会话</button></div>}</div>}</div></header>
      {sync.error && <div className="et-banner" role="alert"><span>{sync.error}</span><button aria-label="关闭提示" onClick={() => setError('')}><X size={15} /></button></div>}
      {downloads.error && <div className="et-banner" role="alert"><span>{downloads.error}</span><button onClick={() => setSettings('files')}>文件保存设置</button></div>}
      {snapshot.pausedEpoch !== undefined && <div className="et-banner"><span>云端数据已被清空，同步已暂停以防旧数据重新上传。本地数据仍保留，请先导出备份。</span><button onClick={exportLocal}>导出备份</button></div>}
      {snapshot.rejected > 0 && <div className="et-banner"><span>{snapshot.rejected} 条记录被服务端拒绝，原内容仍在本机。{snapshot.rejected > snapshot.retryableRejected ? `其中 ${snapshot.rejected - snapshot.retryableRejected} 条需重新编辑后才能上传。` : ''}</span>{snapshot.retryableRejected > 0 && <button onClick={() => void run(async () => { await currentRuntime().client.retryRejected(); await refresh() })}>重试</button>}</div>}
      <div className="et-conversation"><div className="et-messages">
        {!messages.length && <div className="et-welcome"><img src="/favicon.svg" alt="" width="48" height="48" /><p className="et-eyebrow">EVERYTALK</p><h2>{active ? '从一个想法开始。' : '今天，想聊些什么？'}</h2><p>安卓端的对话，在这里继续。</p><div className="et-suggestions">{['梳理一个想法', '一起写点东西', '解释一个问题'].map(s => <button key={s} onClick={() => { setDraft(s); input.current?.focus() }}>{s}<ArrowUp size={13} /></button>)}</div></div>}
        {messages.map(message => {
          const attachments = records.filter(r => r.entity === 'attachments' && !r.deleted && r.payload.messageId === message.id && (!r.payload.chunkCount || r.payload.chunkIndex === 0))
          const searchResults = readSearchResults(message.payload.webSearchResults)
          const fileParts = messageFileParts(str(message.payload.text), attachments)
          const referencedIds = new Set(fileParts.flatMap(part => part.attachment ? [part.attachment.id] : []))
          const remainingAttachments = attachments.filter(attachment => !referencedIds.has(attachment.id))
          const renderFile = (attachment: StoredRecord, label?: string) => <MessageFileLink attachment={attachment} label={label} savedPath={downloads.savedPaths[attachmentDownloadKey(attachment)]} onOpen={() => previewAttachment(attachment)} onSave={() => download(attachment)} onShare={fileSharing ? () => shareFile(attachment) : undefined} />
          return <article key={message.id} className={`et-message ${message.payload.sender === 'User' ? 'from-user' : 'from-assistant'}`}><div className="et-message-meta"><span>{message.payload.sender === 'User' ? '你' : str(message.payload.modelName, 'EveryTalk')}</span><time>{new Date(Number(message.payload.timestamp)).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}</time></div>{Boolean(message.payload.reasoning) && <details className="et-reasoning"><summary>思考过程</summary><p>{str(message.payload.reasoning)}</p></details>}<div className="et-message-text"><MessageFileText parts={fileParts} renderFile={renderFile} /></div>{searchResults.length > 0 && <div className="et-search-results"><strong>联网来源</strong>{searchResults.map(item => <a key={item.url} href={item.url} target="_blank" rel="noreferrer">{item.title}</a>)}</div>}{remainingAttachments.length > 0 && <div className="et-attachments">{remainingAttachments.map(attachment => <div key={attachment.id} className="et-attachment-item">{str(attachment.payload.kind) === 'image' && imageClient && <div className="et-image-preview"><SyncedImage attachment={attachment} records={records} client={imageClient} session={sessionRef.current} refreshSession={refreshSession} /></div>}{attachment.payload.role === 'message_archive' ? <button onClick={() => void run(() => download(attachment))}><FileDown size={15} />完整历史</button> : renderFile(attachment)}</div>)}</div>}<div className="et-message-tools"><button aria-label="复制消息" title="复制消息" onClick={() => void run(async () => { await navigator.clipboard.writeText(str(message.payload.text)); setCopied(message.id); window.setTimeout(() => setCopied(''), 1500) })}>{copied === message.id ? <Check size={13} /> : <Copy size={13} />}</button>{message.payload.sender !== 'User' && <button aria-label={speakingMessage === message.id ? '停止朗读' : '朗读消息'} title={speakingMessage === message.id ? '停止朗读' : '浏览器朗读'} onClick={() => readAloud(message.id, str(message.payload.text))}><Volume2 size={13} /></button>}{message.payload.sender !== 'User' && <button aria-label={reported.includes(message.id) ? '已举报' : '举报内容'} title={reported.includes(message.id) ? '已举报' : '举报不当内容'} disabled={reported.includes(message.id)} onClick={() => setReport({ message, category: 'OTHER', details: '', sending: false })}><Flag size={13} /></button>}{message.payload.sender === 'User' && <button onClick={() => { setDraft(str(message.payload.text)); input.current?.focus() }}>引用到输入框</button>}</div></article>
        })}
        {streaming?.sessionId === activeId && <article className="et-message from-assistant" aria-live="polite"><div className="et-message-meta"><span>{modelLabel}</span><span className="et-generating">正在回复</span></div>{streaming.reasoning && <details className="et-reasoning"><summary>思考过程</summary><p>{streaming.reasoning}</p></details>}<div className="et-message-text">{streaming.text || <span className="et-thinking">···</span>}</div></article>}
        {active && messages.length > 0 && !busy && <div className="et-continue"><button onClick={() => void continueReply()}><RefreshCw size={12} />{messages[messages.length - 1]?.payload.sender === 'User' ? '继续生成' : '重新生成'}</button></div>}
        <div ref={messagesEnd} />
      </div></div>
      <div className="et-composer-area"><div className="et-composer">{files.length > 0 && <div className="et-draft-files">{files.map((file, index) => <span key={`${file.name}:${index}`}>{file.name}<button aria-label={`移除 ${file.name}`} onClick={() => setFiles(items => items.filter((_, i) => i !== index))}><X size={12} /></button></span>)}</div>}<textarea ref={input} value={draft} disabled={!ready || listening} aria-label="消息内容" rows={2} placeholder={imageMode ? '描述你想生成的图片…' : ready ? '发送消息，继续你的思考…' : '正在打开工作区…'} onChange={e => setDraft(e.target.value)} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); void send() } }} /><div className="et-composer-toolbar"><div className="et-composer-left"><input ref={fileInput} type="file" multiple className="et-hidden" onChange={e => { setFiles(items => [...items, ...Array.from(e.target.files ?? [])].slice(0, 5)); e.target.value = '' }} /><button aria-label="添加附件" title="附件同步至安卓端，每个不超过 64 MiB" disabled={!ready || busy} onClick={() => fileInput.current?.click()}><Paperclip size={17} /></button><button aria-label={listening ? '停止语音输入' : '语音输入'} title={listening ? '停止语音输入' : '浏览器语音输入'} disabled={!ready || busy} aria-pressed={listening} className={listening ? 'is-active' : ''} onClick={toggleVoiceInput}><Mic size={17} /></button><button aria-label={webSearchEnabled ? '关闭联网搜索' : '开启联网搜索'} title={webSearchEnabled ? '关闭联网搜索' : '开启联网搜索'} disabled={!ready || busy || !activeId || imageMode} aria-pressed={webSearchEnabled} className={webSearchEnabled ? 'is-active' : ''} onClick={() => void run(toggleWebSearch)}><Search size={16} /></button><div className="et-model-select"><ModelIcon model={str(valueOf(selectedModel).model)} /><select aria-label="聊天模型" disabled={busy} value={str(selectedModel?.payload.key)} onChange={e => void run(() => chooseModel(e.target.value))}><option value="" disabled>{imageMode ? '选择图片模型' : '选择聊天模型'}</option>{models.map(m => <option key={m.id} value={str(m.payload.key)}>{valueOf(m).imageConfig === true ? '图片 · ' : '文本 · '}{str(valueOf(m).name, str(valueOf(m).model))}</option>)}</select><ChevronDown size={12} /></div>{!models.length && <button className="et-add-model" onClick={() => setSettings('models')}>添加模型</button>}</div>{busy ? <button className="et-send" aria-label="停止生成" onClick={() => controller.current?.abort()}><Square size={13} fill="currentColor" /></button> : <button className="et-send" disabled={!ready || (!draft.trim() && !files.length)} aria-label={imageMode ? '生成图片' : '发送消息'} onClick={() => void send()}><ArrowUp size={18} /></button>}</div></div><div className="et-composer-foot"><span>Enter 发送 · Shift + Enter 换行</span><span title={snapshot.lastSyncAt ? new Date(snapshot.lastSyncAt).toLocaleString('zh-CN') : '等待首次同步'}>{snapshot.pendingCount ? `${snapshot.pendingCount} 条待同步` : imageMode ? '图片结果会同步至安卓端' : webSearchEnabled ? '联网搜索已开启' : '与安卓端同步'}</span></div></div>
    </section>
    {settings && <SettingsPanel records={records} onClose={() => setSettings(undefined)} saveSetting={saveSetting} saveMemory={saveMemory} error={sync.error} reportError={setError} downloads={downloads} initialTab={settings} />}
    {report && <div className="et-modal-backdrop"><section role="dialog" aria-modal="true" aria-label="举报内容" className="et-settings"><header><h2>举报内容</h2><button aria-label="关闭举报" onClick={() => setReport(undefined)}><X size={18} /></button></header><div className="et-settings-body"><form onSubmit={e => { e.preventDefault(); void run(sendReport) }}>
      <label>问题类型<select value={report.category} onChange={e => setReport({ ...report, category: e.target.value })}>{reportCategories.map(([id, label]) => <option key={id} value={id}>{label}</option>)}</select></label>
      <label>补充说明<textarea rows={4} maxLength={500} value={report.details} onChange={e => setReport({ ...report, details: e.target.value })} placeholder="可选，最多 500 字" /></label>
      <p className="et-muted">将提交这条回复的文字（最多 4000 字）、模型名称和问题类型，用于内容安全审核。</p>
      <div className="et-form-actions"><button className="et-primary" disabled={report.sending}>{report.sending ? '正在提交' : '提交举报'}</button></div>
    </form></div></section></div>}
    {history && <div className="et-modal-backdrop"><section role="dialog" aria-modal="true" aria-label="完整历史" className="et-settings"><header><h2>完整历史</h2><button aria-label="关闭历史" onClick={() => setHistory(undefined)}><X size={18} /></button></header><div className="et-settings-body"><p className="et-muted">归档已完成解密和摘要校验。以下内容仅供查看。</p><pre className="et-history">{JSON.stringify(history.value, null, 2)}</pre></div></section></div>}
  </main>
}


