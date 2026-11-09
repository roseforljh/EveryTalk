import { isObject } from './sync-core'
import type { SyncClient } from './sync'
import type { JsonObject, StoredRecord } from './types'

/**
 * 网页早期把 MCP / 技能 / 电脑配置写成了 mcp_configs / skill_packages / computer_servers。
 * 服务端只注册 mcp_servers / skills / computers，且 mcp_servers、computers 的 key 必须是 UUID。
 * 这里统一做“网页视图形状 ↔ 安卓同步合同形状”的转换，界面仍按旧字段名渲染。
 */

export type WebSettingKind = 'mcp_configs' | 'skill_packages' | 'computer_servers'
type ContractSettingKind = 'mcp_servers' | 'skills' | 'computers'

export const WEB_SETTING_KIND: Record<ContractSettingKind, WebSettingKind> = {
  mcp_servers: 'mcp_configs',
  skills: 'skill_packages',
  computers: 'computer_servers',
}

const CONTRACT_SETTING_KIND: Record<WebSettingKind, ContractSettingKind> = {
  mcp_configs: 'mcp_servers',
  skill_packages: 'skills',
  computer_servers: 'computers',
}

export const isLegacySettingKind = (kind: string): kind is WebSettingKind => kind in CONTRACT_SETTING_KIND
export const contractKindOf = (kind: WebSettingKind): ContractSettingKind => CONTRACT_SETTING_KIND[kind]

const text = (value: unknown, fallback = ''): string => (typeof value === 'string' ? value : fallback)
const count = (value: unknown, fallback = 0): number => (typeof value === 'number' && Number.isFinite(value) ? value : fallback)
const object = (value: unknown): JsonObject | undefined => (isObject(value) ? value : undefined)
const isEnvelope = (value: unknown): value is JsonObject => isObject(value) && '$enc' in value

function pick(value: JsonObject, names: string[]): JsonObject {
  return Object.fromEntries(names.filter(name => value[name] !== undefined).map(name => [name, value[name]]))
}

// java.util.UUID.nameUUIDFromBytes 没有 WebCrypto 对应实现，这里用最小 MD5（UUID v3）对齐安卓。
const MD5_S = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
]
const MD5_K = [
  0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
  0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
  0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
  0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
  0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
  0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
  0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
  0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
]

function md5(input: Uint8Array): Uint8Array {
  const padded = new Uint8Array(((((input.length + 8) >> 6) + 1) << 6))
  padded.set(input)
  padded[input.length] = 0x80
  const view = new DataView(padded.buffer)
  view.setUint32(padded.length - 8, (input.length * 8) >>> 0, true)
  view.setUint32(padded.length - 4, Math.floor((input.length * 8) / 0x100000000), true)
  let a0 = 0x67452301
  let b0 = 0xefcdab89 | 0
  let c0 = 0x98badcfe | 0
  let d0 = 0x10325476 | 0
  const block = new Int32Array(16)
  for (let offset = 0; offset < padded.length; offset += 64) {
    for (let index = 0; index < 16; index++) block[index] = view.getInt32(offset + index * 4, true)
    let a = a0
    let b = b0
    let c = c0
    let d = d0
    for (let index = 0; index < 64; index++) {
      let f: number
      let g: number
      if (index < 16) { f = (b & c) | (~b & d); g = index }
      else if (index < 32) { f = (d & b) | (~d & c); g = (5 * index + 1) & 15 }
      else if (index < 48) { f = b ^ c ^ d; g = (3 * index + 5) & 15 }
      else { f = c ^ (b | ~d); g = (7 * index) & 15 }
      const previousD = d
      d = c
      c = b
      const sum = (a + f + MD5_K[index] + block[g]) | 0
      b = (b + ((sum << MD5_S[index]) | (sum >>> (32 - MD5_S[index])))) | 0
      a = previousD
    }
    a0 = (a0 + a) | 0
    b0 = (b0 + b) | 0
    c0 = (c0 + c) | 0
    d0 = (d0 + d) | 0
  }
  const out = new Uint8Array(16)
  const outView = new DataView(out.buffer)
  outView.setInt32(0, a0, true)
  outView.setInt32(4, b0, true)
  outView.setInt32(8, c0, true)
  outView.setInt32(12, d0, true)
  return out
}

