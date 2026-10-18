import { isObject } from "./sync-core";
import type { AuthSession, JsonObject } from "./types";
import type { SyncClient } from "./sync";

const utf8 = new TextEncoder();
export const base64 = (bytes: Uint8Array): string => btoa(Array.from(bytes, (b) => String.fromCharCode(b)).join(""));
export function unbase64(value: string): Uint8Array<ArrayBuffer> {
  if (!value || value.length % 4 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) throw new Error("密文编码无效");
  const bytes = Uint8Array.from(atob(value), (c) => c.charCodeAt(0));
  if (base64(bytes) !== value) throw new Error("密文编码无效");
  return bytes;
}
export function envelope(value: unknown): value is JsonObject & { nonce: string; ct: string; keyVersion: number } {
  return isObject(value) && value.$enc === 1 && value.alg === "AES-256-GCM" && typeof value.nonce === "string" &&
    typeof value.ct === "string" && Number.isSafeInteger(value.keyVersion) && Number(value.keyVersion) > 0;
}
export const digest = async (bytes: Uint8Array<ArrayBuffer>): Promise<string> => Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)), (b) => b.toString(16).padStart(2, "0")).join("");

/** 与安卓共用 AES-GCM 和紧凑 JSON AAD。密钥只在内存中持有，退出后销毁引用。 */
export class AccountCrypto {
  private key?: CryptoKey;
  private historyKey?: CryptoKey;
  private epoch = -1;
  private version = 0;
  constructor(private readonly userId: string) {}

  async unlock(client: SyncClient, session: AuthSession, epoch: number, refreshSession?: () => Promise<AuthSession>): Promise<void> {
    if (session.user.id !== this.userId) throw new Error("密钥账号不匹配");
    if (this.key && this.epoch === epoch) return;
    this.key = undefined;
    const result = await client.cloudJson(session, "/sync/account-key", { secrets_epoch: epoch }, undefined, refreshSession);
    if (!isObject(result) || typeof result.dek !== "string" || result.secrets_epoch !== epoch || result.version !== epoch + 1) throw new Error("账号密钥响应无效");
    const raw = unbase64(result.dek);
    try {
      if (raw.length !== 32) throw new Error("账号密钥长度无效");
      this.key = await crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
      this.version = Number(result.version); this.epoch = epoch;
    } finally { raw.fill(0); }
  }

  private aad(kind: string, key: string, pointer: string, version: number): Uint8Array<ArrayBuffer> {
    return utf8.encode(JSON.stringify(["everytalk-sync", "0.2.0", this.userId, kind, key, pointer, version, this.epoch]));
  }

  async encrypt(value: unknown, kind: string, key: string, pointer: string): Promise<JsonObject> {
    if (!this.key) throw new Error("账号密钥尚未解锁");
    const nonce = crypto.getRandomValues(new Uint8Array(12));
    const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce, additionalData: this.aad(kind, key, pointer, this.version) }, this.key, utf8.encode(JSON.stringify(value)));
    const result = { $enc: 1, alg: "AES-256-GCM", nonce: base64(nonce), ct: base64(new Uint8Array(ct)), keyVersion: this.version };
    if (utf8.encode(JSON.stringify(result)).length > 8192) throw new Error("单个加密字段过大，请缩短后重试");
    return result;
  }

  async decrypt(value: unknown, kind: string, key: string, pointer: string): Promise<unknown> {
    if (!envelope(value)) return value;
    if (!this.key || value.keyVersion !== this.version) throw new Error("配置密钥版本不一致，请在安卓端重新保存凭据");
    const nonce = unbase64(value.nonce), ct = unbase64(value.ct);
    if (nonce.length !== 12 || ct.length < 16) throw new Error("配置密文无效");
    const bytes = await crypto.subtle.decrypt({ name: "AES-GCM", iv: nonce, additionalData: this.aad(kind, key, pointer, value.keyVersion) }, this.key, ct);
    return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
  }

  /** 在原始字段路径逐项解密，数组下标参与 AAD，不改变排序。 */
  async decodeSettings(value: unknown, kind: string, key: string, pointer = ""): Promise<unknown> {
    if (envelope(value)) return this.decrypt(value, kind, key, pointer);
    if (Array.isArray(value)) return Promise.all(value.map((item, i) => this.decodeSettings(item, kind, key, `${pointer}/${i}`)));
    if (isObject(value)) return Object.fromEntries(await Promise.all(Object.entries(value).map(async ([name, item]) =>
      [name, await this.decodeSettings(item, kind, key, `${pointer}/${name.replace(/~/g, "~0").replace(/\//g, "~1")}`)])));
    return value;
  }

  /** 配置编辑器统一保护敏感字段，与服务端扫描规则一致；已经加密的字段保持原样。 */
  async encodeSettings(value: unknown, kind: string, key: string, pointer = ""): Promise<unknown> {
    if (envelope(value)) return value;
    if (Array.isArray(value)) return Promise.all(value.map((item, i) => this.encodeSettings(item, kind, key, `${pointer}/${i}`)));
    if (!isObject(value)) return value;
    const secretValue = (v: unknown): boolean => typeof v === 'string' &&
      (/\bsk-[A-Za-z0-9][A-Za-z0-9_-]{7,}\b|\bBearer[ \t]+[A-Za-z0-9._~+/=-]{8,}|-----BEGIN [A-Z0-9 ]+-----|\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b|\b[a-z][a-z0-9+.-]*:\/\/[^/\s:@]+:[^/\s@]+@/i.test(v));
    return Object.fromEntries(await Promise.all(Object.entries(value).map(async ([name, item]) => {
      const path = `${pointer}/${name.replace(/~/g, "~0").replace(/\//g, "~1")}`;
      const sensitiveName = /key|token|secret|password|authorization|cookie|headers|privatekey/.test(name.toLowerCase().replace(/[^a-z0-9]/g, ''));
      const customSecret = name === 'value' && typeof value.name === 'string' && /key|token|secret|password|headers|authorization/i.test(value.name);
      return [name, !envelope(item) && (sensitiveName || customSecret || secretValue(item))
        ? await this.encrypt(item, kind, key, path) : await this.encodeSettings(item, kind, key, path)];
    })));
  }

  async decryptHistory(client: SyncClient, session: AuthSession, bytes: Uint8Array<ArrayBuffer>, sessionId: string, messageId: string, refreshSession?: () => Promise<AuthSession>): Promise<Uint8Array<ArrayBuffer>> {
    if (!this.historyKey) {
      const result = await client.cloudJson(session, "/sync/history-key", {}, undefined, refreshSession);
      if (!isObject(result) || result.version !== 1 || result.secrets_epoch !== 0 || typeof result.dek !== "string") throw new Error("历史密钥无效");
      const raw = unbase64(result.dek);
      try { if (raw.length !== 32) throw new Error("历史密钥无效"); this.historyKey = await crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["decrypt"]); }
      finally { raw.fill(0); }
    }
    if (bytes.length < 28) throw new Error("历史归档不完整");
    const aad = utf8.encode(JSON.stringify(["everytalk-history", this.userId, sessionId, messageId, "0"]));
    return new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: bytes.slice(0, 12), additionalData: aad }, this.historyKey, bytes.slice(12)));
  }

  close(): void { this.key = undefined; this.historyKey = undefined; this.epoch = -1; }
}
