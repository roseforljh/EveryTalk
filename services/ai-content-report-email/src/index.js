const REPORT_PATH = "/ai-content-reports";
const REPORT_VERSION = "1";
const MAX_BODY_BYTES = 32 * 1024;

const CATEGORY_LABELS = Object.freeze({
  CHILD_SAFETY: "儿童安全",
  SEXUAL_CONTENT: "色情或私密内容",
  VIOLENCE_SELF_HARM: "暴力或自伤",
  HATE_HARASSMENT: "仇恨或骚扰",
  DECEPTION_IMPERSONATION: "欺诈或冒充",
  MALICIOUS_CODE: "恶意代码",
  OTHER: "其他问题",
});

class BodyTooLargeError extends Error {}

export default {
  async fetch(request, env) {
    return handleRequest(request, env);
  },
};

export async function handleRequest(request, env) {
  const url = new URL(request.url);
  if (url.pathname !== REPORT_PATH) {
    return jsonResponse(404, { error: "not_found" });
  }
  if (request.method !== "POST") {
    return jsonResponse(405, { error: "method_not_allowed" }, { Allow: "POST" });
  }

  const configError = validateEnvironment(env);
  if (configError) {
    console.error(`Worker 配置错误：${configError}`);
    return jsonResponse(503, { error: "service_unavailable" });
  }

  let rateLimitResult;
  try {
    const clientKey = request.headers.get("CF-Connecting-IP") || "unknown";
    rateLimitResult = await env.REPORT_RATE_LIMITER.limit({ key: clientKey });
  } catch (error) {
    console.error("举报限流检查失败", safeErrorMessage(error));
    return jsonResponse(503, { error: "service_unavailable" });
  }
  if (!rateLimitResult.success) {
    return jsonResponse(
      429,
      { error: "rate_limited" },
      { "Retry-After": "60" },
    );
  }

  if (request.headers.get("X-EveryTalk-Report-Version") !== REPORT_VERSION) {
    return jsonResponse(400, { error: "unsupported_report_version" });
  }
  if (!isJsonContentType(request.headers.get("Content-Type"))) {
    return jsonResponse(415, { error: "unsupported_media_type" });
  }

  let rawBody;
  try {
    rawBody = await readBodyWithLimit(request, MAX_BODY_BYTES);
  } catch (error) {
    if (error instanceof BodyTooLargeError) {
      return jsonResponse(413, { error: "payload_too_large" });
    }
    return jsonResponse(400, { error: "invalid_body" });
  }

  let report;
  try {
    report = JSON.parse(rawBody);
  } catch {
    return jsonResponse(400, { error: "invalid_json" });
  }

  const invalidField = validateReport(report);
  if (invalidField) {
    return jsonResponse(400, { error: "invalid_report", field: invalidField });
  }

  const resendResponse = await sendReportEmail(report, env);
  if (!resendResponse.ok) {
    return jsonResponse(502, { error: "email_delivery_failed" });
  }

  return jsonResponse(200, {
    accepted: true,
    reportId: report.reportId,
  });
}

async function sendReportEmail(report, env) {
  try {
    await env.REPORT_EMAIL.send({
      from: env.REPORT_FROM_EMAIL,
      to: env.REPORT_TO_EMAIL,
      subject: `[EveryTalk AI 举报][${CATEGORY_LABELS[report.category]}] ${report.reportId.slice(0, 8)}`,
      html: buildEmailHtml(report),
      text: buildEmailText(report),
      headers: {
        "X-EveryTalk-Report-ID": report.reportId,
      },
    });
    return { ok: true };
  } catch (error) {
    console.error("Cloudflare 邮件发送失败", safeErrorMessage(error));
    return { ok: false };
  }
}

