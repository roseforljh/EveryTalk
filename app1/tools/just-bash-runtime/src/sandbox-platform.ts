import { TextDecoder, TextEncoder } from "text-encoding";
import AbortController from "abort-controller";

/**
 * Android JavaScriptSandbox 只有 ECMAScript，没有 DOM 和宿主网络。
 * 补齐 just-bash 所用的编码、取消与计时接口，不暴露宿主文件、fetch 或任意 Java 回调。
 * 定时器由 Kotlin 定期调用 pumpTimers 驱动；硬超时由宿主终止 isolate 强制执行。
 */
const host = globalThis as typeof globalThis & { EveryTalkPumpTimers?: () => void };
if (!host.TextEncoder) host.TextEncoder = TextEncoder;
if (!host.TextDecoder) host.TextDecoder = TextDecoder;
if (!host.AbortController) host.AbortController = AbortController;
if (!host.performance) {
  const origin = Date.now();
  Object.defineProperty(host, "performance", { value: { timeOrigin: origin, now: () => Date.now() - origin } });
}
if (!host.DOMException) {
  Object.defineProperty(host, "DOMException", { value: class extends Error {
    constructor(message: string, name = "Error") { super(message); this.name = name; }
  } });
}

type Timer = { due: number; interval: number | null; callback: () => void };
const timers = new Map<number, Timer>();
let nextTimerId = 1;
function schedule(callback: () => void, milliseconds = 0, interval: boolean): number {
  if (typeof callback !== "function") throw new TypeError("定时器仅接受函数");
  if (timers.size >= 1000) throw new Error("定时器数量超过限制");
  const id = nextTimerId++;
  const delay = Math.max(0, Number(milliseconds) || 0);
  timers.set(id, { due: Date.now() + delay, interval: interval ? Math.max(1, delay) : null, callback });
  return id;
}
if (!host.queueMicrotask) host.queueMicrotask = (callback: () => void) => { Promise.resolve().then(callback); };
// 宿主在等待 Promise 时持续泵送到期定时器。保留原生环境的定时器供协议测试使用。
if (!host.setTimeout) Object.assign(host, {
  setTimeout: (callback: () => void, ms?: number) => schedule(callback, ms, false),
  clearTimeout: (id: number) => timers.delete(id),
  setInterval: (callback: () => void, ms?: number) => schedule(callback, ms, true),
  clearInterval: (id: number) => timers.delete(id),
});
host.EveryTalkPumpTimers = () => {
  const now = Date.now();
  for (const [id, timer] of [...timers]) {
    if (timer.due > now || !timers.has(id)) continue;
    if (timer.interval === null) timers.delete(id);
    else timer.due = now + timer.interval;
    timer.callback();
  }
};
