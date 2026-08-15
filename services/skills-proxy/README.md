# EveryTalk Skill 转发服务

部署到 Vercel 后，为 Android 配置 `SKILL_CATALOG_BASE_URL=https://你的域名/v1/skills`。

服务只访问 `skills.sh` 和目录条目对应的 GitHub 仓库。`GITHUB_TOKEN` 可选，用于提高 GitHub 下载限额。Vercel OIDC 由 `@vercel/oidc` 在运行时获取，不进入 Android 客户端。

本地检查：

```powershell
npm install
npm run check
```