export function validateReport(report) {
  if (!isObject(report)) return "body";
  if (!isUuid(report.reportId)) return "reportId";
  if (!isStringInRange(report.messageId, 1, 200)) return "messageId";
  if (!Object.hasOwn(CATEGORY_LABELS, report.category)) return "category";
  if (!isStringInRange(report.details, 0, 500)) return "details";
  if (!isStringInRange(report.messageText, 0, 4_000)) return "messageText";
  if (!Number.isInteger(report.imageCount) || report.imageCount < 0 || report.imageCount > 100) {
    return "imageCount";
  }
  if (typeof report.isImageGeneration !== "boolean") return "isImageGeneration";
  if (!isOptionalString(report.modelName, 200)) return "modelName";
  if (!isOptionalString(report.providerName, 200)) return "providerName";
  if (!isStringInRange(report.appVersion, 1, 100)) return "appVersion";
  if (report.platform !== "android") return "platform";
  if (!Number.isSafeInteger(report.createdAtEpochMillis) || report.createdAtEpochMillis <= 0) {
    return "createdAtEpochMillis";
  }
  if (report.createdAtEpochMillis > Date.now() + 24 * 60 * 60 * 1_000) {
    return "createdAtEpochMillis";
  }
  return null;
}

export function buildEmailHtml(report) {
  const category = CATEGORY_LABELS[report.category];
  const details = cleanText(report.details) || "未填写";
  const messageText = cleanText(report.messageText) || "无文本内容，详见图片数量";
  const modelName = cleanText(report.modelName) || "未提供";
  const providerName = cleanText(report.providerName) || "未提供";
  const createdAt = new Date(report.createdAtEpochMillis).toISOString();

  return `<!doctype html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>EveryTalk AI 内容举报</title></head>
<body style="margin:0;padding:24px;background:#f5f5f5;color:#202124;font-family:Arial,'Microsoft YaHei',sans-serif">
  <main style="max-width:760px;margin:0 auto;padding:24px;background:#ffffff;border-radius:12px">
    <h1 style="margin:0 0 20px;font-size:22px">EveryTalk AI 内容举报</h1>
    <table style="width:100%;border-collapse:collapse;font-size:14px">
      ${emailRow("举报类别", `${category} (${report.category})`)}
      ${emailRow("举报 ID", report.reportId)}
      ${emailRow("消息 ID", report.messageId)}
      ${emailRow("提交时间", createdAt)}
      ${emailRow("模型", modelName)}
      ${emailRow("服务商", providerName)}
      ${emailRow("App 版本", report.appVersion)}
      ${emailRow("图片数量", String(report.imageCount))}
      ${emailRow("图像生成", report.isImageGeneration ? "是" : "否")}
    </table>
    <h2 style="margin:24px 0 8px;font-size:17px">用户补充说明</h2>
    <pre style="margin:0;padding:14px;background:#f8f9fa;border-radius:8px;white-space:pre-wrap;word-break:break-word;font:14px/1.55 Arial,'Microsoft YaHei',sans-serif">${escapeHtml(details)}</pre>
    <h2 style="margin:24px 0 8px;font-size:17px">相关 AI 回复</h2>
    <pre style="margin:0;padding:14px;background:#f8f9fa;border-radius:8px;white-space:pre-wrap;word-break:break-word;font:14px/1.55 Arial,'Microsoft YaHei',sans-serif">${escapeHtml(messageText)}</pre>
  </main>
</body>
</html>`;
}

export function buildEmailText(report) {
  const category = CATEGORY_LABELS[report.category];
  const details = cleanText(report.details) || "未填写";
  const messageText = cleanText(report.messageText) || "无文本内容，详见图片数量";
  return [
    "EveryTalk AI 内容举报",
    "",
    `举报类别：${category} (${report.category})`,
    `举报 ID：${report.reportId}`,
    `消息 ID：${report.messageId}`,
    `提交时间：${new Date(report.createdAtEpochMillis).toISOString()}`,
    `模型：${cleanText(report.modelName) || "未提供"}`,
    `服务商：${cleanText(report.providerName) || "未提供"}`,
    `App 版本：${report.appVersion}`,
    `图片数量：${report.imageCount}`,
    `图像生成：${report.isImageGeneration ? "是" : "否"}`,
    "",
    "用户补充说明：",
    details,
    "",
    "相关 AI 回复：",
    messageText,
  ].join("\n");
}

