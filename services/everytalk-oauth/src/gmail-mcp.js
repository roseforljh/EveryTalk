import { HttpError, json, readBoundedText, fetchWithoutRedirect } from "./http.js";
const GMAIL_API_ORIGIN = "https://gmail.googleapis.com/gmail/v1/users/me";

/**
 * Gmail 的 Streamable HTTP MCP 入口。
 * 每个请求都必须携带 Google access token；Worker 不保存邮件、授权码或 Token。
 */
export async function gmailMcp(request) {
  if (request.method !== "POST") {
    return request.method === "GET"
      ? new Response(null, { status: 405, headers: { allow: "POST" } })
      : json(405, { error: "method_not_allowed" }, { allow: "POST" });
  }
  const authorization = request.headers.get("authorization") || "";
  const accessToken = authorization.match(/^Bearer ([A-Za-z0-9._~+/=-]{16,4096})$/)?.[1];
  if (!accessToken) return json(401, { error: "unauthorized" }, { "www-authenticate": "Bearer" });

  const contentType = request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase();
  if (contentType !== "application/json") return json(415, { error: "unsupported_media_type" });
  const raw = await readBoundedText(request, 512 * 1024);
  let message;
  try { message = JSON.parse(raw); }
  catch { return json(400, { error: "invalid_json" }); }

  if (!message || Array.isArray(message) || message.jsonrpc !== "2.0" || typeof message.method !== "string" ||
      (message.id !== undefined && typeof message.id !== "string" && !Number.isSafeInteger(message.id))) {
    return mcpEvent({ jsonrpc: "2.0", id: null, error: { code: -32600, message: "Invalid Request" } });
  }
  const id = message.id ?? null;
  if (message.id === undefined && message.method.startsWith("notifications/")) {
    return new Response(null, { status: 202, headers: { "cache-control": "no-store" } });
  }
  if (message.id === undefined) return new Response(null, { status: 202 });
  const currentSession = request.headers.get("mcp-session-id")?.match(/^[A-Za-z0-9-]{1,128}$/)?.[0] || null;
  if (message?.method === "initialize") {
    // 用 Google 验证令牌，不能仅凭 Bearer 字符串便显示“已连接”。不缓存用户资料。
    try { await gmailApi(accessToken, "profile"); }
    catch (error) {
      return json(error instanceof HttpError ? error.status : 502, { error: safeGmailError(error) });
    }
    return mcpEvent({
      jsonrpc: "2.0",
      id,
      result: {
        protocolVersion: ["2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"].includes(message.params?.protocolVersion)
          ? message.params.protocolVersion : "2025-03-26",
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: "EveryTalk Gmail", version: "1.0.0" },
      },
    }, currentSession || crypto.randomUUID());
  }
  if (message.method === "ping") return mcpEvent({ jsonrpc: "2.0", id, result: {} }, currentSession);
  if (message?.method === "tools/list") {
    return mcpEvent({ jsonrpc: "2.0", id, result: { tools: gmailTools() } }, currentSession);
  }
  if (message?.method === "tools/call") {
    try {
      const args = message.params?.arguments ?? {};
      if (!args || typeof args !== "object" || Array.isArray(args)) throw new Error("Invalid tool arguments");
      const result = await callGmailTool(message.params?.name, args, accessToken);
      return mcpEvent({ jsonrpc: "2.0", id, result }, currentSession);
    } catch (error) {
      return mcpEvent({
        jsonrpc: "2.0",
        id,
        result: { isError: true, content: [{ type: "text", text: safeGmailError(error) }] },
      }, currentSession);
    }
  }
  return mcpEvent({ jsonrpc: "2.0", id, error: { code: -32601, message: "Method not found" } }, currentSession);
}

function mcpEvent(payload, sessionId = null) {
  return new Response(`event: message\ndata: ${JSON.stringify(payload)}\n\n`, {
    status: 200,
    headers: {
      "content-type": "text/event-stream",
      "cache-control": "no-cache, no-transform",
      "x-accel-buffering": "no",
      ...(sessionId ? { "mcp-session-id": sessionId } : {}),
    },
  });
}

