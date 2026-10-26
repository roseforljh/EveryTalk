'use client'

import { useEffect, useState } from 'react'
import { Plus, X, Check, ChevronRight } from 'lucide-react'
import { isObject } from './sync-core'
import { isBuiltinModel } from './builtin-models'
import { ModelIcon } from './ModelIcon'
import { ModelParametersFields } from './ModelParametersFields'
import { findCatalogModel, modalityLabels, modelCapabilities, parameterProtocol, withModelId, type CatalogModel } from './model-capabilities'
import type { JsonObject, StoredRecord } from './types'
import type { useFileDownloads } from './use-file-downloads'

export const settingNames: Record<string, string> = {
  api_configs: '模型配置', selected_models: '默认模型', web_search_configs: '联网搜索', voice_configs: '语音配置',
  voice_llm_platforms: '语音平台', voice_custom_models: '语音自定义模型', mcp_servers: 'MCP 服务', skills: '技能', skill_variables: '技能变量',
  computers: '电脑', computer_workspaces: '电脑工作区', conversation_settings: '会话设置', memory_switches: '记忆开关', group_members: '会话分组', pinned_items: '置顶会话', crypto_keys: '加密配置',
}
interface Props {
  records: StoredRecord[]; onClose: () => void
  saveSetting: (kind: string, key: string, value: JsonObject, existing?: StoredRecord, deleted?: boolean) => Promise<void>
  saveMemory: (content: string, existing?: StoredRecord, deleted?: boolean) => Promise<void>
  error: string; reportError: (message: string) => void
  downloads: ReturnType<typeof useFileDownloads>
  initialTab: 'models' | 'files'
}