function emailRow(label, value) {
  return `<tr><th style="padding:8px;text-align:left;vertical-align:top;border-bottom:1px solid #eee">${escapeHtml(label)}</th><td style="padding:8px;border-bottom:1px solid #eee;word-break:break-word">${escapeHtml(value)}</td></tr>`;
}

async function readBodyWithLimit(request, maxBytes) {
  const declaredLength = Number(request.headers.get("Content-Length"));
  if (Number.isFinite(declaredLength) && declaredLength > maxBytes) {
    throw new BodyTooLargeError();
  }
  if (!request.body) return "";

  const reader = request.body.getReader();
  const chunks = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      totalBytes += value.byteLength;
      if (totalBytes > maxBytes) {
        await reader.cancel().catch(() => undefined);
        throw new BodyTooLargeError();
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const body = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder("utf-8", { fatal: true }).decode(body);
}

function validateEnvironment(env) {
  if (!env) return "env";
  if (!isSafeEmailHeader(env.REPORT_FROM_EMAIL, 400)) return "REPORT_FROM_EMAIL";
  if (!isSafeRecipient(env.REPORT_TO_EMAIL)) return "REPORT_TO_EMAIL";
  if (!env.REPORT_EMAIL || typeof env.REPORT_EMAIL.send !== "function") return "REPORT_EMAIL";
  if (!env.REPORT_RATE_LIMITER || typeof env.REPORT_RATE_LIMITER.limit !== "function") {
    return "REPORT_RATE_LIMITER";
  }
  return null;
}

function isJsonContentType(value) {
  return typeof value === "string" && value.split(";", 1)[0].trim().toLowerCase() === "application/json";
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isStringInRange(value, minLength, maxLength) {
  return typeof value === "string" && value.length >= minLength && value.length <= maxLength;
}

function isOptionalString(value, maxLength) {
  return value === null || value === undefined || isStringInRange(value, 1, maxLength);
}

function isSafeEmailHeader(value, maxLength) {
  return isStringInRange(value, 3, maxLength)
    && value.includes("@")
    && !value.includes("\r")
    && !value.includes("\n");
}

function isSafeRecipient(value) {
  if (!isSafeEmailHeader(value, 320) || value.includes(" ") || value.includes("<") || value.includes(">")) {
    return false;
  }
  const parts = value.split("@");
  return parts.length === 2 && parts.every((part) => part.length > 0);
}

function isUuid(value) {
  if (typeof value !== "string") return false;
  const parts = value.split("-");
  const lengths = [8, 4, 4, 4, 12];
  return parts.length === lengths.length
    && parts.every((part, index) => part.length === lengths[index] && isHex(part));
}

function isHex(value) {
  for (const character of value.toLowerCase()) {
    const isDigit = character >= "0" && character <= "9";
    const isLetter = character >= "a" && character <= "f";
    if (!isDigit && !isLetter) return false;
  }
  return true;
}

function cleanText(value) {
  if (typeof value !== "string") return "";
  let result = "";
  for (const character of value) {
    const code = character.codePointAt(0);
    if (code >= 32 || character === "\n" || character === "\t") {
      result += character;
    }
  }
  return result.trim();
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    "\"": "&quot;",
    "'": "&#39;",
  })[character]);
}

function safeErrorMessage(error) {
  return error instanceof Error ? error.message.slice(0, 300) : "未知错误";
}

function jsonResponse(status, body, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Cache-Control": "no-store",
      "Content-Type": "application/json; charset=utf-8",
      "X-Content-Type-Options": "nosniff",
      ...extraHeaders,
    },
  });
}
