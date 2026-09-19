import test from "node:test";
import assert from "node:assert/strict";
import worker, { handleRequest } from "../src/index.js";

const env = {
  GITHUB_MCP_OAUTH_CLIENT_ID: "client-id",
  GITHUB_MCP_OAUTH_CLIENT_SECRET: "secret",
};

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
