import http from "node:http";
import { ImapFlow } from "imapflow";
import { simpleParser } from "mailparser";
import nodemailer from "nodemailer";

const PORT = Number(process.env.PORT || 8787);
const SHARED_KEY = process.env.MAIL_GATEWAY_SHARED_KEY || "";
const MAX_BODY = 512 * 1024;
const PROVIDERS = {
  "qq.com": { imap: "imap.qq.com", smtp: "smtp.qq.com" }, "foxmail.com": { imap: "imap.qq.com", smtp: "smtp.qq.com" },
  "163.com": { imap: "imap.163.com", smtp: "smtp.163.com" }, "126.com": { imap: "imap.126.com", smtp: "smtp.126.com" }, "yeah.net": { imap: "imap.yeah.net", smtp: "smtp.yeah.net" },
};

const server = http.createServer(async (request, response) => {
  try {
    if (request.method !== "POST" || request.url !== "/mcp/mail") return send(response, 405, { error: "method_not_allowed" });
    const token = request.headers.authorization?.match(/^Bearer ([A-Za-z0-9_-]{43,4096})$/)?.[1];
    if (!token || !SHARED_KEY) return send(response, 401, { error: "unauthorized" });
    const body = JSON.parse(await readBody(request));
    const message = decodeCredentials(token);
    return await handleMcp(message, body, response);
  } catch (error) {
    if (!response.headersSent) send(response, error.status || 500, { error: error.status ? error.message : "internal_error" });
    else response.end();
    console.error(error.message);
  }
});

async function handleMcp(credentials, request, response) {
  if (!request || request.jsonrpc !== "2.0" || typeof request.method !== "string") return event(response, { jsonrpc: "2.0", id: null, error: { code: -32600, message: "Invalid Request" } });
  if (request.id === undefined) return send(response, 202, null);
  if (request.method === "initialize") {
    await withMailbox(credentials, () => Promise.resolve());
    return event(response, { jsonrpc: "2.0", id: request.id, result: { protocolVersion: "2025-03-26", capabilities: { tools: { listChanged: false } }, serverInfo: { name: "EveryTalk Mail Gateway", version: "1.0.0" } } });
  }
  if (request.method === "ping") return event(response, { jsonrpc: "2.0", id: request.id, result: {} });
  if (request.method === "tools/list") return event(response, { jsonrpc: "2.0", id: request.id, result: { tools } });
  if (request.method !== "tools/call") return event(response, { jsonrpc: "2.0", id: request.id, error: { code: -32601, message: "Method not found" } });
  try { return event(response, { jsonrpc: "2.0", id: request.id, result: await call(credentials, request.params?.name, request.params?.arguments || {}) }); }
  catch (error) { return event(response, { jsonrpc: "2.0", id: request.id, result: { isError: true, content: [{ type: "text", text: safe(error) }] } }); }
}

const tools = [
  { name: "search_emails", description: "Search messages in the mailbox.", inputSchema: { type: "object", properties: { query: { type: "string" }, maxResults: { type: "integer", minimum: 1, maximum: 50 } }, required: ["query"] }, annotations: { readOnlyHint: true } },
  { name: "read_email", description: "Read a message by IMAP UID.", inputSchema: { type: "object", properties: { messageId: { type: "string" } }, required: ["messageId"] }, annotations: { readOnlyHint: true } },
  { name: "send_email", description: "Send a plain text message only after explicit user instruction.", inputSchema: { type: "object", properties: { to: { type: "string" }, subject: { type: "string" }, body: { type: "string" } }, required: ["to", "subject", "body"] }, annotations: { destructiveHint: true } },
  { name: "list_folders", description: "List mailbox folders.", inputSchema: { type: "object", properties: {} }, annotations: { readOnlyHint: true } },
];

