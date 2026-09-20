import { HttpError, json, readBoundedText, fetchWithoutRedirect } from "./http.js";

const GRAPH = "https://graph.microsoft.com/v1.0/me";

/** Microsoft Graph 邮件 MCP；Worker 不保存 Microsoft access token。 */
export async function microsoftMcp(request) {
  if (request.method !== "POST") return json(405, { error: "method_not_allowed" }, { allow: "POST" });
  const token = request.headers.get("authorization")?.match(/^Bearer ([A-Za-z0-9._~+/=-]{16,4096})$/)?.[1];
  if (!token) return json(401, { error: "unauthorized" }, { "www-authenticate": "Bearer" });
  if (request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase() !== "application/json") {
    return json(415, { error: "unsupported_media_type" });
  }
  let message;
  try { message = JSON.parse(await readBoundedText(request, 512 * 1024)); } catch { return json(400, { error: "invalid_json" }); }
  if (!message || Array.isArray(message) || message.jsonrpc !== "2.0" || typeof message.method !== "string") {
    return event({ jsonrpc: "2.0", id: null, error: { code: -32600, message: "Invalid Request" } });
  }
  if (message.id === undefined) return new Response(null, { status: 202 });
  const id = message.id ?? null;
  if (message.method === "initialize") {
    try { await graph(token, "/mailFolders/inbox?$top=1"); }
    catch (error) { return json(error instanceof HttpError ? error.status : 502, { error: safe(error) }); }
    return event({ jsonrpc: "2.0", id, result: { protocolVersion: "2025-03-26", capabilities: { tools: { listChanged: false } }, serverInfo: { name: "EveryTalk Microsoft Mail", version: "1.0.0" } } });
  }
  if (message.method === "ping") return event({ jsonrpc: "2.0", id, result: {} });
  if (message.method === "tools/list") return event({ jsonrpc: "2.0", id, result: { tools } });
  if (message.method === "tools/call") {
    try { return event({ jsonrpc: "2.0", id, result: await call(message.params?.name, message.params?.arguments ?? {}, token) }); }
    catch (error) { return event({ jsonrpc: "2.0", id, result: { isError: true, content: [{ type: "text", text: safe(error) }] } }); }
  }
  return event({ jsonrpc: "2.0", id, error: { code: -32601, message: "Method not found" } });
}

const tools = [
  { name: "search_emails", description: "Search Microsoft mailbox messages.", inputSchema: { type: "object", properties: { query: { type: "string" }, maxResults: { type: "integer", minimum: 1, maximum: 50 } }, required: ["query"] }, annotations: { readOnlyHint: true } },
  { name: "read_email", description: "Read one Microsoft mailbox message.", inputSchema: { type: "object", properties: { messageId: { type: "string" } }, required: ["messageId"] }, annotations: { readOnlyHint: true } },
  { name: "send_email", description: "Send a plain text email only after explicit user instruction.", inputSchema: { type: "object", properties: { to: { type: "string" }, subject: { type: "string" }, body: { type: "string" } }, required: ["to", "subject", "body"] }, annotations: { destructiveHint: true } },
  { name: "list_folders", description: "List Microsoft mail folders.", inputSchema: { type: "object", properties: {} }, annotations: { readOnlyHint: true } },
];

async function call(name, args, token) {
  if (name === "search_emails") {
    if (typeof args.query !== "string" || !args.query.trim() || args.query.length > 512) throw new Error("Invalid query");
    const max = args.maxResults ?? 20;
    if (!Number.isInteger(max) || max < 1 || max > 50) throw new Error("Invalid maxResults");
    const query = new URLSearchParams({ "$search": `"${args.query.replaceAll('"', "")}"`, "$top": String(max), "$select": "id,subject,from,receivedDateTime,isRead,bodyPreview" });
    return result(await graph(token, `/messages?${query}`, { ConsistencyLevel: "eventual" }));
  }
  if (name === "read_email") {
    const id = safeId(args.messageId); return result(await graph(token, `/messages/${encodeURIComponent(id)}?$select=id,subject,from,toRecipients,receivedDateTime,body,bodyPreview,isRead`));
  }
  if (name === "send_email") {
    const to = recipients(args.to); const subject = string(args.subject, "subject", 998); const body = string(args.body, "body", 32768);
    await graph(token, "/sendMail", {}, { method: "POST", body: JSON.stringify({ message: { subject, body: { contentType: "Text", content: body }, toRecipients: to.split(",").map(address => ({ emailAddress: { address } })) }, saveToSentItems: true }) });
    return result({ sent: true });
  }
  if (name === "list_folders") return result(await graph(token, "/mailFolders?$top=100&$select=id,displayName,totalItemCount,unreadItemCount"));
  throw new Error("Unknown Microsoft mail tool");
}

async function graph(token, path, headers = {}, init = {}) {
  const response = await fetchWithoutRedirect(`${GRAPH}${path}`, { ...init, signal: AbortSignal.timeout(30_000), headers: { authorization: `Bearer ${token}`, accept: "application/json", ...(init.body ? { "content-type": "application/json" } : {}), ...headers, ...(init.headers || {}) } });
  if (!response.ok) { await response.body?.cancel(); throw new HttpError(response.status, `Microsoft Graph HTTP ${response.status}`); }
  const raw = await readBoundedText(response, 4 * 1024 * 1024); return raw ? JSON.parse(raw) : {};
}
function event(payload) { return new Response(`event: message\ndata: ${JSON.stringify(payload)}\n\n`, { status: 200, headers: { "content-type": "text/event-stream", "cache-control": "no-cache" } }); }
function result(value) { return { content: [{ type: "text", text: JSON.stringify(value) }] }; }
function string(value, name, max) { if (typeof value !== "string" || !value.trim() || value.length > max || /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(value)) throw new Error(`Invalid ${name}`); return value.trim(); }
function safeId(value) { const id = string(value, "messageId", 1024); if (/[\r\n]/.test(id)) throw new Error("Invalid messageId"); return id; }
function recipients(value) { const raw = string(value, "recipients", 900).split(",").map(x => x.trim()); if (raw.some(x => !/^[^\s@,]+@[^\s@,]+\.[^\s@,]+$/.test(x))) throw new Error("Invalid recipients"); return raw.join(","); }
function safe(error) { if (error instanceof HttpError || /^Invalid |^Unknown /.test(error?.message ?? "")) return error.message; return "Microsoft mail request failed"; }
