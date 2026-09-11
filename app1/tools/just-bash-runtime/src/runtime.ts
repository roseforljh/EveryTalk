import "./sandbox-platform";
import { Bash, InMemoryFs } from "just-bash/browser";
import type { CommandName } from "just-bash";

type Request = {
  id: string;
  command: string;
  cwd?: string;
  files?: Record<string, string>;
  timeoutMs?: number;
  maxOutputChars?: number;
};

type Response = {
  id: string;
  ok: boolean;
  exitCode: number;
  stdout: string;
  stderr: string;
  files: Record<string, string>;
  truncated: boolean;
};

/**
 * Android 隔离运行时使用的最小协议。
 * 文件内容通过消息传入，just-bash 不获得 Android 真实文件路径或网络能力。
 */

const COMMANDS: CommandName[] = ["cat", "head", "tail", "grep", "find", "sed", "awk", "jq", "sort", "wc", "cut", "tr", "echo", "printf", "pwd", "mkdir", "cp", "mv", "rm"];
const MAX_OUTPUT_CHARS = 200_000;
function bounded(value: string, limit: number): [string, boolean] {
  return value.length <= limit ? [value, false] : [value.slice(0, limit), true];
}

const activeRequests = new Map<string, AbortController>();

async function execute(requestJson: string): Promise<Response> {
  const request = JSON.parse(requestJson) as Request;
  if (typeof request.id !== "string" || typeof request.command !== "string" ||
      request.command.length > 32_000 || !request.command.trim()) throw new Error("invalid shell request");
  const timeoutMs = request.timeoutMs ?? 10_000;
  const limit = request.maxOutputChars ?? 20_000;
  if (!Number.isInteger(timeoutMs) || timeoutMs < 100 || timeoutMs > 30_000 ||
      !Number.isInteger(limit) || limit < 1 || limit > MAX_OUTPUT_CHARS) throw new Error("invalid execution limits");
  const cwd = request.cwd === "." || !request.cwd ? "/" : request.cwd;
  if (!cwd.startsWith("/") || cwd.includes("\\") || cwd.split("/").includes("..")) throw new Error("invalid cwd");
  const fs = new InMemoryFs(undefined, { maxTotalBytes: 25 * 1024 * 1024 });
  // Workspace 与 Bash 自带的 /bin、/proc、/dev 分开，避免把虚拟系统节点算作用户文件变化。
  const workspacePrefix = "/workspace/";
  const executionCwd = "/workspace" + (cwd === "/" ? "" : cwd);
  await fs.mkdir("/workspace", { recursive: true });
  if (Object.keys(request.files ?? {}).length > 500) throw new Error("workspace file count exceeded");
  for (const [relativePath, content] of Object.entries(request.files ?? {})) {
    const parts = relativePath.split("/");
    if (relativePath.length > 512 || parts.length > 20 ||
        parts.some((p) => !p || p === "." || p === ".." || /[\\:\u0000-\u001f]/.test(p))) throw new Error("invalid workspace path");
    const path = workspacePrefix + relativePath;
    if (typeof content !== "string" || new TextEncoder().encode(content).length > 8 * 1024 * 1024) throw new Error("workspace file too large");
    await fs.mkdir(path.substring(0, path.lastIndexOf("/")) || "/", { recursive: true });
    await fs.writeFile(path, content);
  }
  const bash = new Bash({ fs, cwd: executionCwd, commands: COMMANDS, executionLimitProfile: "hardened", executionLimits: { maxExecutionTimeMs: timeoutMs, maxOutputSize: MAX_OUTPUT_CHARS } });
  const controller = new AbortController();
  activeRequests.set(request.id, controller);
  let result;
  try {
    result = await bash.exec(request.command, { cwd: executionCwd, signal: controller.signal });
  } finally {
    activeRequests.delete(request.id);
  }
  const [stdout, stdoutTruncated] = bounded(result.stdout, limit);
  const [stderr, stderrTruncated] = bounded(result.stderr, limit - stdout.length);
  const files: Record<string, string> = Object.create(null);
  for (const path of fs.getAllPaths()) if (path.startsWith(workspacePrefix) && (await fs.lstat(path)).isFile) {
    const relative = path.slice(workspacePrefix.length);
    if (relative.split("/").length > 20 || Object.keys(files).length >= 500) throw new Error("workspace limit exceeded");
    const content = await fs.readFile(path);
    if (new TextEncoder().encode(content).length > 8 * 1024 * 1024) throw new Error("workspace file too large");
    files[relative] = content;
  }
  const response: Response = {
    id: request.id,
    ok: result.exitCode === 0,
    exitCode: result.exitCode,
    stdout, stderr, files, truncated: stdoutTruncated || stderrTruncated,
  };
  return response;
}

const runtimeGlobal = globalThis as typeof globalThis & {
  EveryTalkJustBashAsync?: (requestJson: string) => Promise<Response>;
  EveryTalkJustBashAsyncFromNamedData?: (name: string) => Promise<Response>;
  EveryTalkJustBashCancel?: (id: string) => void;
};
runtimeGlobal.EveryTalkJustBashAsync = execute;
runtimeGlobal.EveryTalkJustBashAsyncFromNamedData = async (name) => {
  const bytes = await (globalThis as typeof globalThis & {
    android?: { consumeNamedDataAsArrayBuffer(name: string): Promise<ArrayBuffer> }
  }).android?.consumeNamedDataAsArrayBuffer(name) ?? new ArrayBuffer(0);
  return execute(new TextDecoder().decode(new Uint8Array(bytes)));
};
runtimeGlobal.EveryTalkJustBashCancel = (id) => activeRequests.get(id)?.abort();

export { execute };
