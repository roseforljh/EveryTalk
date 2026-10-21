import { authConfig, syncBaseUrl } from "./auth";
import { isObject, isPermanentSyncFailure } from "./sync-core";
import { SyncStore } from "./sync-store";
import type { DownloadPreferences } from './file-downloads';
import { syncKey, type AuthSession, type JsonObject, type PendingChange, type PullResponse, type PushResponse, type StoredRecord, type SyncEntity } from "./types";

const VERSION = "0.2.0";
const MAX_JSON_BYTES = 40 * 1024 * 1024;
const isInteger = (value: unknown): value is number => typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
const isUuid = (value: unknown): value is string => typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
const entities = new Set(["sessions", "messages", "memories", "user_settings", "attachments"]);
export type SyncFetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

/** 网络响应进入本地事务前先校验；错误页、重复序号和无进展分页不能推进游标。 */
function validatePull(body: unknown, since: number): PullResponse {
  if (!isObject(body) || !Array.isArray(body.changes) || !isInteger(body.next_cursor) || !isInteger(body.epoch) ||
      !isInteger(body.secrets_epoch) || typeof body.has_more !== "boolean" || body.next_cursor < since ||
      body.changes.length > 500 || (body.has_more && body.next_cursor <= since)) throw new Error("同步服务返回了无效分页");
  let sequence = since;
  for (const item of body.changes) {
    if (!isObject(item) || !entities.has(String(item.entity)) || !isUuid(item.id) || typeof item.deleted !== "boolean" ||
        !isObject(item.payload) || !isInteger(item.server_seq) || item.server_seq <= sequence || item.server_seq > body.next_cursor) throw new Error("同步记录校验失败，游标未推进");
    sequence = item.server_seq;
  }
  return body as unknown as PullResponse;
}

function validatePush(body: unknown, batch: PendingChange[]): PushResponse {
  if (!isObject(body) || !Array.isArray(body.results) || body.results.length !== batch.length) throw new Error("同步确认缺失，保留本地队列");
  const seen = new Set<number>();
  for (const result of body.results) {
    if (!isObject(result) || !isInteger(result.index) || !batch[result.index] || seen.has(result.index) ||
        !["accepted", "ignored", "unchanged", "rejected"].includes(String(result.status))) throw new Error("同步确认校验失败，保留本地队列");
    const sent = batch[result.index];
    if ((result.id !== undefined && result.id !== sent.id) || (result.entity !== undefined && result.entity !== sent.entity) ||
        (result.canonical_id !== undefined && (!isUuid(result.canonical_id) || sent.entity !== "user_settings"))) throw new Error("同步确认身份不匹配");
    if (result.status !== "rejected" && !isInteger(result.server_seq)) throw new Error("同步确认缺少序号");
    seen.add(result.index);
  }
  return body as unknown as PushResponse;
}

class SyncHttpError extends Error {
  constructor(public readonly status: number, public readonly retryAt: number | undefined, code: string) {
    super(`同步服务返回 HTTP ${status}${code ? `：${code}` : ""}`);
  }
}

export interface SyncSnapshot {
  records: StoredRecord[];
  cursor: number;
  lastSyncAt?: number;
  rejected: number;
  retryableRejected: number;
  pendingCount: number;
  pausedEpoch?: number;
  retryAt?: number;
  secretsEpoch?: number;
}
export type SyncStatus = "ok" | "disabled" | "paused" | "deferred" | "pending";

/** 账号隔离的同步运行器。串行化周期、手动、恢复网络及多标签页同步，退出立即取消请求。 */
export class SyncClient {
  private store?: SyncStore;
  private opening?: Promise<void>;
  private running?: Promise<{ snapshot: SyncSnapshot; status: SyncStatus }>;
  private readonly abort = new AbortController();
  private userId = "";
  private namespace = "";

  constructor(private readonly baseUrl = syncBaseUrl, private readonly fetcher: SyncFetcher = fetch) {}

  open(userId: string): Promise<void> {
    if (this.abort.signal.aborted) return Promise.reject(new Error("工作区已关闭"));
    if (this.opening && userId !== this.userId) return Promise.reject(new Error("不能在同一工作区切换账号"));
    this.userId = userId;
    this.opening ??= (async () => {
      this.namespace = `${authConfig.url}:${this.baseUrl}:${userId}`;
      const store = await SyncStore.open(this.namespace);
      if (this.abort.signal.aborted) { store.close(); throw new Error("工作区已关闭"); }
      this.store = store;
    })();
    return this.opening;
  }

