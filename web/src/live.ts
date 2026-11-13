import { apiBaseUrl } from "./auth";
import type { ExecutionStepItem, ModelResult } from "./model";
import type { AuthSession, JsonObject } from "./types";

/**
 * 同账号多设备实时状态（与安卓 LiveSync 同一协议）：运行端上报回复快照，
 * 其他设备经 /live/events 收到快照、结束、远程停止和"云端有新数据"。
 * 快照是安卓 Message 的 JSON，gzip 后 base64，服务器只在内存里转发。
 */
export const liveDevice = crypto.randomUUID();

export interface LiveRemote { messageUuid: string; result: ModelResult; running: boolean; startedAt: number }
export type LiveAuth = { session: () => AuthSession; refresh: () => Promise<AuthSession> };

const isObj = (value: unknown): value is JsonObject => typeof value === "object" && value !== null && !Array.isArray(value);
const text = (value: unknown) => (typeof value === "string" ? value : "");
const sleep = (ms: number) => new Promise(resolve => window.setTimeout(resolve, ms));

async function authed(auth: LiveAuth, path: string, init: RequestInit = {}): Promise<Response> {
  const send = (token: string) => fetch(`${apiBaseUrl}${path}`, { ...init, cache: "no-store",
    headers: { ...Object.fromEntries(new Headers(init.headers)), Authorization: `Bearer ${token}`, "X-EveryTalk-Device": liveDevice } });
  const response = await send(auth.session().access_token);
  if (response.status !== 401) return response;
  await response.body?.cancel().catch(() => undefined);
  return send((await auth.refresh()).access_token);
}

async function gzipBase64(value: string): Promise<string> {
  const bytes = new Uint8Array(await new Response(new Blob([value]).stream().pipeThrough(new CompressionStream("gzip"))).arrayBuffer());
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary);
}

async function gunzipBase64(value: string): Promise<string> {
  const bytes = Uint8Array.from(atob(value), c => c.charCodeAt(0));
  return new Response(new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"))).text();
}

const stepTypes: Record<string, ExecutionStepItem["type"]> = { Search: "search", Web: "web", Tool: "tool", Agent: "tool" };

/** 安卓 Message JSON → 网页流式展示结构。Agent 的工具步骤在 executionTrace 里，正文为空时按顺序拼 Content。 */
export function fromAndroidMessage(message: JsonObject): ModelResult {
  const trace = Array.isArray(message.executionTrace) ? message.executionTrace.filter(isObj) : [];
  const kind = (event: JsonObject) => text(event.type).split(".").pop();
  const steps = new Map<string, ExecutionStepItem>();
  for (const step of [...(Array.isArray(message.executionSteps) ? message.executionSteps : []), ...trace.map(event => event.step)]) {
    if (!isObj(step) || !text(step.id)) continue;
    steps.set(text(step.id), {
      id: text(step.id), type: stepTypes[text(step.type)] ?? "tool", title: text(step.title),
      labels: Array.isArray(step.labels) ? step.labels.map(text) : [], completed: step.completed === true,
      isError: step.isError === true, ...(text(step.detail) ? { detail: text(step.detail) } : {}),
    });
  }
  const join = (name: string) => trace.filter(event => kind(event) === name).map(event => text(event.text)).join("");
  return {
    text: text(message.text) || join("Content"),
    reasoning: text(message.reasoning) || join("Reasoning"),
    executionSteps: [...steps.values()],
    ...(text(message.executionStatus) ? { activeStatus: text(message.executionStatus) } : {}),
  };
}

/** 网页流式结构 → 安卓 Message JSON（安卓按同一个数据类解码）。 */
export function toAndroidMessage(messageUuid: string, result: ModelResult): JsonObject {
  const types = { search: "Search", web: "Web", tool: "Tool" } as const;
  return {
    messageUuid,
    message: {
      id: messageUuid, text: result.text, sender: "AI", contentStarted: Boolean(result.text),
      ...(result.reasoning ? { reasoning: result.reasoning } : {}),
      ...(result.activeStatus ? { executionStatus: result.activeStatus } : {}),
      executionSteps: (result.executionSteps ?? []).map(step => ({
        id: step.id, type: types[step.type], title: step.title, labels: step.labels, completed: step.completed,
        started: true, isError: step.isError === true, ...(step.detail ? { detail: step.detail } : {}),
      })),
    },
  };
}

/** 运行中会话 → 远程停止回调。快照响应和实时通道都可能带来停止请求，每次运行只触发一次。 */
const cancelHandlers = new Map<string, () => void>();

/**
 * 网页生成回复时上报快照：有其他设备在看时每秒一次，否则 5 秒一次，首个快照立即发。
 * messageUuid 必须等于回复定稿后保存的消息 ID，其他设备才能无缝换成正式消息。
 */
export function livePublisher(auth: LiveAuth, sessionId: string, messageUuid: string, onCancel: () => void) {
  let latest: ModelResult | undefined;
  let changed = false;
  let finished = false;
  let sentOnce = false;
  let cancelled = false;
  let peers = 0;
  let lastSentAt = 0;
  let wake: (() => void) | undefined;
  const cancel = () => { if (!cancelled) { cancelled = true; onCancel(); } };
  cancelHandlers.set(sessionId, cancel);
  void (async () => {
    while (!finished) {
      await Promise.race([sleep(peers > 0 ? 1000 : 5000), new Promise<void>(resolve => { wake = resolve; })]);
      if (finished || !latest || (!changed && Date.now() - lastSentAt < 10_000)) continue;
      changed = false;
      lastSentAt = Date.now();
      try {
        const body = await gzipBase64(JSON.stringify(toAndroidMessage(messageUuid, latest)));
        const response = await authed(auth, `/live/sessions/${sessionId}`, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ body }) });
        if (!response.ok) continue;
        sentOnce = true;
        const reply = await response.json() as { peers?: number; cancel?: boolean };
        peers = reply.peers ?? 0;
        if (reply.cancel) cancel();
      } catch { /* 网络失败不影响本地生成，下一轮重试。 */ }
    }
    // 在上报协程里收尾，保证结束通知排在最后一次快照之后。
    if (sentOnce) void authed(auth, `/live/sessions/${sessionId}`, { method: "DELETE" }).catch(() => undefined);
  })();
  return {
    update(result: ModelResult) {
      latest = result;
      changed = true;
      if (!lastSentAt) wake?.();
    },
    finish() {
      if (finished) return;
      finished = true;
      wake?.();
      if (cancelHandlers.get(sessionId) === cancel) cancelHandlers.delete(sessionId);
    },
  };
}

