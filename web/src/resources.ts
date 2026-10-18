import { digest, type AccountCrypto } from './crypto'
import { isObject } from './sync-core'
import type { SyncClient } from './sync'
import { syncKey, type AuthSession, type JsonObject, type StoredRecord } from './types'

const MAX_RESOURCE = 64 * 1024 * 1024

/** 下载、解压均计数，只有长度和 SHA-256 校验通过的字节才交给界面。 */
async function bounded(stream: ReadableStream<Uint8Array> | null, limit = MAX_RESOURCE): Promise<Uint8Array<ArrayBuffer>> {
  if (!stream) throw new Error('文件内容为空')
  const reader = stream.getReader(), chunks: Uint8Array[] = []
  let size = 0
  try { for (;;) { const { value, done } = await reader.read(); if (done) break; size += value.length; if (size > limit) throw new Error('文件超过浏览器处理上限（64 MiB）'); chunks.push(value) } }
  finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
  const bytes = new Uint8Array(size)
  let offset = 0
  for (const part of chunks) { bytes.set(part, offset); offset += part.length }
  return bytes
}

export async function downloadPart(client: SyncClient, session: AuthSession, payload: JsonObject, refreshSession?: () => Promise<AuthSession>): Promise<Uint8Array<ArrayBuffer>> {
  if (typeof payload.fileId !== 'string' || !/^[0-9a-f-]{36}$/i.test(payload.fileId) || !Number.isSafeInteger(payload.size) || Number(payload.size) < 1 || Number(payload.size) > MAX_RESOURCE) throw new Error('文件尚未上传或元数据无效')
  const response = await client.fileRequest(session, `/files/${payload.fileId}`, {}, refreshSession)
  const bytes = await bounded(response.body, Number(payload.size))
  if (bytes.length !== payload.size || await digest(bytes) !== payload.sha256) throw new Error('文件校验失败，未打开损坏数据')
  return bytes
}

/** 分块必须来自同一 resourceVersion，序号完整且没有重复。 */
export function resourceParts(records: StoredRecord[], first: StoredRecord): StoredRecord[] {
  const meta = first.payload
  if (!meta.chunkCount) return [first]
  const parts = records.filter(r => !r.deleted && r.entity === 'attachments' && r.payload.messageId === meta.messageId &&
    r.payload.role === meta.role && r.payload.resourceVersion === meta.resourceVersion && r.payload.mediaId === meta.mediaId && r.payload.resultPath === meta.resultPath)
    .sort((a, b) => Number(a.payload.chunkIndex) - Number(b.payload.chunkIndex))
  if (!Number.isSafeInteger(meta.chunkCount) || Number(meta.chunkCount) > 256 || parts.length !== meta.chunkCount ||
    parts.some((p, i) => p.payload.chunkIndex !== i || p.payload.chunkCount !== meta.chunkCount)) throw new Error('资源尚未同步完整，请稍后重试')
  return parts
}

export async function downloadResource(client: SyncClient, session: AuthSession, records: StoredRecord[], first: StoredRecord, refreshSession?: () => Promise<AuthSession>): Promise<Uint8Array<ArrayBuffer>> {
  const parts = resourceParts(records, first)
  const size = parts.reduce((sum, r) => sum + Number(r.payload.size), 0)
  if (!Number.isSafeInteger(size) || size > MAX_RESOURCE) throw new Error('文件超过浏览器处理上限（64 MiB）')
  const bytes = new Uint8Array(size)
  let offset = 0
  for (const part of parts) { const data = await downloadPart(client, session, part.payload, refreshSession); bytes.set(data, offset); offset += data.length }
  const expected = first.payload.originalDigest
  if (typeof expected === 'string' && await digest(bytes) !== expected) throw new Error('完整资源哈希校验失败')
  if (typeof first.payload.originalSize === 'number' && first.payload.originalSize !== bytes.length) throw new Error('完整资源长度校验失败')
  return bytes
}

