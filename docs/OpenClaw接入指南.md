# OpenClaw 接入指南

本文档说明如何通过 `EveryTalk -> VPS Bridge -> OpenClaw Gateway(127.0.0.1)` 的方式接入 OpenClaw，并实现随时随地通过 ET 控制龙虾。

## 1. 最终架构

```text
EveryTalk
  -> OpenClaw Bridge
  -> OpenClaw Gateway (ws://127.0.0.1:18789)
  -> 龙虾
```

关键原则：
- ET 不直接连接 VPS 本机 `127.0.0.1`
- 对外暴露的是 Bridge，不是 OpenClaw Gateway
- OpenClaw Gateway 仍只监听 VPS 本机回环地址
- 反代、域名、TLS 由用户后续自行处理，脚本默认直接输出公网 IP + 端口

## 2. 最终推荐安装方式

推荐只保留这一种安装方式：

```bash
curl -fsSL https://claw.everytalk.cc | bash
```

脚本会交互式询问：
- OpenClaw Gateway Token
- Bridge 对外端口（默认 `8787`）

然后自动完成：
- 在服务器上部署 OpenClaw Bridge
- 自动探测公网 IP
- 自动生成 Bridge Token
- 自动启动服务
- 最后直接输出 ET 可填写的地址与 Key

## 3. 脚本执行完成后会输出什么

脚本完成后会直接打印类似：

```text
ET 端请填写：
地址: ws://你的公网IP:8787/ws
Key: 自动生成的 Bridge Token
```

同时还会给出健康检查地址：

```text
http://你的公网IP:8787/health
```

## 4. ET 端最终填写方式

在 EveryTalk 中新增或编辑 OpenClaw 配置时：

### 必填项

- 渠道：`OpenClaw`
- 地址：脚本输出的 Bridge 地址
- Key：脚本输出的 Bridge Token

### 示例

```text
地址: ws://123.45.67.89:8787/ws
Key: xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### 不要填错

不要把下面这个地址填到 ET：

```text
ws://127.0.0.1:18789
```

这个地址只给 VPS 本机 Bridge 使用，不能给手机上的 ET 使用。

## 5. VPS 侧内部运行参数

虽然用户不需要手动部署整个项目，但脚本内部实际仍会按以下参数启动 Bridge：

```bash
NODE_ENV=production

OPENCLAW_BRIDGE_HOST=0.0.0.0
OPENCLAW_BRIDGE_PORT=8787
OPENCLAW_BRIDGE_PATH=/ws
OPENCLAW_BRIDGE_HEALTH_PATH=/health
OPENCLAW_BRIDGE_TOKEN=自动生成或用户指定

OPENCLAW_GATEWAY_URL=ws://127.0.0.1:18789
OPENCLAW_GATEWAY_TOKEN=用户交互输入

OPENCLAW_BRIDGE_CLIENT_PING_INTERVAL_MS=30000
OPENCLAW_BRIDGE_GATEWAY_RECONNECT_DELAY_MS=2000
OPENCLAW_BRIDGE_SHUTDOWN_GRACE_MS=5000
```

说明：
- `OPENCLAW_BRIDGE_TOKEN`：ET 连接 Bridge 时使用
- `OPENCLAW_GATEWAY_TOKEN`：Bridge 连接 OpenClaw Gateway 时使用
- 两个 token 建议保持不同

## 6. 运行链路说明

ET 发起请求时：

1. ET 连接 Bridge
2. Bridge 校验 Bridge Token
3. Bridge 在 VPS 本机连接 `ws://127.0.0.1:18789`
4. Bridge 转发 `chat.send/chat.abort/chat.history`
5. Gateway 的流式事件被 Bridge 转成 ET 可消费的事件流

## 7. 当前已实现内容

当前项目已完成：
- Android 侧 OpenClaw Provider 接入
- BridgeTransport / DirectTransport 抽象
- OpenClaw Bridge 服务骨架
- `chat.send` / `chat.abort` / 基础事件映射
- Bridge 路径、健康检查、鉴权
- 客户端保活、网关断线后重新建连基础
- PM2 / systemd / Docker 部署入口
- 远程单文件安装脚本

## 8. Cloudflare Worker 入口

推荐把 `claw.everytalk.cc` 配成 Cloudflare Worker，然后由 Worker 返回 GitHub 上的原始安装脚本文本。

这样最终用户只需要记住：

```bash
curl -fsSL https://claw.everytalk.cc | bash
```

而不需要关心 GitHub Raw 地址。

## 9. 建议上线方案

如果目标是“任何时间任何地点通过 ET 控制龙虾”，推荐最终配置是：

```text
ET
  -> ws://公网IP:端口/ws
  -> VPS OpenClaw Bridge
  -> ws://127.0.0.1:18789
  -> OpenClaw Gateway / 龙虾
```

后续如果用户自己做反代或 TLS，则可以再把 ET 地址改成：

```text
wss://你的域名/ws
```
