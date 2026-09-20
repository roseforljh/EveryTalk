import test from "node:test";
import assert from "node:assert/strict";
import worker, { handleRequest } from "../src/index.js";

const env = {
  GITHUB_MCP_OAUTH_CLIENT_ID: "client-id",
  GITHUB_MCP_OAUTH_CLIENT_SECRET: "secret",
  GMAIL_MCP_OAUTH_CLIENT_ID: "google-client-id",
  GMAIL_MCP_OAUTH_CLIENT_SECRET: "google-secret",
};

test("Gmail callback forwards only safe fields and pins the callback route", async () => {
  const response = await handleRequest(new Request(
    "https://oauth.everytalk.cc/oauth/mcp/gmail?code=c&state=s&access_token=leak",
  ), env);
  const body = await response.text();
  assert.equal(response.status, 200);
  assert.match(body, /everytalk:\/\/oauth\/mcp\/gmail/);
  assert.match(body, /code=c/);
  assert.match(body, /state=s/);
  assert.doesNotMatch(body, /access_token/);
});

test("Gmail OAuth exchange validates redirect and forwards only Google token fields", async () => {
  const originalFetch = globalThis.fetch;
  let upstreamCalls = 0;
  globalThis.fetch = async (url, options) => {
    upstreamCalls++;
    assert.equal(url, "https://oauth2.googleapis.com/token");
    const form = new URLSearchParams(options.body);
    assert.equal(form.get("client_secret"), "google-secret");
    assert.equal(form.get("redirect_uri"), "https://oauth.everytalk.cc/oauth/mcp/gmail");
    return Response.json({ access_token: "google-access", refresh_token: "google-refresh", expires_in: 3600, token_type: "Bearer", id_token: "must-not-return" });
  };
  try {
    const response = await handleRequest(new Request("https://oauth.everytalk.cc/oauth/mcp/gmail/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code", client_id: "google-client-id", code: "code",
        code_verifier: "v".repeat(43), redirect_uri: "https://oauth.everytalk.cc/oauth/mcp/gmail",
      }),
    }), env);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
      access_token: "google-access", refresh_token: "google-refresh", expires_in: 3600, token_type: "Bearer",
    });
    assert.equal(upstreamCalls, 1);
  } finally { globalThis.fetch = originalFetch; }
});

test("Gmail MCP requires bearer auth and exposes safe tool metadata", async () => {
  const unauthorized = await handleRequest(new Request("https://oauth.everytalk.cc/mcp/gmail", {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: {} }),
  }), env);
  assert.equal(unauthorized.status, 401);

  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    assert.equal(url, "https://gmail.googleapis.com/gmail/v1/users/me/profile");
    return Response.json({ emailAddress: "test@example.com", messagesTotal: 1 });
  };
  try {
    const response = await handleRequest(new Request("https://oauth.everytalk.cc/mcp/gmail", {
      method: "POST",
      headers: { authorization: "Bearer google-access-token", "content-type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 2, method: "initialize", params: { protocolVersion: "2025-03-26" } }),
    }), env);
    assert.equal(response.status, 200);
    assert.match(await response.text(), /EveryTalk Gmail/);
  } finally { globalThis.fetch = originalFetch; }
});

test("Gmail MCP sends a validated plain-text message through Gmail API", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    assert.equal(url, "https://gmail.googleapis.com/gmail/v1/users/me/messages/send");
    assert.equal(options.headers.authorization, "Bearer google-access-token");
    const request = JSON.parse(options.body);
    assert.equal(typeof request.raw, "string");
    const mime = Buffer.from(request.raw, "base64url").toString("utf8");
    assert.match(mime, /To: user@example.com\r\n/);
    const subjectWord = /Subject: =\?UTF-8\?B\?([^?]+)\?=/.exec(mime)[1];
    assert.equal(Buffer.from(subjectWord, "base64").toString("utf8"), "测试");
    assert.equal(Buffer.from(mime.split("\r\n\r\n")[1], "base64").toString("utf8"), "正文");
    return Response.json({ id: "sent-1" });
  };
  try {
    const response = await handleRequest(new Request("https://oauth.everytalk.cc/mcp/gmail", {
      method: "POST",
      headers: { authorization: "Bearer google-access-token", "content-type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 3, method: "tools/call", params: {
        name: "send_email", arguments: { to: "user@example.com", subject: "测试", body: "正文" },
      } }),
    }), env);
    assert.equal(response.status, 200);
    assert.match(await response.text(), /sent-1/);
  } finally { globalThis.fetch = originalFetch; }
});