  async snapshot(): Promise<SyncSnapshot> {
    const { records, pending, state } = await this.requireStore().snapshot();
    return { records, ...state, rejected: pending.filter((item) => item.failed).length,
      retryableRejected: pending.filter((item) => item.failed && !isPermanentSyncFailure(item.failed)).length,
      pendingCount: pending.length };
  }

  async saveLocal(entity: SyncEntity, id: string, payload: JsonObject, deleted = false): Promise<void> {
    await this.saveRecords([{ entity, id, payload, deleted, key: syncKey(entity, id) }]);
  }

  async saveRecords(records: StoredRecord[]): Promise<void> {
    for (const record of records) {
      if (!isUuid(record.id) || !entities.has(record.entity)) throw new Error("记录身份无效");
    }
    await this.requireStore().saveLocal(records);
  }

  sync(session: AuthSession, refreshSession?: () => Promise<AuthSession>): Promise<{ snapshot: SyncSnapshot; status: SyncStatus }> {
    if (session.user.id !== this.userId) return Promise.reject(new Error("同步账号不匹配"));
    this.running ??= (async () => {
      // Web Locks 在同一来源的多个标签页间共享；不支持时仍由 IndexedDB 事务保护版本。
      const run = () => this.runOnce(session, refreshSession);
      return typeof navigator !== "undefined" && navigator.locks
        ? navigator.locks.request(`everytalk-sync:${this.namespace}`, { signal: this.abort.signal }, run)
        : run();
    })().finally(() => { this.running = undefined; });
    return this.running;
  }

  private async runOnce(session: AuthSession, refreshSession?: () => Promise<AuthSession>): Promise<{ snapshot: SyncSnapshot; status: SyncStatus }> {
    if (!this.baseUrl) return { snapshot: await this.snapshot(), status: "disabled" };
    const initial = await this.snapshot();
    if (initial.pausedEpoch !== undefined) return { snapshot: initial, status: "paused" };
    if (initial.retryAt && initial.retryAt > Date.now()) return { snapshot: initial, status: "deferred" };
    let current = session;
    let refreshed = false;
    for (;;) {
      try {
        let status = await this.pull(current);
        if (status !== "ok") return { snapshot: await this.snapshot(), status };
        for (let count = 0; count < 20; count++) {
          const { pending } = await this.requireStore().snapshot();
          const eligible = pending.filter((item) => !item.failed);
          if (eligible.length === 0) break;
          const batch: PendingChange[] = [];
          let bytes = 20;
          for (const item of eligible.slice(0, 100)) {
            const size = new TextEncoder().encode(JSON.stringify(item)).length + 1;
            if (batch.length && bytes + size > 900_000) break;
            batch.push(item); bytes += size;
          }
          const requestBody = { changes: batch.map(({ entity, id, payload, deleted }) => ({ entity, id, payload, deleted })) };
          const body = await this.request(current, "/sync/push", { method: "POST", body: JSON.stringify(requestBody) });
          await this.requireStore().acknowledge(batch, validatePush(body, batch).results);
          status = await this.pull(current);
          if (status !== "ok") return { snapshot: await this.snapshot(), status };
        }
        await this.requireStore().setState({ lastSyncAt: Date.now(), retryAt: undefined });
        const snapshot = await this.snapshot();
        return { snapshot, status: snapshot.pendingCount ? "pending" : "ok" };
      } catch (error) {
        if (error instanceof SyncHttpError && error.status === 401 && refreshSession && !refreshed) {
          current = await refreshSession(); refreshed = true;
          if (current.user.id !== this.userId) throw new Error("登录账号已变化");
          continue;
        }
        if (error instanceof SyncHttpError && error.status === 429) {
          await this.requireStore().setState({ retryAt: error.retryAt ?? Date.now() + 60_000 });
        }
        throw error;
      }
    }
  }

  private async pull(session: AuthSession): Promise<SyncStatus> {
    for (let pageIndex = 0; pageIndex < 100; pageIndex++) {
      const { state } = await this.requireStore().snapshot();
      const body = await this.request(session, `/sync/pull?since=${state.cursor}&limit=500`);
      const page = validatePull(body, state.cursor);
      const result = await this.requireStore().applyPage(page);
      if (result === "paused") return "paused";
      if (result === "reset-secrets") continue;
      if (!page.has_more) return "ok";
    }
    return "pending";
  }

  async retryRejected(): Promise<void> { await this.requireStore().retryRejected(); }