function gmailTools() {
  return [
    {
      name: "search_emails",
      description: "Search Gmail messages with a Gmail search query.",
      inputSchema: { type: "object", properties: { query: { type: "string" }, pageToken: { type: "string", description: "Use nextPageToken from the previous result." }, maxResults: { type: "integer", minimum: 1, maximum: 100 } }, required: ["query"] },
      annotations: { readOnlyHint: true },
    },
    {
      name: "read_email",
      description: "Read one Gmail message by ID.",
      inputSchema: { type: "object", properties: { messageId: { type: "string" } }, required: ["messageId"] },
      annotations: { readOnlyHint: true },
    },
    {
      name: "send_email",
      description: "Send a plain-text Gmail message. Only send when the user explicitly requests sending to these recipients with this content. No attachments. Do not retry automatically after a timeout; check Sent mail first.",
      inputSchema: { type: "object", properties: { to: { type: "string" }, cc: { type: "string" }, bcc: { type: "string" }, subject: { type: "string" }, body: { type: "string" } }, required: ["to", "subject", "body"] },
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false },
    },
    {
      name: "list_labels",
      description: "List Gmail labels.",
      inputSchema: { type: "object", properties: {} },
      annotations: { readOnlyHint: true },
    },
    {
      name: "modify_email",
      description: "Add or remove Gmail labels from a message.",
      inputSchema: { type: "object", properties: { messageId: { type: "string" }, addLabelIds: { type: "array", items: { type: "string" } }, removeLabelIds: { type: "array", items: { type: "string" } } }, required: ["messageId"] },
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: true },
    },
  ];
}

async function callGmailTool(name, args, accessToken) {
  switch (name) {
    case "search_emails": {
      const query = requireString(args.query, "query");
      const maxResults = args.maxResults ?? 20;
      if (!Number.isInteger(maxResults) || maxResults < 1 || maxResults > 100) throw new Error("Invalid maxResults");
      const params = new URLSearchParams({ q: query, maxResults: String(maxResults) });
      if (args.pageToken !== undefined) params.set("pageToken", requireString(args.pageToken, "pageToken"));
      return textResult(await gmailApi(accessToken, `messages?${params}`));
    }
    case "read_email": {
      const id = messageId(args.messageId);
      return textResult(decodeEmail(await gmailApi(accessToken, `messages/${id}?format=full`)));
    }
    case "send_email": {
      const to = recipients(args.to);
      const subject = requireString(args.subject, "subject");
      if (/[\x00-\x1f\x7f]/.test(subject) || subject.length > 998) throw new Error("Invalid subject");
      if (typeof args.body !== "string" || args.body.length > 32_768) throw new Error("Invalid body");
      // RFC 2047 按码点分块编码中文标题；正文独立 Base64，保留首尾空白与长行。
      const words = subject.match(/.{1,12}/gu).map(part => `=?UTF-8?B?${base64(part)}?=`);
      const headers = [`To: ${to}`, `Subject: ${words.join("\r\n ")}`];
      if (args.cc !== undefined) headers.push(`Cc: ${recipients(args.cc)}`);
      if (args.bcc !== undefined) headers.push(`Bcc: ${recipients(args.bcc)}`);
      headers.push("Content-Type: text/plain; charset=UTF-8", "MIME-Version: 1.0", "Content-Transfer-Encoding: base64");
      const content = base64(args.body.replace(/\r?\n/g, "\r\n")).match(/.{1,76}/g)?.join("\r\n") ?? "";
      return textResult(await gmailApi(accessToken, "messages/send", { method: "POST", body: JSON.stringify({ raw: base64Url(`${headers.join("\r\n")}\r\n\r\n${content}`) }) }));
    }
    case "list_labels":
      return textResult(await gmailApi(accessToken, "labels"));
    case "modify_email": {
      const id = messageId(args.messageId);
      const addLabelIds = stringArray(args.addLabelIds);
      const removeLabelIds = stringArray(args.removeLabelIds);
      if (!addLabelIds.length && !removeLabelIds.length) throw new Error("No labels to modify");
      if (addLabelIds.some(label => removeLabelIds.includes(label))) throw new Error("Conflicting labels");
      return textResult(await gmailApi(accessToken, `messages/${encodeURIComponent(id)}/modify`, { method: "POST", body: JSON.stringify({
        addLabelIds, removeLabelIds,
      }) }));
    }
    default: throw new Error("Unknown Gmail tool");
  }
}