async function gmailRequest(method, params = {}, overrides = {}) {
  return worker.fetch(new Request("https://oauth.everytalk.cc/mcp/gmail", {
    method: "POST", headers: { authorization: "Bearer google-access-token", "content-type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 9, method, params, ...overrides }),
  }), env);
}

async function rpcPayload(response) {
  assert.equal(response.status, 200);
  const body = await response.text();
  return JSON.parse(body.split("data: ")[1].trim());
}

test("Gmail MCP negotiates protocol, lists tools, handles notifications and invalid requests", async () => {
  const payload = await rpcPayload(await gmailRequest("tools/list"));
  assert.deepEqual(payload.result.tools.map(tool => tool.name), ["search_emails", "read_email", "send_email", "list_labels", "modify_email"]);
  assert.equal(payload.result.tools.find(tool => tool.name === "send_email").annotations.idempotentHint, false);
  assert.equal((await gmailRequest("notifications/initialized", {}, { id: undefined })).status, 202);
  assert.deepEqual((await rpcPayload(await gmailRequest("ping"))).result, {});
  assert.equal((await rpcPayload(await gmailRequest("missing"))).error.code, -32601);
  assert.equal((await rpcPayload(await gmailRequest(123))).error.code, -32600);
  assert.equal((await rpcPayload(await gmailRequest("ping", {}, { jsonrpc: "1.0" }))).error.code, -32600);
  const tooLarge = await worker.fetch(new Request("https://oauth.everytalk.cc/mcp/gmail", {
    method: "POST", headers: { authorization: "Bearer google-access-token", "content-type": "application/json" }, body: "x".repeat(512 * 1024 + 1),
  }), env);
  assert.equal(tooLarge.status, 413);
});

test("Gmail tools preserve pagination, decode nested Unicode mail, and modify labels", async () => {
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (rawUrl, options) => {
    const url = new URL(rawUrl);
    assert.equal(url.origin, "https://gmail.googleapis.com");
    assert.equal(options.redirect, "manual");
    assert.equal(options.headers.authorization, "Bearer google-access-token");
    calls.push(url.pathname);
    if (url.pathname.endsWith("/messages")) {
      assert.equal(url.searchParams.get("q"), "from:test@example.com & is:unread");
      assert.equal(url.searchParams.get("pageToken"), "next&page");
      assert.equal(url.searchParams.get("maxResults"), "7");
      return Response.json({ messages: [{ id: "abc" }], nextPageToken: "next" });
    }
    if (url.pathname.endsWith("/messages/abc")) {
      return Response.json({ id: "abc", payload: { mimeType: "multipart/mixed", parts: [
        { mimeType: "text/plain", body: { data: Buffer.from("中文正文\n第二行").toString("base64url") } },
        { mimeType: "application/pdf", filename: "test.pdf", body: { attachmentId: "attachment", size: 50 } },
      ] } });
    }
    if (url.pathname.endsWith("/modify")) {
      assert.deepEqual(JSON.parse(options.body), { addLabelIds: ["STARRED"], removeLabelIds: ["UNREAD"] });
      return Response.json({ id: "abc", labelIds: ["STARRED"] });
    }
    if (url.pathname.endsWith("/labels")) return Response.json({ labels: [{ id: "INBOX" }] });
    throw new Error("unexpected request");
  };
  try {
    const search = await rpcPayload(await gmailRequest("tools/call", { name: "search_emails", arguments: { query: "from:test@example.com & is:unread", pageToken: "next&page", maxResults: 7 } }));
    assert.equal(JSON.parse(search.result.content[0].text).nextPageToken, "next");
    const read = await rpcPayload(await gmailRequest("tools/call", { name: "read_email", arguments: { messageId: "abc" } }));
    const mail = JSON.parse(read.result.content[0].text);
    assert.equal(mail.bodies[0].text, "中文正文\n第二行");
    assert.equal(mail.attachments[0].filename, "test.pdf");
    for (const [name, args] of [["modify_email", { messageId: "abc", addLabelIds: ["STARRED"], removeLabelIds: ["UNREAD"] }], ["list_labels", {}]]) {
      assert.equal((await rpcPayload(await gmailRequest("tools/call", { name, arguments: args }))).result.isError, undefined);
    }
    assert.equal(calls.length, 4);
  } finally { globalThis.fetch = originalFetch; }
});

test("Gmail rejects injection and malformed tool arguments before contacting Google", async () => {
  const originalFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => { calls++; throw new Error("unexpected"); };
  try {
    for (const [name, args] of [
      ["send_email", { to: "a@example.com\r\nBcc: hidden@example.com", subject: "test", body: "body" }],
      ["send_email", { to: "a@example.com", subject: "test\nBcc: b@example.com", body: "body" }],
      ["send_email", { to: "a@example.com", cc: 123, subject: "test", body: "body" }],
      ["read_email", { messageId: "../profile" }],
      ["modify_email", { messageId: "abc", addLabelIds: [false] }],
      ["modify_email", { messageId: "abc" }],
      ["modify_email", { messageId: "abc", addLabelIds: ["STARRED"], removeLabelIds: ["STARRED"] }],
      ["search_emails", { query: "test", maxResults: "oops" }],
    ]) {
      const payload = await rpcPayload(await gmailRequest("tools/call", { name, arguments: args }));
      assert.equal(payload.result.isError, true);
    }
    assert.equal(calls, 0);
  } finally { globalThis.fetch = originalFetch; }
});

test("Gmail initialization rejects expired tokens and tool errors never leak upstream response", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => Response.json({ error: "secret-mail-and-token" }, { status: 401 });
  try {
    const initialized = await gmailRequest("initialize", { protocolVersion: "2025-03-26" });
    assert.equal(initialized.status, 401);
    const response = await gmailRequest("tools/call", { name: "list_labels" });
    const text = await response.text();
    assert.match(text, /isError/);
    assert.match(text, /401/);
    assert.doesNotMatch(text, /secret-mail-and-token/);
  } finally { globalThis.fetch = originalFetch; }
});

