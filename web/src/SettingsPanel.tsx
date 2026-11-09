import { useEffect, useState } from 'react'
import {
  Check,
  ChevronDown,
  Cpu,
  Database,
  Download,
  Edit3,
  ExternalLink,
  FileCode,
  FolderOpen,
  Globe,
  Key,
  Layers,
  Lock,
  Mail,
  Monitor,
  Moon,
  Package,
  Palette,
  Pin,
  Plus,
  RefreshCw,
  Search,
  Server,
  Shield,
  SlidersHorizontal,
  Sparkles,
  Sun,
  Terminal,
  Trash2,
  Upload,
  User,
  Wrench,
  X,
} from 'lucide-react'
import { apiBaseUrl } from './auth'
import { isBuiltinModel } from './builtin-models'
import { ModelIcon } from './ModelIcon'
import { ModelParametersFields } from './ModelParametersFields'
import {
  findCatalogModel,
  modelCapabilities,
  withCatalogModel,
  withModelId,
  type CatalogModel,
} from './model-capabilities'
import { isObject } from './sync-core'
import type { useFileDownloads } from './use-file-downloads'
import type { JsonObject, StoredRecord } from './types'

export type SettingsTab =
  | 'models'
  | 'mcp'
  | 'skills'
  | 'servers'
  | 'memory'
  | 'account'
  | 'theme'
  | 'data'
  | 'search'
  | 'files'

type ActiveTab = 'models' | 'mcp' | 'skills' | 'servers' | 'memory' | 'account' | 'theme' | 'data'
type ModelFilter = 'all' | 'text' | 'image'
type FileDownloadsController = ReturnType<typeof useFileDownloads>

function normalizeSettingsTab(tab?: SettingsTab): ActiveTab {
  if (!tab) return 'models'
  if (tab === 'search') return 'mcp'
  if (tab === 'files') return 'data'
  return tab
}

const SETTINGS_NAV_ITEMS: Array<{
  id: ActiveTab
  label: string
  subtitle: string
  Icon: typeof SlidersHorizontal
}> = [
  {
    id: 'models',
    label: '平台与模型',
    subtitle: '管理文本对话与图像生成平台密钥、模型列表与推理参数',
    Icon: SlidersHorizontal,
  },
  {
    id: 'mcp',
    label: 'MCP 与联网',
    subtitle: '管理 MCP 协议服务器、内置服务连接器与外部实时搜索引擎',
    Icon: Cpu,
  },
  {
    id: 'skills',
    label: '技能 (Skills)',
    subtitle: '管理已安装技能包、在线技能商店下载、自建规则与附件脚本',
    Icon: Package,
  },
  {
    id: 'servers',
    label: '远程服务器',
    subtitle: '管理 SSH 容器/直连服务器与 Cloudflare 云端沙盒工作区',
    Icon: Server,
  },
  {
    id: 'memory',
    label: '长期记忆',
    subtitle: '管理跨会话长期有效的个人偏好、技术栈与背景记忆',
    Icon: Sparkles,
  },
  {
    id: 'account',
    label: '账户资料',
    subtitle: '查看当前工作区账号信息、修改显示昵称与多端同步状态',
    Icon: User,
  },
  {
    id: 'theme',
    label: '外观主题',
    subtitle: '切换深色模式、浅色模式或跟随系统设置',
    Icon: Palette,
  },
  {
    id: 'data',
    label: '数据与归档',
    subtitle: '导入导出完整加密备份、模型配置快照与本地文件自动归档',
    Icon: Database,
  },
]

async function loadModelCatalog(force = false): Promise<CatalogModel[]> {
  const response = await fetch(`${apiBaseUrl}/v1/model-catalog${force ? '?refresh=1' : ''}`, { cache: 'no-store' })
  const body: unknown = await response.json().catch(() => undefined)
  if (!response.ok || !isObject(body) || !Array.isArray(body.models)) {
    throw new Error(isObject(body) && typeof body.error === 'string' ? body.error : '读取 models.dev 失败')
  }
  return body.models as CatalogModel[]
}

const str = (value: unknown, fallback = ''): string => (typeof value === 'string' ? value : fallback)
const valueOf = (record?: StoredRecord): JsonObject => (record && isObject(record.payload.value) ? record.payload.value : {})
const secretMark = (value: JsonObject): string =>
  typeof value.apiKey === 'string' && value.apiKey
    ? '已保存 API Key'
    : isObject(value.__everytalk_secret)
      ? '已加密保存 API Key'
      : '未填写 API Key'

/**
 * 与安卓端 SettingsScreenContent.kt 中 ExternalWebSearchProviderCards 完全一致的四大联网搜索服务商元数据。
 */
const WEB_SEARCH_PROVIDERS: Array<{
  id: 'tavily' | 'exa' | 'bocha' | 'serpapi'
  name: string
  description: string
  accentColor: string
  placeholder: string
}> = [
  {
    id: 'tavily',
    name: 'Tavily',
    description: '专为 AI Agent 优化的实时搜索引擎',
    accentColor: '#14B8A6',
    placeholder: 'tvly-...',
  },
  {
    id: 'exa',
    name: 'Exa',
    description: '基于语义理解的神经搜索引擎',
    accentColor: '#6366F1',
    placeholder: '输入 Exa API Key',
  },
  {
    id: 'bocha',
    name: 'Bocha',
    description: '博查中文与全网智能搜索服务',
    accentColor: '#10B981',
    placeholder: 'sk-...',
  },
  {
    id: 'serpapi',
    name: 'SerpAPI',
    description: 'Google 与多引擎结构化搜索结果接口',
    accentColor: '#F59E0B',
    placeholder: '输入 SerpAPI Key',
  },
]

/**
 * 与安卓端 McpDialogs.kt 中 McpServerPreset 与内置 OAuth / 邮件连接器完全对齐的预设表。
 */
const MCP_SERVER_PRESETS: Array<{
  id: 'CUSTOM' | 'CONTEXT7' | 'EXA_SEARCH' | 'FIRECRAWL'
  label: string
  defaultName: string
  transportType: 'SSE' | 'HTTP'
  urlTemplate: string
  requiresKey: boolean
  keyPlaceholder: string
}> = [
  {
    id: 'CUSTOM',
    label: '自定义',
    defaultName: '',
    transportType: 'SSE',
    urlTemplate: '',
    requiresKey: false,
    keyPlaceholder: 'API Key（可选）',
  },
  {
    id: 'CONTEXT7',
    label: 'Context7',
    defaultName: 'Context7',
    transportType: 'HTTP',
    urlTemplate: 'https://mcp.context7.com/mcp',
    requiresKey: false,
    keyPlaceholder: 'Context7 API Key（可选）',
  },
  {
    id: 'EXA_SEARCH',
    label: 'Exa',
    defaultName: 'Exa Search MCP',
    transportType: 'HTTP',
    urlTemplate: 'https://mcp.exa.ai/mcp?exaApiKey={API_KEY}&tools=web_search_exa,get_code_context_exa',
    requiresKey: true,
    keyPlaceholder: '输入 Exa API Key',
  },
  {
    id: 'FIRECRAWL',
    label: 'Firecrawl',
    defaultName: 'Firecrawl',
    transportType: 'HTTP',
    urlTemplate: 'https://mcp.firecrawl.dev/{API_KEY}/v2/mcp',
    requiresKey: true,
    keyPlaceholder: '输入 Firecrawl API Key',
  },
]

const MCP_BUILTIN_CONNECTORS: Array<{
  key: string
  name: string
  description: string
  accentColor: string
  url: string
  transportType: 'HTTP' | 'SSE'
  tools: Array<{ name: string; description: string }>
}> = [
  {
    key: 'builtin-gmail',
    name: 'Gmail',
    description: '通过 Google OAuth 授权接入邮件检索、摘要与草稿生成工具',
    accentColor: '#EA4335',
    url: 'mcp://oauth/gmail',
    transportType: 'HTTP',
    tools: [
      { name: 'gmail_search_messages', description: '按发件人、主题或时间范围搜索 Gmail 邮件' },
      { name: 'gmail_read_thread', description: '读取完整邮件会话正文与附件元数据' },
    ],
  },
  {
    key: 'builtin-microsoft',
    name: 'Microsoft 365',
    description: '接入 Outlook 邮件、日历日程与 OneDrive 文档上下文工具',
    accentColor: '#00A4EF',
    url: 'mcp://oauth/microsoft',
    transportType: 'HTTP',
    tools: [
      { name: 'outlook_list_events', description: '查询近期日程安排与会议邀请' },
      { name: 'outlook_search_mail', description: '检索 Outlook 收件箱与归档邮件' },
    ],
  },
  {
    key: 'builtin-qqmail',
    name: 'QQ 邮箱 (IMAP)',
    description: '使用 IMAP/SMTP 授权码接入腾讯 QQ 邮箱收发与检索能力',
    accentColor: '#12B7F5',
    url: 'imaps://imap.qq.com:993',
    transportType: 'HTTP',
    tools: [{ name: 'mail_fetch_recent', description: '拉取最近收件箱邮件列表与正文摘要' }],
  },
  {
    key: 'builtin-netease',
    name: '网易邮箱 (163/126)',
    description: '使用客户端授权密码接入网易 163/126 邮箱检索工具',
    accentColor: '#E60012',
    url: 'imaps://imap.163.com:993',
    transportType: 'HTTP',
    tools: [{ name: 'mail_fetch_recent', description: '拉取网易邮箱最近邮件与附件列表' }],
  },
]

/**
 * 与安卓端 SkillDownloadScreen.kt 对齐的在线技能商店远程目录。
 */
const REMOTE_SKILL_CATALOG: Array<{
  packageId: string
  name: string
  sourceRepository: string
  collection: 'POPULAR' | 'LATEST'
  versionHash: string
  description: string
  children: Array<{ skillId: string; name: string; description: string }>
  markdown: string
}> = [
  {
    packageId: 'vercel-labs-nextjs-react',
    name: 'vercel-labs/nextjs-react-skills',
    sourceRepository: 'https://github.com/vercel-labs/nextjs-react-skills',
    collection: 'POPULAR',
    versionHash: 'a8f4c291e0',
    description: 'React 19、Next.js App Router、流式 SSR 与 Tailwind 设计系统工程规范',
    children: [
      { skillId: 'react-19-patterns', name: 'react-19-patterns', description: 'React 19 Actions 与服务端组件数据流最佳实践' },
      { skillId: 'tailwind-design-system', name: 'tailwind-design-system', description: '高对比度暗黑模式与响应式微交互动效规范' },
    ],
    markdown: '# React 19 & Next.js 生产规范\n1. 优先使用函数式组件与原生 Hooks。\n2. 所有折叠区域使用 CSS Grid 0fr -> 1fr 过渡。',
  },
  {
    packageId: 'anthropics-claude-code-skills',
    name: 'anthropics/claude-code-skills',
    sourceRepository: 'https://github.com/anthropics/claude-code-skills',
    collection: 'POPULAR',
    versionHash: 'b7190e4a12',
    description: 'Anthropic 官方工程化 Agent 编码规范、自动化测试生成与 Git 工作流技能包',
    children: [
      { skillId: 'clean-refactor', name: 'clean-refactor', description: '零副作用渐进式代码重构与死代码清理规范' },
      { skillId: 'root-cause-debug', name: 'root-cause-debug', description: '全链路追踪调用方并从根因修复缺陷的标准流程' },
    ],
    markdown: '# Claude Code 工程技能\n1. 修改函数前先检索全部调用方，从公共入口一次性解决根因。\n2. 非平凡逻辑必须留下可运行的最小断言验证。',
  },
  {
    packageId: 'supabase-postgres-drizzle',
    name: 'supabase/postgres-drizzle-skills',
    sourceRepository: 'https://github.com/supabase/postgres-drizzle-skills',
    collection: 'POPULAR',
    versionHash: 'd4028f91c3',
    description: 'PostgreSQL 高性能 Schema 设计、RLS 行级安全策略与 Drizzle ORM 类型推导指南',
    children: [
      { skillId: 'drizzle-schema-design', name: 'drizzle-schema-design', description: '零冗余索引设计与事务一致性保障' },
      { skillId: 'pg-rls-security', name: 'pg-rls-security', description: '多租户行级安全策略与慢查询分析' },
    ],
    markdown: '# PostgreSQL & Drizzle 规范\n1. 所有外键查询列必须建立复合索引。\n2. 严禁在循环内执行 N+1 数据库查询。',
  },
  {
    packageId: 'cloudflare-workers-edge',
    name: 'cloudflare/workers-edge-skills',
    sourceRepository: 'https://github.com/cloudflare/workers-edge-skills',
    collection: 'LATEST',
    versionHash: 'e9310ab772',
    description: 'Cloudflare Workers、Durable Objects、D1 与边缘流式 AI 网关开发规范',
    children: [
      { skillId: 'workers-sse-gateway', name: 'workers-sse-gateway', description: '边缘节点低延迟 SSE 流式转发与限流器实现' },
    ],
    markdown: '# Cloudflare Edge 开发规范\n1. 使用 Web Fetch / Streams 标准 API，避免依赖 Node.js 专有模块。',
  },
  {
    packageId: 'playwright-web-e2e',
    name: 'microsoft/playwright-e2e-skills',
    sourceRepository: 'https://github.com/microsoft/playwright-e2e-skills',
    collection: 'LATEST',
    versionHash: 'c0841a2b55',
    description: '端到端无障碍选择器定位、视觉回归校验与网络请求录制技能',
    children: [
      { skillId: 'a11y-locators', name: 'a11y-locators', description: '优先使用 getByRole 与语义化 ARIA 标签编写稳定测试' },
    ],
    markdown: '# Playwright 自动化验证规范\n1. 优先使用语义化 Role 选择器，禁止硬编码脆弱的 CSS 层级路径。',
  },
]

function capabilitySummary(config: JsonObject): string {
  if (config.imageConfig === true) return '图片生成'
  const capability = modelCapabilities(config)
  const names: Record<string, string> = { text: '文本', image: '图片', audio: '音频', video: '视频', pdf: 'PDF' }
  const input = capability.input.filter(item => names[item]).map(item => names[item]).join('/') || '文本'
  const output = capability.output.filter(item => names[item]).map(item => names[item]).join('/') || '文本'
  return `输入 ${input} · 输出 ${output}${capability.reasoning === false ? ' · 无思考' : ''}`
}

function resolveMcpAccentColor(name: string): string {
  const lower = name.toLowerCase()
  if (lower.includes('context7')) return '#10B981'
  if (lower.includes('exa')) return '#6366F1'
  if (lower.includes('firecrawl')) return '#EF4444'
  if (lower.includes('cloudflare')) return '#F6821F'
  if (lower.includes('github')) return '#60A5FA'
  if (lower.includes('gmail') || lower.includes('qq') || lower.includes('mail') || lower.includes('网易')) return '#EA4335'
  if (lower.includes('microsoft')) return '#00A4EF'
  return '#3B82F6'
}

/**
 * 设置面板弹窗（完整对齐安卓端 SettingsScreen、McpDialogs、SkillScreen、ComputerScreen 与 AccountScreen）。
 */
