import { syncKey, type JsonObject, type PendingChange, type StoredRecord, type SyncRecord } from "./types";

export const isObject = (value: unknown): value is JsonObject => value !== null && typeof value === "object" && !Array.isArray(value);

/** 这些拒绝代表数据已经无法按原版本重放，必须由用户重新编辑后才会产生新队列项。 */
export function isPermanentSyncFailure(value: string | undefined): boolean {
  return value !== undefined && new Set(["session_deleted", "message_deleted", "IMMUTABLE_FIELD", "INVALID_SESSION_ID", "ATTACHMENT_PARENT_MISMATCH", "SECRETS_EPOCH_CHANGED"]).has(value);
}

export function modifiedAt(record: { payload: JsonObject }): number {
  const value = record.payload.clientModifiedAt ?? record.payload.timestamp ?? record.payload.updatedAt;
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : 0;
}

/** 配置身份是 kind/key；服务器 canonical id 变更不能生成第二份相同配置。 */
export function logicalKey(record: SyncRecord | PendingChange): string {
  if (record.entity === "user_settings") return JSON.stringify([record.entity, record.payload.kind, record.payload.key]);
  return syncKey(record.entity, record.id);
}

/** 对象可逐层补缺失键，数组、null、false 和空字符串均视为已有完整值。 */
export function supplement(current: JsonObject, incoming: JsonObject): JsonObject {
  return Object.fromEntries(Object.entries({ ...incoming, ...current }).map(([key, value]) => [key,
    isObject(value) && isObject(incoming[key]) ? supplement(value, incoming[key]) : value,
  ]));
}

/** 会话、消息、附件和记忆的删除不能被旧设备复活。配置允许更晚重新加入。 */
export function mergeRemote(current: StoredRecord | undefined, remote: SyncRecord, pending?: PendingChange): StoredRecord {
  const key = syncKey(remote.entity, remote.id);
  if (current?.deleted && remote.entity !== "user_settings") return { ...current, id: remote.id, key, pending: false };
  if (remote.deleted && remote.entity !== "user_settings") return { ...remote, payload: { ...current?.payload, ...remote.payload }, key, pending: false };
  if (remote.entity === "messages" && current && !remote.deleted) {
    return { ...remote, key, payload: supplement(current.payload, remote.payload), pending: Boolean(pending) };
  }
  if (pending && modifiedAt(pending) >= modifiedAt(remote) && !(remote.deleted && modifiedAt(pending) === modifiedAt(remote))) {
    return { ...remote, payload: pending.payload, deleted: pending.deleted, key, pending: true };
  }
  return { ...remote, key, pending: false };
}

export function pendingChange(record: StoredRecord): PendingChange {
  return { key: record.key, entity: record.entity, id: record.id, payload: record.payload, deleted: record.deleted, revision: crypto.randomUUID() };
}