test("Google token refresh never retries, pins client and callback, and reports missing credentials", async () => {
  const originalFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async (url, options) => {
    calls++;
    assert.equal(url, "https://oauth2.googleapis.com/token");
    assert.equal(options.redirect, "manual");
    assert.equal(options.body.get("refresh_token"), "refresh");
    assert.equal(options.body.get("client_secret"), "google-secret");
    return Response.json({ access_token: "new-token", expires_in: 3600 });
  };
  const request = params => new Request("https://oauth.everytalk.cc/oauth/mcp/gmail/token", {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: new URLSearchParams(params),
  });
  const form = { grant_type: "refresh_token", client_id: "google-client-id", refresh_token: "refresh" };
  try {
    assert.equal((await worker.fetch(request(form), {})).status, 503);
    assert.equal((await worker.fetch(request({ ...form, client_id: "wrong" }), env)).status, 400);
    assert.equal((await worker.fetch(request({ grant_type: "authorization_code", client_id: "google-client-id", code: "code", code_verifier: "v".repeat(43), redirect_uri: "https://evil.example" }), env)).status, 400);
    assert.equal((await worker.fetch(request(form), env)).status, 200);
    assert.equal(calls, 1);
    globalThis.fetch = async () => { calls++; throw new Error("secret should not leak"); };
    const failed = await worker.fetch(request(form), env);
    assert.equal(failed.status, 502);
    assert.equal(calls, 2);
    assert.doesNotMatch(await failed.text(), /secret should/);
  } finally { globalThis.fetch = originalFetch; }
});

test("keeps asset links for both Android variants", async () => {
  const response = await handleRequest(
    new Request("https://oauth.everytalk.cc/.well-known/assetlinks.json"),
    env,
  );
  assert.equal(response.status, 200);
  const links = await response.json();
  assert.equal(links.length, 2);
  for (const link of links) {
    assert.match(link.target.sha256_cert_fingerprints[0], /^(?:[0-9A-F]{2}:){31}[0-9A-F]{2}$/);
  }
});

test("preserves legacy Computer nonce and rejects duplicate callback state", async () => {
  const response = await worker.fetch(new Request("https://oauth.everytalk.cc/oauth/cloudflare?state=s&nonce=n&code=c"), env);
  assert.match(await response.text(), /nonce=n/);
  const invalid = await worker.fetch(new Request("https://oauth.everytalk.cc/oauth/mcp/github?state=a&state=b"), env);
  assert.equal(invalid.status, 400);
});

