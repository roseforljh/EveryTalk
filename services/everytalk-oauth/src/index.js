import { gmailMcp } from "./gmail-mcp.js";
import { microsoftMcp } from "./microsoft-mcp.js";
import { HttpError, json, readBoundedText, fetchWithoutRedirect } from "./http.js";
const WORKER_ORIGIN = "https://oauth.everytalk.cc";
const GITHUB_CALLBACK = "/oauth/mcp/github";
const CLOUDFLARE_CALLBACK = "/oauth/mcp/cloudflare";
const GMAIL_CALLBACK = "/oauth/mcp/gmail";
const GITHUB_TOKEN = "/oauth/mcp/github/token";
const GMAIL_TOKEN = "/oauth/mcp/gmail/token";
const GMAIL_MCP = "/mcp/gmail";
const MICROSOFT_CALLBACK = "/oauth/mcp/microsoft";
const MICROSOFT_TOKEN = "/oauth/mcp/microsoft/token";
const MICROSOFT_MCP = "/mcp/microsoft";
const GITHUB_REDIRECT_URI = WORKER_ORIGIN + GITHUB_CALLBACK;
const GMAIL_REDIRECT_URI = WORKER_ORIGIN + GMAIL_CALLBACK;
const GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
const MICROSOFT_TOKEN_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/token";
const MICROSOFT_REDIRECT_URI = WORKER_ORIGIN + MICROSOFT_CALLBACK;
const MAX_BODY_BYTES = 32 * 1024;
const MAX_QUERY_CHARS = 16 * 1024;
const ALLOWED_CALLBACK_FIELDS = new Set(["code", "state", "error", "error_description", "iss", "nonce"]);

export default {
  async fetch(request, env) {
    try {
      return await handleRequest(request, env);
    } catch (error) {
      if (error instanceof HttpError) return json(error.status, { error: error.message });
      console.error("oauth worker request failed", safeError(error));
      return json(500, { error: "internal_error" });
    }
  },
};

export async function handleRequest(request, env) {
  const url = new URL(request.url);
  if (url.pathname === "/.well-known/assetlinks.json") {
    return new Response(JSON.stringify(assetLinks()), {
      headers: {
        "content-type": "application/json; charset=utf-8",
        "cache-control": "public, max-age=3600",
      },
    });
  }
  if (url.pathname === "/oauth/cloudflare") {
    return callbackRedirect(request, "/oauth/cloudflare", "everytalk://oauth/cloudflare");
  }
  if (url.pathname === GITHUB_CALLBACK) {
    return callbackRedirect(request, GITHUB_CALLBACK, "everytalk://oauth/mcp/github");
  }
  if (url.pathname === GMAIL_CALLBACK) {
    return callbackRedirect(request, GMAIL_CALLBACK, "everytalk://oauth/mcp/gmail");
  }
  if (url.pathname === MICROSOFT_CALLBACK) return callbackRedirect(request, MICROSOFT_CALLBACK, "everytalk://oauth/mcp/microsoft");
  if (url.pathname === CLOUDFLARE_CALLBACK) {
    return callbackRedirect(request, CLOUDFLARE_CALLBACK, "everytalk://oauth/mcp/cloudflare");
  }
  if (url.pathname === GITHUB_TOKEN) return githubToken(request, env);
  if (url.pathname === GMAIL_TOKEN) return gmailToken(request, env);
  if (url.pathname === GMAIL_MCP) return gmailMcp(request);
  if (url.pathname === MICROSOFT_TOKEN) return microsoftToken(request, env);
  if (url.pathname === MICROSOFT_MCP) return microsoftMcp(request);
  return json(404, { error: "not_found" });
}

function callbackRedirect(request, expectedPath, appRedirect) {
  const url = new URL(request.url);
  if (request.method !== "GET" || url.pathname !== expectedPath) {
    return json(405, { error: "method_not_allowed" }, { allow: "GET" });
  }
  if (url.search.length > MAX_QUERY_CHARS) return json(400, { error: "query_too_large" });
  const keys = [...url.searchParams.keys()];
  if (new Set(keys).size !== keys.length) return json(400, { error: "duplicate_parameter" });
  const target = new URL(appRedirect);
  for (const [key, value] of url.searchParams) {
    if (ALLOWED_CALLBACK_FIELDS.has(key)) target.searchParams.set(key, value);
  }
  if (!target.searchParams.get("state")) return html(400, "<h1>OAuth state missing</h1>");
  const location = target.toString();
  return new Response(
    "<!doctype html><meta charset=\"utf-8\"><title>EveryTalk</title>" +
      "<p>正在返回 EveryTalk…</p><script>location.replace(" +
      JSON.stringify(location) + ")</script>",
    {
      status: 200,
      headers: {
        "content-type": "text/html; charset=utf-8",
        "cache-control": "no-store",
        "x-content-type-options": "nosniff",
      },
    },
  );
}

