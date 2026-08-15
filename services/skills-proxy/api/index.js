import { createHash } from "node:crypto";
import { getVercelOidcToken } from "@vercel/oidc";
import { unzipSync } from "fflate";

const SKILLS_ORIGIN = "https://skills.sh";
const GITHUB_ARCHIVE_ORIGIN = "https://codeload.github.com";
const MAX_ARCHIVE_BYTES = 100 * 1024 * 1024;
const MAX_FILES = 1000;
const CACHE_SECONDS = 300;
const RATE_LIMIT_WINDOW_MS = 60_000;
const RATE_LIMIT_REQUESTS = 60;
const rateWindows = new Map();

export default async function handler(request, response) {
  if (request.method !== "GET") return sendJson(response, 405, { error: "只支持 GET" });
  if (!allowRequest(request)) return sendJson(response, 429, { error: "请求过于频繁，请稍后再试" });
  try {
    const path = firstQuery(request.query.path).replace(/^\/+|\/+$/g, "");
    if (path === "search") return proxyCatalog(request, response, "/api/search");
    if (path.startsWith("collections/")) return proxyCollection(path, request, response);

    const archive = path.endsWith("/archive");
    const hashOnly = path.endsWith("/hash");
    const encodedId = path.replace(/\/(archive|hash)$/, "");
    const skill = decodeSkillId(encodedId);
    if (archive) return proxyArchive(skill, response);

    const detail = await buildDetail(skill);
    return sendJson(response, 200, hashOnly ? {
      id: detail.id,
      contentHash: detail.contentHash,
      updatedAt: detail.updatedAt,
      auditStatus: detail.auditStatus,
    } : detail, CACHE_SECONDS);
  } catch (error) {
    const status = Number(error?.statusCode) || 502;
    return sendJson(response, status, { error: safeError(error) });
  }
}

async function proxyCatalog(request, response, upstreamPath) {
  const query = firstQuery(request.query.q).trim().slice(0, 200);
  if (!query) return sendJson(response, 400, { error: "缺少搜索词" });
  return proxyJson(`${SKILLS_ORIGIN}${upstreamPath}?q=${encodeURIComponent(query)}&limit=100`, response);
}

async function proxyCollection(path, request, response) {
  const type = path.substring("collections/".length);
  const upstreamType = { popular: "all-time", trending: "trending", hot: "hot", official: "all-time" }[type];
  if (!upstreamType) return sendJson(response, 404, { error: "目录类型不存在" });
  const page = Math.max(1, Math.min(100, Number(firstQuery(request.query.cursor)) || 1));
  const upstream = await fetchJson(`${SKILLS_ORIGIN}/api/skills/${upstreamType}/${page}`);
  if (type === "official" && Array.isArray(upstream.skills)) {
    upstream.skills = upstream.skills.filter((item) => item?.isOfficial === true);
  }
  return sendJson(response, 200, upstream, CACHE_SECONDS);
}

async function proxyJson(url, response) {
  const payload = await fetchJson(url);
  return sendJson(response, 200, payload, CACHE_SECONDS);
}

async function buildDetail(skill) {
  const protectedDetail = await fetchProtectedDetail(skill).catch(() => null);
  const publicAudit = protectedDetail ? null : await fetchPublicAudit(skill).catch(() => null);
  const archive = await downloadArchive(skill.repository);
  const files = selectSkillFiles(archive, skill.skillName);
  const manifest = files.map(({ path, bytes }) => ({
    path,
    size: bytes.byteLength,
    sha256: sha256(bytes),
    text: isTextPath(path),
  })).sort((left, right) => left.path.localeCompare(right.path));
  const contentHash = treeHash(manifest);
  const markdown = new TextDecoder("utf-8", { fatal: true }).decode(files.find((file) => file.path === "SKILL.md").bytes);
  const frontmatter = parseFrontmatter(markdown);
  const auditSource = protectedDetail ?? publicAudit;
  const auditStatus = normalizeAuditStatus(auditSource);
  return {
    id: encodeSkillId(skill),
    source: skill.repository,
    skillId: skill.skillName,
    name: frontmatter.name || skill.skillName,
    description: frontmatter.description || "第三方 Skill",
    sourceRepository: `https://github.com/${skill.repository}`,
    sourcePath: files.sourcePath,
    contentHash,
    files: manifest,
    auditStatus,
    audit: protectedDetail?.audit ?? protectedDetail?.security ?? publicAudit?.audit ?? null,
    updatedAt: protectedDetail?.updatedAt != null || protectedDetail?.updated_at != null
      ? String(protectedDetail?.updatedAt ?? protectedDetail?.updated_at)
      : null,
  };
}

