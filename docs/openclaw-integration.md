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

## 2. VPS 侧部署参数

Bridge 服务目录：`reverse/openclaw-bridge/`

建议生产环境使用以下环境变量：

```bash
NODE_ENV=production

OPENCLAW_BRIDGE_HOST=0.0.0.0
OPENCLAW_BRIDGE_PORT=8787
OPENCLAW_BRIDGE_PATH=/ws
OPENCLAW_BRIDGE_HEALTH_PATH=/health
OPENCLAW_BRIDGE_TOKEN=替换成你自己的高强度随机字符串

OPENCLAW_GATEWAY_URL=ws://127.0.0.1:18789
OPENCLAW_GATEWAY_TOKEN=替换成你的 OpenClaw Gateway Token

OPENCLAW_BRIDGE_CLIENT_PING_INTERVAL_MS=30000
OPENCLAW_BRIDGE_GATEWAY_RECONNECT_DELAY_MS=2000
OPENCLAW_BRIDGE_SHUTDOWN_GRACE_MS=5000
```

说明：
- `OPENCLAW_BRIDGE_TOKEN`：ET 连接 Bridge 时使用
- `OPENCLAW_GATEWAY_TOKEN`：Bridge 连接 OpenClaw Gateway 时使用
- 这两个 token 建议分开，不要共用

## 3. Bridge 启动方式

### 3.1 直接启动

```bash
cd reverse/openclaw-bridge
npm install
npm run start:prod
```

### 3.2 PM2

项目已提供：`reverse/openclaw-bridge/ecosystem.config.cjs`

```bash
cd reverse/openclaw-bridge
pm2 start ecosystem.config.cjs
pm2 save
```

### 3.3 systemd

项目已提供：`reverse/openclaw-bridge/openclaw-bridge.service`

把它放到：

```bash
/etc/systemd/system/openclaw-bridge.service
```

然后执行：

```bash
sudo systemctl daemon-reload
sudo systemctl enable openclaw-bridge
sudo systemctl start openclaw-bridge
```

### 3.4 Docker

项目已提供：`reverse/openclaw-bridge/Dockerfile`

```bash
cd reverse/openclaw-bridge
docker build -t openclaw-bridge .
docker run -d \
  --name openclaw-bridge \
  -p 8787:8787 \
  -e OPENCLAW_BRIDGE_HOST=0.0.0.0 \
  -e OPENCLAW_BRIDGE_PORT=8787 \
  -e OPENCLAW_BRIDGE_PATH=/ws \
  -e OPENCLAW_BRIDGE_HEALTH_PATH=/health \
  -e OPENCLAW_BRIDGE_TOKEN=你的bridge-token \
  -e OPENCLAW_GATEWAY_URL=ws://127.0.0.1:18789 \
  -e OPENCLAW_GATEWAY_TOKEN=你的gateway-token \
  openclaw-bridge
```

## 4. 对外访问方式

推荐只暴露 Bridge：

- WebSocket：`wss://你的域名/ws`
- 健康检查：`https://你的域名/health`

也可以临时直接使用：

- `ws://你的公网IP:8787/ws`

但更推荐使用域名 + 反向代理 + TLS。

## 5. ET 端填写方式

在 EveryTalk 中新增或编辑 OpenClaw 配置时：

### 必填项

- 渠道：`OpenClaw`
- 地址：Bridge 地址，而不是 Gateway 地址
- Key：`OPENCLAW_BRIDGE_TOKEN`

### 推荐填写示例

如果你有域名：

```text
地址: wss://bridge.example.com/ws
Key: 你的 OPENCLAW_BRIDGE_TOKEN
```

如果你直接走端口：

```text
地址: ws://你的公网IP:8787/ws
Key: 你的 OPENCLAW_BRIDGE_TOKEN
```

### 不要填错

不要把下面这个地址填到 ET：

```text
ws://127.0.0.1:18789
```

这个地址只给 VPS 本机 Bridge 用，不能给手机上的 ET 用。

## 6. 运行链路说明

ET 发起请求时：

1. ET 连接 Bridge
2. Bridge 校验 `OPENCLAW_BRIDGE_TOKEN`
3. Bridge 在 VPS 本机连接 `OPENCLAW_GATEWAY_URL`
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

## 8. 建议上线方案

如果目标是“任何时间任何地点通过 ET 控制龙虾”，推荐最终配置是：

```text
ET
  -> wss://bridge.yourdomain.com/ws
  -> VPS OpenClaw Bridge
  -> ws://127.0.0.1:18789
  -> OpenClaw Gateway / 龙虾
```

推荐：
- 只暴露 Bridge
- Gateway 继续只监听本地回环
- Bridge token 与 Gateway token 分离
- 使用域名 + TLS
