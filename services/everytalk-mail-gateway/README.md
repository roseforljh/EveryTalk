# EveryTalk 邮件网关

QQ 和网易邮箱的 IMAP/SMTP 连接不能由 Cloudflare Worker 直接建立，因此需要部署这个 Node 服务。服务只接收 HTTPS MCP 请求，不保存邮箱密码、授权码或邮件正文。

## 配置

```powershell
$env:MAIL_GATEWAY_SHARED_KEY = "随机生成的 32 位以上密钥"
$env:PORT = "8787"
npm install
npm start
```

反向代理必须启用 HTTPS，并把 `/mcp/mail` 转发到本服务。EveryTalk 设置页的“网关密钥”必须和 `MAIL_GATEWAY_SHARED_KEY` 完全一致。

QQ 邮箱需要在网页设置里开启 IMAP/SMTP 并生成授权码；网易邮箱同样需要开启客户端协议并生成授权码。账号输入完整邮箱地址，授权码不是登录密码。

微软邮箱不使用此网关，走 `services/everytalk-oauth` 的 Microsoft Graph OAuth。
