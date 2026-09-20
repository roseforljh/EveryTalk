import test from "node:test";
import assert from "node:assert/strict";
import { handleRequest } from "../src/index.js";

test("Microsoft OAuth code exchange pins client, redirect and PKCE", async () => {
  const originalFetch = globalThis.fetch;
  let request;
  globalThis.fetch = async (input, init) => {
    request = { url: String(input), body: new URLSearchParams(init.body) };
    return new Response(JSON.stringify({ access_token: "microsoft-access", refresh_token: "microsoft-refresh", expires_in: 3600, token_type: "Bearer" }), { status: 200, headers: { "content-type": "application/json" } });
  };
  try {
    const response = await handleRequest(new Request("https://oauth.everytalk.cc/oauth/mcp/microsoft/token", {
      method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ client_id: "microsoft-client", grant_type: "authorization_code", code: "code", code_verifier: "v".repeat(43), redirect_uri: "https://oauth.everytalk.cc/oauth/mcp/microsoft" }),
    }), { MICROSOFT_MCP_OAUTH_CLIENT_ID: "microsoft-client", MICROSOFT_MCP_OAUTH_CLIENT_SECRET: "secret" });
    assert.equal(response.status, 200);
    assert.equal(request.url, "https://login.microsoftonline.com/common/oauth2/v2.0/token");
    assert.equal(request.body.get("client_secret"), "secret");
    assert.equal(request.body.get("redirect_uri"), "https://oauth.everytalk.cc/oauth/mcp/microsoft");
    assert.equal(request.body.get("code_verifier"), "v".repeat(43));
  } finally { globalThis.fetch = originalFetch; }
});
