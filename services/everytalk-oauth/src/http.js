export function json(status, value, extra = {}) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      ...extra,
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}
export class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/** Workers 仅支持 follow/manual。手动拒绝重定向，避免 OAuth 密钥或 Bearer 被转发。 */
export async function fetchWithoutRedirect(url, init = {}) {
  const response = await fetch(url, { ...init, redirect: "manual" });
  if (response.status >= 300 && response.status < 400) {
    await response.body?.cancel();
    throw new HttpError(502, "upstream_redirect_rejected");
  }
  return response;
}

/** 流式限制请求和上游响应，避免读完整个不可信正文后才检查大小。 */
export async function readBoundedText(message, maxBytes = 32 * 1024) {
  if (!message.body) return "";
  const reader = message.body.getReader();
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > maxBytes) {
        await reader.cancel();
        throw new HttpError(413, "payload_too_large");
      }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return new TextDecoder().decode(bytes);
}