/** 模型使用常规表单，其余安卓配置保留完整 JSON 字段；未知字段不会被表单覆盖丢失。 */
export function SettingsPanel({ records, onClose, saveSetting, saveMemory, error, reportError, downloads, initialTab }: Props) {
  const [tab, setTab] = useState<'models' | 'search' | 'memory' | 'advanced' | 'files'>(initialTab)
  const [editing, setEditing] = useState<StoredRecord | null | undefined>(undefined)
  const [kind, setKind] = useState('web_search_configs')
  const [entryKey, setEntryKey] = useState('default')
  const [json, setJson] = useState('{}')
  const [name, setName] = useState(''), [address, setAddress] = useState(''), [model, setModel] = useState(''), [apiKey, setApiKey] = useState('')
  const [parameterConfig, setParameterConfig] = useState<JsonObject>({})
  const [catalog, setCatalog] = useState<CatalogModel[]>([])
  const [catalogLoading, setCatalogLoading] = useState(false), [catalogError, setCatalogError] = useState('')
  const [catalogVersion, setCatalogVersion] = useState(0)
  useEffect(() => {
    const abort = new AbortController()
    setCatalogLoading(true); setCatalogError('')
    void fetch('/api/model-catalog', { signal: abort.signal }).then(async response => {
      const value: unknown = await response.json()
      if (!response.ok || !isObject(value) || !Array.isArray(value.models)) throw new Error('目录暂不可用，可手动配置或稍后刷新')
      setCatalog(value.models as unknown as CatalogModel[])
    }).catch(error => { if (!abort.signal.aborted) setCatalogError(error instanceof Error ? error.message : '目录加载失败') })
      .finally(() => { if (!abort.signal.aborted) setCatalogLoading(false) })
    return () => abort.abort()
  }, [catalogVersion])
  const [searchProvider, setSearchProvider] = useState('tavily'), [searchApiKey, setSearchApiKey] = useState('')
  const [memory, setMemory] = useState('')
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState(false)
  const run = async (work: () => Promise<void>) => {
    setBusy(true); setSaved(false); reportError('')
    try { await work(); setEditing(undefined); setSaved(true) } catch (value) { reportError(value instanceof Error ? value.message : '保存失败') }
    finally { setBusy(false) }
  }
  const edit = (record: StoredRecord | null) => {
    setEditing(record); setSaved(false); setApiKey('')
    const value = record && isObject(record.payload.value) ? record.payload.value : {}
    setName(String(value.name ?? '')); setAddress(String(value.address ?? '')); setModel(String(value.model ?? ''))
    setParameterConfig(value)
    setSearchProvider(String(value.providerId ?? record?.payload.key ?? 'tavily')); setSearchApiKey('')
    setKind(String(record?.payload.kind ?? 'web_search_configs')); setEntryKey(String(record?.payload.key ?? 'default')); setJson(JSON.stringify(value, null, 2))
    setMemory(String(record?.payload.content ?? ''))
  }
  const models = records.filter(r => r.entity === 'user_settings' && r.payload.kind === 'api_configs' && !r.deleted)
  const searchConfigs = records.filter(r => r.entity === 'user_settings' && r.payload.kind === 'web_search_configs' && !r.deleted)
  const memories = records.filter(r => r.entity === 'memories' && !r.deleted)
  const settings = records.filter(r => r.entity === 'user_settings' && !r.deleted)
  return <div className="et-modal-backdrop" onClick={event => { if (event.target === event.currentTarget && !busy) onClose() }}>
    <section role="dialog" aria-modal="true" aria-labelledby="settings-title" className="et-settings" onKeyDown={event => { if (event.key === 'Escape' && !busy) onClose() }}>
      <header><div><p className="et-eyebrow">工作区</p><h2 id="settings-title">设置</h2></div><button aria-label="关闭设置" disabled={busy} onClick={onClose}><X size={19} /></button></header>
      <nav className="et-tabs">{([['models', '模型'], ['search', '联网搜索'], ['memory', '记忆'], ['advanced', '同步配置'], ['files', '文件保存']] as const).map(([id, label]) => <button key={id} className={tab === id ? 'selected' : ''} onClick={() => { setTab(id); setEditing(undefined) }}>{label}</button>)}</nav>
      <div className="et-settings-body">
        {error && <p role="alert" className="et-notice et-error">{error}</p>}
        {saved && <p role="status" className="et-muted et-inline"><Check size={14} />已保存，联网后自动同步至安卓端</p>}
        {tab === 'files' && <>
          <h3>AI 文件自动保存</h3>
          <p className="et-muted">安卓端固定保存到“下载/App/AppTalk”。网页端选择一次目录后，会自动保存此后生成并同步收到的 AI 附件；历史附件仍可点击保存。</p>
          <p className="et-muted">目录仅对此浏览器和当前账号生效。需要保持网页打开；重新打开后会补收，权限失效时请恢复授权。同名文件会编号保留。</p>
          <p>当前目录：{downloads.preferences?.directory.name || '未选择'}</p>
          {!downloads.supported && <p role="status" className="et-muted">当前浏览器不支持指定目录，请使用桌面版 Chrome 或 Edge。你仍可点击聊天附件，保存到浏览器下载位置。</p>}
          <div className="et-inline">
            <button className="et-outline" disabled={!downloads.supported} onClick={() => void downloads.choose()}>{downloads.preferences ? '更换目录' : '选择保存目录'}</button>
            {downloads.preferences && downloads.supported && <>
              <button className="et-outline" onClick={() => void downloads.authorize()}>恢复授权</button>
              <button className="et-outline" aria-pressed={downloads.preferences.enabled} onClick={() => void downloads.toggle()}>{downloads.preferences.enabled ? '暂停自动保存' : '开启自动保存'}</button>
            </>}
          </div>
          {downloads.status && <p role="status" className="et-muted">{downloads.status}</p>}
          {downloads.error && <p role="alert" className="et-notice et-error">{downloads.error}</p>}
        </>}
        {editing === undefined && tab !== 'files' && <>
          <div className="et-settings-intro"><p className="et-muted">{tab === 'models' ? '使用安卓端同步的模型，也可以在这里添加。' : tab === 'search' ? '配置后可在当前对话开启联网搜索，密钥只用于当前搜索请求。' : tab === 'memory' ? '记忆随账号同步；启用的记忆可用于聊天上下文。' : '保留全部协议字段。MCP、技能和电脑配置会同步，浏览器暂不执行。'}</p><button className="et-outline" onClick={() => edit(null)}><Plus size={14} />新增</button></div>
          {tab === 'models' && models.map(record => { const value = isObject(record.payload.value) ? record.payload.value : {}; const caps = modelCapabilities(value); return <button className="et-setting-row et-model-row" key={record.id} onClick={() => { if (!isBuiltinModel(value, String(record.payload.key))) edit(record) }}><ModelIcon model={String(value.model ?? '')} /><div><strong>{isBuiltinModel(value, String(record.payload.key)) ? '默认配置 · ' : ''}{String(value.name || value.model || '未命名模型')}</strong><small>{String(value.model || '')} · {isBuiltinModel(value, String(record.payload.key)) ? '内置 · 不可删除' : parameterProtocol(value)}</small><span className="et-model-output">{caps.input.map(value => modalityLabels[value] ?? value).join(' / ')} → {caps.output.map(value => modalityLabels[value] ?? value).join(' / ')}</span></div><ChevronRight size={16} /></button> })}
          {tab === 'search' && searchConfigs.map(record => { const value = isObject(record.payload.value) ? record.payload.value : {}; return <button className="et-setting-row" key={record.id} onClick={() => edit(record)}><div><strong>{String(value.providerId || record.payload.key || '搜索服务')}</strong><small>API Key 已加密保存</small></div><ChevronRight size={16} /></button> })}
          {tab === 'memory' && memories.map(record => <button className="et-setting-row" key={record.id} onClick={() => edit(record)}><div><strong>{String(record.payload.content)}</strong><small>{String(record.payload.category || '记忆')} · {record.payload.enabled === false ? '已关闭' : '已启用'}</small></div><ChevronRight size={16} /></button>)}
          {tab === 'advanced' && settings.filter(record => !(record.payload.kind === 'api_configs' && isObject(record.payload.value) && isBuiltinModel(record.payload.value, String(record.payload.key)))).map(record => <button className="et-setting-row" key={record.id} onClick={() => edit(record)}><div><strong>{settingNames[String(record.payload.kind)] || String(record.payload.kind)}</strong><small>{String(record.payload.key)}</small></div><ChevronRight size={16} /></button>)}
          {(tab === 'models' ? models : tab === 'search' ? searchConfigs : tab === 'memory' ? memories : settings).length === 0 && <p className="et-empty-list">还没有{tab === 'models' ? '模型' : tab === 'search' ? '搜索服务' : tab === 'memory' ? '记忆' : '配置'}，可新增或等待安卓端同步。</p>}
        </>}
        {editing !== undefined && <form onSubmit={event => {
          event.preventDefault(); void run(async () => {
            if (tab === 'memory') { await saveMemory(memory, editing ?? undefined); return }
            if (tab === 'search') {
              const provider = searchProvider.trim().toLowerCase()
              if (!['tavily', 'exa', 'bocha', 'serpapi'].includes(provider)) throw new Error('搜索服务商无效')
              const old = editing && isObject(editing.payload.value) ? editing.payload.value : {}
              const key = String(editing?.payload.key ?? provider)
              await saveSetting('web_search_configs', key, { ...old, providerId: provider, ...(searchApiKey.trim() ? { apiKey: searchApiKey.trim() } : {}) }, editing ?? undefined)
              return
            }
            if (tab === 'models') {
              const old = editing && isObject(editing.payload.value) ? editing.payload.value : {}
              const key = String(editing?.payload.key ?? crypto.randomUUID())
              const capabilities = modelCapabilities(parameterConfig)
              const imageConfig = capabilities.output.includes('image')
              const parameters = isObject(parameterConfig.modelParameters) ? parameterConfig.modelParameters : {}
              if (capabilities.output.includes('text') && (!Number.isSafeInteger(Number(parameterConfig.outputLimit ?? 4096)) || !Number.isSafeInteger(Number(parameters.contextLimit ?? 128000)) || Number(parameters.contextLimit ?? 128000) > 10000000 || Number(parameterConfig.outputLimit ?? 4096) < 1 || Number(parameterConfig.outputLimit ?? 4096) >= Number(parameters.contextLimit ?? 128000))) throw new Error('最大输出必须大于 0 且小于上下文窗口')
              await saveSetting('api_configs', key, { ...old, ...parameterConfig, name: name.trim(), address: address.trim(), model: model.trim(), provider: old.provider ?? findCatalogModel(catalog, model)?.provider ?? 'OpenAI',
                imageConfig, channel: old.channel ?? '', modality: imageConfig ? 'IMAGE' : 'TEXT',
                ...(apiKey ? { apiKey } : {}) }, editing ?? undefined)
              return
            }
            const value: unknown = JSON.parse(json)
            if (!isObject(value)) throw new Error('配置值必须是 JSON 对象')
            if (kind === 'crypto_keys') throw new Error('加密配置由账号管理，不能在通用编辑器覆盖')
            await saveSetting(kind, entryKey.trim(), value, editing ?? undefined)
          })
        }}>
          {tab === 'models' && <>
            <label>名称<input required value={name} onChange={e => setName(e.target.value)} placeholder="我的模型" /></label>
            <label>模型地址<input type="url" value={address} onChange={e => setAddress(e.target.value)} placeholder="留空使用 OpenAI / Gemini 官方地址" /></label>
            <label>模型名称<input required list="et-model-catalog" value={model} onChange={e => {
              const id = e.target.value; setModel(id)
              setParameterConfig(current => withModelId(current, id, catalog))
            }} placeholder="搜索 models.dev 或填写自定义模型 ID" /><datalist id="et-model-catalog">{[...new Map(catalog.filter(item => !model || `${item.id} ${item.name}`.toLowerCase().includes(model.toLowerCase())).map(item => [item.id, item])).values()].slice(0, 80).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</datalist></label>
            <label>API Key<input type="password" required={!editing} value={apiKey} onChange={e => setApiKey(e.target.value)} autoComplete="new-password" placeholder={editing ? '留空保留已有密钥' : '加密后同步'} /></label>
            <ModelParametersFields config={{ ...parameterConfig, model, address }} onChange={setParameterConfig} catalog={catalog} loading={catalogLoading} error={catalogError} onRefresh={() => setCatalogVersion(value => value + 1)} />
          </>}
          {tab === 'search' && <>
            <label>搜索服务商<select value={searchProvider} onChange={e => setSearchProvider(e.target.value)}><option value="tavily">Tavily</option><option value="exa">Exa</option><option value="bocha">Bocha</option><option value="serpapi">SerpAPI</option></select></label>
            <label>API Key<input type="password" required={!editing} value={searchApiKey} onChange={e => setSearchApiKey(e.target.value)} autoComplete="new-password" placeholder={editing ? '留空保留已有密钥' : '加密后同步'} /></label>
            <p className="et-muted">搜索请求只发送到选中的固定供应商，搜索结果会随助手消息同步到安卓端。</p>
          </>}
          {tab === 'memory' && <label>记忆内容<textarea required rows={8} value={memory} onChange={e => setMemory(e.target.value)} placeholder="希望模型记住的信息" /></label>}
          {tab === 'advanced' && <>
            <label>配置类型<select disabled={Boolean(editing)} value={kind} onChange={e => { setKind(e.target.value); setEntryKey(['memory_switches', 'crypto_keys'].includes(e.target.value) ? 'default' : ['selected_models'].includes(e.target.value) ? 'text' : crypto.randomUUID()); if (e.target.value === 'memory_switches') setJson('{"memoryEnabled":true,"autoCaptureEnabled":false}') }}>{Object.entries(settingNames).filter(([id]) => id !== 'crypto_keys').map(([id, label]) => <option key={id} value={id}>{label}</option>)}</select></label>
            <label>配置标识<input required disabled={Boolean(editing)} value={entryKey} onChange={e => setEntryKey(e.target.value)} /></label>
            <label>JSON 值<textarea rows={12} spellCheck={false} className="et-code-input" value={json} onChange={e => setJson(e.target.value)} disabled={kind === 'crypto_keys'} /></label>
            <p className="et-muted">敏感字段保存前加密。已有密文请保持原字段路径和数组顺序，勿移动或复制。</p>
          </>}
          <div className="et-form-actions"><button type="button" disabled={busy} onClick={() => setEditing(undefined)}>返回</button>{editing && kind !== 'crypto_keys' && <button type="button" className="et-danger" disabled={busy} onClick={() => void run(() => tab === 'memory' ? saveMemory(memory, editing, true) : saveSetting(String(editing.payload.kind), String(editing.payload.key), isObject(editing.payload.value) ? editing.payload.value : {}, editing, true))}>删除</button>}<button className="et-primary" disabled={busy || kind === 'crypto_keys'}>{busy ? '保存中…' : '保存并同步'}</button></div>
        </form>}
      </div>
    </section>
  </div>
}
