# EveryTalk OAuth Worker

部署前设置 GitHub OAuth App 的 Client Secret：

1. bun install
2. bunx wrangler secret put GITHUB_MCP_OAUTH_CLIENT_SECRET --name everytalk-oauth
3. bunx wrangler deploy --config wrangler.jsonc

Client ID 可以作为公开变量；Secret 只存在 Worker Secret，不能写入 Android local.properties。