/** 与安卓 localSyncUuid 一致：UUID.nameUUIDFromBytes("EveryTalk:<kind>:<localId>") 即 UUID v3。 */
export async function canonicalSettingKey(kind: string, localId: string): Promise<string> {
  if (isCanonicalUuid(localId)) return localId.toLowerCase()
  const bytes = md5(new TextEncoder().encode(`EveryTalk:${kind}:${localId}`))
  bytes[6] = (bytes[6] & 0x0f) | 0x30
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`
}

export const isCanonicalUuid = (value: string): boolean =>
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)

/** 网页独有字段（工具列表、技能包文件、工作区演示数据）不进入合同，按账号存本机，不伪造同步。 */
const extrasStoreKey = (userId: string): string => `everytalk-web-setting-extras:${userId}`

export function readSettingExtras(userId: string, kind: string, key: string): JsonObject {
  if (typeof localStorage === 'undefined') return {}
  try {
    const all: unknown = JSON.parse(localStorage.getItem(extrasStoreKey(userId)) ?? '{}')
    const entry = isObject(all) ? all[`${kind}:${key}`] : undefined
    return object(entry) ?? {}
  } catch {
    return {}
  }
}

export function writeSettingExtras(userId: string, kind: string, key: string, extras?: JsonObject): void {
  if (typeof localStorage === 'undefined') return
  try {
    const all: unknown = JSON.parse(localStorage.getItem(extrasStoreKey(userId)) ?? '{}')
    const map = isObject(all) ? { ...all } : {}
    if (extras && Object.keys(extras).length) map[`${kind}:${key}`] = extras
    else delete map[`${kind}:${key}`]
    localStorage.setItem(extrasStoreKey(userId), JSON.stringify(map))
  } catch {
    /* 本机存储受限时只丢网页独有字段，合同字段仍已保存。 */
  }
}

/** 合同值 → 设置面板使用的网页形状。只读不改写，敏感信封原样带回以便再次保存时不丢凭据。 */
function mcpToView(value: JsonObject, key: string, extras: JsonObject): JsonObject {
  const endpoint = value.endpoint
  const url = typeof endpoint === 'string' ? endpoint : ''
  const enabled = value.enabled !== false
  return {
    ...extras,
    id: text(value.localId, key),
    name: text(value.name),
    url,
    transportType: value.transport === 'SSE' ? 'SSE' : 'HTTP',
    preset: text(extras.preset, 'CUSTOM'),
    enabled,
    status: enabled ? 'connected' : 'idle',
    description: text(extras.description, url),
    tools: Array.isArray(extras.tools) ? extras.tools : [],
    ...(isEnvelope(endpoint) ? { endpoint } : {}),
    ...(value.headers !== undefined ? { headers: value.headers } : {}),
  }
}

/** 网页形状 → 合同值；返回的 extras 是本机保留的网页独有字段。 */
function mcpFromView(view: JsonObject, key: string): { value: JsonObject; extras: JsonObject } {
  const url = text(view.url).trim()
  const preservedEndpoint = isEnvelope(view.endpoint) ? view.endpoint : undefined
  const apiKey = text(view.apiKey).trim()
  // 网页只提供单个密钥输入框；安卓 headers 是 [name, value] 数组。按 MCP HTTP 通行做法写入 Bearer。
  const headers = view.headers !== undefined
    ? view.headers
    : apiKey ? [['Authorization', `Bearer ${apiKey}`]] : undefined
  const value: JsonObject = {
    localId: text(view.id, text(view.localId, key)),
    name: text(view.name),
    endpoint: url || preservedEndpoint || text(view.endpoint),
    transport: view.transportType === 'SSE' ? 'SSE' : 'HTTP',
    enabled: view.enabled !== false,
  }
  if (headers !== undefined) value.headers = headers
  return { value, extras: pick(view, ['preset', 'description', 'tools']) }
}

function skillToView(value: JsonObject, key: string, extras: JsonObject): JsonObject {
  const contentHash = text(value.contentHash)
  return {
    ...extras,
    packageId: text(value.packageId, key),
    packageName: text(value.packageName),
    name: text(value.name, text(value.packageName, key)),
    sourceType: text(value.sourceType, 'LOCAL_IMPORT'),
    sourceRepository: text(value.sourceRepository, text(extras.sourceRepository)),
    currentHash: contentHash,
    contentHash,
    enabled: value.enabled !== false,
    description: text(value.description),
    invocationMode: text(value.invocationMode),
    installedAt: count(value.installedAt, count(value.createdAt)),
    ...(value.fileRefs !== undefined ? { fileRefs: value.fileRefs } : {}),
    ...(value.fileManifest !== undefined ? { fileManifest: value.fileManifest } : {}),
    ...(value.manifestRef !== undefined ? { manifestRef: value.manifestRef } : {}),
    ...(value.repositoryPath !== undefined ? { repositoryPath: value.repositoryPath } : {}),
  }
}

function skillFromView(view: JsonObject, key: string): { value: JsonObject; extras: JsonObject } {
  const sourceType = text(view.sourceType, 'LOCAL_IMPORT')
  const value: JsonObject = {
    packageId: text(view.packageId, key),
    packageName: text(view.packageName, text(view.name)),
    name: text(view.name),
    sourceType,
    // Android 技能包文件走加密文件同步；网页只写元数据，fileRefs/manifest 原样透传不伪造。
    contentHash: text(view.contentHash, text(view.currentHash)),
    enabled: view.enabled !== false,
    invocationMode: text(view.invocationMode, 'AUTO'),
    installedAt: count(view.installedAt, Date.now()),
    description: text(view.description),
  }
  if (sourceType === 'REMOTE' && text(view.sourceRepository)) value.sourceRepository = text(view.sourceRepository)
  if (text(view.repositoryPath)) value.repositoryPath = text(view.repositoryPath)
  for (const name of ['fileRefs', 'fileManifest', 'manifestRef']) {
    if (view[name] !== undefined) value[name] = view[name]
  }
  const extras = pick(view, ['children', 'files', 'markdown', 'updateHash'])
  if (sourceType !== 'REMOTE' && text(view.sourceRepository)) extras.sourceRepository = text(view.sourceRepository)
  return { value, extras }
}

function computerToView(value: JsonObject, key: string, extras: JsonObject): JsonObject {
  const provider = text(value.provider, 'SSH')
  const permissionMode = text(value.permissionMode)
  return {
    ...extras,
    id: key,
    name: text(value.displayName, key),
    displayName: text(value.displayName),
    provider,
    host: text(value.host),
    port: count(value.port, 22),
    username: text(value.username, 'root'),
    // 安卓的 Cloudflare 计算机本身也是 PASSWORD 认证；网页沿用 OAUTH 只用于界面展示。
    authKind: provider === 'CLOUDFLARE' ? 'OAUTH' : text(value.authKind, 'PASSWORD'),
    runMode: text(value.runMode, 'DIRECT'),
    sandboxImage: text(value.sandboxImage),
    permissionMode: permissionMode === 'FULL' ? 'AUTO' : 'ASK',
    privateNetworkAllowed: value.allowPrivateNetwork === true,
    allowPrivateNetwork: value.allowPrivateNetwork === true,
    createdAt: count(value.createdAt),
    status: 'READY',
  }
}

function computerFromView(view: JsonObject, _key: string): { value: JsonObject; extras: JsonObject } {
  const provider = text(view.provider, 'SSH')
  const port = count(view.port, 22)
  return {
    value: {
      displayName: text(view.name, text(view.displayName)),
      provider,
      host: text(view.host),
      port: port >= 1 && port <= 65535 ? port : 22,
      username: text(view.username),
      authKind: provider === 'CLOUDFLARE' ? 'PASSWORD' : text(view.authKind) === 'PASSWORD' ? 'PASSWORD' : 'PRIVATE_KEY',
      runMode: text(view.runMode) === 'CONTAINER' ? 'CONTAINER' : 'DIRECT',
      sandboxImage: text(view.sandboxImage),
      allowPrivateNetwork: view.privateNetworkAllowed === true || view.allowPrivateNetwork === true,
      // 安卓枚举是 MANUAL / SMART / FULL；网页 ASK 对应 MANUAL，AUTO 对应 FULL。
      permissionMode: text(view.permissionMode) === 'AUTO' ? 'FULL' : 'MANUAL',
      createdAt: count(view.createdAt, Date.now()),
    },
    // 工作区、Secret、预览与主机指纹属于安卓本机数据，网页不自造同步记录。
    extras: pick(view, ['cloudflareAccountName', 'cloudflareAccountId', 'hostKeyFingerprint', 'activeTasks', 'containers', 'workspaces']),
  }
}

export function settingContractToView(kind: WebSettingKind, key: string, value: JsonObject, extras: JsonObject): JsonObject {
  switch (kind) {
    case 'mcp_configs':
      return mcpToView(value, key, extras)
    case 'skill_packages':
      return skillToView(value, key, extras)
    case 'computer_servers':
      return computerToView(value, key, extras)
  }
}

export function settingViewToContract(kind: WebSettingKind, key: string, view: JsonObject): { value: JsonObject; extras: JsonObject } {
  switch (kind) {
    case 'mcp_configs':
      return mcpFromView(view, key)
    case 'skill_packages':
      return skillFromView(view, key)
    case 'computer_servers':
      return computerFromView(view, key)
  }
}

/** 设置面板只消费网页形状；合同 kind 在这里换成旧 kind，面板其余代码不需要改。 */
export function settingsViewRecords(records: StoredRecord[], userId: string): StoredRecord[] {
  return records.map(record => {
    if (record.entity !== 'user_settings' || record.deleted) return record
    const kind = record.payload.kind
    if (typeof kind !== 'string' || isLegacySettingKind(kind)) return record
    const webKind = WEB_SETTING_KIND[kind as ContractSettingKind]
    if (!webKind) return record
    const key = text(record.payload.key)
    const value = object(record.payload.value) ?? {}
    return {
      ...record,
      payload: { ...record.payload, kind: webKind, value: settingContractToView(webKind, key, value, readSettingExtras(userId, kind, key)) },
    }
  })
}

/**
 * 把本地旧 kind 记录就地转成合同 kind：复用原 record id，savedRecords 会覆盖同一条本地记录与待传项，
 * 因此旧 kind 不会再上传，也不需要向服务器发送不认识的墓碑。转换失败的原样保留。
 */
export async function migrateLegacySettingRecords(client: SyncClient, userId: string): Promise<number> {
  const { records } = await client.snapshot()
  const migrated: StoredRecord[] = []
  for (const record of records) {
    if (record.entity !== 'user_settings' || record.deleted) continue
    const kind = record.payload.kind
    if (typeof kind !== 'string' || !isLegacySettingKind(kind)) continue
    try {
      const contractKind = contractKindOf(kind)
      const localKey = text(record.payload.key, record.id)
      const key = await canonicalSettingKey(contractKind, localKey)
      const mapped = settingViewToContract(kind, key, object(record.payload.value) ?? {})
      writeSettingExtras(userId, contractKind, key, mapped.extras)
      migrated.push({
        ...record,
        payload: {
          clientModifiedAt: count(record.payload.clientModifiedAt, Date.now()),
          kind: contractKind,
          key,
          value: mapped.value,
        },
      })
    } catch {
      /* 迁移失败的保持旧 kind，不丢用户数据，也不进入待传队列。 */
    }
  }
  if (migrated.length) {
    try {
      await client.saveRecords(migrated)
    } catch {
      // 写入失败时本地事务整体回滚，旧记录仍在；不能因此阻塞工作区打开。
      return 0
    }
  }
  return migrated.length
}
