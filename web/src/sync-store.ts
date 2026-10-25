import { isPermanentSyncFailure, logicalKey, mergeRemote, pendingChange } from "./sync-core";
import { syncKey, type PendingChange, type PullResponse, type PushResult, type StoredRecord, type SyncState } from "./types";
import type { DownloadPreferences } from './file-downloads';

const TABLES = ["records", "pending", "meta"];

function read<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

/**
 * 记录、待传队列、游标使用同一事务。网络请求永远在事务外执行，
 * 浏览器关闭、配额耗尽或事务中止时不会留下“游标已推进、正文未保存”的状态。
 */
export class SyncStore {
  private constructor(private readonly db: IDBDatabase) {}

  static async open(namespace: string): Promise<SyncStore> {
    const request = indexedDB.open(`everytalk-web-${namespace}`, 1);
    request.onupgradeneeded = () => {
      for (const name of TABLES) request.result.createObjectStore(name, { keyPath: "key" });
    };
    const db = await read(request);
    db.onversionchange = () => db.close();
    return new SyncStore(db);
  }

  private async transaction<T>(mode: IDBTransactionMode, work: (tx: IDBTransaction) => Promise<T>): Promise<T> {
    const tx = this.db.transaction(TABLES, mode);
    const complete = new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onabort = () => reject(tx.error ?? new Error("本地数据事务已取消"));
      tx.onerror = () => reject(tx.error ?? new Error("本地数据保存失败"));
    });
    void complete.catch(() => undefined);
    try {
      const result = await work(tx);
      await complete;
      return result;
    } catch (error) {
      try { tx.abort(); } catch { /* 事务可能已经由 IndexedDB 回滚。 */ }
      throw error;
    }
  }

  async snapshot(): Promise<{ records: StoredRecord[]; pending: PendingChange[]; state: SyncState }> {
    return this.transaction("readonly", async (tx) => {
      const [records, pending, state] = await Promise.all([
        read(tx.objectStore("records").getAll()) as Promise<StoredRecord[]>,
        read(tx.objectStore("pending").getAll()) as Promise<PendingChange[]>,
        read(tx.objectStore("meta").get("state")) as Promise<{ value: SyncState } | undefined>,
      ]);
      return { records, pending, state: state?.value ?? { cursor: 0 } };
    });
  }

  async setState(patch: Partial<SyncState>): Promise<void> {
    await this.transaction("readwrite", async (tx) => {
      const table = tx.objectStore("meta");
      const old = await read(table.get("state")) as { value: SyncState } | undefined;
      table.put({ key: "state", value: { cursor: 0, ...old?.value, ...patch } });
    });
  }

  /** 目录句柄与下载回执只保存在账号本地 meta 表，不加入同步队列或云端备份。 */
  async downloadPreferences(): Promise<DownloadPreferences | undefined> {
    return this.transaction('readonly', async tx => {
      const row = await read(tx.objectStore('meta').get('file-downloads')) as { value: DownloadPreferences } | undefined;
      return row?.value;
    });
  }

  async saveDownloadPreferences(value: DownloadPreferences): Promise<void> {
    await this.transaction('readwrite', async tx => {
      await read(tx.objectStore('meta').put({ key: 'file-downloads', value }));
    });
  }

  /** 已校验附件只保留在当前账号本机，不进入同步队列；重开网页可继续离线预览。 */
  async cachedAttachment(key: string): Promise<Blob | undefined> {
    return this.transaction('readonly', async tx => {
      const row = await read(tx.objectStore('meta').get(`attachment:${key}`)) as { value: Blob } | undefined;
      return row?.value;
    });
  }

  async cacheAttachment(key: string, value: Blob): Promise<void> {
    await this.transaction('readwrite', async tx => {
      await read(tx.objectStore('meta').put({ key: `attachment:${key}`, value }));
    });
  }

  /** 一次业务操作可以同时更新会话时间和消息，失败时一起回滚。 */
  async saveLocal(records: StoredRecord[]): Promise<void> {
    await this.transaction("readwrite", async (tx) => {
      const table = tx.objectStore("records");
      for (const candidate of records) {
        const old = await read(table.get(candidate.key)) as StoredRecord | undefined;
        if (old?.deleted && candidate.entity !== "user_settings") throw new Error("这条记录已经删除，不能恢复旧版本");
        if (candidate.entity === "messages" && old && !candidate.deleted) throw new Error("修改消息必须创建新消息，不能覆盖原记录");
        const record = { ...candidate, pending: true };
        table.put(record);
        tx.objectStore("pending").put(pendingChange(record));
      }
      const all = await read(table.getAll()) as StoredRecord[];
      for (const child of all) {
        const parent = all.find((item) => item.deleted && ((item.entity === "sessions" && item.id === child.payload.sessionId) ||
          (item.entity === "messages" && item.id === child.payload.messageId)));
        if (parent) {
          table.put({ ...child, deleted: true, pending: false });
          tx.objectStore("pending").delete(child.key);
        }
      }
    });
  }

  /** 每页应用读取事务内最新待传版本；epoch 改变先暂停，密钥代次改变则从零协调。 */
  async applyPage(page: PullResponse): Promise<"applied" | "reset-secrets" | "paused"> {
    return this.transaction("readwrite", async (tx) => {
      const meta = tx.objectStore("meta");
      const row = await read(meta.get("state")) as { value: SyncState } | undefined;
      const state = row?.value ?? { cursor: 0 };
      if (state.epoch !== undefined && state.epoch !== page.epoch) {
        meta.put({ key: "state", value: { ...state, pausedEpoch: page.epoch } });
        return "paused";
      }
      if (state.secretsEpoch !== undefined && state.secretsEpoch !== page.secrets_epoch) {
        const queue = tx.objectStore("pending");
        const items = await read(queue.getAll()) as PendingChange[];
        const encrypted = (value: unknown): boolean => value !== null && typeof value === "object" &&
          ("$enc" in value || Object.values(value).some(encrypted));
        // 旧代次密文不能继续上传；保留本地数据，等待用户重新编辑凭据。
        for (const item of items) if (encrypted(item.payload)) queue.put({ ...item, failed: "SECRETS_EPOCH_CHANGED" });
        meta.put({ key: "state", value: { ...state, cursor: 0, secretsEpoch: page.secrets_epoch } });
        return "reset-secrets";
      }
      const table = tx.objectStore("records"), queue = tx.objectStore("pending");
      const [records, pending] = await Promise.all([
        read(table.getAll()) as Promise<StoredRecord[]>, read(queue.getAll()) as Promise<PendingChange[]>,
      ]);
      const byLogical = new Map(records.map((record) => [logicalKey(record), record]));
      const queued = new Map(pending.map((change) => [logicalKey(change), change]));
      for (const remote of page.changes) {
        const identity = logicalKey(remote);
        const old = byLogical.get(identity), local = queued.get(identity);
        const merged = mergeRemote(old, remote, local);
        if (old && old.key !== merged.key) table.delete(old.key);
        if (local && local.key !== merged.key) queue.delete(local.key);
        table.put(merged);
        if (local && merged.pending) queue.put({ ...local, id: merged.id, key: merged.key });
        else if (local) queue.delete(local.key);
        byLogical.set(identity, merged);
        if (!merged.pending) queued.delete(identity);
        if (remote.deleted && remote.entity === "sessions") {
          for (const child of records) {
            if (child.payload.sessionId !== remote.id) continue;
            const tombstone = { ...child, deleted: true, pending: false };
            table.put(tombstone);
            byLogical.set(logicalKey(child), tombstone);
            queued.delete(logicalKey(child));
            queue.delete(child.key);
          }
        }
      }
      // 一页内父子顺序不固定；最终统一落实父墓碑，不能让后出现的子消息复活。
      const all = [...byLogical.values()];
      const deletedSessions = new Set(all.filter((r) => r.entity === "sessions" && r.deleted).map((r) => r.id));
      const deletedMessages = new Set(all.filter((r) => r.entity === "messages" && r.deleted).map((r) => r.id));
      for (const child of all) {
        if (deletedSessions.has(String(child.payload.sessionId)) || deletedMessages.has(String(child.payload.messageId))) {
          table.put({ ...child, deleted: true, pending: false }); queue.delete(child.key);
        }
      }
      meta.put({ key: "state", value: { ...state, cursor: page.next_cursor, epoch: page.epoch, secretsEpoch: page.secrets_epoch } });
      return "applied";
    });
  }

  /** 成功确认只移除发送时的版本；用户在网络等待中产生的新编辑继续排队。 */
  async acknowledge(batch: PendingChange[], results: PushResult[]): Promise<void> {
    await this.transaction("readwrite", async (tx) => {
      const table = tx.objectStore("records"), queue = tx.objectStore("pending");
      let replay = false;
      for (const result of results) {
        const sent = batch[result.index];
        const current = await read(queue.get(sent.key)) as PendingChange | undefined;
        if (!current) continue;
        const id = result.canonical_id ?? current.id, key = syncKey(current.entity, id);
        const record = await read(table.get(current.key)) as StoredRecord | undefined;
        const sameVersion = current.revision === sent.revision;
        if (result.status === "rejected") {
          const code = result.error?.code ?? "INVALID_CHANGE";
          if (sameVersion && code === "session_deleted" && sent.entity === "messages" && record) {
            // 安卓端会丢弃无法归属已删除会话的本地消息；保留墓碑展示状态，但不再把它发回云端。
            queue.delete(current.key);
            table.put({ ...record, deleted: true, pending: false });
          } else if (sameVersion && code === "message_deleted" && sent.entity === "attachments" && record) {
            queue.delete(current.key);
            table.put({ ...record, deleted: true, pending: false });
          } else if (sameVersion) queue.put({ ...current, failed: code });
          continue;
        }
        // unchanged 也可能表示服务器已有更晚版本，必须重读旧游标之前的权威值。
        replay ||= result.status === "ignored" || result.status === "unchanged";
        if (current.key !== key) { queue.delete(current.key); table.delete(current.key); }
        if (sameVersion) queue.delete(key);
        else queue.put({ ...current, id, key });
        if (record) table.put({ ...record, id, key, pending: !sameVersion });
      }
      if (replay) {
        const meta = tx.objectStore("meta");
        const row = await read(meta.get("state")) as { value: SyncState };
        meta.put({ key: "state", value: { ...row?.value, cursor: 0 } });
      }
    });
  }

  async retryRejected(): Promise<void> {
    await this.transaction("readwrite", async (tx) => {
      const queue = tx.objectStore("pending");
      const items = await read(queue.getAll()) as PendingChange[];
      // 密钥换代后旧密文不能靠重试恢复；必须由设置编辑器用新密钥保存并替换队列项。
      for (const item of items) if (!isPermanentSyncFailure(item.failed)) {
        queue.put({ ...item, failed: undefined, revision: crypto.randomUUID() });
      }
    });
  }

  close(): void { this.db.close(); }
}