export async function readHistory(client: SyncClient, crypto: AccountCrypto, session: AuthSession, records: StoredRecord[], first: StoredRecord, refreshSession?: () => Promise<AuthSession>): Promise<JsonObject> {
  const bytes = await downloadResource(client, session, records, first, refreshSession)
  const compressed = await crypto.decryptHistory(client, session, bytes, String(first.payload.sessionId), String(first.payload.messageId), refreshSession)
  const unpacked = await bounded(new Blob([compressed]).stream().pipeThrough(new DecompressionStream('gzip')))
  if (await digest(unpacked) !== first.payload.resourceDigest) throw new Error('历史归档摘要校验失败')
  const history: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(unpacked))
  if (!isObject(history) || !isObject(history.message) || history.message.id !== first.payload.messageId || history.message.conversationId !== first.payload.sessionId) throw new Error('历史归档身份不匹配')
  // 归档仅展示用户文字与统计，恢复出来的工具命令、路径和脚本绝不执行。
  return history
}

export async function uploadResource(client: SyncClient, session: AuthSession, file: File, sessionId: string, messageId: string, refreshSession?: () => Promise<AuthSession>): Promise<StoredRecord[]> {
  if (file.size < 1 || file.size > MAX_RESOURCE) throw new Error('请选择 1 字节到 64 MiB 的文件')
  const quota = await client.cloudJson(session, '/files/quota', undefined, undefined, refreshSession)
  if (!isObject(quota) || !Number.isSafeInteger(quota.max_file_bytes) || Number(quota.max_file_bytes) < 1) throw new Error('无法读取文件配额')
  const chunkSize = Math.min(Number(quota.max_file_bytes), 4 * 1024 * 1024)
  const original = new Uint8Array(await file.arrayBuffer()), originalDigest = await digest(original)
  const count = Math.ceil(original.length / chunkSize), mediaId = crypto.randomUUID()
  const mime = /^[a-zA-Z0-9.+-]+\/[a-zA-Z0-9.+-]+$/.test(file.type) ? file.type : 'application/octet-stream'
  const records: StoredRecord[] = []
  for (let index = 0; index < count; index++) {
    const bytes = original.slice(index * chunkSize, (index + 1) * chunkSize), sha256 = await digest(bytes)
    const reservation = await client.cloudJson(session, '/files/uploads', { size: bytes.length, sha256, mime }, { 'Idempotency-Key': crypto.randomUUID() }, refreshSession)
    if (!isObject(reservation) || typeof reservation.file_id !== 'string' || !/^[0-9a-f-]{36}$/i.test(reservation.file_id)) throw new Error('文件预约响应无效')
    if (reservation.status !== 'ready') {
      if (reservation.status !== 'upload_required' || typeof reservation.upload_id !== 'string' || !/^[0-9a-f-]{36}$/i.test(reservation.upload_id)) throw new Error('上传预约无效')
      const response = await client.fileRequest(session, `/files/uploads/${reservation.upload_id}`, { method: 'PUT', body: bytes, headers: { 'Content-Type': 'application/octet-stream' } }, refreshSession)
      const receipt: unknown = await response.json()
      if (!isObject(receipt) || receipt.file_id !== reservation.file_id || receipt.sha256 !== sha256 || receipt.size !== bytes.length) throw new Error('上传回执校验失败')
    }
    const id = crypto.randomUUID()
    records.push({ key: syncKey('attachments', id), id, entity: 'attachments', deleted: false, payload: {
      clientModifiedAt: Date.now(), sessionId, messageId, name: file.name.replace(/[\\/\x00-\x1f]/g, '_').slice(0, 200) || 'file', mime,
      size: bytes.length, sha256, fileId: reservation.file_id, kind: mime.startsWith('image/') ? 'image' : mime.startsWith('audio/') ? 'audio' : 'file', position: index,
      role: 'media', mediaId, resourceVersion: `${originalDigest}:${chunkSize}`, chunkIndex: index, chunkCount: count, originalSize: file.size, originalDigest,
    } })
  }
  return records
}
