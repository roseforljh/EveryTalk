# EveryTalk AI 内容举报邮件服务

这是一个无网页的 Cloudflare Worker。它只接收 EveryTalk App 发出的 `POST /ai-content-reports` 请求，并通过 Cloudflare Email Routing 将举报发送到固定的已验证邮箱。

## 安全边界

- 不使用邮箱密码、SMTP 密钥或第三方邮件 API Key。
- 真实发件地址、收件地址和自定义域名保存在本机 `wrangler.jsonc`，该文件不会进入 Git。
- 请求体上限为 32 KiB，只接受 Android 举报协议 v1 的固定字段。
- Cloudflare 按来源 IP 每分钟最多接收 10 次举报。
- 邮件正文会执行 HTML 转义，不加载外部图片或脚本。
- 除举报接口外，所有路径均返回 404。

## Cloudflare 前置配置

1. 在域名的 Email Routing 中启用邮件路由。
2. 添加并验证实际收件邮箱。
3. 将 `wrangler.jsonc.example` 复制为 `wrangler.jsonc`，填写自定义接口域名、发件地址和已验证收件地址。

## 验证与部署

```powershell
npm install
npm test
npm run deploy
```

部署完成后，将完整接口写入未纳入 Git 的 `app1/local.properties`：

```properties
AI_CONTENT_REPORT_URL=https://reports.everytalk.cc/ai-content-reports
```

Release AAB 必须在该配置生效后重新构建。Worker 只有在 Cloudflare 接受邮件后才返回成功；其他状态会让 App 保留举报并自动重试。