/** skills.sh 公开详情页包含各维护者的安全审计结果，受保护 JSON 不可用时从页面降级读取。 */
async function fetchPublicAudit(skill) {
  const [owner, repository] = skill.repository.split("/");
  const response = await fetchWithTimeout(
    `${SKILLS_ORIGIN}/${encodeURIComponent(owner)}/${encodeURIComponent(repository)}/${encodeURIComponent(skill.skillName)}`,
    { headers: { Accept: "text/html" } },
  );
  if (!response.ok) throw httpError(response.status, "skills.sh 详情页请求失败");
  return parsePublicAudit(await response.text());
}

function parsePublicAudit(html) {
  const marker = [html.indexOf(">Security Audits<"), html.indexOf('"children":"Security Audits"')]
    .filter((index) => index >= 0).sort((left, right) => left - right)[0] ?? -1;
  if (marker < 0) return null;
  const section = html.slice(marker, marker + 40_000);
  const pass = countOccurrences(section, ">Pass<") || countOccurrences(section, '"children":"Pass"');
  const warn = countOccurrences(section, ">Warn<") + countOccurrences(section, ">Warning<") ||
    countOccurrences(section, '"children":"Warn"') + countOccurrences(section, '"children":"Warning"');
  const fail = countOccurrences(section, ">Fail<") || countOccurrences(section, '"children":"Fail"');
  const status = fail > 0 ? "FAIL" : warn > 0 ? "WARN" : pass > 0 ? "PASS" : "UNVERIFIED";
  return { status, audit: { source: "skills.sh", pass, warn, fail } };
}

async function fetchProtectedDetail(skill) {
  const token = await getVercelOidcToken();
  const [owner, repository] = skill.repository.split("/");
  return fetchJson(
    `${SKILLS_ORIGIN}/api/skill/${encodeURIComponent(owner)}/${encodeURIComponent(repository)}/${encodeURIComponent(skill.skillName)}`,
    {
      Authorization: `Bearer ${token}`,
      "x-vercel-oidc-token": token,
    },
  );
}

async function proxyArchive(skill, response) {
  // Vercel 函数响应体有平台上限。这里只签发到已校验 GitHub 仓库的临时跳转，
  // Android 下载端继续按实际字节执行 100 MB 限制，不允许客户端传入任意 URL。
  response.status(307);
  response.setHeader("Location", `${GITHUB_ARCHIVE_ORIGIN}/${skill.repository}/zip/HEAD`);
  response.setHeader("Cache-Control", `public, s-maxage=${CACHE_SECONDS}, stale-while-revalidate=3600`);
  return response.end();
}

async function downloadArchive(repository) {
  const upstream = await fetchWithTimeout(`${GITHUB_ARCHIVE_ORIGIN}/${repository}/zip/HEAD`, { headers: githubHeaders() });
  if (!upstream.ok) throw httpError(upstream.status, "GitHub 压缩包下载失败");
  const declared = Number(upstream.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > MAX_ARCHIVE_BYTES) throw httpError(413, "仓库压缩包超过 100 MB");
  const bytes = new Uint8Array(await upstream.arrayBuffer());
  if (bytes.byteLength > MAX_ARCHIVE_BYTES) throw httpError(413, "仓库压缩包超过 100 MB");
  return unzipSync(bytes);
}

function selectSkillFiles(archive, skillName) {
  const entries = Object.entries(archive).filter(([path]) => !path.endsWith("/") && safeArchivePath(path));
  const candidates = entries.filter(([path]) => path.endsWith("/SKILL.md") || path === "SKILL.md");
  const normalized = normalizeName(skillName);
  const matches = candidates.filter(([path]) => normalizeName(path.split("/").at(-2) ?? "") === normalized);
  const selected = matches.length === 1 ? matches[0] : candidates.find(([, bytes]) => {
    if (bytes.byteLength > 1024 * 1024) return false;
    try { return normalizeName(parseFrontmatter(new TextDecoder().decode(bytes)).name ?? "") === normalized; } catch { return false; }
  });
  if (!selected) throw httpError(404, "来源仓库中找不到该 Skill");
  const root = selected[0].slice(0, -"SKILL.md".length);
  const files = entries.filter(([path]) => path.startsWith(root)).map(([path, bytes]) => ({ path: path.slice(root.length), bytes }));
  if (files.length > MAX_FILES) throw httpError(413, "Skill 文件数超过 1000");
  const total = files.reduce((sum, file) => sum + file.bytes.byteLength, 0);
  if (total > MAX_ARCHIVE_BYTES) throw httpError(413, "Skill 解压后超过 100 MB");
  files.sourcePath = root.split("/").slice(1).join("/").replace(/\/$/, "") || ".";
  return files;
}

function decodeSkillId(encoded) {
  let decoded;
  try { decoded = Buffer.from(encoded, "base64url").toString("utf8"); } catch { throw httpError(400, "Skill ID 无效"); }
  const separator = decoded.lastIndexOf("#");
  const repository = decoded.slice(0, separator);
  const skillName = decoded.slice(separator + 1);
  if (!safeRepository(repository) || !safeSegment(skillName)) throw httpError(400, "Skill ID 无效");
  return { repository, skillName };
}