async function gmailApi(accessToken, path, init = {}) {
  const response = await fetchWithoutRedirect(`${GMAIL_API_ORIGIN}/${path}`, {
    ...init,
    signal: AbortSignal.timeout(30_000),
    headers: { authorization: `Bearer ${accessToken}`, accept: "application/json", "content-type": "application/json", ...(init.headers || {}) },
  });
  if (!response.ok) {
    await response.body?.cancel();
    throw new HttpError(response.status, `Gmail API HTTP ${response.status}`);
  }
  const raw = await readBoundedText(response, 4 * 1_024 * 1024);
  let payload;
  try { payload = raw ? JSON.parse(raw) : {}; } catch { throw new Error("Invalid Gmail API response"); }
  return payload;
}

function textResult(value) {
  return { content: [{ type: "text", text: JSON.stringify(value) }] };
}

function requireString(value, name) {
  if (typeof value !== "string" || !value.trim() || value.length > 32_768) throw new Error(`Missing or invalid ${name}`);
  return value.trim();
}

function stringArray(value) {
  if (value === undefined) return [];
  if (!Array.isArray(value) || value.length > 100 || value.some(item => typeof item !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(item))) {
    throw new Error("Invalid label IDs");
  }
  return value;
}

function messageId(value) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{1,256}$/.test(value)) throw new Error("Invalid messageId");
  return value;
}

/** 收件人仅接受逗号分隔的邮箱，拒绝控制字符和额外邮件头。 */
function recipients(value) {
  const text = requireString(value, "recipients");
  if (/[\x00-\x1f\x7f]/.test(text) || text.length > 900) throw new Error("Invalid recipients");
  const addresses = text.split(",").map(item => item.trim());
  if (addresses.some(item => !/^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+$/.test(item))) {
    throw new Error("Invalid recipients");
  }
  return addresses.join(", ");
}

/** 展开 MIME 正文，不下载附件；返回附件元数据并明确正文过大时的限制。 */
function decodeEmail(message) {
  const bodies = [];
  const attachments = [];
  function visit(part, depth = 0) {
    if (!part) return;
    if (depth > 30) throw new Error("Gmail MIME nesting too deep");
    if (part.filename || part.body?.attachmentId) {
      attachments.push({ filename: part.filename, mimeType: part.mimeType, size: part.body?.size, attachmentId: part.body?.attachmentId });
    } else if ((part.mimeType === "text/plain" || part.mimeType === "text/html") && part.body?.data) {
      const encoded = part.body.data.replace(/-/g, "+").replace(/_/g, "/");
      const binary = atob(encoded + "=".repeat((4 - encoded.length % 4) % 4));
      const contentType = part.headers?.find(header => header.name.toLowerCase() === "content-type")?.value ?? "";
      const charset = /charset\s*=\s*["']?([^\s;"']+)/i.exec(contentType)?.[1] ?? "utf-8";
      let decoder;
      try { decoder = new TextDecoder(charset); } catch { decoder = new TextDecoder(); }
      bodies.push({ mimeType: part.mimeType, text: decoder.decode(Uint8Array.from(binary, c => c.charCodeAt(0))) });
    }
    for (const child of part.parts ?? []) visit(child, depth + 1);
  }
  visit(message.payload);
  return { id: message.id, threadId: message.threadId, labelIds: message.labelIds, snippet: message.snippet,
    headers: message.payload?.headers, bodies, attachments };
}

function base64Url(value) {
  return base64(value).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function base64(value) {
  const bytes = new TextEncoder().encode(value);
  let binary = "";
  for (let index = 0; index < bytes.length; index += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000));
  }
  return btoa(binary);
}

function safeGmailError(error) {
  if (error instanceof HttpError) return error.message;
  // 网络异常可能包含上游 URL；只回传已知的校验错误，避免回显凭据或邮箱正文。
  const message = error instanceof Error ? error.message : "";
  return /^(Invalid |Missing or invalid |Unknown Gmail tool$|No labels to modify$|Conflicting labels$|Gmail MIME nesting too deep$)/.test(message)
    ? message : "Gmail request failed. For sending, check Sent mail before retrying.";
}