test("bounds form bodies and rejects wrong media type and duplicate grants", async () => {
  for (const [body, type, status] of [
    ["x".repeat(33000), "application/x-www-form-urlencoded", 413],
    ["{}", "application/json", 415],
    ["grant_type=refresh_token&grant_type=authorization_code", "application/x-www-form-urlencoded", 400],
  ]) {
    const response = await worker.fetch(new Request("https://oauth.everytalk.cc/oauth/mcp/github/token", {
      method: "POST", headers: { "content-type": type }, body,
    }), env);
    assert.equal(response.status, status);
  }
});

test("code exchange pins redirect URI and forwards PKCE to GitHub", async () => {
  const originalFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async (url, options) => {
    calls++;
    assert.equal(url, "https://github.com/login/oauth/access_token");
    assert.equal(options.redirect, "manual");
    assert.equal(options.body.get("code_verifier"), "v".repeat(43));
    assert.equal(options.body.get("redirect_uri"), "https://oauth.everytalk.cc/oauth/mcp/github");
    return Response.json({ access_token: "access", client_secret: "must-not-return" });
  };
  try {
    for (const [redirect, status] of [["https://evil.example", 400], ["https://oauth.everytalk.cc/oauth/mcp/github", 200]]) {
      const response = await worker.fetch(new Request("https://oauth.everytalk.cc/oauth/mcp/github/token", {
        method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ grant_type: "authorization_code", client_id: "client-id", code: "c", code_verifier: "v".repeat(43), redirect_uri: redirect }),
      }), env);
      assert.equal(response.status, status);
      assert.doesNotMatch(await response.text(), /must-not-return/);
    }
    assert.equal(calls, 1);
  } finally { globalThis.fetch = originalFetch; }
});

test("forwards only safe MCP callback fields to the app scheme", async () => {
  const response = await handleRequest(
    new Request("https://oauth.everytalk.cc/oauth/mcp/github?code=c&state=s&access_token=leak"),
    env,
  );
  const body = await response.text();
  assert.equal(response.status, 200);
  assert.match(body, /everytalk:\/\/oauth\/mcp\/github/);
  assert.match(body, /code=c/);
  assert.match(body, /state=s/);
  assert.doesNotMatch(body, /access_token/);
});

test("rejects callbacks without state", async () => {
  const response = await handleRequest(
    new Request("https://oauth.everytalk.cc/oauth/mcp/cloudflare?code=c"),
    env,
  );
  assert.equal(response.status, 400);
});

test("rejects GitHub token requests with a wrong client id", async () => {
  const response = await handleRequest(
    new Request("https://oauth.everytalk.cc/oauth/mcp/github/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "refresh_token",
        client_id: "wrong",
        refresh_token: "refresh",
      }),
    }),
    env,
  );
  assert.equal(response.status, 400);
});

test("does not call GitHub for malformed token request", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    throw new Error("unexpected upstream call");
  };
  try {
    const response = await handleRequest(
      new Request("https://oauth.everytalk.cc/oauth/mcp/github/token", {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          grant_type: "authorization_code",
          client_id: "client-id",
          code: "c",
        }),
      }),
      env,
    );
    assert.equal(response.status, 400);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("exchanges GitHub token without exposing the secret in the response", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (_url, options) => {
    assert.equal(options.method, "POST");
    const form = new URLSearchParams(options.body);
    assert.equal(form.get("client_secret"), "secret");
    return new Response(
      JSON.stringify({ access_token: "token", refresh_token: "refresh", expires_in: 28800 }),
      { status: 200 },
    );
  };
  try {
    const response = await handleRequest(
      new Request("https://oauth.everytalk.cc/oauth/mcp/github/token", {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          grant_type: "refresh_token",
          client_id: "client-id",
          refresh_token: "refresh",
        }),
      }),
      env,
    );
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
      access_token: "token",
      refresh_token: "refresh",
      expires_in: 28800,
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("maps GitHub upstream network failure to 502", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => { throw new Error("network timeout"); };
  try {
    const response = await handleRequest(new Request("https://oauth.everytalk.cc/oauth/mcp/github/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "refresh_token",
        client_id: "client-id",
        refresh_token: "refresh",
      }),
    }), env);
    assert.equal(response.status, 502);
    assert.deepEqual(await response.json(), { error: "upstream_unavailable" });
  } finally { globalThis.fetch = originalFetch; }
});
