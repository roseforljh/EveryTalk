# EveryTalk OAuth Worker

部署前设置 OAuth 配置：

1. bun install
2. bunx wrangler secret put GITHUB_MCP_OAUTH_CLIENT_SECRET --name everytalk-oauth
3. bunx wrangler secret put GMAIL_MCP_OAUTH_CLIENT_SECRET --name everytalk-oauth
4. bunx wrangler deploy --config wrangler.jsonc

GitHub 和 Gmail 的 Client ID 可以作为公开变量；Secret 只存在 Worker Secret，不能写入 Android local.properties。

## Gmail MCP

Worker 已提供以下固定地址：

- OAuth 回调：`https://oauth.everytalk.cc/oauth/mcp/gmail`
- Token 交换：`https://oauth.everytalk.cc/oauth/mcp/gmail/token`
- MCP 服务：`https://oauth.everytalk.cc/mcp/gmail`

在 Google Cloud 中需要完成：

1. 创建或选择项目，并启用 **Gmail API**。
2. 配置 OAuth consent screen。测试阶段把 Gmail 账号加入测试用户；如果要面向非测试用户，`gmail.modify` 属于敏感/受限范围，可能需要 Google 审核。
3. 创建 **Web application** OAuth Client ID。
4. 在 Authorized redirect URIs 中加入 `https://oauth.everytalk.cc/oauth/mcp/gmail`。
5. 把 Client ID 填到 `wrangler.jsonc` 的 `GMAIL_MCP_OAUTH_CLIENT_ID`，把 Client Secret 写入 Cloudflare Worker Secret。
6. 构建 APK 时把同一个 Client ID 写入 `app1/local.properties` 的 `GMAIL_MCP_OAUTH_CLIENT_ID`。

ET 只在安卓端加密保存 Google access/refresh token。Worker 不保存 Token、邮件正文或用户资料；MCP 当前提供搜索、读取、发送纯文本邮件、列出标签和增删标签。发送邮件是有副作用的操作，ET 的模型调用仍应在用户明确要求时执行。