async function call(credentials, name, args) {
  if (name === "send_email") {
    const to = recipients(args.to); const subject = text(args.subject, 998); const body = text(args.body, 32768);
    const smtp = providerFor(credentials.email).smtp;
    const transport = nodemailer.createTransport({ host: smtp, port: 465, secure: true, auth: { user: credentials.email, pass: credentials.authorizationCode }, disableFileAccess: true, disableUrlAccess: true });
    await transport.sendMail({ from: credentials.email, to, subject, text: body });
    return result({ sent: true });
  }
  return withMailbox(credentials, async client => {
    if (name === "search_emails") {
      const query = text(args.query, 512); const max = args.maxResults ?? 20;
      if (!Number.isInteger(max) || max < 1 || max > 50) throw new Error("Invalid maxResults");
      const uids = await client.search({ or: [{ subject: query }, { from: query }, { body: query }] }, { uid: true });
      const selected = uids.slice(-max).reverse(); const messages = [];
      for await (const item of client.fetch(selected, { envelope: true, uid: true, flags: true, bodyStructure: true }, { uid: true })) messages.push({ id: String(item.uid), subject: item.envelope?.subject, from: item.envelope?.from, date: item.envelope?.date, flags: item.flags });
      return result(messages);
    }
    if (name === "read_email") {
      const uid = safeUid(args.messageId); const item = await client.fetchOne(uid, { source: true, envelope: true }, { uid: true });
      if (!item) throw new Error("Message not found");
      const parsed = await simpleParser(item.source); return result({ id: String(uid), subject: parsed.subject, from: parsed.from?.text, to: parsed.to?.text, date: parsed.date, text: parsed.text?.slice(0, 65536), html: parsed.html ? String(parsed.html).slice(0, 65536) : undefined, attachments: parsed.attachments.map(x => ({ filename: x.filename, contentType: x.contentType, size: x.size })) });
    }
    if (name === "list_folders") { const boxes = await client.list(); return result(boxes.map(x => x.path)); }
    throw new Error("Unknown mail tool");
  });
}

async function withMailbox(credentials, action) {
  const provider = providerFor(credentials.email); const client = new ImapFlow({ host: provider.imap, port: 993, secure: true, auth: { user: credentials.email, pass: credentials.authorizationCode }, logger: false });
  try { await client.connect(); await client.mailboxOpen("INBOX", { readOnly: true }); return await action(client); } finally { await client.logout().catch(() => client.close()); }
}
function decodeCredentials(token) {
  let value; try { value = JSON.parse(Buffer.from(token, "base64url").toString("utf8")); } catch { throw status(401, "invalid_credentials"); }
  if (value.gatewayKey !== SHARED_KEY || typeof value.email !== "string" || typeof value.authorizationCode !== "string") throw status(401, "invalid_credentials");
  providerFor(value.email); return value;
}
function providerFor(email) { const provider = PROVIDERS[email.toLowerCase().split("@")[1]]; if (!provider) throw status(400, "unsupported_mailbox"); return provider; }
function text(value, max) { if (typeof value !== "string" || !value.trim() || value.length > max || /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(value)) throw new Error("Invalid text"); return value.trim(); }
function recipients(value) { const list = text(value, 900).split(",").map(x => x.trim()); if (list.some(x => !/^[^\s@,]+@[^\s@,]+\.[^\s@,]+$/.test(x))) throw new Error("Invalid recipients"); return list; }
function safeUid(value) { if (!/^[1-9][0-9]{0,15}$/.test(String(value))) throw new Error("Invalid messageId"); return Number(value); }
function result(value) { return { content: [{ type: "text", text: JSON.stringify(value) }] }; }
function event(response, payload) { response.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" }); response.end(`event: message\ndata: ${JSON.stringify(payload)}\n\n`); }
function send(response, status, body) { response.writeHead(status, body ? { "content-type": "application/json", "cache-control": "no-store" } : {}); response.end(body ? JSON.stringify(body) : undefined); }
function readBody(request) { return new Promise((resolve, reject) => { let data = ""; request.on("data", chunk => { data += chunk; if (Buffer.byteLength(data) > MAX_BODY) reject(status(413, "payload_too_large")); }); request.on("end", () => resolve(data)); request.on("error", reject); }); }
function status(code, message) { const error = new Error(message); error.status = code; return error; }
function safe(error) { return /^(Invalid |Unknown |Message not found|unsupported_)/.test(error?.message || "") ? error.message : "Mail gateway request failed"; }

server.listen(PORT, () => console.log(`EveryTalk mail gateway listening on ${PORT}`));