export function requestLiveStop(auth: LiveAuth, sessionId: string): Promise<unknown> {
  return authed(auth, `/live/sessions/${sessionId}/cancel`, { method: "POST" }).catch(() => undefined);
}

/** 订阅其他设备的实时状态，断线自动重连；返回关闭函数。 */
export function connectLive(auth: LiveAuth, handlers: {
  onChanged: () => void;
  onLive: (sessionId: string, remote: LiveRemote) => void;
  onEnd: (sessionId: string) => void;
  onReset: () => void;
}): () => void {
  const abort = new AbortController();
  const startedAt = new Map<string, number>();
  async function dispatch(event: string, data: string) {
    try {
      const value = data ? JSON.parse(data) as JsonObject : {};
      const session = text(value.session);
      if (event === "changed") handlers.onChanged();
      else if (event === "cancel") cancelHandlers.get(session)?.();
      else if (event === "end") { startedAt.delete(session); handlers.onEnd(session); }
      else if (event === "live") {
        const snapshot = JSON.parse(await gunzipBase64(text(value.body))) as JsonObject;
        if (!isObj(snapshot.message)) return;
        if (!startedAt.has(session)) startedAt.set(session, Date.now());
        handlers.onLive(session, { messageUuid: text(snapshot.messageUuid), result: fromAndroidMessage(snapshot.message), running: true, startedAt: startedAt.get(session)! });
      }
    } catch { /* 单条坏数据不影响连接。 */ }
  }
  void (async () => {
    let backoff = 2000;
    while (!abort.signal.aborted) {
      try {
        const response = await authed(auth, `/live/events?device=${liveDevice}`, { signal: abort.signal });
        if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);
        backoff = 2000;
        // 断线期间可能错过 changed，连上先补同步一次。
        handlers.onChanged();
        const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
        let buffer = "", event = "", data = "";
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += value;
          for (let index = buffer.indexOf("\n"); index >= 0; index = buffer.indexOf("\n")) {
            const line = buffer.slice(0, index).replace(/\r$/, "");
            buffer = buffer.slice(index + 1);
            if (line === "") { await dispatch(event, data); event = ""; data = ""; }
            else if (line.startsWith("event:")) event = line.slice(6).trim();
            else if (line.startsWith("data:")) data += line.slice(5).replace(/^ /, "");
          }
        }
      } catch {
        backoff = Math.min(backoff * 2, 30_000);
      }
      startedAt.clear();
      handlers.onReset();
      if (!abort.signal.aborted) await sleep(backoff);
    }
  })();
  return () => abort.abort();
}