async function githubToken(request, env) {
  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: corsHeaders() });
  }
  if (request.method !== "POST") {
    return json(405, { error: "method_not_allowed" }, { allow: "POST, OPTIONS" });
  }
  if (!env.GITHUB_MCP_OAUTH_CLIENT_ID || !env.GITHUB_MCP_OAUTH_CLIENT_SECRET) {
    return json(503, { error: "service_unavailable" });
  }
  const body = await readForm(request);
  const grantType = body.get("grant_type");
  if (grantType !== "authorization_code" && grantType !== "refresh_token") {
    return json(400, { error: "unsupported_grant_type" });
  }
  if (body.get("client_id") !== env.GITHUB_MCP_OAUTH_CLIENT_ID) {
    return json(400, { error: "invalid_client" });
  }
  const upstream = new URLSearchParams();
  upstream.set("client_id", env.GITHUB_MCP_OAUTH_CLIENT_ID);
  upstream.set("client_secret", env.GITHUB_MCP_OAUTH_CLIENT_SECRET);
  upstream.set("grant_type", grantType);
  if (grantType === "authorization_code") {
    for (const name of ["code", "redirect_uri", "code_verifier"]) {
      const value = body.get(name);
      if (!value || value.length > 4096) return json(400, { error: "missing_" + name });
      upstream.set(name, value);
    }
    if (!/^[A-Za-z0-9._~-]{43,128}$/.test(body.get("code_verifier"))) return json(400, { error: "invalid_verifier" });
    if (body.get("redirect_uri") !== GITHUB_REDIRECT_URI) {
      return json(400, { error: "invalid_redirect_uri" });
    }
  } else {
    const refreshToken = body.get("refresh_token");
    if (!refreshToken || refreshToken.length > 16 * 1024) {
      return json(400, { error: "missing_refresh_token" });
    }
    upstream.set("refresh_token", refreshToken);
  }
  let response;
  let lastError;
  // GitHub 可能会短暂重置 OAuth 连接；只重试一次，避免授权码交换被长时间阻塞。
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      response = await fetch("https://github.com/login/oauth/access_token", {
        method: "POST",
        // 先手动检查重定向，避免把 client_secret 带到其他域名。
        redirect: "manual",
        signal: AbortSignal.timeout(25_000),
        headers: {
          accept: "application/json",
          "content-type": "application/x-www-form-urlencoded",
          "user-agent": "EveryTalk-OAuth/1.0 (+https://oauth.everytalk.cc)",
        },
        body: upstream,
      });
      break;
    } catch (error) {
      lastError = error;
      if (attempt === 0) await new Promise((resolve) => setTimeout(resolve, 250));
    }
  }
  if (!response) {
    console.error("GitHub OAuth upstream unavailable", safeError(lastError));
    return json(502, { error: "upstream_unavailable" });
  }
  if (response.status >= 300 && response.status < 400) {
    const location = response.headers.get("location");
    let redirect;
    try { redirect = location ? new URL(location, "https://github.com/login/oauth/access_token") : null; }
    catch { redirect = null; }
    if (!redirect || redirect.origin !== "https://github.com" ||
        redirect.pathname !== "/login/oauth/access_token") {
      return json(502, { error: "upstream_redirect_rejected" });
    }
    try {
      response = await fetchWithoutRedirect(redirect, {
        method: "POST",
          signal: AbortSignal.timeout(25_000),
        headers: {
          accept: "application/json",
          "content-type": "application/x-www-form-urlencoded",
          "user-agent": "EveryTalk-OAuth/1.0 (+https://oauth.everytalk.cc)",
        },
        body: upstream,
      });
    } catch (error) {
      console.error("GitHub OAuth redirected upstream unavailable", safeError(error));
      return json(502, { error: "upstream_unavailable" });
    }
  }
  const text = await readBoundedText(response);
  if (text.length > MAX_BODY_BYTES) return json(502, { error: "upstream_response_too_large" });
  let payload;
  try {
    payload = JSON.parse(text);
  } catch {
    return json(502, { error: "invalid_upstream_response" });
  }
  // 仅转发协议字段，避免 GitHub 诊断正文或未知字段回显凭据。
  if (!response.ok || payload.error) return json(400, { error: "authorization_failed" });
  if (typeof payload.access_token !== "string" || !payload.access_token) return json(502, { error: "invalid_token_response" });
  const result = {};
  for (const key of ["access_token", "refresh_token", "expires_in", "refresh_token_expires_in", "scope", "token_type"]) {
    if (payload[key] !== undefined) result[key] = payload[key];
  }
  return new Response(JSON.stringify(result), {
    status: response.ok ? 200 : 400,
    headers: {
      ...corsHeaders(),
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

/** Google OAuth code/refresh 交换。client secret 只存在 Worker Secret，不进入 APK。 */
async function gmailToken(request, env) {
  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: corsHeaders() });
  }
  if (request.method !== "POST") {
    return json(405, { error: "method_not_allowed" }, { allow: "POST, OPTIONS" });
  }
  if (!env.GMAIL_MCP_OAUTH_CLIENT_ID || !env.GMAIL_MCP_OAUTH_CLIENT_SECRET) {
    return json(503, { error: "service_unavailable" });
  }

  const body = await readForm(request);
  const grantType = body.get("grant_type");
  if (grantType !== "authorization_code" && grantType !== "refresh_token") {
    return json(400, { error: "unsupported_grant_type" });
  }
  if (body.get("client_id") !== env.GMAIL_MCP_OAUTH_CLIENT_ID) {
    return json(400, { error: "invalid_client" });
  }

  const upstream = new URLSearchParams({
    client_id: env.GMAIL_MCP_OAUTH_CLIENT_ID,
    client_secret: env.GMAIL_MCP_OAUTH_CLIENT_SECRET,
    grant_type: grantType,
  });
  if (grantType === "authorization_code") {
    const code = body.get("code");
    const verifier = body.get("code_verifier");
    if (!code || code.length > 4096 || !verifier || !/^[A-Za-z0-9._~-]{43,128}$/.test(verifier)) {
      return json(400, { error: "invalid_authorization_request" });
    }
    if (body.get("redirect_uri") !== GMAIL_REDIRECT_URI) {
      return json(400, { error: "invalid_redirect_uri" });
    }
    upstream.set("code", code);
    upstream.set("redirect_uri", GMAIL_REDIRECT_URI);
    upstream.set("code_verifier", verifier);
  } else {
    const refreshToken = body.get("refresh_token");
    if (!refreshToken || refreshToken.length > 16 * 1024) {
      return json(400, { error: "missing_refresh_token" });
    }
    upstream.set("refresh_token", refreshToken);
  }

  let response;
  try {
    response = await fetchWithoutRedirect(GOOGLE_TOKEN_ENDPOINT, {
      method: "POST",
      signal: AbortSignal.timeout(25_000),
      headers: {
        accept: "application/json",
        "content-type": "application/x-www-form-urlencoded",
        "user-agent": "EveryTalk-OAuth/1.0 (+https://oauth.everytalk.cc)",
      },
      body: upstream,
    });
  } catch (error) {
    console.error("Google OAuth upstream unavailable", safeError(error));
    return json(502, { error: "upstream_unavailable" });
  }

  const text = await readBoundedText(response);
  if (text.length > MAX_BODY_BYTES) return json(502, { error: "upstream_response_too_large" });
  let payload;
  try { payload = JSON.parse(text); }
  catch { return json(502, { error: "invalid_upstream_response" }); }
  if (!response.ok || payload.error) return json(400, { error: "authorization_failed" });
  if (typeof payload.access_token !== "string" || !payload.access_token) {
    return json(502, { error: "invalid_token_response" });
  }

  const result = {};
  for (const key of ["access_token", "refresh_token", "expires_in", "token_type"]) {
    if (payload[key] !== undefined) result[key] = payload[key];
  }
  return new Response(JSON.stringify(result), {
    status: 200,
    headers: { ...corsHeaders(), "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

/** Microsoft OAuth code/refresh 交换；Client Secret 仅保存在 Worker Secret。 */
async function microsoftToken(request, env) {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders() });
  if (request.method !== "POST") return json(405, { error: "method_not_allowed" }, { allow: "POST, OPTIONS" });
  if (!env.MICROSOFT_MCP_OAUTH_CLIENT_ID || !env.MICROSOFT_MCP_OAUTH_CLIENT_SECRET) return json(503, { error: "service_unavailable" });
  const body = await readForm(request);
  if (body.get("client_id") !== env.MICROSOFT_MCP_OAUTH_CLIENT_ID) return json(400, { error: "invalid_client" });
  const grantType = body.get("grant_type");
  if (grantType !== "authorization_code" && grantType !== "refresh_token") return json(400, { error: "unsupported_grant_type" });
  const upstream = new URLSearchParams({ client_id: env.MICROSOFT_MCP_OAUTH_CLIENT_ID, client_secret: env.MICROSOFT_MCP_OAUTH_CLIENT_SECRET, grant_type: grantType, scope: "offline_access https://graph.microsoft.com/Mail.ReadWrite https://graph.microsoft.com/Mail.Send" });
  if (grantType === "authorization_code") {
    const code = body.get("code"); const verifier = body.get("code_verifier");
    if (!code || code.length > 4096 || !verifier || !/^[A-Za-z0-9._~-]{43,128}$/.test(verifier) || body.get("redirect_uri") !== MICROSOFT_REDIRECT_URI) return json(400, { error: "invalid_authorization_request" });
    upstream.set("code", code); upstream.set("redirect_uri", MICROSOFT_REDIRECT_URI); upstream.set("code_verifier", verifier);
  } else {
    const refresh = body.get("refresh_token"); if (!refresh || refresh.length > 16 * 1024) return json(400, { error: "missing_refresh_token" }); upstream.set("refresh_token", refresh);
  }
  let response;
  try { response = await fetchWithoutRedirect(MICROSOFT_TOKEN_ENDPOINT, { method: "POST", signal: AbortSignal.timeout(25_000), headers: { accept: "application/json", "content-type": "application/x-www-form-urlencoded", "user-agent": "EveryTalk-OAuth/1.0 (+https://oauth.everytalk.cc)" }, body: upstream }); }
  catch (error) { console.error("Microsoft OAuth upstream unavailable", safeError(error)); return json(502, { error: "upstream_unavailable" }); }
  const text = await readBoundedText(response); let payload; try { payload = JSON.parse(text); } catch { return json(502, { error: "invalid_upstream_response" }); }
  if (!response.ok || payload.error) return json(400, { error: "authorization_failed" });
  if (typeof payload.access_token !== "string" || !payload.access_token) return json(502, { error: "invalid_token_response" });
  const result = {}; for (const key of ["access_token", "refresh_token", "expires_in", "token_type", "scope"]) if (payload[key] !== undefined) result[key] = payload[key];
  return new Response(JSON.stringify(result), { status: 200, headers: { ...corsHeaders(), "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
}

async function readForm(request) {
  const contentType = request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase();
  if (contentType !== "application/x-www-form-urlencoded") {
    throw new HttpError(415, "unsupported_media_type");
  }
  const length = Number(request.headers.get("content-length"));
  if (Number.isFinite(length) && length > MAX_BODY_BYTES) {
    throw new HttpError(413, "payload_too_large");
  }
  const text = await readBoundedText(request);
  if (new TextEncoder().encode(text).byteLength > MAX_BODY_BYTES) {
    throw new HttpError(413, "payload_too_large");
  }
  const form = new URLSearchParams(text);
  const keys = [...form.keys()];
  if (new Set(keys).size !== keys.length) throw new HttpError(400, "duplicate_parameter");
  return form;
}

function assetLinks() {
  return [
    {
      relation: ["delegate_permission/common.handle_all_urls"],
      target: {
        namespace: "android_app",
        package_name: "io.github.roseforljh.everytalk",
        sha256_cert_fingerprints: ["32:36:5E:5F:C6:0C:A9:0E:0B:2D:79:EA:01:35:09:6A:08:B0:91:30:B6:71:7B:51:AF:F0:94:2A:F2:BF:01:82"],
      },
    },
    {
      relation: ["delegate_permission/common.handle_all_urls"],
      target: {
        namespace: "android_app",
        package_name: "io.github.roseforljh.everytalk.debug",
        sha256_cert_fingerprints: ["C0:3B:26:31:18:46:76:F5:E0:23:6A:97:BB:DF:11:AA:BA:3C:9D:FC:17:68:0B:9B:92:F9:61:51:E0:9A:9D:16"],
      },
    },
  ];
}

function corsHeaders() {
  return {
    "access-control-allow-methods": "POST, OPTIONS",
    "access-control-allow-headers": "content-type",
  };
}
function html(status, body) {
  return new Response(body, {
    status,
    headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" },
  });
}
function safeError(error) {
  return error instanceof HttpError ? error.message : "unexpected";
}
