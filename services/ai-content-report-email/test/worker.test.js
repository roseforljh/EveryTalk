import assert from "node:assert/strict";
import test from "node:test";
import { handleRequest } from "../src/index.js";

const VALID_REPORT = Object.freeze({
  reportId: "4dd346fa-b7b8-42b4-873f-306e864239a1",
  messageId: "message-1",
  category: "HATE_HARASSMENT",
  details: "包含针对群体的攻击",
  messageText: "<script>alert('xss')</script>",
  imageCount: 0,
  isImageGeneration: false,
  modelName: "test-model",
  providerName: "test-provider",
  appVersion: "1.26.0",
  platform: "android",
  createdAtEpochMillis: 1_786_379_400_000,
});

test("有效举报会通过 Cloudflare 发送转义后的邮件", async () => {
  let sentEmail;
  let rateLimitKey;
  const response = await handleRequest(createRequest(VALID_REPORT), createEnv({
    onSend: (email) => {
      sentEmail = email;
    },
    onRateLimit: (key) => {
      rateLimitKey = key;
    },
  }));

  assert.equal(response.status, 200);
  assert.equal(rateLimitKey, "203.0.113.10");
  assert.equal(sentEmail.to, "owner@example.com");
  assert.equal(sentEmail.from, "reports@example.com");
  assert.match(sentEmail.subject, /EveryTalk AI 举报/);
  assert.equal(sentEmail.headers["X-EveryTalk-Report-ID"], VALID_REPORT.reportId);
  assert.ok(sentEmail.html.includes("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;"));
  assert.ok(!sentEmail.html.includes("<script>alert('xss')</script>"));
  assert.ok(sentEmail.text.includes("<script>alert('xss')</script>"));
});

test("非法举报字段会被拒绝且不会发送邮件", async () => {
  const response = await handleRequest(
    createRequest({ ...VALID_REPORT, category: "UNKNOWN" }),
    createEnv({ onSend: () => assert.fail("非法举报不应发送邮件") }),
  );
  assert.equal(response.status, 400);
  assert.deepEqual(await response.json(), { error: "invalid_report", field: "category" });
});

test("超过频率限制时返回 429", async () => {
  const response = await handleRequest(
    createRequest(VALID_REPORT),
    createEnv({
      rateLimitSuccess: false,
      onSend: () => assert.fail("被限流的举报不应发送邮件"),
    }),
  );
  assert.equal(response.status, 429);
  assert.equal(response.headers.get("Retry-After"), "60");
});

test("超过请求体限制时返回 413", async () => {
  const response = await handleRequest(
    createRequest({ ...VALID_REPORT, messageText: "a".repeat(33_000) }),
    createEnv(),
  );
  assert.equal(response.status, 413);
});

test("Cloudflare 拒绝邮件时返回可重试错误", async (t) => {
  const originalConsoleError = console.error;
  t.after(() => {
    console.error = originalConsoleError;
  });
  console.error = () => undefined;

  const response = await handleRequest(
    createRequest(VALID_REPORT),
    createEnv({ sendError: new Error("rate limited") }),
  );
  assert.equal(response.status, 502);
  assert.deepEqual(await response.json(), { error: "email_delivery_failed" });
});

test("除举报接口外不暴露网页", async () => {
  const response = await handleRequest(new Request("https://worker.example/"), createEnv());
  assert.equal(response.status, 404);
});

function createRequest(report) {
  return new Request("https://worker.example/ai-content-reports", {
    method: "POST",
    headers: {
      "CF-Connecting-IP": "203.0.113.10",
      "Content-Type": "application/json; charset=utf-8",
      "X-EveryTalk-Report-Version": "1",
    },
    body: JSON.stringify(report),
  });
}

function createEnv({
  rateLimitSuccess = true,
  sendError = null,
  onRateLimit = () => undefined,
  onSend = () => undefined,
} = {}) {
  return {
    REPORT_FROM_EMAIL: "reports@example.com",
    REPORT_TO_EMAIL: "owner@example.com",
    REPORT_EMAIL: {
      async send(email) {
        if (sendError) throw sendError;
        onSend(email);
      },
    },
    REPORT_RATE_LIMITER: {
      async limit({ key }) {
        onRateLimit(key);
        return { success: rateLimitSuccess };
      },
    },
  };
}