function encodeSkillId(skill) {
  return Buffer.from(`${skill.repository}#${skill.skillName}`, "utf8").toString("base64url");
}

function safeRepository(value) {
  const parts = value.split("/");
  return parts.length === 2 && parts.every(safeSegment);
}

function safeSegment(value) {
  return value.length > 0 && value.length <= 100 && /^[A-Za-z0-9_.-]+$/.test(value) && value !== "." && value !== "..";
}

function safeArchivePath(path) {
  const normalized = path.replaceAll("\\", "/");
  return !normalized.startsWith("/") && !normalized.split("/").includes("..");
}

function parseFrontmatter(markdown) {
  const lines = markdown.split(/\r?\n/);
  if (lines[0]?.trim() !== "---") return {};
  const result = {};
  for (let index = 1; index < lines.length && lines[index].trim() !== "---"; index += 1) {
    const separator = lines[index].indexOf(":");
    if (separator > 0 && !/^\s/.test(lines[index])) {
      result[lines[index].slice(0, separator).trim()] = lines[index].slice(separator + 1).trim().replace(/^['"]|['"]$/g, "");
    }
  }
  return result;
}

function treeHash(manifest) {
  const hash = createHash("sha256");
  for (const entry of manifest) hash.update(entry.path).update("\0").update(entry.sha256).update("\0");
  return hash.digest("hex");
}

function sha256(bytes) { return createHash("sha256").update(bytes).digest("hex"); }
function normalizeName(value) { return value.toLowerCase().replaceAll("_", "-").replaceAll(" ", "-"); }
function isTextPath(path) { return ["md","txt","json","yaml","yml","toml","xml","csv","tsv","kt","kts","java","js","ts","tsx","jsx","py","rb","sh","ps1","html","css","sql","ini","cfg","conf","properties"].includes(path.split(".").at(-1)?.toLowerCase()); }
function normalizeAuditStatus(detail) {
  const raw = JSON.stringify(detail ?? {}).toLowerCase();
  if (/\"(status|result)\":\"fail(ed)?\"/.test(raw)) return "FAIL";
  if (/\"(status|result)\":\"warn(ing)?\"/.test(raw)) return "WARN";
  if (/\"(status|result)\":\"pass(ed)?\"/.test(raw)) return "PASS";
  return "UNVERIFIED";
}

function countOccurrences(value, needle) {
  let count = 0;
  let index = 0;
  while ((index = value.indexOf(needle, index)) >= 0) { count += 1; index += needle.length; }
  return count;
}

function allowRequest(request, now = Date.now()) {
  const forwarded = firstQuery(request.headers?.["x-forwarded-for"]).split(",")[0].trim().slice(0, 80) || "unknown";
  const current = rateWindows.get(forwarded);
  if (!current || now - current.startedAt >= RATE_LIMIT_WINDOW_MS) {
    if (rateWindows.size > 10_000) {
      for (const [key, window] of rateWindows) if (now - window.startedAt >= RATE_LIMIT_WINDOW_MS) rateWindows.delete(key);
    }
    rateWindows.set(forwarded, { startedAt: now, count: 1 });
    return true;
  }
  current.count += 1;
  return current.count <= RATE_LIMIT_REQUESTS;
}

async function fetchJson(url, headers = {}) {
  const upstream = await fetchWithTimeout(url, { headers: { Accept: "application/json", ...headers } });
  if (!upstream.ok) throw httpError(upstream.status, `上游请求失败：HTTP ${upstream.status}`);
  return upstream.json();
}

async function fetchWithTimeout(url, init) {
  return fetch(url, { ...init, signal: AbortSignal.timeout(20_000) });
}

function githubHeaders() {
  const headers = { "User-Agent": "EveryTalk-Skill-Proxy" };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  return headers;
}

function sendJson(response, status, body, cacheSeconds = 0) {
  response.status(status).setHeader("Content-Type", "application/json; charset=utf-8");
  response.setHeader("Cache-Control", cacheSeconds > 0 ? `public, s-maxage=${cacheSeconds}, stale-while-revalidate=3600` : "no-store");
  return response.end(JSON.stringify(body));
}

function httpError(statusCode, message) { return Object.assign(new Error(message), { statusCode }); }
function safeError(error) { return error instanceof Error ? error.message.slice(0, 300) : "转发服务暂时不可用"; }
function firstQuery(value) { return String(Array.isArray(value) ? value[0] ?? "" : value ?? ""); }

export const __test = { decodeSkillId, encodeSkillId, safeRepository, selectSkillFiles, treeHash, firstQuery, parsePublicAudit, allowRequest };