  async downloadPreferences(): Promise<DownloadPreferences | undefined> { return this.requireStore().downloadPreferences(); }
  async saveDownloadPreferences(value: DownloadPreferences): Promise<void> { await this.requireStore().saveDownloadPreferences(value); }

  /** 资源和密钥请求共用账号、退出取消和协议头，不另建无保护的网络入口。 */
  async cloudJson(session: AuthSession, path: string, body?: unknown, headers?: HeadersInit, refreshSession?: () => Promise<AuthSession>): Promise<unknown> {
    return this.request(session, path, { method: body === undefined ? "GET" : "POST", body: body === undefined ? undefined : JSON.stringify(body), headers }, refreshSession);
  }

  async fileRequest(session: AuthSession, path: string, init: RequestInit = {}, refreshSession?: () => Promise<AuthSession>): Promise<Response> {
    if (session.user.id !== this.userId) throw new Error("资源账号不匹配");
    this.requireStore();
    let current = session;
    let refreshed = false;
    for (;;) {
      const headers = new Headers(init.headers);
      headers.set("Authorization", `Bearer ${current.access_token}`);
      headers.set("X-EveryTalk-Sync-Version", VERSION);
      const response = await this.fetcher(`${this.baseUrl}${path}`, { ...init, headers, cache: "no-store", redirect: "error",
        signal: AbortSignal.any([this.abort.signal, AbortSignal.timeout(120_000)]) });
      if (response.status === 401 && refreshSession && !refreshed) {
        await response.body?.cancel().catch(() => undefined);
        current = await refreshSession();
        if (current.user.id !== this.userId) throw new Error("登录账号已变化");
        refreshed = true;
        continue;
      }
      if (!response.ok) throw new Error(`文件操作失败（HTTP ${response.status}）`);
      return response;
    }
  }

  close(): void { this.abort.abort(); this.store?.close(); this.store = undefined; }
  private requireStore(): SyncStore {
    if (!this.store || this.abort.signal.aborted) throw new Error("工作区尚未打开或已经关闭");
    return this.store;
  }

  private async request(session: AuthSession, path: string, init: RequestInit = {}, refreshSession?: () => Promise<AuthSession>): Promise<unknown> {
    if (session.user.id !== this.userId) throw new Error("请求账号不匹配");
    let current = session;
    let refreshed = false;
    for (;;) {
      const secretsEpoch = (await this.requireStore().snapshot()).state.secretsEpoch ?? 0;
      const response = await this.fetcher(`${this.baseUrl}${path}`, { ...init, redirect: "error", cache: "no-store",
        signal: AbortSignal.any([this.abort.signal, AbortSignal.timeout(30_000)]),
        headers: { Authorization: `Bearer ${current.access_token}`, "X-EveryTalk-Sync-Version": VERSION,
          "X-EveryTalk-Secrets-Epoch": String(secretsEpoch), ...(init.body ? { "Content-Type": "application/json" } : {}), ...Object.fromEntries(new Headers(init.headers)) },
      });
      if (response.status === 401 && refreshSession && !refreshed) {
        await response.body?.cancel().catch(() => undefined);
        current = await refreshSession();
        if (current.user.id !== this.userId) throw new Error("登录账号已变化");
        refreshed = true;
        continue;
      }
      if (!response.ok) {
        const body: unknown = await response.json().catch(() => null);
        const code = isObject(body) && isObject(body.error) && typeof body.error.code === "string" && /^[a-zA-Z_]{1,64}$/.test(body.error.code) ? body.error.code : "";
        const retry = response.headers.get("Retry-After");
        const retryAt = retry ? (/^\d+$/.test(retry) ? Date.now() + Number(retry) * 1000 : Date.parse(retry)) : undefined;
        throw new SyncHttpError(response.status, retryAt && Number.isFinite(retryAt) ? retryAt : undefined, code);
      }
      const reader = response.body?.getReader();
      if (!reader) throw new Error("同步响应为空");
      const chunks: Uint8Array[] = []; let size = 0;
      try {
        for (;;) {
          const { value, done } = await reader.read(); if (done) break;
          size += value.byteLength;
          if (size > MAX_JSON_BYTES) { await reader.cancel(); throw new Error("同步响应超过允许大小，未推进游标"); }
          chunks.push(value);
        }
      } finally { reader.releaseLock(); }
      const bytes = new Uint8Array(size); let offset = 0;
      for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
      try { return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)); }
      catch { throw new Error("同步响应格式无效，未推进游标"); }
    }
  }
}