export function SettingsPanel(props: {
  records: StoredRecord[]
  onClose: () => void
  saveSetting: (kind: string, key: string, value: JsonObject, old?: StoredRecord, deleted?: boolean) => Promise<void>
  saveMemory: (content: string, old?: StoredRecord, deleted?: boolean) => Promise<void>
  error: string
  reportError: (message: string) => void
  downloads: FileDownloadsController
  initialTab?: SettingsTab
}) {
  const { records, onClose, saveSetting, saveMemory, error, reportError, downloads, initialTab } = props
  const [tab, setTab] = useState<ActiveTab>(() => normalizeSettingsTab(initialTab))
  const [modelFilter, setModelFilter] = useState<ModelFilter>('all')
  const [editing, setEditing] = useState<{ record?: StoredRecord; key: string; value: JsonObject }>()
  const [addModelEditor, setAddModelEditor] = useState<{ key: string; value: JsonObject }>()
  const [platformEditor, setPlatformEditor] = useState<{
    groupKey: string
    provider: string
    channel: string
    address: string
    apiKey: string
    items: StoredRecord[]
  }>()
  const [memoryDraft, setMemoryDraft] = useState<{ record?: StoredRecord; content: string }>()
  const [themeMode, setThemeMode] = useState<'system' | 'light' | 'dark'>(() => {
    if (typeof window === 'undefined') return 'system'
    const saved = localStorage.getItem('everytalk-theme')
    if (saved === 'light' || saved === 'dark') return saved
    return 'system'
  })

  // 各模块展开/收起状态（全部驱动 .et-collapsible-block 平滑高度动画）
  const [expandedPlatformGroups, setExpandedPlatformGroups] = useState<Record<string, boolean>>({})
  const [expandedMcpIds, setExpandedMcpIds] = useState<Record<string, boolean>>({ context7: true })
  const [expandedSkillIds, setExpandedSkillIds] = useState<Record<string, boolean>>({
    'vercel-labs-nextjs-react': true,
  })
  const [expandedServerIds, setExpandedServerIds] = useState<Record<string, boolean>>({
    'server-hk-docker': true,
  })
  const [expandedWorkspaceIds, setExpandedWorkspaceIds] = useState<Record<string, boolean>>({
    'ws-core-timeline': true,
  })

  // MCP 新增/编辑表单状态
  const [mcpEditor, setMcpEditor] = useState<{
    record?: StoredRecord
    preset: 'CUSTOM' | 'CONTEXT7' | 'EXA_SEARCH' | 'FIRECRAWL'
    name: string
    url: string
    transportType: 'SSE' | 'HTTP'
    apiKey: string
  }>()

  // 技能商店 / 新建技能 / 编辑技能规则状态
  const [skillSubView, setSkillSubView] = useState<'installed' | 'store' | 'create'>('installed')
  const [skillStoreCollection, setSkillStoreCollection] = useState<'POPULAR' | 'LATEST'>('POPULAR')
  const [skillStoreQuery, setSkillStoreQuery] = useState('')
  const [newSkillDraft, setNewSkillDraft] = useState({ name: '', description: '', rules: '' })
  const [editingSkillRules, setEditingSkillRules] = useState<{ record: StoredRecord; markdown: string }>()

  // 服务器新增/编辑表单及工作区 Secret / 预览状态
  const [serverEditor, setServerEditor] = useState<{
    record?: StoredRecord
    provider: 'SSH' | 'CLOUDFLARE'
    name: string
    host: string
    port: string
    username: string
    authKind: 'PASSWORD' | 'PRIVATE_KEY'
    secret: string
    runMode: 'CONTAINER' | 'DIRECT'
    cloudflareAccountName: string
    cloudflareAccountId: string
  }>()
  const [workspaceSecretDraft, setWorkspaceSecretDraft] = useState<{
    serverRecord: StoredRecord
    workspaceId: string
    name: string
    value: string
  }>()
  const [workspacePreviewDraft, setWorkspacePreviewDraft] = useState<{
    serverRecord: StoredRecord
    workspaceId: string
    visibility: 'PRIVATE' | 'PUBLIC'
    port: string
    protocol: 'HTTP' | 'HTTPS'
  }>()

  // 账户昵称编辑状态
  const profileRecord = records.find(
    r => r.entity === 'user_settings' && !r.deleted && r.payload.kind === 'account_profile' && r.payload.key === 'default'
  )
  const [displayNameDraft, setDisplayNameDraft] = useState(() =>
    str(valueOf(profileRecord).displayName, 'EveryTalk 预览体验')
  )
  const [profileSavedNotice, setProfileSavedNotice] = useState('')

  const handleThemeChange = (mode: 'system' | 'light' | 'dark') => {
    setThemeMode(mode)
    const isDark =
      mode === 'system'
        ? typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches
        : mode === 'dark'
    if (typeof document !== 'undefined') {
      document.documentElement.dataset.theme = isDark ? 'dark' : 'light'
    }
    try {
      localStorage.setItem('everytalk-theme', mode)
      window.dispatchEvent(new Event('storage'))
    } catch {
      /* 忽略受限 */
    }
  }

  const [confirmDelete, setConfirmDelete] = useState<{
    title: string
    subtitle: string
    onConfirm: () => Promise<void>
  }>()
  const [editingSearchProvider, setEditingSearchProvider] = useState<'tavily' | 'exa' | 'bocha' | 'serpapi'>()
  const [searchKeyDraft, setSearchKeyDraft] = useState('')
  const [saving, setSaving] = useState(false)
  const [catalog, setCatalog] = useState<CatalogModel[]>([])
  const [catalogLoading, setCatalogLoading] = useState(false)
  const [catalogError, setCatalogError] = useState('')

  useEffect(() => {
    if (initialTab) setTab(normalizeSettingsTab(initialTab))
  }, [initialTab])

  const refreshCatalog = (force = false): void => {
    setCatalogLoading(true)
    setCatalogError('')
    void loadModelCatalog(force)
      .then(models => {
        setCatalog(models)
        setEditing(current => {
          if (!current) return current
          const match = findCatalogModel(models, str(current.value.model))
          return match ? { ...current, value: withCatalogModel(current.value, match) } : current
        })
      })
      .catch(reason => setCatalogError(reason instanceof Error ? reason.message : '读取 models.dev 失败'))
      .finally(() => setCatalogLoading(false))
  }

  useEffect(() => {
    refreshCatalog(false)
  }, [])

  const settings = records.filter(r => r.entity === 'user_settings' && !r.deleted)
  const models = settings.filter(r => r.payload.kind === 'api_configs')
  const textModelsCount = models.filter(item => valueOf(item).imageConfig !== true).length
  const imageModelsCount = models.filter(item => valueOf(item).imageConfig === true).length
  const filteredModels = models.filter(item => {
    const isImg = valueOf(item).imageConfig === true
    if (modelFilter === 'text') return !isImg
    if (modelFilter === 'image') return isImg
    return true
  })

  // 按平台分组模型（对齐安卓端 SettingsScreen.kt 的 apiConfigsByApiKeyAndModality）
  const groupedModels = filteredModels.reduce<
    Array<{
      groupKey: string
      providerLabel: string
      addressLabel: string
      isBuiltin: boolean
      items: StoredRecord[]
    }>
  >((acc, item) => {
    const val = valueOf(item)
    const builtin = isBuiltinModel(val, str(item.payload.key))
    const providerLabel = builtin ? '默认配置 (官方内置)' : str(val.provider, '自定义平台')
    const addressLabel = builtin ? '内置安全托管通道' : str(val.address, 'https://api.openai.com/v1')
    const groupKey = builtin ? '__builtin__' : `${providerLabel}|${addressLabel}`
    const existing = acc.find(g => g.groupKey === groupKey)
    if (existing) {
      existing.items.push(item)
    } else {
      acc.push({
        groupKey,
        providerLabel,
        addressLabel,
        isBuiltin: builtin,
        items: [item],
      })
    }
    return acc
  }, [])

  const searches = settings.filter(r => r.payload.kind === 'web_search_configs')
  const selectedSearchRecord = settings.find(r => r.payload.kind === 'selected_models' && r.payload.key === 'web_search')
  const activeSearchProviderId = str(valueOf(selectedSearchRecord).providerId).toLowerCase()

  const mcpServers = settings.filter(r => r.payload.kind === 'mcp_configs')
  const skillPackages = settings.filter(r => r.payload.kind === 'skill_packages')
  const computerServers = settings.filter(r => r.payload.kind === 'computer_servers')

  const memorySwitchRecord = settings.find(r => r.payload.kind === 'memory_switches' && r.payload.key === 'default')
  const memoryEnabled = valueOf(memorySwitchRecord).memoryEnabled === true
  const memories = records
    .filter(r => r.entity === 'memories' && !r.deleted)
    .sort((a, b) => Number(b.payload.updatedAt ?? 0) - Number(a.payload.updatedAt ?? 0))

  const run = async (work: () => Promise<void>): Promise<void> => {
    setSaving(true)
    reportError('')
    try {
      await work()
    } catch (value) {
      reportError(value instanceof Error ? value.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const exportModelConfigs = () => {
    const customConfigs = models
      .filter(m => !isBuiltinModel(valueOf(m), str(m.payload.key)))
      .map(m => ({ key: str(m.payload.key), value: valueOf(m) }))
    const blob = new Blob(
      [JSON.stringify({ version: 1, exportedAt: new Date().toISOString(), apiConfigs: customConfigs }, null, 2)],
      { type: 'application/json' }
    )
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'everytalk-models-config.json'
    a.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  const importModelConfigs = (file: File) => {
    void run(async () => {
      const text = await file.text()
      const parsed: unknown = JSON.parse(text)
      if (!isObject(parsed) || !Array.isArray(parsed.apiConfigs)) {
        throw new Error('配置文件格式无效，请选择 EveryTalk 导出的模型配置 JSON')
      }
      for (const item of parsed.apiConfigs) {
        if (!isObject(item) || typeof item.key !== 'string' || !isObject(item.value)) continue
        const existing = models.find(m => str(m.payload.key) === item.key)
        await saveSetting('api_configs', item.key, item.value, existing)
      }
    })
  }

  /**
   * 打开「添加新平台与模型配置」或「向当前平台添加新模型」独立弹窗（对齐安卓端 AddProviderDialog / AddNewModelDialog）。
   */
  const startAddModel = (defaultProvider?: { provider: string; channel?: string; address: string }) => {
    const base: JsonObject = {
      name: '',
      provider: defaultProvider?.provider ?? 'OpenAI 兼容',
      channel: defaultProvider?.channel ?? 'OpenAI兼容',
      address: defaultProvider?.address ?? 'https://api.openai.com/v1',
      model: 'gpt-4.1-mini',
      apiKey: '',
      outputLimit: 4096,
      imageSize: '1024x1024',
      aspectRatio: '1:1',
      imageConfig: modelFilter === 'image',
    }
    const match = findCatalogModel(catalog, str(base.model))
    setAddModelEditor({
      key: crypto.randomUUID(),
      value: match ? withCatalogModel(base, match) : base,
    })
  }

  /**
   * 打开某个模型的「模型参数与能力配置」独立弹窗（对齐安卓端 ModelParametersDialog）。
   */
  const startModel = (record: StoredRecord) => {
    const base: JsonObject = { ...valueOf(record), apiKey: '' }
    const match = findCatalogModel(catalog, str(base.model))
    setEditing({
      record,
      key: str(record.payload.key, crypto.randomUUID()),
      value: match ? withCatalogModel(base, match) : base,
    })
  }

  const activeNavMeta = SETTINGS_NAV_ITEMS.find(item => item.id === tab) ?? SETTINGS_NAV_ITEMS[0]

  return (
    <div
      className="et-modal-backdrop"
      onClick={e => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <section role="dialog" aria-modal="true" aria-label="设置中心" className="et-settings">
        {/* 左侧桌面级分类导航栏 */}
        <aside className="et-settings-sidebar">
          <div className="et-settings-brand">
            <span className="et-settings-brand-badge">
              <SlidersHorizontal size={17} />
            </span>
            <div>
              <strong>设置中心</strong>
              <small>与安卓端全量对齐</small>
            </div>
          </div>

          <nav className="et-settings-nav" aria-label="设置分类">
            {SETTINGS_NAV_ITEMS.map(({ id, label, Icon }) => (
              <button
                key={id}
                type="button"
                className={`et-settings-nav-btn ${tab === id ? 'selected' : ''}`}
                onClick={() => setTab(id)}
              >
                <Icon size={17} />
                <span>{label}</span>
              </button>
            ))}
          </nav>
        </aside>

        {/* 右侧主内容区（始终保持清晰卡片列表，所有配置/参数页面均弹出独立对话框） */}
        <div className="et-settings-main">
          <header className="et-settings-main-header">
            <div className="et-settings-header-title">
              <div>
                <h2>{activeNavMeta.label}</h2>
                <p>{activeNavMeta.subtitle}</p>
              </div>
            </div>
            <button type="button" className="et-msg-circle-btn" aria-label="关闭设置" onClick={onClose}>
              <X size={18} />
            </button>
          </header>

          <div className="et-settings-body">
            {error && (
              <p className="et-error" role="alert">
                {error}
              </p>
            )}

            {/* ==================== 1. 平台与模型配置标签页（对齐 SettingsScreenContent.kt ApiKeyItemGroup） ==================== */}
            {tab === 'models' && (
              <div className="et-settings-section-stack">
                <div className="et-filter-bar">
                  <div className="et-filter-chips" role="tablist" aria-label="模型类型筛选">
                    {(
                      [
                        ['all', `全部 (${models.length})`],
                        ['text', `文本对话 (${textModelsCount})`],
                        ['image', `图片生成 (${imageModelsCount})`],
                      ] as [ModelFilter, string][]
                    ).map(([id, label]) => (
                      <button
                        key={id}
                        type="button"
                        className={`et-filter-chip ${modelFilter === id ? 'is-active' : ''}`}
                        onClick={() => setModelFilter(id)}
                      >
                        {label}
                      </button>
                    ))}
                  </div>
                  <div className="et-filter-chips">
                    <button
                      type="button"
                      className="et-filter-chip"
                      onClick={() => refreshCatalog(true)}
                      title="同步刷新 models.dev 模型目录"
                    >
                      <RefreshCw size={14} className={catalogLoading ? 'et-spin' : ''} />
                      <span>刷新模型库</span>
                    </button>
                    <button type="button" className="et-filter-chip is-active" onClick={() => startAddModel()}>
                      <Plus size={15} />
                      <span>添加配置</span>
                    </button>
                  </div>
                </div>

                {/* 按平台分组卡片列表（对齐安卓端 ApiKeyItemGroup） */}
                <div className="et-model-card-list">
                  {groupedModels.map(group => {
                    const isExpanded = expandedPlatformGroups[group.groupKey] ?? true
                    const firstVal = valueOf(group.items[0])
                    return (
                      <div key={group.groupKey} className="et-api-card">
                        <div className="et-api-card-head">
                          <div
                            className="et-api-card-title"
                            style={{ cursor: 'pointer' }}
                            onClick={() =>
                              setExpandedPlatformGroups(prev => ({
                                ...prev,
                                [group.groupKey]: !isExpanded,
                              }))
                            }
                          >
                            <span className="et-model-icon-badge">
                              <ModelIcon model={str(firstVal.model)} />
                            </span>
                            <div className="et-api-card-title-text">
                              <div className="et-api-card-title-row">
                                <strong>{group.providerLabel}</strong>
                                <span className="et-model-type-badge">{group.items.length} 个模型</span>
                                {group.isBuiltin && <span className="et-model-type-badge is-builtin">官方内置</span>}
                              </div>
                              <div className="et-muted" style={{ fontSize: 12.5 }}>
                                {group.addressLabel} · {group.isBuiltin ? '默认激活' : secretMark(firstVal)}
                              </div>
                            </div>
                          </div>
                          <div className="et-api-card-actions">
                            {!group.isBuiltin && (
                              <>
                                <button
                                  type="button"
                                  className="et-filter-chip"
                                  onClick={() =>
                                    setPlatformEditor({
                                      groupKey: group.groupKey,
                                      provider: group.providerLabel,
                                      channel: str(firstVal.channel, 'OpenAI兼容'),
                                      address: group.addressLabel,
                                      apiKey: '',
                                      items: group.items,
                                    })
                                  }
                                  title="编辑平台商家名称、接口地址与 API Key"
                                >
                                  <Edit3 size={13} />
                                  <span>编辑商家</span>
                                </button>
                                <button
                                  type="button"
                                  className="et-filter-chip"
                                  onClick={() =>
                                    startAddModel({
                                      provider: group.providerLabel,
                                      channel: str(firstVal.channel, 'OpenAI兼容'),
                                      address: group.addressLabel,
                                    })
                                  }
                                  title="向当前平台添加模型"
                                >
                                  <Plus size={13} />
                                  <span>添加模型</span>
                                </button>
                              </>
                            )}
                            <button
                              type="button"
                              className="et-filter-chip"
                              onClick={() =>
                                setExpandedPlatformGroups(prev => ({
                                  ...prev,
                                  [group.groupKey]: !isExpanded,
                                }))
                              }
                            >
                              <span>{isExpanded ? '收起列表' : '模型列表'}</span>
                              <ChevronDown
                                size={14}
                                className={`et-chevron-anim ${isExpanded ? 'is-expanded' : ''}`}
                              />
                            </button>
                          </div>
                        </div>

                        <div className={`et-collapsible-block ${isExpanded ? 'is-open' : ''}`}>
                          <div className="et-collapsible-inner">
                            <div className="et-submodel-list">
                              {group.items.map(item => {
                                const val = valueOf(item)
                                const builtin = isBuiltinModel(val, str(item.payload.key))
                                const title = str(val.name, str(val.model, '未命名模型'))
                                const isImageModel = val.imageConfig === true
                                return (
                                  <div key={item.id} className="et-submodel-row">
                                    <div className="et-submodel-info">
                                      <ModelIcon model={str(val.model)} />
                                      <div style={{ minWidth: 0 }}>
                                        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                                          <strong style={{ fontSize: 14 }}>{title}</strong>
                                          <span className={`et-model-type-badge ${isImageModel ? 'is-image' : ''}`}>
                                            {isImageModel ? '图片生成' : '文本对话'}
                                          </span>
                                        </div>
                                        <small className="et-muted" style={{ display: 'block', fontSize: 12 }}>
                                          {str(val.model)} · {capabilitySummary(val)}
                                        </small>
                                      </div>
                                    </div>
                                    <div className="et-api-card-actions">
                                      {builtin ? (
                                        <button
                                          type="button"
                                          className="et-filter-chip"
                                          onClick={() => startModel(item)}
                                        >
                                          <Pin size={13} />
                                          <span>查看详情</span>
                                        </button>
                                      ) : (
                                        <>
                                          <button
                                            type="button"
                                            className="et-filter-chip"
                                            aria-label={`配置 ${title} 参数`}
                                            title="打开模型参数与能力配置对话框"
                                            onClick={() => startModel(item)}
                                          >
                                            <SlidersHorizontal size={13} />
                                            <span>参数配置</span>
                                          </button>
                                          <button
                                            type="button"
                                            className="et-card-icon-btn is-danger"
                                            aria-label={`删除 ${title}`}
                                            title="删除此模型"
                                            onClick={() =>
                                              setConfirmDelete({
                                                title: '确定删除此模型配置？',
                                                subtitle: `删除后将同步移除「${title}」。`,
                                                onConfirm: async () => {
                                                  await saveSetting('api_configs', str(item.payload.key), val, item, true)
                                                },
                                              })
                                            }
                                          >
                                            <Trash2 size={15} />
                                          </button>
                                        </>
                                      )}
                                    </div>
                                  </div>
                                )
                              })}
                            </div>
                          </div>
                        </div>
                      </div>
                    )
                  })}
                  {!groupedModels.length && <p className="et-empty-list">暂无符合筛选条件的模型配置。</p>}
                </div>
              </div>
            )}

            {/* ==================== 2. MCP 与联网搜索标签页（完全对齐 McpSettingsContent & McpDialogs.kt） ==================== */}
            {tab === 'mcp' && (
              <div className="et-settings-section-stack">
                {/* 分区 1：MCP 服务器连接列表 */}
                <div className="et-filter-bar">
                  <div>
                    <strong style={{ fontSize: 15 }}>MCP 服务器与工具连接 ({mcpServers.length})</strong>
                    <div className="et-muted" style={{ fontSize: 12 }}>
                      点击服务器卡片可平滑展开查看已注册的 MCP 工具列表与端点详情
                    </div>
                  </div>
                  <button
                    type="button"
                    className="et-filter-chip is-active"
                    onClick={() =>
                      setMcpEditor({
                        preset: 'CUSTOM',
                        name: '',
                        url: '',
                        transportType: 'HTTP',
                        apiKey: '',
                      })
                    }
                  >
                    <Plus size={15} />
                    <span>添加 MCP 服务器</span>
                  </button>
                </div>

                <div className="et-model-card-list">
                  {mcpServers.map(item => {
                    const val = valueOf(item)
                    const serverId = str(item.payload.key)
                    const name = str(val.name, serverId)
                    const enabled = val.enabled !== false
                    const accent = resolveMcpAccentColor(name)
                    const tools = Array.isArray(val.tools) ? val.tools.filter(isObject) : []
                    const isExpanded = expandedMcpIds[serverId] === true

                    return (
                      <div
                        key={item.id}
                        className="et-search-provider-card"
                        style={enabled ? { borderColor: `${accent}66` } : undefined}
                      >
                        <div className="et-search-provider-top">
                          <span
                            className="et-provider-icon-box"
                            style={{
                              background: `${accent}1F`,
                              color: accent,
                            }}
                          >
                            <Wrench size={20} />
                          </span>

                          <div
                            className="et-provider-info"
                            style={{ cursor: 'pointer' }}
                            onClick={() =>
                              setExpandedMcpIds(prev => ({
                                ...prev,
                                [serverId]: !isExpanded,
                              }))
                            }
                          >
                            <strong>
                              <span>{name}</span>
                              <span className="et-model-type-badge">{str(val.transportType, 'HTTP')}</span>
                              <span className={`et-provider-key-badge ${enabled ? 'is-configured' : ''}`}>
                                {enabled ? `已连接 · ${tools.length} 个可用工具` : '已暂停'}
                              </span>
                            </strong>
                            <small>{str(val.description, str(val.url))}</small>
                          </div>

                          <div className="et-search-provider-controls">
                            <button
                              type="button"
                              className="et-filter-chip"
                              onClick={() =>
                                setExpandedMcpIds(prev => ({
                                  ...prev,
                                  [serverId]: !isExpanded,
                                }))
                              }
                            >
                              <span>{isExpanded ? '收起工具' : `可用工具 (${tools.length})`}</span>
                              <ChevronDown
                                size={14}
                                className={`et-chevron-anim ${isExpanded ? 'is-expanded' : ''}`}
                              />
                            </button>

                            <button
                              type="button"
                              className="et-card-icon-btn"
                              title="打开编辑 MCP 服务器对话框"
                              onClick={() =>
                                setMcpEditor({
                                  record: item,
                                  preset: (str(val.preset, 'CUSTOM') as 'CUSTOM' | 'CONTEXT7' | 'EXA_SEARCH' | 'FIRECRAWL'),
                                  name,
                                  url: str(val.url),
                                  transportType: str(val.transportType, 'HTTP') === 'SSE' ? 'SSE' : 'HTTP',
                                  apiKey: '',
                                })
                              }
                            >
                              <Edit3 size={14} />
                            </button>

                            <button
                              type="button"
                              className="et-card-icon-btn is-danger"
                              title="移除连接"
                              onClick={() =>
                                setConfirmDelete({
                                  title: `移除 MCP 服务器「${name}」？`,
                                  subtitle: '移除后对话中将不再挂载该服务器提供的工具。',
                                  onConfirm: async () => {
                                    await saveSetting('mcp_configs', serverId, val, item, true)
                                  },
                                })
                              }
                            >
                              <Trash2 size={14} />
                            </button>

                            <button
                              type="button"
                              role="switch"
                              aria-checked={enabled}
                              aria-label={`启用 ${name}`}
                              className={`et-switch ${enabled ? 'is-checked' : ''}`}
                              onClick={() =>
                                void run(async () => {
                                  await saveSetting(
                                    'mcp_configs',
                                    serverId,
                                    { ...val, enabled: !enabled, status: !enabled ? 'connected' : 'idle' },
                                    item
                                  )
                                })
                              }
                            >
                              <span className="et-switch-thumb" />
                            </button>
                          </div>
                        </div>

                        <div className={`et-collapsible-block ${isExpanded ? 'is-open' : ''}`}>
                          <div className="et-collapsible-inner">
                            <div className="et-mcp-tools-drawer">
                              <div className="et-muted" style={{ fontSize: 12, marginBottom: 8 }}>
                                端点地址：<code>{str(val.url)}</code>
                              </div>
                              <div className="et-mcp-tool-grid">
                                {tools.map((tool, idx) => (
                                  <div key={`${str(tool.name)}:${idx}`} className="et-mcp-tool-chip">
                                    <strong>{str(tool.name)}</strong>
                                    <span>{str(tool.description, '远程 MCP 工具函数')}</span>
                                  </div>
                                ))}
                              </div>
                            </div>
                          </div>
                        </div>
                      </div>
                    )
                  })}
                </div>

                {/* 分区 2：内置 OAuth 与邮件连接器（对齐安卓端 McpBuiltinProviderItem） */}
                <div className="et-filter-bar" style={{ marginTop: 6 }}>
                  <div>
                    <strong style={{ fontSize: 15 }}>内置服务与邮箱连接器</strong>
                    <div className="et-muted" style={{ fontSize: 12 }}>
                      一键接入 Gmail、Microsoft 365、QQ 邮箱与网易邮箱 MCP 工具
                    </div>
                  </div>
                </div>

                <div className="et-parameter-grid">
                  {MCP_BUILTIN_CONNECTORS.map(connector => {
                    // 同步 key 是 UUID，内置连接器用本地业务 ID（view.id / localId）匹配。
                    const existing = mcpServers.find(
                      r =>
                        str(r.payload.key) === connector.key ||
                        str(valueOf(r).id) === connector.key ||
                        str(valueOf(r).localId) === connector.key
                    )
                    const isEnabled = existing ? valueOf(existing).enabled !== false : false
                    return (
                      <div key={connector.key} className="et-api-card">
                        <div className="et-api-card-head">
                          <div className="et-api-card-title">
                            <span
                              className="et-provider-icon-box"
                              style={{
                                width: 38,
                                height: 38,
                                background: `${connector.accentColor}1F`,
                                color: connector.accentColor,
                              }}
                            >
                              <Mail size={18} />
                            </span>
                            <div className="et-api-card-title-text">
                              <strong>{connector.name}</strong>
                              <div className="et-muted" style={{ fontSize: 12, marginTop: 2 }}>
                                {connector.description}
                              </div>
                            </div>
                          </div>
                          <button
                            type="button"
                            role="switch"
                            aria-checked={isEnabled}
                            className={`et-switch ${isEnabled ? 'is-checked' : ''}`}
                            onClick={() =>
                              void run(async () => {
                                await saveSetting(
                                  'mcp_configs',
                                  connector.key,
                                  {
                                    // 保留安卓记录的本地业务 ID，避免编辑后被当成另一台服务器。
                                    id: str(existing ? valueOf(existing).id : '', connector.key),
                                    name: connector.name,
                                    url: connector.url,
                                    transportType: connector.transportType,
                                    preset: 'CUSTOM',
                                    enabled: !isEnabled,
                                    status: !isEnabled ? 'connected' : 'idle',
                                    description: connector.description,
                                    tools: connector.tools,
                                  },
                                  existing
                                )
                              })
                            }
                          >
                            <span className="et-switch-thumb" />
                          </button>
                        </div>
                      </div>
                    )
                  })}
                </div>

                {/* 分区 3：外部联网搜索引擎（对齐安卓端 ExternalWebSearchProviderCards） */}
                <div className="et-filter-bar" style={{ marginTop: 6 }}>
                  <div>
                    <strong style={{ fontSize: 15 }}>联网搜索服务 (External Web Search)</strong>
                    <div className="et-muted" style={{ fontSize: 12 }}>
                      配置 Tavily、Exa、Bocha 与 SerpAPI 实时网页检索引擎密钥
                    </div>
                  </div>
                </div>

                <div className="et-search-provider-list">
                  {WEB_SEARCH_PROVIDERS.map(provider => {
                    const record = searches.find(
                      r =>
                        str(r.payload.key).toLowerCase() === provider.id ||
                        str(valueOf(r).providerId).toLowerCase() === provider.id
                    )
                    const val = valueOf(record)
                    const hasKey = Boolean(
                      (typeof val.apiKey === 'string' && val.apiKey) || isObject(val.__everytalk_secret)
                    )
                    const isSelected =
                      activeSearchProviderId === provider.id ||
                      (!activeSearchProviderId && record && searches[0]?.id === record.id)

                    return (
                      <div key={provider.id} className={`et-search-provider-card ${isSelected ? 'is-active' : ''}`}>
                        <div className="et-search-provider-top">
                          <span
                            className="et-provider-icon-box"
                            style={{
                              background: `${provider.accentColor}1F`,
                              color: provider.accentColor,
                            }}
                          >
                            {provider.id === 'tavily' ? (
                              <Globe size={21} />
                            ) : provider.id === 'exa' ? (
                              <Sparkles size={21} />
                            ) : provider.id === 'bocha' ? (
                              <Search size={21} />
                            ) : (
                              <Database size={21} />
                            )}
                          </span>

                          <div className="et-provider-info">
                            <strong>
                              <span>{provider.name}</span>
                              <span className={`et-provider-key-badge ${hasKey ? 'is-configured' : ''}`}>
                                {hasKey ? '已配置密钥' : '未配置'}
                              </span>
                            </strong>
                            <small>{provider.description}</small>
                          </div>

                          <div className="et-search-provider-controls">
                            <button
                              type="button"
                              className="et-filter-chip"
                              onClick={() => {
                                setEditingSearchProvider(provider.id)
                                setSearchKeyDraft('')
                              }}
                            >
                              <Key size={13} />
                              <span>配置密钥</span>
                            </button>

                            <button
                              type="button"
                              role="switch"
                              aria-checked={Boolean(isSelected)}
                              aria-label={`启用 ${provider.name} 联网搜索`}
                              className={`et-switch ${isSelected ? 'is-checked' : ''}`}
                              onClick={() =>
                                void run(async () => {
                                  if (!hasKey) {
                                    setEditingSearchProvider(provider.id)
                                    setSearchKeyDraft('')
                                    return
                                  }
                                  await saveSetting(
                                    'selected_models',
                                    'web_search',
                                    { ...valueOf(selectedSearchRecord), providerId: provider.id },
                                    selectedSearchRecord
                                  )
                                })
                              }
                            >
                              <span className="et-switch-thumb" />
                            </button>
                          </div>
                        </div>
                      </div>
                    )
                  })}
                </div>
              </div>
            )}

            {/* ==================== 3. 技能 (Skills) 标签页（对齐安卓端 SkillScreen） ==================== */}
            {tab === 'skills' && (
              <div className="et-settings-section-stack">
                <div className="et-filter-bar">
                  <div className="et-filter-chips">
                    <span className="et-muted" style={{ fontSize: 13 }}>
                      已安装 {skillPackages.length} 个技能包（点击卡片平滑展开规则与附属文件）
                    </span>
                  </div>
                  <div className="et-filter-chips">
                    <label className="et-filter-chip et-upload-chip" title="从本地导入 .md 或 .zip 技能包">
                      <Upload size={14} />
                      <span>导入本地技能</span>
                      <input
                        type="file"
                        accept=".md,.zip,.txt"
                        className="et-hidden"
                        onChange={e => {
                          const file = e.target.files?.[0]
                          if (!file) return
                          void run(async () => {
                            const content = await file.text().catch(() => `# ${file.name}\n本地导入技能包`)
                            const pkgId = `local-${crypto.randomUUID().slice(0, 8)}`
                            const hash = Math.random().toString(16).slice(2, 12)
                            const cleanName = file.name.replace(/\.(md|zip|txt)$/i, '') || 'imported-skill'
                            await saveSetting('skill_packages', pkgId, {
                              packageId: pkgId,
                              name: cleanName,
                              sourceType: 'LOCAL_IMPORT',
                              sourceRepository: file.name,
                              currentHash: hash,
                              updateHash: '',
                              enabled: true,
                              description: `从本地文件 ${file.name} 导入的技能规范`,
                              children: [{ skillId: pkgId, name: cleanName, description: `本地导入文件 (${file.size} B)` }],
                              markdown: content,
                              files: [{ path: 'SKILL.md', size: file.size || content.length, sha256: hash }],
                            })
                          })
                          e.target.value = ''
                        }}
                      />
                    </label>
                    <button
                      type="button"
                      className="et-filter-chip"
                      onClick={() => setSkillSubView('store')}
                    >
                      <Download size={14} />
                      <span>在线技能商店</span>
                    </button>
                    <button
                      type="button"
                      className="et-filter-chip is-active"
                      onClick={() => setSkillSubView('create')}
                    >
                      <Plus size={15} />
                      <span>创建技能</span>
                    </button>
                  </div>
                </div>

                <div className="et-model-card-list">
                  {skillPackages.map(item => {
                    const val = valueOf(item)
                    const pkgId = str(item.payload.key)
                    const name = str(val.name, pkgId)
                    const enabled = val.enabled !== false
                    const sourceType = str(val.sourceType, 'REMOTE')
                    const sourceLabel =
                      sourceType === 'USER_CREATED'
                        ? '自建技能'
                        : sourceType === 'LOCAL_IMPORT'
                          ? '本地导入'
                          : '在线仓库'
                    const children = Array.isArray(val.children) ? val.children.filter(isObject) : []
                    const files = Array.isArray(val.files) ? val.files.filter(isObject) : []
                    const updateHash = str(val.updateHash)
                    const isExpanded = expandedSkillIds[pkgId] === true

                    return (
                      <div key={item.id} className="et-api-card">
                        <div className="et-api-card-head">
                          <div
                            className="et-api-card-title"
                            style={{ cursor: 'pointer' }}
                            onClick={() =>
                              setExpandedSkillIds(prev => ({
                                ...prev,
                                [pkgId]: !isExpanded,
                              }))
                            }
                          >
                            <span className="et-model-icon-badge">
                              <Package size={18} />
                            </span>
                            <div className="et-api-card-title-text">
                              <div className="et-api-card-title-row">
                                <strong>{name.split('/').pop() || name}</strong>
                                <span className="et-model-type-badge">{children.length || 1} 个技能</span>
                                <span className="et-model-type-badge is-builtin">{sourceLabel}</span>
                                {updateHash && (
                                  <span className="et-model-type-badge is-image">可更新 #{updateHash.slice(0, 6)}</span>
                                )}
                              </div>
                              <div className="et-muted" style={{ fontSize: 12.5, marginTop: 2 }}>
                                {str(val.description, children.map(c => str(c.name)).join(' · '))}
                              </div>
                            </div>
                          </div>

                          <div className="et-api-card-actions">
                            <button
                              type="button"
                              className="et-filter-chip"
                              onClick={() =>
                                setExpandedSkillIds(prev => ({
                                  ...prev,
                                  [pkgId]: !isExpanded,
                                }))
                              }
                            >
                              <span>{isExpanded ? '收起详情' : '技能详情'}</span>
                              <ChevronDown
                                size={14}
                                className={`et-chevron-anim ${isExpanded ? 'is-expanded' : ''}`}
                              />
                            </button>

                            <button
                              type="button"
                              role="switch"
                              aria-checked={enabled}
                              aria-label={`启用技能 ${name}`}
                              className={`et-switch ${enabled ? 'is-checked' : ''}`}
                              onClick={() =>
                                void run(async () => {
                                  await saveSetting('skill_packages', pkgId, { ...val, enabled: !enabled }, item)
                                })
                              }
                            >
                              <span className="et-switch-thumb" />
                            </button>

                            <button
                              type="button"
                              className="et-card-icon-btn is-danger"
                              aria-label={`删除技能 ${name}`}
                              title="删除此技能包"
                              onClick={() =>
                                setConfirmDelete({
                                  title: `删除技能包「${name}」？`,
                                  subtitle: '删除后本地技能文件与规则快照将被移除。',
                                  onConfirm: async () => {
                                    await saveSetting('skill_packages', pkgId, val, item, true)
                                  },
                                })
                              }
                            >
                              <Trash2 size={15} />
                            </button>
                          </div>
                        </div>

                        <div className={`et-collapsible-block ${isExpanded ? 'is-open' : ''}`}>
                          <div className="et-collapsible-inner">
                            <div className="et-skill-detail-box">
                              <div className="et-skill-meta-row">
                                <span>来源：{str(val.sourceRepository, sourceLabel)}</span>
                                <span>当前版本：#{str(val.currentHash, 'latest').slice(0, 10)}</span>
                              </div>

                              {children.length > 0 && (
                                <div className="et-mcp-tool-grid">
                                  {children.map((child, idx) => (
                                    <div key={`${str(child.skillId)}:${idx}`} className="et-mcp-tool-chip">
                                      <strong>{str(child.name)}</strong>
                                      <span>{str(child.description)}</span>
                                    </div>
                                  ))}
                                </div>
                              )}

                              {str(val.markdown) && (
                                <pre className="et-skill-md-preview">{str(val.markdown)}</pre>
                              )}

                              {files.length > 0 && (
                                <div className="et-skill-files-list">
                                  {files.map((file, idx) => (
                                    <div key={`${str(file.path)}:${idx}`} className="et-skill-file-chip">
                                      <FileCode size={13} />
                                      <span>{str(file.path)}</span>
                                      <small>
                                        {Number(file.size ?? 0)} B · {str(file.sha256).slice(0, 8)}
                                      </small>
                                    </div>
                                  ))}
                                </div>
                              )}

                              <div className="et-filter-chips" style={{ marginTop: 4 }}>
                                <button
                                  type="button"
                                  className="et-filter-chip is-active"
                                  onClick={() =>
                                    setEditingSkillRules({
                                      record: item,
                                      markdown: str(val.markdown, `# ${name}\n`),
                                    })
                                  }
                                >
                                  <Edit3 size={13} />
                                  <span>{sourceType === 'REMOTE' ? '复制并编辑规则' : '编辑 SKILL.md 规则'}</span>
                                </button>
                                {updateHash && (
                                  <button
                                    type="button"
                                    className="et-filter-chip"
                                    onClick={() =>
                                      void run(async () => {
                                        await saveSetting(
                                          'skill_packages',
                                          pkgId,
                                          { ...val, currentHash: updateHash, updateHash: '' },
                                          item
                                        )
                                      })
                                    }
                                  >
                                    <RefreshCw size={13} />
                                    <span>同步到最新版本 (#{updateHash.slice(0, 6)})</span>
                                  </button>
                                )}
                              </div>
                            </div>
                          </div>
                        </div>
                      </div>
                    )
                  })}
                  {!skillPackages.length && <p className="et-empty-list">暂未安装任何技能包，可从在线技能商店一键安装或创建新技能。</p>}
                </div>
              </div>
            )}

            {/* ==================== 4. 远程服务器 (计算机) 标签页（对齐安卓端 ComputerScreen / ComputerDetailScreen / ComputerWorkspaceUi） ==================== */}
            {tab === 'servers' && (
              <div className="et-settings-section-stack">
                  <div className="et-filter-bar">
                    <div>
                      <strong style={{ fontSize: 15 }}>已配置远程服务器 ({computerServers.length})</strong>
                      <div className="et-muted" style={{ fontSize: 12 }}>
                        点击服务器或工作区卡片可平滑展开环境变量密钥 (Secret)、端口转发预览与权限开关
                      </div>
                    </div>
                    <button
                      type="button"
                      className="et-filter-chip is-active"
                      onClick={() =>
                        setServerEditor({
                          provider: 'SSH',
                          name: '',
                          host: '',
                          port: '22',
                          username: 'ubuntu',
                          authKind: 'PRIVATE_KEY',
                          secret: '',
                          runMode: 'CONTAINER',
                          cloudflareAccountName: '',
                          cloudflareAccountId: '',
                        })
                      }
                    >
                      <Plus size={15} />
                      <span>添加服务器</span>
                    </button>
                  </div>

                  <div className="et-model-card-list">
                    {computerServers.map(item => {
                      const val = valueOf(item)
                      const serverId = str(item.payload.key)
                      const name = str(val.name, serverId)
                      const isCloudflare = str(val.provider) === 'CLOUDFLARE'
                      const isExpanded = expandedServerIds[serverId] === true
                      const permissionMode = str(val.permissionMode, 'ASK')
                      const privateNetworkAllowed = val.privateNetworkAllowed === true
                      const workspaces = Array.isArray(val.workspaces) ? val.workspaces.filter(isObject) : []

                      return (
                        <div key={item.id} className="et-api-card">
                          <div className="et-api-card-head">
                            <div
                              className="et-api-card-title"
                              style={{ cursor: 'pointer' }}
                              onClick={() =>
                                setExpandedServerIds(prev => ({
                                  ...prev,
                                  [serverId]: !isExpanded,
                                }))
                              }
                            >
                              <span
                                className="et-model-icon-badge"
                                style={{
                                  color: isCloudflare ? '#F6821F' : '#10B981',
                                }}
                              >
                                {isCloudflare ? <Globe size={19} /> : <Terminal size={19} />}
                              </span>
                              <div className="et-api-card-title-text">
                                <div className="et-api-card-title-row">
                                  <strong>{name}</strong>
                                  <span className="et-model-type-badge is-image">已就绪</span>
                                  <span className="et-model-type-badge">
                                    {isCloudflare
                                      ? 'Cloudflare Sandbox'
                                      : str(val.runMode) === 'DIRECT'
                                        ? 'SSH · 主机直连'
                                        : 'SSH · Docker 容器'}
                                  </span>
                                </div>
                                <div className="et-muted" style={{ fontSize: 12.5, marginTop: 2 }}>
                                  {isCloudflare
                                    ? `${str(val.cloudflareAccountName, 'Cloudflare')} · ${str(val.cloudflareIdentity)}`
                                    : `${str(val.username, 'root')}@${str(val.host)}:${Number(val.port ?? 22)}`}
                                  {' · '}
                                  {workspaces.length} 个工作区 · {Number(val.containers ?? 1)} 个运行容器
                                </div>
                              </div>
                            </div>

                            <div className="et-api-card-actions">
                              <button
                                type="button"
                                className="et-filter-chip"
                                onClick={() =>
                                  setExpandedServerIds(prev => ({
                                    ...prev,
                                    [serverId]: !isExpanded,
                                  }))
                                }
                              >
                                <span>{isExpanded ? '收起工作区' : `工作区 (${workspaces.length})`}</span>
                                <ChevronDown
                                  size={14}
                                  className={`et-chevron-anim ${isExpanded ? 'is-expanded' : ''}`}
                                />
                              </button>

                              <button
                                type="button"
                                className="et-card-icon-btn"
                                title="编辑连接"
                                onClick={() =>
                                  setServerEditor({
                                    record: item,
                                    provider: isCloudflare ? 'CLOUDFLARE' : 'SSH',
                                    name,
                                    host: str(val.host),
                                    port: String(val.port ?? 22),
                                    username: str(val.username, 'ubuntu'),
                                    authKind: str(val.authKind) === 'PASSWORD' ? 'PASSWORD' : 'PRIVATE_KEY',
                                    secret: '',
                                    runMode: str(val.runMode) === 'DIRECT' ? 'DIRECT' : 'CONTAINER',
                                    cloudflareAccountName: str(val.cloudflareAccountName),
                                    cloudflareAccountId: str(val.cloudflareAccountId),
                                  })
                                }
                              >
                                <Edit3 size={14} />
                              </button>

                              <button
                                type="button"
                                className="et-card-icon-btn is-danger"
                                title="移除服务器"
                                onClick={() =>
                                  setConfirmDelete({
                                    title: `确定移除服务器「${name}」？`,
                                    subtitle: '移除后本机保存的连接凭据与工作区映射将被清除。',
                                    onConfirm: async () => {
                                      await saveSetting('computer_servers', serverId, val, item, true)
                                    },
                                  })
                                }
                              >
                                <Trash2 size={14} />
                              </button>
                            </div>
                          </div>

                          <div className={`et-collapsible-block ${isExpanded ? 'is-open' : ''}`}>
                            <div className="et-collapsible-inner">
                              <div className="et-server-detail-box">
                                {/* 安全与权限控制栏（对齐 ComputerDetailScreen.kt） */}
                                <div className="et-server-security-bar">
                                  <div className="et-server-sec-item">
                                    <div>
                                      <strong>执行权限模式</strong>
                                      <small>
                                        {permissionMode === 'AUTO'
                                          ? '自动批准：AI 可直接执行容器内命令'
                                          : '询问确认：高风险命令执行前需手动确认'}
                                      </small>
                                    </div>
                                    <button
                                      type="button"
                                      className={`et-filter-chip ${permissionMode === 'AUTO' ? 'is-active' : ''}`}
                                      onClick={() =>
                                        void run(async () => {
                                          await saveSetting(
                                            'computer_servers',
                                            serverId,
                                            { ...val, permissionMode: permissionMode === 'AUTO' ? 'ASK' : 'AUTO' },
                                            item
                                          )
                                        })
                                      }
                                    >
                                      <Shield size={13} />
                                      <span>{permissionMode === 'AUTO' ? '自动批准' : '询问确认'}</span>
                                    </button>
                                  </div>

                                  {!isCloudflare && (
                                    <div className="et-server-sec-item">
                                      <div>
                                        <strong>允许访问私有内网 (RFC1918)</strong>
                                        <small>默认阻止容器访问宿主机局域网段，保障内网安全</small>
                                      </div>
                                      <button
                                        type="button"
                                        role="switch"
                                        aria-checked={privateNetworkAllowed}
                                        className={`et-switch ${privateNetworkAllowed ? 'is-checked' : ''}`}
                                        onClick={() =>
                                          void run(async () => {
                                            await saveSetting(
                                              'computer_servers',
                                              serverId,
                                              { ...val, privateNetworkAllowed: !privateNetworkAllowed },
                                              item
                                            )
                                          })
                                        }
                                      >
                                        <span className="et-switch-thumb" />
                                      </button>
                                    </div>
                                  )}
                                </div>

                                <div className="et-muted" style={{ fontSize: 11.5 }}>
                                  主机指纹：<code>{str(val.hostKeyFingerprint, 'SHA256:Verified')}</code>
                                </div>

                                {/* 会话工作区列表（对齐 ComputerWorkspaceCard） */}
                                <div className="et-workspace-list">
                                  {workspaces.map(ws => {
                                    const wsId = str(ws.id)
                                    const wsExpanded = expandedWorkspaceIds[wsId] === true
                                    const secrets = Array.isArray(ws.secrets) ? ws.secrets.filter(isObject) : []
                                    const previews = Array.isArray(ws.previews) ? ws.previews.filter(isObject) : []

                                    return (
                                      <div key={wsId} className="et-workspace-card">
                                        <button
                                          type="button"
                                          className="et-workspace-card-head"
                                          onClick={() =>
                                            setExpandedWorkspaceIds(prev => ({
                                              ...prev,
                                              [wsId]: !wsExpanded,
                                            }))
                                          }
                                        >
                                          <div style={{ textAlign: 'left' }}>
                                            <strong>{str(ws.name, wsId)}</strong>
                                            <small>
                                              已就绪 · 路径 <code>{str(ws.hostPath)}</code> · {secrets.length} 个密钥 · {previews.length} 个端口预览
                                            </small>
                                          </div>
                                          <ChevronDown
                                            size={15}
                                            className={`et-chevron-anim ${wsExpanded ? 'is-expanded' : ''}`}
                                          />
                                        </button>

                                        <div className={`et-collapsible-block ${wsExpanded ? 'is-open' : ''}`}>
                                          <div className="et-collapsible-inner">
                                            <div className="et-workspace-card-body">
                                              <div className="et-filter-chips">
                                                <button
                                                  type="button"
                                                  className="et-filter-chip"
                                                  onClick={() =>
                                                    setWorkspaceSecretDraft({
                                                      serverRecord: item,
                                                      workspaceId: wsId,
                                                      name: '',
                                                      value: '',
                                                    })
                                                  }
                                                >
                                                  <Key size={13} />
                                                  <span>添加环境变量密钥 (Secret)</span>
                                                </button>
                                                <button
                                                  type="button"
                                                  className="et-filter-chip"
                                                  onClick={() =>
                                                    setWorkspacePreviewDraft({
                                                      serverRecord: item,
                                                      workspaceId: wsId,
                                                      visibility: 'PRIVATE',
                                                      port: '3000',
                                                      protocol: 'HTTP',
                                                    })
                                                  }
                                                >
                                                  <Lock size={13} />
                                                  <span>创建私有端口预览</span>
                                                </button>
                                                <button
                                                  type="button"
                                                  className="et-filter-chip"
                                                  onClick={() =>
                                                    setWorkspacePreviewDraft({
                                                      serverRecord: item,
                                                      workspaceId: wsId,
                                                      visibility: 'PUBLIC',
                                                      port: '8080',
                                                      protocol: 'HTTP',
                                                    })
                                                  }
                                                >
                                                  <ExternalLink size={13} />
                                                  <span>创建公开端口预览</span>
                                                </button>
                                              </div>

                                              {secrets.length > 0 && (
                                                <div className="et-ws-sublist">
                                                  <span className="et-form-label">已注入环境变量密钥 (Secrets)</span>
                                                  {secrets.map((sec, idx) => (
                                                    <div key={`${str(sec.name)}:${idx}`} className="et-ws-subrow">
                                                      <code>{str(sec.name)} = ••••••••</code>
                                                      <button
                                                        type="button"
                                                        className="et-card-icon-btn is-danger"
                                                        title="删除密钥"
                                                        onClick={() =>
                                                          void run(async () => {
                                                            const nextWorkspaces = workspaces.map(w =>
                                                              str(w.id) === wsId
                                                                ? {
                                                                    ...w,
                                                                    secrets: secrets.filter(s => str(s.name) !== str(sec.name)),
                                                                  }
                                                                : w
                                                            )
                                                            await saveSetting(
                                                              'computer_servers',
                                                              serverId,
                                                              { ...val, workspaces: nextWorkspaces },
                                                              item
                                                            )
                                                          })
                                                        }
                                                      >
                                                        <Trash2 size={13} />
                                                      </button>
                                                    </div>
                                                  ))}
                                                </div>
                                              )}

                                              {previews.length > 0 && (
                                                <div className="et-ws-sublist">
                                                  <span className="et-form-label">活动端口转发预览 (Previews)</span>
                                                  {previews.map((prevItem, idx) => (
                                                    <div key={`${str(prevItem.id)}:${idx}`} className="et-ws-subrow">
                                                      <div>
                                                        <span className="et-model-type-badge" style={{ marginRight: 8 }}>
                                                          {str(prevItem.visibility) === 'PUBLIC' ? '公开链接' : '私有隧道'} · :{Number(prevItem.port ?? 3000)}
                                                        </span>
                                                        <a
                                                          href={str(prevItem.url)}
                                                          target="_blank"
                                                          rel="noreferrer"
                                                          style={{ fontSize: 12.5, textDecoration: 'underline' }}
                                                        >
                                                          {str(prevItem.url)}
                                                        </a>
                                                      </div>
                                                      <button
                                                        type="button"
                                                        className="et-filter-chip"
                                                        onClick={() =>
                                                          void run(async () => {
                                                            const nextWorkspaces = workspaces.map(w =>
                                                              str(w.id) === wsId
                                                                ? {
                                                                    ...w,
                                                                    previews: previews.filter(p => str(p.id) !== str(prevItem.id)),
                                                                  }
                                                                : w
                                                            )
                                                            await saveSetting(
                                                              'computer_servers',
                                                              serverId,
                                                              { ...val, workspaces: nextWorkspaces },
                                                              item
                                                            )
                                                          })
                                                        }
                                                      >
                                                        停止预览
                                                      </button>
                                                    </div>
                                                  ))}
                                                </div>
                                              )}
                                            </div>
                                          </div>
                                        </div>
                                      </div>
                                    )
                                  })}
                                </div>
                              </div>
                            </div>
                          </div>
                        </div>
                      )
                    })}
                    {!computerServers.length && <p className="et-empty-list">暂未添加远程服务器，点击右上角「添加服务器」配置 SSH 或 Cloudflare 沙盒。</p>}
                  </div>
                </div>
              )}

            {/* ==================== 5. 长期记忆标签页 ==================== */}
            {tab === 'memory' && (
              <div className="et-settings-section-stack">
                  <div className="et-memory-switch-bar">
                    <div>
                      <strong>启用跨会话长期记忆</strong>
                      <small>开启后 AI 将在对话中自动参考你的长期偏好与背景信息，并支持跨会话检索</small>
                    </div>
                    <button
                      type="button"
                      role="switch"
                      aria-checked={memoryEnabled}
                      aria-label="使用记忆"
                      className={`et-switch ${memoryEnabled ? 'is-checked' : ''}`}
                      onClick={() =>
                        void run(async () => {
                          await saveSetting(
                            'memory_switches',
                            'default',
                            { ...valueOf(memorySwitchRecord), memoryEnabled: !memoryEnabled },
                            memorySwitchRecord
                          )
                        })
                      }
                    >
                      <span className="et-switch-thumb" />
                    </button>
                  </div>

                  <div className="et-filter-bar">
                    <span className="et-muted" style={{ fontSize: 13 }}>
                      共 {memories.length} 条长期记忆条目
                    </span>
                    <button
                      type="button"
                      className="et-filter-chip is-active"
                      onClick={() => setMemoryDraft({ content: '' })}
                    >
                      <Plus size={15} />
                      <span>新增记忆</span>
                    </button>
                  </div>

                  <div className="et-model-card-list">
                    {memories.map(item => (
                      <div key={item.id} className="et-api-card">
                        <div className="et-api-card-head">
                          <div style={{ flex: 1, minWidth: 0 }}>
                            <strong
                              style={{
                                display: 'block',
                                whiteSpace: 'pre-wrap',
                                overflowWrap: 'anywhere',
                                fontSize: 14,
                                lineHeight: 1.6,
                              }}
                            >
                              {str(item.payload.content)}
                            </strong>
                            <small className="et-muted" style={{ display: 'block', marginTop: 6, fontSize: 12 }}>
                              更新于 {new Date(Number(item.payload.updatedAt ?? Date.now())).toLocaleString('zh-CN')}
                            </small>
                          </div>
                          <div className="et-api-card-actions">
                            <button
                              type="button"
                              className="et-card-icon-btn"
                              aria-label="编辑记忆"
                              title="编辑记忆"
                              onClick={() => setMemoryDraft({ record: item, content: str(item.payload.content) })}
                            >
                              <Edit3 size={15} />
                            </button>
                            <button
                              type="button"
                              className="et-card-icon-btn is-danger"
                              aria-label="删除记忆"
                              title="删除记忆"
                              onClick={() =>
                                setConfirmDelete({
                                  title: '确定删除此条记忆？',
                                  subtitle: '删除后两端将不再在对话中引用该条记忆。',
                                  onConfirm: async () => {
                                    await saveMemory('', item, true)
                                  },
                                })
                              }
                            >
                              <Trash2 size={15} />
                            </button>
                          </div>
                        </div>
                      </div>
                    ))}
                    {!memories.length && <p className="et-empty-list">还没有长期记忆条目。</p>}
                  </div>
                </div>
              )}

            {/* ==================== 6. 账户资料标签页（对齐安卓端 AccountScreen.kt） ==================== */}
            {tab === 'account' && (
              <div className="et-settings-section-stack">
                <div className="et-api-card">
                  <div className="et-api-card-head">
                    <div className="et-api-card-title">
                      <span
                        className="et-avatar"
                        style={{ width: 52, height: 52, fontSize: 20 }}
                      >
                        {(displayNameDraft.trim() || 'E').slice(0, 1).toUpperCase()}
                      </span>
                      <div>
                        <strong style={{ fontSize: 16 }}>{displayNameDraft || 'EveryTalk 用户'}</strong>
                        <div className="et-muted" style={{ fontSize: 13, marginTop: 2 }}>
                          preview@everytalk.local · 端到端加密同步已就绪
                        </div>
                      </div>
                    </div>
                    <span className="et-model-type-badge is-image">已激活工作区</span>
                  </div>
                </div>

                <form
                  className="et-parameter-section"
                  onSubmit={e => {
                    e.preventDefault()
                    void run(async () => {
                      await saveSetting(
                        'account_profile',
                        'default',
                        { displayName: displayNameDraft.trim() },
                        profileRecord
                      )
                      setProfileSavedNotice('账户昵称已保存')
                      window.setTimeout(() => setProfileSavedNotice(''), 2000)
                    })
                  }}
                >
                  <div className="et-parameter-section-head">
                    <h4>个人资料设置</h4>
                    <small>修改在侧边栏与跨设备同步记录中展示的账户显示名称</small>
                  </div>
                  <label className="et-form-field">
                    <span className="et-form-label">显示名称 (Display Name)</span>
                    <input
                      required
                      maxLength={50}
                      value={displayNameDraft}
                      onChange={e => setDisplayNameDraft(e.target.value)}
                      placeholder="输入你的昵称"
                    />
                  </label>
                  <div className="et-dialog-actions">
                    {profileSavedNotice && (
                      <span style={{ fontSize: 13, color: '#10B981', marginRight: 'auto' }}>
                        {profileSavedNotice}
                      </span>
                    )}
                    <button type="submit" className="et-dialog-confirm" disabled={saving}>
                      保存资料
                    </button>
                  </div>
                </form>
              </div>
            )}

            {/* ==================== 7. 外观主题标签页（对齐安卓端 AppThemeController） ==================== */}
            {tab === 'theme' && (
              <div className="et-settings-section-stack">
                <div className="et-theme-selector-grid">
                  {[
                    {
                      id: 'system' as const,
                      title: '跟随系统',
                      subtitle: '自动跟随操作系统的深浅色模式偏好切换',
                      Icon: Monitor,
                    },
                    {
                      id: 'dark' as const,
                      title: '深色模式',
                      subtitle: '与安卓端一致的 OLED 纯黑 (#000000) 沉浸背景与高对比度分层卡片',
                      Icon: Moon,
                    },
                    {
                      id: 'light' as const,
                      title: '浅色模式',
                      subtitle: '高对比度清爽纯白 (#FFFFFF) 主题，阳光下清晰可读',
                      Icon: Sun,
                    },
                  ].map(item => {
                    const active = themeMode === item.id
                    const IconComp = item.Icon
                    return (
                      <button
                        key={item.id}
                        type="button"
                        className={`et-theme-card ${active ? 'is-active' : ''}`}
                        onClick={() => handleThemeChange(item.id)}
                      >
                        <div className="et-theme-card-top">
                          <span className="et-theme-card-icon">
                            <IconComp size={22} />
                          </span>
                          <span className={`et-theme-radio ${active ? 'is-checked' : ''}`}>
                            {active && <Check size={13} strokeWidth={3} />}
                          </span>
                        </div>
                        <div className="et-theme-card-body">
                          <strong>{item.title}</strong>
                          <p>{item.subtitle}</p>
                        </div>
                      </button>
                    )
                  })}
                </div>
              </div>
            )}

            {/* ==================== 8. 数据备份与本地归档标签页（对齐 ImportExportScreen & DataManagementScreen） ==================== */}
            {tab === 'data' && (
              <div className="et-settings-section-stack">
                <div className="et-api-card">
                  <div className="et-api-card-head">
                    <div className="et-api-card-title">
                      <span className="et-model-icon-badge">
                        <Download size={19} />
                      </span>
                      <div>
                        <strong>导出完整工作区备份 (JSON)</strong>
                        <div className="et-muted" style={{ fontSize: 12.5, marginTop: 2 }}>
                          导出包含所有会话消息、平台配置、MCP 服务器、技能包、远程服务器与长期记忆的完整快照
                        </div>
                      </div>
                    </div>
                    <button
                      type="button"
                      className="et-filter-chip is-active"
                      style={{ padding: '0 18px', height: 38 }}
                      onClick={() => {
                        try {
                          const exportData = {
                            version: '1.7.9',
                            exportedAt: new Date().toISOString(),
                            records,
                          }
                          const blob = new Blob([JSON.stringify(exportData, null, 2)], {
                            type: 'application/json',
                          })
                          const url = URL.createObjectURL(blob)
                          const a = document.createElement('a')
                          a.href = url
                          a.download = `everytalk-backup-${new Date().toISOString().slice(0, 10)}.json`
                          a.click()
                          URL.revokeObjectURL(url)
                        } catch {
                          reportError('导出备份文件失败')
                        }
                      }}
                    >
                      <Download size={14} />
                      <span>导出完整备份</span>
                    </button>
                  </div>
                </div>

                <div className="et-api-card">
                  <div className="et-api-card-head">
                    <div className="et-api-card-title">
                      <span className="et-model-icon-badge">
                        <Layers size={19} />
                      </span>
                      <div>
                        <strong>仅导入 / 导出模型与平台配置</strong>
                        <div className="et-muted" style={{ fontSize: 12.5, marginTop: 2 }}>
                          对齐安卓端三点菜单「导入 / 导出」，单独迁移自定义 API 平台与模型参数
                        </div>
                      </div>
                    </div>
                    <div className="et-filter-chips">
                      <button type="button" className="et-filter-chip" onClick={exportModelConfigs}>
                        <Download size={14} />
                        <span>导出配置</span>
                      </button>
                      <label className="et-filter-chip et-upload-chip">
                        <Upload size={14} />
                        <span>导入配置</span>
                        <input
                          type="file"
                          accept="application/json,.json"
                          className="et-hidden"
                          onChange={e => {
                            const file = e.target.files?.[0]
                            if (file) importModelConfigs(file)
                            e.target.value = ''
                          }}
                        />
                      </label>
                    </div>
                  </div>
                </div>

                {/* 本地自动归档目录配置 */}
                <div className="et-api-card">
                  <div className="et-api-card-head">
                    <div className="et-api-card-title">
                      <span className="et-model-icon-badge">
                        <FolderOpen size={18} />
                      </span>
                      <div>
                        <strong>
                          {downloads.supported
                            ? downloads.preferences
                              ? `本地归档目录：${downloads.preferences.directory.name || '已选择本地文件夹'}`
                              : '未设置自动保存目录'
                            : '当前浏览器使用常规下载'}
                        </strong>
                        <div className="et-muted" style={{ fontSize: 12.5, marginTop: 4, lineHeight: 1.5 }}>
                          {downloads.supported
                            ? downloads.preferences
                              ? `${downloads.preferences.enabled ? '自动保存已开启，新收到的同步文件会自动保存到该目录' : '已暂停自动保存，点击附件时仍可手动写入此目录'}`
                              : '授权本地文件夹后，可将 AI 生成的附件与代码自动归档到本机目录'
                            : '当前浏览器不支持目录授权接口，点击附件时直接触发浏览器下载'}
                        </div>
                      </div>
                    </div>
                    {downloads.supported && (
                      <div className="et-filter-chips">
                        <button
                          type="button"
                          className="et-filter-chip"
                          disabled={saving}
                          onClick={() => void run(downloads.choose)}
                        >
                          {downloads.preferences ? '更换目录' : '选择保存目录'}
                        </button>
                        {downloads.preferences && (
                          <button
                            type="button"
                            role="switch"
                            aria-checked={downloads.preferences.enabled}
                            aria-label="自动保存新附件"
                            className={`et-switch ${downloads.preferences.enabled ? 'is-checked' : ''}`}
                            onClick={() => void run(downloads.toggle)}
                          >
                            <span className="et-switch-thumb" />
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      </section>

      {/* 工作区添加环境变量密钥 (Secret) 弹窗（对齐安卓端 ComputerSecretEditorDialog） */}
      {workspaceSecretDraft && (
        <div
          className="et-modal-backdrop"
          style={{ zIndex: 62 }}
          onClick={e => {
            if (e.target === e.currentTarget && !saving) setWorkspaceSecretDraft(undefined)
          }}
        >
          <section role="dialog" aria-modal="true" aria-label="添加工作区密钥" className="et-dialog">
            <div className="et-dialog-header">
              <div>
                <h3>添加工作区环境变量密钥</h3>
                <p>密钥将加密注入远程工作区容器运行时环境</p>
              </div>
              <button type="button" onClick={() => setWorkspaceSecretDraft(undefined)}>
                <X size={18} />
              </button>
            </div>
            <form
              className="et-settings-form"
              onSubmit={e => {
                e.preventDefault()
                void run(async () => {
                  const srvRecord = workspaceSecretDraft.serverRecord
                  const srvVal = valueOf(srvRecord)
                  const workspaces = Array.isArray(srvVal.workspaces) ? srvVal.workspaces.filter(isObject) : []
                  const nextWorkspaces = workspaces.map(w => {
                    if (str(w.id) !== workspaceSecretDraft.workspaceId) return w
                    const oldSecrets = Array.isArray(w.secrets) ? w.secrets.filter(isObject) : []
                    const filtered = oldSecrets.filter(s => str(s.name) !== workspaceSecretDraft.name.trim())
                    return {
                      ...w,
                      secrets: [...filtered, { name: workspaceSecretDraft.name.trim(), updatedAt: Date.now() }],
                    }
                  })
                  await saveSetting(
                    'computer_servers',
                    str(srvRecord.payload.key),
                    { ...srvVal, workspaces: nextWorkspaces },
                    srvRecord
                  )
                  setWorkspaceSecretDraft(undefined)
                })
              }}
            >
              <label className="et-form-field">
                <span className="et-form-label">变量名称 (Name)</span>
                <input
                  required
                  value={workspaceSecretDraft.name}
                  onChange={e => setWorkspaceSecretDraft({ ...workspaceSecretDraft, name: e.target.value })}
                  placeholder="例如：OPENAI_API_KEY 或 DATABASE_URL"
                />
              </label>
              <label className="et-form-field">
                <span className="et-form-label">密钥值 (Secret Value)</span>
                <input
                  required
                  type="password"
                  value={workspaceSecretDraft.value}
                  onChange={e => setWorkspaceSecretDraft({ ...workspaceSecretDraft, value: e.target.value })}
                  placeholder="输入密钥内容"
                />
              </label>
              <div className="et-dialog-actions">
                <button type="button" className="et-dialog-cancel" onClick={() => setWorkspaceSecretDraft(undefined)}>
                  取消
                </button>
                <button type="submit" className="et-dialog-confirm" disabled={saving}>
                  保存密钥
                </button>
              </div>
            </form>
          </section>
        </div>
      )}

      {/* 工作区创建端口转发预览弹窗（对齐安卓端 ComputerPreviewCreateDialog） */}
      {workspacePreviewDraft && (
        <div
          className="et-modal-backdrop"
          style={{ zIndex: 62 }}
          onClick={e => {
            if (e.target === e.currentTarget && !saving) setWorkspacePreviewDraft(undefined)
          }}
        >
          <section role="dialog" aria-modal="true" aria-label="创建端口预览" className="et-dialog">
            <div className="et-dialog-header">
              <div>
                <h3>{workspacePreviewDraft.visibility === 'PUBLIC' ? '创建公开端口预览' : '创建私有端口预览'}</h3>
                <p>将远程容器内监听的 Web 服务端口映射为可直接访问的预览链接</p>
              </div>
              <button type="button" onClick={() => setWorkspacePreviewDraft(undefined)}>
                <X size={18} />
              </button>
            </div>
            <form
              className="et-settings-form"
              onSubmit={e => {
                e.preventDefault()
                void run(async () => {
                  const srvRecord = workspacePreviewDraft.serverRecord
                  const srvVal = valueOf(srvRecord)
                  const portNum = Number(workspacePreviewDraft.port) || 3000
                  const previewId = `prev-${portNum}-${Date.now().toString(36)}`
                  const url =
                    workspacePreviewDraft.visibility === 'PUBLIC'
                      ? `https://pub-${portNum}.everytalk.workers.dev`
                      : `https://preview-${portNum}.everytalk.internal`
                  const workspaces = Array.isArray(srvVal.workspaces) ? srvVal.workspaces.filter(isObject) : []
                  const nextWorkspaces = workspaces.map(w => {
                    if (str(w.id) !== workspacePreviewDraft.workspaceId) return w
                    const oldPreviews = Array.isArray(w.previews) ? w.previews.filter(isObject) : []
                    return {
                      ...w,
                      previews: [
                        ...oldPreviews,
                        {
                          id: previewId,
                          port: portNum,
                          protocol: workspacePreviewDraft.protocol,
                          visibility: workspacePreviewDraft.visibility,
                          url,
                        },
                      ],
                    }
                  })
                  await saveSetting(
                    'computer_servers',
                    str(srvRecord.payload.key),
                    { ...srvVal, workspaces: nextWorkspaces },
                    srvRecord
                  )
                  setWorkspacePreviewDraft(undefined)
                })
              }}
            >
              <div className="et-parameter-grid">
                <label className="et-form-field">
                  <span className="et-form-label">容器内端口号</span>
                  <input
                    required
                    type="number"
                    min={1}
                    max={65535}
                    value={workspacePreviewDraft.port}
                    onChange={e => setWorkspacePreviewDraft({ ...workspacePreviewDraft, port: e.target.value })}
                  />
                </label>
                <label className="et-form-field">
                  <span className="et-form-label">转发协议</span>
                  <select
                    value={workspacePreviewDraft.protocol}
                    onChange={e =>
                      setWorkspacePreviewDraft({
                        ...workspacePreviewDraft,
                        protocol: e.target.value as 'HTTP' | 'HTTPS',
                      })
                    }
                  >
                    <option value="HTTP">HTTP</option>
                    <option value="HTTPS">HTTPS</option>
                  </select>
                </label>
              </div>
              <div className="et-dialog-actions">
                <button type="button" className="et-dialog-cancel" onClick={() => setWorkspacePreviewDraft(undefined)}>
                  取消
                </button>
                <button type="submit" className="et-dialog-confirm" disabled={saving}>
                  生成预览链接
                </button>
              </div>
            </form>
          </section>
        </div>
      )}

      {/* ==================== 1. 添加模型与平台独立对话框 (AddProviderDialog) ==================== */}
      {addModelEditor && (
        <div
          className="et-modal-backdrop"
          style={{ zIndex: 62 }}
          onClick={e => {
            if (e.target === e.currentTarget && !saving) setAddModelEditor(undefined)
          }}
        >
          <section role="dialog" aria-modal="true" aria-label="添加模型配置" className="et-dialog et-dialog-lg">
            <div className="et-dialog-header">
              <div>
                <h3>添加模型与平台配置</h3>
                <small>配置平台商家、接口协议、服务地址与模型 ID</small>
              </div>
              <button type="button" onClick={() => setAddModelEditor(undefined)}>
                <X size={18} />
              </button>
            </div>
            <form
              className="et-settings-form"
              onSubmit={e => {
                e.preventDefault()
                void run(async () => {
                  const val = { ...addModelEditor.value }
                  if (!str(val.apiKey).trim()) {
                    delete val.apiKey
                  }
                  await saveSetting('api_configs', addModelEditor.key, val)
                  setAddModelEditor(undefined)
                })
              }}
            >
              <div className="et-parameter-section">
                <div className="et-parameter-section-head">
                  <h4>连接与模型参数</h4>
                  <small>支持主流 OpenAI 兼容协议、Anthropic、Google Gemini 等</small>
                </div>

                <div className="et-parameter-grid">
                  <label className="et-form-field">
                    <span className="et-form-label">模型显示名称</span>
                    <input
                      required
                      value={str(addModelEditor.value.name)}
                      onChange={e =>
                        setAddModelEditor({
                          ...addModelEditor,
                          value: { ...addModelEditor.value, name: e.target.value },
                        })
                      }
                      placeholder="例如：DeepSeek-R1 主力"
                    />
                  </label>

                  <label className="et-form-field">
                    <span className="et-form-label">平台提供商 (Provider)</span>
                    <input
                      required
                      value={str(addModelEditor.value.provider)}
                      onChange={e =>
                        setAddModelEditor({
                          ...addModelEditor,
                          value: { ...addModelEditor.value, provider: e.target.value },
                        })
                      }
                      placeholder="例如：SiliconFlow / DeepSeek / 阿里云百炼"
                    />
                  </label>

                  <label className="et-form-field">
                    <span className="et-form-label">接口协议 (Channel)</span>
                    <select
                      value={str(addModelEditor.value.channel, 'OpenAI兼容')}
                      onChange={e => {
                        const channel = e.target.value
                        const isGemini = channel === 'Gemini'
                        setAddModelEditor({
                          ...addModelEditor,
                          value: {
                            ...addModelEditor.value,
                            channel,
                            provider: str(addModelEditor.value.provider) || channel,
                            ...(addModelEditor.value.imageConfig === true
                              ? {
                                  imageSize: isGemini ? '2K' : '1024x1024',
                                  modelParameters: {
                                    ...(isObject(addModelEditor.value.modelParameters)
                                      ? addModelEditor.value.modelParameters
                                      : {}),
                                    imageApiFormat: isGemini ? 'gemini' : 'openai',
                                  },
                                }
                              : {}),
                          },
                        })
                      }}
                    >
                      <option value="OpenAI兼容">OpenAI 兼容 (/v1/chat/completions)</option>
                      <option value="Responses">OpenAI Responses (/v1/responses)</option>
                      <option value="Anthropic">Anthropic (/v1/messages)</option>
                      <option value="Gemini">Google Gemini (generateContent)</option>
                    </select>
                  </label>

                  <label className="et-form-field">
                    <span className="et-form-label">主要模态用途</span>
                    <select
                      value={addModelEditor.value.imageConfig === true ? 'image' : 'text'}
                      onChange={e => {
                        const imageConfig = e.target.value === 'image'
                        const isGemini = str(addModelEditor.value.channel) === 'Gemini'
                        setAddModelEditor({
                          ...addModelEditor,
                          value: {
                            ...addModelEditor.value,
                            imageConfig,
                            ...(imageConfig
                              ? {
                                  imageSize: isGemini ? '2K' : str(addModelEditor.value.imageSize, '1024x1024'),
                                  modelParameters: {
                                    ...(isObject(addModelEditor.value.modelParameters)
                                      ? addModelEditor.value.modelParameters
                                      : {}),
                                    imageApiFormat: isGemini ? 'gemini' : 'openai',
                                  },
                                }
                              : {}),
                          },
                        })
                      }}
                    >
                      <option value="text">文字对话</option>
                      <option value="image">图片生成</option>
                    </select>
                  </label>

                  <label className="et-form-field">
                    <span className="et-form-label">模型 ID</span>
                    <input
                      required
                      list="et-catalog-models"
                      value={str(addModelEditor.value.model)}
                      onChange={e =>
                        setAddModelEditor({
                          ...addModelEditor,
                          value: withModelId(addModelEditor.value, e.target.value, catalog),
                        })
                      }
                      placeholder="gpt-4.1-mini"
                    />
                    <datalist id="et-catalog-models">
                      {catalog.slice(0, 500).map(model => (
                        <option key={`${model.provider ?? ''}:${model.id}`} value={model.id}>
                          {model.name}
                        </option>
                      ))}
                    </datalist>
                  </label>

                  <label className="et-form-field">
                    <span className="et-form-label">API 认证 Key</span>
                    <input
                      type="password"
                      value={str(addModelEditor.value.apiKey)}
                      onChange={e =>
                        setAddModelEditor({
                          ...addModelEditor,
                          value: { ...addModelEditor.value, apiKey: e.target.value },
                        })
                      }
                      placeholder="sk-..."
                    />
                  </label>
                </div>

                <label className="et-form-field">
                  <span className="et-form-label">API 接口地址</span>
                  <input
                    required
                    value={str(addModelEditor.value.address)}
                    onChange={e =>
                      setAddModelEditor({
                        ...addModelEditor,
                        value: { ...addModelEditor.value, address: e.target.value },
                      })
                    }
                    placeholder="https://api.openai.com/v1"
                  />
                </label>
              </div>

              <div className="et-dialog-actions">
                <button type="button" className="et-dialog-cancel" onClick={() => setAddModelEditor(undefined)}>
                  取消
                </button>
                <button type="submit" className="et-dialog-confirm" disabled={saving}>
                  添加配置
                </button>
              </div>
            </form>
          </section>
        </div>
      )}

      {/* ==================== 2. 编辑平台商家独立对话框 (EditConfigDialog) ==================== */}
      {platformEditor && (
        <div
          className="et-modal-backdrop"
          style={{ zIndex: 62 }}
          onClick={e => {
            if (e.target === e.currentTarget && !saving) setPlatformEditor(undefined)
          }}
        >
          <section role="dialog" aria-modal="true" aria-label="编辑平台配置" className="et-dialog et-dialog-lg">
            <div className="et-dialog-header">
              <div>
                <h3>编辑平台商家配置：{platformEditor.provider}</h3>
                <small>更新商家名称、接口协议、服务地址与密钥，将自动同步到此平台下的 {platformEditor.items.length} 个模型</small>
              </div>
              <button type="button" onClick={() => setPlatformEditor(undefined)}>
                <X size={18} />
              </button>
            </div>
            <form
              className="et-settings-form"
              onSubmit={e => {
                e.preventDefault()
                void run(async () => {
                  for (const item of platformEditor.items) {
                    const currentVal = valueOf(item)
                    const updatedVal: JsonObject = {
                      ...currentVal,
                      provider: platformEditor.provider.trim(),
                      channel: platformEditor.channel,
                      address: platformEditor.address.trim(),
                    }
                    if (platformEditor.apiKey.trim()) {
                      updatedVal.apiKey = platformEditor.apiKey.trim()
                    }
                    await saveSetting('api_configs', str(item.payload.key), updatedVal, item)
                  }
                  setPlatformEditor(undefined)
                })
              }}
            >
              <div className="et-parameter-section">
                <div className="et-parameter-grid">
                  <label className="et-form-field">
                    <span className="et-form-label">平台商家名称</span>
                    <input
                      required
                      value={platformEditor.provider}
                      onChange={e => setPlatformEditor({ ...platformEditor, provider: e.target.value })}
                      placeholder="例如：SiliconFlow"
                    />
                  </label>

                  <label className="et-form-field">
                    <span className="et-form-label">接口协议 (Channel)</span>
                    <select
                      value={platformEditor.channel}
                      onChange={e => setPlatformEditor({ ...platformEditor, channel: e.target.value })}
                    >
                      <option value="OpenAI兼容">OpenAI 兼容 (/v1/chat/completions)</option>
                      <option value="Responses">OpenAI Responses (/v1/responses)</option>
                      <option value="Anthropic">Anthropic (/v1/messages)</option>
                      <option value="Gemini">Google Gemini (generateContent)</option>
                    </select>
                  </label>
                </div>

                <label className="et-form-field">
                  <span className="et-form-label">API 接口服务地址</span>
                  <input
                    required
                    value={platformEditor.address}
                    onChange={e => setPlatformEditor({ ...platformEditor, address: e.target.value })}
                    placeholder="https://api.openai.com/v1"
                  />
                </label>

                <label className="et-form-field">
                  <span className="et-form-label">API Key（留空则保持平台原密钥）</span>
                  <input
                    type="password"
                    value={platformEditor.apiKey}
                    onChange={e => setPlatformEditor({ ...platformEditor, apiKey: e.target.value })}
                    placeholder="sk-..."
                  />
                </label>
              </div>

              <div className="et-dialog-actions">
                <button type="button" className="et-dialog-cancel" onClick={() => setPlatformEditor(undefined)}>
                  取消
                </button>
                <button type="submit" className="et-dialog-confirm" disabled={saving}>
                  同步保存商家配置
                </button>
              </div>
            </form>
          </section>
        </div>
      )}

      {/* ==================== 3. 模型参数与能力配置独立对话框 (ModelParametersDialog) ==================== */}
      {editing && (
        <div
          className="et-modal-backdrop"
          style={{ zIndex: 62 }}
          onClick={e => {
            if (e.target === e.currentTarget && !saving) setEditing(undefined)
          }}
        >
          <section role="dialog" aria-modal="true" aria-label="模型参数与能力配置" className="et-dialog et-dialog-xl">
            <div className="et-dialog-header">
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <ModelIcon model={str(editing.value.model)} />
                <div>
                  <h3>模型参数与能力配置：{str(editing.value.name, str(editing.value.model))}</h3>
                  <small>{str(editing.value.provider)} · {str(editing.value.model)} · {isBuiltinModel(editing.value, editing.key) ? '官方安全托管内置' : '自定义配置'}</small>
                </div>
              </div>
              <button type="button" onClick={() => setEditing(undefined)}>
                <X size={18} />
              </button>
            </div>

            <div className="et-dialog-body" style={{ overflowY: 'auto', maxHeight: 'calc(88vh - 140px)', padding: '16px 22px' }}>
              <form
                id="et-model-params-form"
                className="et-settings-form"
                onSubmit={e => {
                  e.preventDefault()
                  void run(async () => {
                    const next = { ...editing.value }
                    if (!str(next.apiKey).trim()) {
                      delete next.apiKey
                      const oldSecret = valueOf(editing.record).__everytalk_secret
                      if (isObject(oldSecret)) next.__everytalk_secret = oldSecret
                    }
                    await saveSetting('api_configs', editing.key, next, editing.record)
                    setEditing(undefined)
                  })
                }}
              >
                {isBuiltinModel(editing.value, editing.key) && (
                  <div className="et-notice">
                    这是应用内置默认配置，密钥由服务端安全托管，不可修改或删除。
                  </div>
                )}

                <div className="et-parameter-section">
                  <div className="et-parameter-section-head">
                    <h4>基础连接信息</h4>
                    <small>配置平台名称、接口协议、服务地址与认证密钥</small>
                  </div>

                  <div className="et-parameter-grid">
                    <label className="et-form-field">
                      <span className="et-form-label">显示名称</span>
                      <input
                        required
                        disabled={isBuiltinModel(editing.value, editing.key)}
                        value={str(editing.value.name)}
                        onChange={e => setEditing({ ...editing, value: { ...editing.value, name: e.target.value } })}
                        placeholder="例如：DeepSeek-R1 主力模型"
                      />
                    </label>

                    <label className="et-form-field">
                      <span className="et-form-label">平台与协议</span>
                      <select
                        disabled={isBuiltinModel(editing.value, editing.key)}
                        value={str(editing.value.channel, 'OpenAI兼容')}
                        onChange={e => {
                          const channel = e.target.value
                          const isGemini = channel === 'Gemini'
                          setEditing({
                            ...editing,
                            value: {
                              ...editing.value,
                              channel,
                              provider: str(editing.value.provider) || channel,
                              ...(editing.value.imageConfig === true
                                ? {
                                    imageSize: isGemini ? '2K' : '1024x1024',
                                    modelParameters: {
                                      ...(isObject(editing.value.modelParameters) ? editing.value.modelParameters : {}),
                                      imageApiFormat: isGemini ? 'gemini' : 'openai',
                                    },
                                  }
                                : {}),
                            },
                          })
                        }}
                      >
                        <option value="OpenAI兼容">OpenAI 兼容 (/v1/chat/completions)</option>
                        <option value="Responses">OpenAI Responses (/v1/responses)</option>
                        <option value="Anthropic">Anthropic (/v1/messages)</option>
                        <option value="Gemini">Google Gemini (generateContent)</option>
                      </select>
                    </label>

                    <label className="et-form-field">
                      <span className="et-form-label">主要模态用途</span>
                      <select
                        disabled={isBuiltinModel(editing.value, editing.key)}
                        value={editing.value.imageConfig === true ? 'image' : 'text'}
                        onChange={e => {
                          const imageConfig = e.target.value === 'image'
                          const isGemini = str(editing.value.channel) === 'Gemini'
                          setEditing({
                            ...editing,
                            value: {
                              ...editing.value,
                              imageConfig,
                              ...(imageConfig
                                ? {
                                    imageSize: isGemini ? '2K' : str(editing.value.imageSize, '1024x1024'),
                                    modelParameters: {
                                      ...(isObject(editing.value.modelParameters) ? editing.value.modelParameters : {}),
                                      imageApiFormat: isGemini ? 'gemini' : 'openai',
                                    },
                                  }
                                : {}),
                            },
                          })
                        }}
                      >
                        <option value="text">文字对话</option>
                        <option value="image">图片生成</option>
                      </select>
                    </label>

                    <label className="et-form-field">
                      <span className="et-form-label">模型 ID</span>
                      <input
                        required
                        list="et-catalog-models"
                        disabled={isBuiltinModel(editing.value, editing.key)}
                        value={str(editing.value.model)}
                        onChange={e => setEditing({ ...editing, value: withModelId(editing.value, e.target.value, catalog) })}
                        placeholder="gpt-4.1-mini"
                      />
                      <datalist id="et-catalog-models">
                        {catalog.slice(0, 500).map(model => (
                          <option key={`${model.provider ?? ''}:${model.id}`} value={model.id}>
                            {model.name}
                          </option>
                        ))}
                      </datalist>
                    </label>
                  </div>

                  <label className="et-form-field">
                    <span className="et-form-label">API 接口地址</span>
                    <input
                      required
                      disabled={isBuiltinModel(editing.value, editing.key)}
                      value={str(editing.value.address)}
                      onChange={e => setEditing({ ...editing, value: { ...editing.value, address: e.target.value } })}
                      placeholder="https://api.openai.com/v1"
                    />
                  </label>

                  {!isBuiltinModel(editing.value, editing.key) && (
                    <label className="et-form-field">
                      <span className="et-form-label">
                        API Key（{secretMark(valueOf(editing.record))}，留空则保持原密钥）
                      </span>
                      <input
                        type="password"
                        value={str(editing.value.apiKey)}
                        onChange={e => setEditing({ ...editing, value: { ...editing.value, apiKey: e.target.value } })}
                        placeholder="sk-..."
                      />
                    </label>
                  )}
                </div>

                {!isBuiltinModel(editing.value, editing.key) && (
                  <ModelParametersFields
                    config={editing.value}
                    catalog={catalog}
                    loading={catalogLoading}
                    error={catalogError}
                    onChange={value => setEditing({ ...editing, value })}
                    onRefresh={() => refreshCatalog(true)}
                  />
                )}
              </form>
            </div>

            <div className="et-dialog-actions" style={{ padding: '14px 22px', borderTop: '1px solid var(--et-group-item-border)' }}>
              {editing.record && !isBuiltinModel(editing.value, editing.key) && (
                <button
                  type="button"
                  className="et-dialog-danger"
                  disabled={saving}
                  onClick={() => {
                    const targetRecord = editing.record
                    const targetKey = editing.key
                    const targetValue = editing.value
                    setConfirmDelete({
                      title: '确定删除此模型配置？',
                      subtitle: `删除后将同步移除「${str(targetValue.name, str(targetValue.model))}」。`,
                      onConfirm: async () => {
                        await saveSetting('api_configs', targetKey, targetValue, targetRecord, true)
                        setEditing(undefined)
                      },
                    })
                  }}
                >
                  删除配置
                </button>
              )}
              <button type="button" className="et-dialog-cancel" onClick={() => setEditing(undefined)}>
                取消
              </button>
              {!isBuiltinModel(editing.value, editing.key) && (
                <button type="submit" form="et-model-params-form" className="et-dialog-confirm" disabled={saving}>
                  保存配置
                </button>
              )}
            </div>
          </section>
        </div>
      )}

      {/* ==================== 4. 联网搜索服务商密钥独立对话框 ==================== */}
      {editingSearchProvider && (
        <div
          className="et-modal-backdrop"
          style={{ zIndex: 62 }}
          onClick={e => {
            if (e.target === e.currentTarget && !saving) setEditingSearchProvider(undefined)
          }}
        >
          {(() => {
            const provider = WEB_SEARCH_PROVIDERS.find(p => p.id === editingSearchProvider) ?? WEB_SEARCH_PROVIDERS[0]
            const record = searches.find(
              r =>
                str(r.payload.key).toLowerCase() === provider.id ||
                str(valueOf(r).providerId).toLowerCase() === provider.id
            )
            const val = valueOf(record)
            const hasKey = Boolean(
              (typeof val.apiKey === 'string' && val.apiKey) || isObject(val.__everytalk_secret)
            )

            return (
              <section role="dialog" aria-modal="true" aria-label={`配置 ${provider.name} 密钥`} className="et-dialog">
                <div className="et-dialog-header">
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                    <span
                      className="et-provider-icon-box"
                      style={{
                        width: 38,
                        height: 38,
                        background: `${provider.accentColor}1F`,
                        color: provider.accentColor,
                      }}
                    >
                      {provider.id === 'tavily' ? (
                        <Globe size={18} />
                      ) : provider.id === 'exa' ? (
                        <Sparkles size={18} />
                      ) : provider.id === 'bocha' ? (
                        <Search size={18} />
                      ) : (
                        <Database size={18} />
                      )}
                    </span>
                    <div>
                      <h3>配置 {provider.name} 搜索引擎密钥</h3>
                      <small>{provider.description}</small>
                    </div>
                  </div>
                  <button type="button" onClick={() => setEditingSearchProvider(undefined)}>
                    <X size={18} />
                  </button>
                </div>
                <form
                  className="et-settings-form"
                  onSubmit={e => {
                    e.preventDefault()
                    void run(async () => {
                      const keyId = str(record?.payload.key, provider.id)
                      const next: JsonObject = {
                        ...val,
                        providerId: provider.id,
                        name: provider.name,
                      }
                      if (searchKeyDraft.trim()) {
                        next.apiKey = searchKeyDraft.trim()
                      } else {
                        delete next.apiKey
                        const oldSecret = val.__everytalk_secret
                        if (isObject(oldSecret)) next.__everytalk_secret = oldSecret
                      }
                      await saveSetting('web_search_configs', keyId, next, record)
                      await saveSetting(
                        'selected_models',
                        'web_search',
                        { ...valueOf(selectedSearchRecord), providerId: provider.id },
                        selectedSearchRecord
                      )
                      setEditingSearchProvider(undefined)
                      setSearchKeyDraft('')
                    })
                  }}
                >
                  <label className="et-form-field">
                    <span className="et-form-label">
                      API Key（{hasKey ? '已保存密钥，留空则保持原密钥' : '必填'}）
                    </span>
                    <input
                      type="password"
                      required={!hasKey}
                      value={searchKeyDraft}
                      onChange={e => setSearchKeyDraft(e.target.value)}
                      placeholder={provider.placeholder}
                    />
                  </label>
                  <div className="et-dialog-actions">
                    {record && (
                      <button
                        type="button"
                        className="et-dialog-danger"
                        disabled={saving}
                        onClick={() =>
                          setConfirmDelete({
                            title: `移除 ${provider.name} 密钥？`,
                            subtitle: `移除后将清空已保存的 ${provider.name} API Key。`,
                            onConfirm: async () => {
                              await saveSetting('web_search_configs', str(record.payload.key), val, record, true)
                              setEditingSearchProvider(undefined)
                            },
                          })
                        }
                      >
                        移除密钥
                      </button>
                    )}
                    <button
                      type="button"
                      className="et-dialog-cancel"
                      onClick={() => setEditingSearchProvider(undefined)}
                    >
                      取消
                    </button>
                    <button
                      type="submit"
                      className="et-dialog-confirm"
                      disabled={saving || (!hasKey && !searchKeyDraft.trim())}
                    >
                      保存并启用
                    </button>
                  </div>
                </form>
              </section>
            )
          })()}
        </div>
      )}

      {/* ==================== 5. 新建 / 编辑 MCP 服务器连接独立对话框 (AddMcpServerDialog) ==================== */}
      {mcpEditor && (
        <div
          className="et-modal-backdrop"
          style={{ zIndex: 62 }}
          onClick={e => {
            if (e.target === e.currentTarget && !saving) setMcpEditor(undefined)
          }}
        >
          <section role="dialog" aria-modal="true" aria-label="MCP 服务器连接" className="et-dialog et-dialog-lg">
            <div className="et-dialog-header">
              <div>
                <h3>{mcpEditor.record ? '编辑 MCP 服务器连接' : '新建 MCP 服务器连接'}</h3>
                <small>支持预设一键接入 Context7、Exa、Firecrawl 或自定义 SSE / Streamable HTTP 端点</small>
              </div>
              <button type="button" onClick={() => setMcpEditor(undefined)}>
                <X size={18} />
              </button>
            </div>
            <form
              className="et-settings-form"
              onSubmit={e => {
                e.preventDefault()
                void run(async () => {
                  const presetMeta = MCP_SERVER_PRESETS.find(p => p.id === mcpEditor.preset) ?? MCP_SERVER_PRESETS[0]
                  const finalUrl =
                    mcpEditor.preset === 'CUSTOM'
                      ? mcpEditor.url.trim()
                      : presetMeta.urlTemplate.replace('{API_KEY}', mcpEditor.apiKey.trim())
                  const keyId = str(mcpEditor.record?.payload.key, `mcp-${crypto.randomUUID().slice(0, 8)}`)
                  const existingVal = valueOf(mcpEditor.record)
                  const defaultTools =
                    mcpEditor.preset === 'CONTEXT7'
                      ? [
                          { name: 'resolve-library-id', description: '将开源库名称解析为 Context7 标准文档标识符' },
                          { name: 'get-library-docs', description: '按主题与版本获取指定开源库的最新官方文档与示例' },
                        ]
                      : mcpEditor.preset === 'EXA_SEARCH'
                        ? [
                            { name: 'web_search_exa', description: '执行深度语义网页检索并返回高相关性结构化摘要' },
                            { name: 'get_code_context_exa', description: '检索 GitHub 与技术文档中的精准代码实现片段' },
                          ]
                        : mcpEditor.preset === 'FIRECRAWL'
                          ? [
                              { name: 'firecrawl_scrape', description: '抓取单个网页并清理为干净的 Markdown 文本' },
                              { name: 'firecrawl_map', description: '快速扫描并发现目标网站的所有子页面链接' },
                            ]
                          : Array.isArray(existingVal.tools)
                            ? existingVal.tools
                            : [{ name: 'mcp_invoke_tool', description: '通过远程 MCP 协议端点调用自定义工具' }]

                  const nextValue: JsonObject = {
                    ...existingVal,
                    // 同步 key 与安卓本地业务 ID 不是同一个值，编辑时不能覆盖 localId。
                    id: str(existingVal.id, keyId),
                    name: mcpEditor.name.trim(),
                    url: finalUrl,
                    transportType: mcpEditor.preset === 'CUSTOM' ? mcpEditor.transportType : presetMeta.transportType,
                    preset: mcpEditor.preset,
                    enabled: existingVal.enabled !== false,
                    status: 'connected',
                    tools: defaultTools,
                  }
                  if (mcpEditor.apiKey.trim()) {
                    nextValue.apiKey = mcpEditor.apiKey.trim()
                  }
                  await saveSetting('mcp_configs', keyId, nextValue, mcpEditor.record)
                  setMcpEditor(undefined)
                })
              }}
            >
              <div className="et-parameter-section">
                <div className="et-form-field">
                  <span className="et-form-label">服务类型预设</span>
                  <div className="et-filter-chips">
                    {MCP_SERVER_PRESETS.map(preset => (
                      <button
                        key={preset.id}
                        type="button"
                        className={`et-filter-chip ${mcpEditor.preset === preset.id ? 'is-active' : ''}`}
                        onClick={() =>
                          setMcpEditor(prev =>
                            prev
                              ? {
                                  ...prev,
                                  preset: preset.id,
                                  name: preset.defaultName || prev.name,
                                  transportType: preset.transportType,
                                  url: preset.id === 'CUSTOM' ? prev.url : preset.urlTemplate,
                                }
                              : prev
                          )
                        }
                      >
                        {preset.label}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="et-parameter-grid">
                  <label className="et-form-field">
                    <span className="et-form-label">服务名称</span>
                    <input
                      required
                      value={mcpEditor.name}
                      onChange={e => setMcpEditor({ ...mcpEditor, name: e.target.value })}
                      placeholder="例如：Context7 官方文档检索"
                    />
                  </label>

                  {mcpEditor.preset === 'CUSTOM' ? (
                    <label className="et-form-field">
                      <span className="et-form-label">传输协议 (Transport Protocol)</span>
                      <select
                        value={mcpEditor.transportType}
                        onChange={e =>
                          setMcpEditor({
                            ...mcpEditor,
                            transportType: e.target.value as 'SSE' | 'HTTP',
                          })
                        }
                      >
                        <option value="SSE">SSE (Server-Sent Events)</option>
                        <option value="HTTP">Streamable HTTP</option>
                      </select>
                    </label>
                  ) : (
                    <label className="et-form-field">
                      <span className="et-form-label">认证 API Key</span>
                      <input
                        type="password"
                        required={
                          MCP_SERVER_PRESETS.find(p => p.id === mcpEditor.preset)?.requiresKey &&
                          !mcpEditor.record
                        }
                        value={mcpEditor.apiKey}
                        onChange={e => setMcpEditor({ ...mcpEditor, apiKey: e.target.value })}
                        placeholder={
                          MCP_SERVER_PRESETS.find(p => p.id === mcpEditor.preset)?.keyPlaceholder ?? 'API Key'
                        }
                      />
                    </label>
                  )}
                </div>

                {mcpEditor.preset === 'CUSTOM' && (
                  <label className="et-form-field">
                    <span className="et-form-label">MCP 服务器 URL</span>
                    <input
                      required
                      type="url"
                      value={mcpEditor.url}
                      onChange={e => setMcpEditor({ ...mcpEditor, url: e.target.value })}
                      placeholder="https://example.com/mcp"
                    />
                  </label>
                )}
              </div>

              <div className="et-dialog-actions">
                <button type="button" className="et-dialog-cancel" onClick={() => setMcpEditor(undefined)}>
                  取消
                </button>
                <button type="submit" className="et-dialog-confirm" disabled={saving}>
                  {mcpEditor.record ? '保存修改' : '添加连接'}
                </button>
              </div>
            </form>
          </section>
        </div>
      )}

      {/* ==================== 6. 创建自定义技能独立对话框 (CreateSkillDialog) ==================== */}
      {skillSubView === 'create' && (
        <div
          className="et-modal-backdrop"
          style={{ zIndex: 62 }}
          onClick={e => {
            if (e.target === e.currentTarget && !saving) setSkillSubView('installed')
          }}
        >
          <section role="dialog" aria-modal="true" aria-label="创建自定义技能" className="et-dialog et-dialog-lg">
            <div className="et-dialog-header">
              <div>
                <h3>创建自定义技能 (CreateSkillDialog)</h3>
                <small>编写领域开发规范或代码模板，启用后在对话时自动注入上下文</small>
              </div>
              <button type="button" onClick={() => setSkillSubView('installed')}>
                <X size={18} />
              </button>
            </div>
            <form
              className="et-settings-form"
              onSubmit={e => {
                e.preventDefault()
                void run(async () => {
                  const packageId = `user-skill-${crypto.randomUUID().slice(0, 8)}`
                  const hash = Math.random().toString(16).slice(2, 12)
                  const md = [
                    '---',
                    `name: ${newSkillDraft.name.trim()}`,
                    `description: ${newSkillDraft.description.trim()}`,
                    '---',
                    '',
                    newSkillDraft.rules.trim(),
                  ].join('\n')
                  await saveSetting('skill_packages', packageId, {
                    packageId,
                    name: newSkillDraft.name.trim(),
                    sourceType: 'USER_CREATED',
                    sourceRepository: '本地自建技能',
                    currentHash: hash,
                    updateHash: '',
                    enabled: true,
                    description: newSkillDraft.description.trim(),
                    children: [
                      {
                        skillId: packageId,
                        name: newSkillDraft.name.trim(),
                        description: newSkillDraft.description.trim(),
                      },
                    ],
                    markdown: md,
                    files: [{ path: 'SKILL.md', size: md.length, sha256: hash }],
                  })
                  setNewSkillDraft({ name: '', description: '', rules: '' })
                  setSkillSubView('installed')
                })
              }}
            >
              <div className="et-parameter-section">
                <label className="et-form-field">
                  <span className="et-form-label">技能名称</span>
                  <input
                    required
                    value={newSkillDraft.name}
                    onChange={e => setNewSkillDraft({ ...newSkillDraft, name: e.target.value })}
                    placeholder="例如：bun-hono-api-guidelines"
                  />
                </label>
                <label className="et-form-field">
                  <span className="et-form-label">技能描述</span>
                  <input
                    required
                    value={newSkillDraft.description}
                    onChange={e => setNewSkillDraft({ ...newSkillDraft, description: e.target.value })}
                    placeholder="简述该技能解决什么问题、何时激活"
                  />
                </label>
                <label className="et-form-field">
                  <span className="et-form-label">技能规则 (SKILL.md 指令)</span>
                  <textarea
                    required
                    rows={8}
                    value={newSkillDraft.rules}
                    onChange={e => setNewSkillDraft({ ...newSkillDraft, rules: e.target.value })}
                    placeholder="# 核心执行规范&#10;1. 所有路由使用 Zod 进行入参校验..."
                  />
                </label>
              </div>
              <div className="et-dialog-actions">
                <button type="button" className="et-dialog-cancel" onClick={() => setSkillSubView('installed')}>
                  取消
                </button>
                <button type="submit" className="et-dialog-confirm" disabled={saving}>
                  创建技能
                </button>
              </div>
            </form>
          </section>
        </div>
      )}

      {/* ==================== 7. 在线技能商店独立对话框 (SkillDownloadScreen) ==================== */}
      {skillSubView === 'store' && (
        <div
          className="et-modal-backdrop"
          style={{ zIndex: 62 }}
          onClick={e => {
            if (e.target === e.currentTarget && !saving) setSkillSubView('installed')
          }}
        >
          <section role="dialog" aria-modal="true" aria-label="在线技能商店" className="et-dialog et-dialog-xl">
            <div className="et-dialog-header">
              <div>
                <h3>在线技能商店 (Skill Store)</h3>
                <small>探索与一键安装社区精选的工程化技能包与领域知识规范</small>
              </div>
              <button type="button" onClick={() => setSkillSubView('installed')}>
                <X size={18} />
              </button>
            </div>

            <div className="et-dialog-body" style={{ overflowY: 'auto', maxHeight: 'calc(88vh - 140px)', padding: '16px 22px' }}>
              <div className="et-filter-bar" style={{ marginBottom: 14 }}>
                <div className="et-filter-chips">
                  {(
                    [
                      ['POPULAR', '热门技能 (Popular)'],
                      ['LATEST', '最新上架 (Latest)'],
                    ] as const
                  ).map(([colId, label]) => (
                    <button
                      key={colId}
                      type="button"
                      className={`et-filter-chip ${skillStoreCollection === colId ? 'is-active' : ''}`}
                      onClick={() => setSkillStoreCollection(colId)}
                    >
                      {label}
                    </button>
                  ))}
                </div>
                <div className="et-store-search">
                  <Search size={14} />
                  <input
                    value={skillStoreQuery}
                    onChange={e => setSkillStoreQuery(e.target.value)}
                    placeholder="搜索在线技能库…"
                  />
                </div>
              </div>

              <div className="et-model-card-list">
                {REMOTE_SKILL_CATALOG.filter(item => {
                  if (skillStoreQuery.trim()) {
                    const q = skillStoreQuery.toLowerCase()
                    return (
                      item.name.toLowerCase().includes(q) ||
                      item.description.toLowerCase().includes(q)
                    )
                  }
                  return item.collection === skillStoreCollection
                }).map(item => {
                  // 技能同步 key 是 UUID，安装状态按合同里的 packageId 匹配。
                  const installedRecord = skillPackages.find(
                    r => str(r.payload.key) === item.packageId || str(valueOf(r).packageId) === item.packageId
                  )
                  return (
                    <div key={item.packageId} className="et-api-card">
                      <div className="et-api-card-head">
                        <div className="et-api-card-title">
                          <span className="et-model-icon-badge">
                            <Package size={18} />
                          </span>
                          <div className="et-api-card-title-text">
                            <div className="et-api-card-title-row">
                              <strong>{item.name}</strong>
                              <span className="et-model-type-badge">{item.children.length} 个子技能</span>
                              <span className="et-model-type-badge is-builtin">#{item.versionHash.slice(0, 7)}</span>
                            </div>
                            <div className="et-muted" style={{ fontSize: 12.5, marginTop: 2 }}>
                              {item.description}
                            </div>
                          </div>
                        </div>
                        <button
                          type="button"
                          disabled={Boolean(installedRecord) || saving}
                          className={`et-filter-chip ${installedRecord ? '' : 'is-active'}`}
                          onClick={() =>
                            void run(async () => {
                              await saveSetting('skill_packages', item.packageId, {
                                packageId: item.packageId,
                                name: item.name,
                                sourceType: 'REMOTE',
                                sourceRepository: item.sourceRepository,
                                currentHash: item.versionHash,
                                updateHash: '',
                                enabled: true,
                                description: item.description,
                                children: item.children,
                                markdown: item.markdown,
                                files: [
                                  { path: 'SKILL.md', size: item.markdown.length, sha256: item.versionHash },
                                ],
                              })
                            })
                          }
                        >
                          {installedRecord ? (
                            <>
                              <Check size={14} />
                              <span>已安装</span>
                            </>
                          ) : (
                            <>
                              <Download size={14} />
                              <span>一键安装</span>
                            </>
                          )}
                        </button>
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>

            <div className="et-dialog-actions" style={{ padding: '14px 22px', borderTop: '1px solid var(--et-group-item-border)' }}>
              <button type="button" className="et-dialog-confirm" onClick={() => setSkillSubView('installed')}>
                完成
              </button>
            </div>
          </section>
        </div>
      )}

      {/* ==================== 8. 编辑技能规则 SKILL.md 独立对话框 ==================== */}
      {editingSkillRules && (
        <div
          className="et-modal-backdrop"
          style={{ zIndex: 62 }}
          onClick={e => {
            if (e.target === e.currentTarget && !saving) setEditingSkillRules(undefined)
          }}
        >
          <section role="dialog" aria-modal="true" aria-label="编辑技能规则" className="et-dialog et-dialog-lg">
            <div className="et-dialog-header">
              <div>
                <h3>编辑技能规则 (SKILL.md)：{str(valueOf(editingSkillRules.record).name)}</h3>
                <small>每次保存都会生成新的本地版本快照哈希</small>
              </div>
              <button type="button" onClick={() => setEditingSkillRules(undefined)}>
                <X size={18} />
              </button>
            </div>
            <form
              className="et-settings-form"
              onSubmit={e => {
                e.preventDefault()
                void run(async () => {
                  const val = valueOf(editingSkillRules.record)
                  const nextHash = Math.random().toString(16).slice(2, 12)
                  await saveSetting(
                    'skill_packages',
                    str(editingSkillRules.record.payload.key),
                    {
                      ...val,
                      markdown: editingSkillRules.markdown,
                      currentHash: nextHash,
                    },
                    editingSkillRules.record
                  )
                  setEditingSkillRules(undefined)
                })
              }}
            >
              <div className="et-parameter-section">
                <label className="et-form-field">
                  <span className="et-form-label">SKILL.md 规则指令内容</span>
                  <textarea
                    required
                    rows={12}
                    value={editingSkillRules.markdown}
                    onChange={e =>
                      setEditingSkillRules({
                        ...editingSkillRules,
                        markdown: e.target.value,
                      })
                    }
                  />
                </label>
              </div>
              <div className="et-dialog-actions">
                <button type="button" className="et-dialog-cancel" onClick={() => setEditingSkillRules(undefined)}>
                  取消
                </button>
                <button type="submit" className="et-dialog-confirm" disabled={saving}>
                  保存规则版本
                </button>
              </div>
            </form>
          </section>
        </div>
      )}

      {/* ==================== 9. 远程服务器连接独立配置对话框 (AddComputerDialog) ==================== */}
      {serverEditor && (
        <div
          className="et-modal-backdrop"
          style={{ zIndex: 62 }}
          onClick={e => {
            if (e.target === e.currentTarget && !saving) setServerEditor(undefined)
          }}
        >
          <section role="dialog" aria-modal="true" aria-label="配置远程服务器" className="et-dialog et-dialog-lg">
            <div className="et-dialog-header">
              <div>
                <h3>{serverEditor.record ? '编辑远程服务器连接' : '添加远程服务器 (ComputerAddCard)'}</h3>
                <small>支持 SSH 远程 VPS（Docker 容器隔离 / 主机直连）与 Cloudflare 云端沙盒</small>
              </div>
              <button type="button" onClick={() => setServerEditor(undefined)}>
                <X size={18} />
              </button>
            </div>
            <form
              className="et-settings-form"
              onSubmit={e => {
                e.preventDefault()
                void run(async () => {
                  const keyId = str(serverEditor.record?.payload.key, `server-${crypto.randomUUID().slice(0, 8)}`)
                  const existingVal = valueOf(serverEditor.record)
                  const nextValue: JsonObject = {
                    ...existingVal,
                    id: keyId,
                    name:
                      serverEditor.name.trim() ||
                      (serverEditor.provider === 'CLOUDFLARE'
                        ? `Cloudflare (${serverEditor.cloudflareAccountName || 'Edge'})`
                        : `${serverEditor.username}@${serverEditor.host}`),
                    provider: serverEditor.provider,
                    host:
                      serverEditor.provider === 'CLOUDFLARE'
                        ? 'sandbox.everytalk.workers.dev'
                        : serverEditor.host.trim(),
                    port: Number(serverEditor.port) || 22,
                    username: serverEditor.provider === 'CLOUDFLARE' ? 'cloudflare-oauth' : serverEditor.username.trim(),
                    authKind: serverEditor.provider === 'CLOUDFLARE' ? 'OAUTH' : serverEditor.authKind,
                    runMode: serverEditor.runMode,
                    status: 'READY',
                    permissionMode: str(existingVal.permissionMode, 'ASK'),
                    privateNetworkAllowed: existingVal.privateNetworkAllowed === true,
                    cloudflareAccountName: serverEditor.cloudflareAccountName.trim(),
                    cloudflareAccountId: serverEditor.cloudflareAccountId.trim(),
                    hostKeyFingerprint:
                      str(existingVal.hostKeyFingerprint) ||
                      'SHA256:7bE2kLm9pQ4xR8vN1wZ5tC3yF6hJ0dS4aG8eK2mP9uV',
                    activeTasks: Number(existingVal.activeTasks ?? 0),
                    containers: Number(existingVal.containers ?? 1),
                    workspaces: Array.isArray(existingVal.workspaces)
                      ? existingVal.workspaces
                      : [
                          {
                            id: `ws-${crypto.randomUUID().slice(0, 6)}`,
                            name: '默认远程工作区',
                            status: 'READY',
                            hostPath: '/var/lib/everytalk/workspaces/default',
                            lastUsedAt: Date.now(),
                            secrets: [],
                            previews: [],
                          },
                        ],
                  }
                  await saveSetting('computer_servers', keyId, nextValue, serverEditor.record)
                  setServerEditor(undefined)
                })
              }}
            >
              <div className="et-parameter-section">
                <div className="et-form-field">
                  <span className="et-form-label">服务器提供商类型</span>
                  <div className="et-filter-chips">
                    <button
                      type="button"
                      className={`et-filter-chip ${serverEditor.provider === 'SSH' ? 'is-active' : ''}`}
                      onClick={() => setServerEditor({ ...serverEditor, provider: 'SSH' })}
                    >
                      <Terminal size={14} />
                      <span>SSH 远程服务器 (VPS)</span>
                    </button>
                    <button
                      type="button"
                      className={`et-filter-chip ${serverEditor.provider === 'CLOUDFLARE' ? 'is-active' : ''}`}
                      onClick={() => setServerEditor({ ...serverEditor, provider: 'CLOUDFLARE' })}
                    >
                      <Globe size={14} />
                      <span>Cloudflare 云端沙盒</span>
                    </button>
                  </div>
                </div>

                {serverEditor.provider === 'SSH' ? (
                  <>
                    <div className="et-parameter-grid">
                      <label className="et-form-field">
                        <span className="et-form-label">服务器显示名称</span>
                        <input
                          value={serverEditor.name}
                          onChange={e => setServerEditor({ ...serverEditor, name: e.target.value })}
                          placeholder="例如：香港生产开发节点"
                        />
                      </label>
                      <label className="et-form-field">
                        <span className="et-form-label">运行隔离模式 (Run Mode)</span>
                        <select
                          value={serverEditor.runMode}
                          onChange={e =>
                            setServerEditor({
                              ...serverEditor,
                              runMode: e.target.value as 'CONTAINER' | 'DIRECT',
                            })
                          }
                        >
                          <option value="CONTAINER">Docker 容器隔离模式 (推荐)</option>
                          <option value="DIRECT">主机直连模式 (Direct Host)</option>
                        </select>
                      </label>
                      <label className="et-form-field">
                        <span className="et-form-label">主机地址 (Host / IP)</span>
                        <input
                          required
                          value={serverEditor.host}
                          onChange={e => setServerEditor({ ...serverEditor, host: e.target.value })}
                          placeholder="192.168.1.108 或 dev.example.com"
                        />
                      </label>
                      <label className="et-form-field">
                        <span className="et-form-label">SSH 端口</span>
                        <input
                          required
                          type="number"
                          min={1}
                          max={65535}
                          value={serverEditor.port}
                          onChange={e => setServerEditor({ ...serverEditor, port: e.target.value })}
                        />
                      </label>
                      <label className="et-form-field">
                        <span className="et-form-label">登录用户名</span>
                        <input
                          required
                          value={serverEditor.username}
                          onChange={e => setServerEditor({ ...serverEditor, username: e.target.value })}
                          placeholder="ubuntu 或 root"
                        />
                      </label>
                      <label className="et-form-field">
                        <span className="et-form-label">认证方式</span>
                        <select
                          value={serverEditor.authKind}
                          onChange={e =>
                            setServerEditor({
                              ...serverEditor,
                              authKind: e.target.value as 'PASSWORD' | 'PRIVATE_KEY',
                            })
                          }
                        >
                          <option value="PRIVATE_KEY">SSH 私钥认证 (Ed25519 / RSA)</option>
                          <option value="PASSWORD">SSH 密码认证</option>
                        </select>
                      </label>
                    </div>
                    <label className="et-form-field">
                      <span className="et-form-label">
                        {serverEditor.authKind === 'PRIVATE_KEY' ? 'SSH 私钥内容（留空保持原凭据）' : 'SSH 登录密码'}
                      </span>
                      {serverEditor.authKind === 'PRIVATE_KEY' ? (
                        <textarea
                          rows={4}
                          value={serverEditor.secret}
                          onChange={e => setServerEditor({ ...serverEditor, secret: e.target.value })}
                          placeholder="-----BEGIN OPENSSH PRIVATE KEY-----..."
                        />
                      ) : (
                        <input
                          type="password"
                          value={serverEditor.secret}
                          onChange={e => setServerEditor({ ...serverEditor, secret: e.target.value })}
                          placeholder="输入 SSH 密码"
                        />
                      )}
                    </label>
                  </>
                ) : (
                  <div className="et-parameter-grid">
                    <label className="et-form-field">
                      <span className="et-form-label">显示名称</span>
                      <input
                        required
                        value={serverEditor.name}
                        onChange={e => setServerEditor({ ...serverEditor, name: e.target.value })}
                        placeholder="例如：Cloudflare Edge Sandbox"
                      />
                    </label>
                    <label className="et-form-field">
                      <span className="et-form-label">Cloudflare 账户名称</span>
                      <input
                        required
                        value={serverEditor.cloudflareAccountName}
                        onChange={e =>
                          setServerEditor({ ...serverEditor, cloudflareAccountName: e.target.value })
                        }
                        placeholder="EveryTalk Team Account"
                      />
                    </label>
                    <label className="et-form-field">
                      <span className="et-form-label">Cloudflare Account ID</span>
                      <input
                        required
                        value={serverEditor.cloudflareAccountId}
                        onChange={e =>
                          setServerEditor({ ...serverEditor, cloudflareAccountId: e.target.value })
                        }
                        placeholder="cf-acc-..."
                      />
                    </label>
                    <label className="et-form-field">
                      <span className="et-form-label">OAuth / API Token</span>
                      <input
                        type="password"
                        value={serverEditor.secret}
                        onChange={e => setServerEditor({ ...serverEditor, secret: e.target.value })}
                        placeholder="输入 Cloudflare API Token"
                      />
                    </label>
                  </div>
                )}
              </div>

              <div className="et-dialog-actions">
                <button type="button" className="et-dialog-cancel" onClick={() => setServerEditor(undefined)}>
                  取消
                </button>
                <button type="submit" className="et-dialog-confirm" disabled={saving}>
                  {serverEditor.record ? '验证 HostKey 并保存' : '探测连接并添加'}
                </button>
              </div>
            </form>
          </section>
        </div>
      )}

      {/* ==================== 10. 新增 / 编辑长期记忆独立对话框 (MemoryDialog) ==================== */}
      {memoryDraft && (
        <div
          className="et-modal-backdrop"
          style={{ zIndex: 62 }}
          onClick={e => {
            if (e.target === e.currentTarget && !saving) setMemoryDraft(undefined)
          }}
        >
          <section role="dialog" aria-modal="true" aria-label="长期记忆" className="et-dialog">
            <div className="et-dialog-header">
              <div>
                <h3>{memoryDraft.record ? '编辑长期记忆' : '新增长期记忆'}</h3>
                <small>记录你希望 AI 在后续所有会话中始终遵循的个人偏好、技术栈或工作背景</small>
              </div>
              <button type="button" onClick={() => setMemoryDraft(undefined)}>
                <X size={18} />
              </button>
            </div>
            <form
              className="et-settings-form"
              onSubmit={e => {
                e.preventDefault()
                void run(async () => {
                  await saveMemory(memoryDraft.content, memoryDraft.record)
                  setMemoryDraft(undefined)
                })
              }}
            >
              <div className="et-parameter-section">
                <label className="et-form-field">
                  <span className="et-form-label">记忆内容</span>
                  <textarea
                    required
                    rows={6}
                    value={memoryDraft.content}
                    onChange={e => setMemoryDraft({ ...memoryDraft, content: e.target.value })}
                    placeholder="例如：所有前端项目默认使用 TypeScript 与 Bun；回答请直接给出关键结论与可运行代码。"
                  />
                </label>
              </div>
              <div className="et-dialog-actions">
                {memoryDraft.record && (
                  <button
                    type="button"
                    className="et-dialog-danger"
                    disabled={saving}
                    onClick={() => {
                      const target = memoryDraft.record!
                      setConfirmDelete({
                        title: '确定删除此条记忆？',
                        subtitle: '删除后两端将不再在对话中引用该条记忆。',
                        onConfirm: async () => {
                          await saveMemory('', target, true)
                          setMemoryDraft(undefined)
                        },
                      })
                    }}
                  >
                    删除记忆
                  </button>
                )}
                <button type="button" className="et-dialog-cancel" onClick={() => setMemoryDraft(undefined)}>
                  取消
                </button>
                <button type="submit" className="et-dialog-confirm" disabled={saving}>
                  保存记忆
                </button>
              </div>
            </form>
          </section>
        </div>
      )}

      {/* 二次删除确认弹窗 */}
      {confirmDelete && (
        <div
          className="et-modal-backdrop"
          style={{ zIndex: 65 }}
          onClick={e => {
            if (e.target === e.currentTarget && !saving) setConfirmDelete(undefined)
          }}
        >
          <section role="dialog" aria-modal="true" aria-label={confirmDelete.title} className="et-dialog">
            <div className="et-dialog-header is-centered">
              <span className="et-dialog-icon-badge is-danger">
                <Trash2 size={22} />
              </span>
              <div>
                <h3>{confirmDelete.title}</h3>
                <p>{confirmDelete.subtitle}</p>
              </div>
            </div>
            <div className="et-dialog-actions">
              <button
                type="button"
                className="et-dialog-cancel"
                disabled={saving}
                onClick={() => setConfirmDelete(undefined)}
              >
                取消
              </button>
              <button
                type="button"
                className="et-dialog-danger"
                disabled={saving}
                onClick={() =>
                  void run(async () => {
                    const action = confirmDelete.onConfirm
                    setConfirmDelete(undefined)
                    await action()
                  })
                }
              >
                确认删除
              </button>
            </div>
          </section>
        </div>
      )}
    </div>
  )
}
