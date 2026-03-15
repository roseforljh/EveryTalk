#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${OPENCLAW_BRIDGE_APP_DIR:-/opt/openclaw-bridge}"
BRIDGE_HOST="${OPENCLAW_BRIDGE_HOST:-0.0.0.0}"
BRIDGE_PORT="${OPENCLAW_BRIDGE_PORT:-8787}"
BRIDGE_PATH="${OPENCLAW_BRIDGE_PATH:-/ws}"
HEALTH_PATH="${OPENCLAW_BRIDGE_HEALTH_PATH:-/health}"
GATEWAY_URL="${OPENCLAW_GATEWAY_URL:-ws://127.0.0.1:18789}"
GATEWAY_TOKEN="${OPENCLAW_GATEWAY_TOKEN:-}"
BRIDGE_TOKEN="${OPENCLAW_BRIDGE_TOKEN:-}"
PING_MS="${OPENCLAW_BRIDGE_CLIENT_PING_INTERVAL_MS:-30000}"
RECONNECT_MS="${OPENCLAW_BRIDGE_GATEWAY_RECONNECT_DELAY_MS:-2000}"
SHUTDOWN_MS="${OPENCLAW_BRIDGE_SHUTDOWN_GRACE_MS:-5000}"
USE_SYSTEMD="${OPENCLAW_BRIDGE_USE_SYSTEMD:-1}"

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "[ERROR] 缺少命令: $1" >&2
    exit 1
  }
}

rand_token() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 24
  else
    tr -dc 'A-Za-z0-9' </dev/urandom | head -c 48
  fi
}

normalize_path() {
  local p="${1:-/ws}"
  p="${p//[$'\r\n\t ']/}"
  [[ -z "$p" ]] && p="/ws"
  [[ "$p" != /* ]] && p="/$p"
  echo "$p"
}

detect_public_host() {
  if command -v curl >/dev/null 2>&1; then
    local ip
    ip="$(curl -4 -fsS --max-time 4 https://api.ipify.org 2>/dev/null || true)"
    [[ -n "$ip" ]] && { echo "$ip"; return; }
  fi
  if command -v hostname >/dev/null 2>&1; then
    local ip
    ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
    [[ -n "$ip" ]] && { echo "$ip"; return; }
  fi
  echo "<你的服务器公网IP>"
}

prompt_input() {
  local prompt_text="$1"
  local default_value="${2:-}"
  local secret_mode="${3:-0}"
  local value=""

  if [[ "$secret_mode" == "1" ]]; then
    while [[ -z "$value" ]]; do
      if [[ -n "$default_value" ]]; then
        read -r -s -p "$prompt_text [默认隐藏值]: " value || true
      else
        read -r -s -p "$prompt_text: " value || true
      fi
      echo
      [[ -z "$value" ]] && value="$default_value"
    done
  else
    if [[ -n "$default_value" ]]; then
      read -r -p "$prompt_text [$default_value]: " value || true
      [[ -z "$value" ]] && value="$default_value"
    else
      while [[ -z "$value" ]]; do
        read -r -p "$prompt_text: " value || true
      done
    fi
  fi

  printf '%s' "$value"
}

need_cmd node
need_cmd npm
need_cmd bash

if [[ -z "$GATEWAY_TOKEN" ]]; then
  GATEWAY_TOKEN="$(prompt_input "请输入 OpenClaw Gateway Token" "" 1)"
fi

BRIDGE_PORT="$(prompt_input "请输入 Bridge 对外端口" "$BRIDGE_PORT")"
BRIDGE_PATH="$(normalize_path "$BRIDGE_PATH")"
HEALTH_PATH="$(normalize_path "$HEALTH_PATH")"
[[ -z "$BRIDGE_TOKEN" ]] && BRIDGE_TOKEN="$(rand_token)"
PUBLIC_HOST="$(detect_public_host)"

mkdir -p "$APP_DIR/src"
cd "$APP_DIR"

cat > package.json <<'EOF'
{
  "name": "openclaw-bridge",
  "private": true,
  "type": "module",
  "scripts": {
    "start": "node src/server.js"
  },
  "dependencies": {
    "ws": "^8.19.0"
  }
}
EOF

cat > src/logger.js <<'EOF'
export function createLogger(scope = 'openclaw-bridge') {
  function log(level, message, extra) {
    const timestamp = new Date().toISOString();
    const suffix = extra ? ` ${JSON.stringify(extra)}` : '';
    console.log(`[${timestamp}] [${scope}] [${level}] ${message}${suffix}`);
  }

  return {
    debug(message, extra) { log('DEBUG', message, extra); },
    info(message, extra) { log('INFO', message, extra); },
    warn(message, extra) { log('WARN', message, extra); },
    error(message, extra) { log('ERROR', message, extra); },
  };
}
EOF

cat > src/security.js <<'EOF'
export function normalizeBridgePath(path) {
  const trimmed = (path || '/ws').trim();
  if (!trimmed) return '/ws';
  return trimmed.startsWith('/') ? trimmed : `/${trimmed}`;
}

export function shouldHandleBridgePath(requestPath, bridgePath) {
  const current = (requestPath || '/').split('?')[0];
  return current === normalizeBridgePath(bridgePath);
}

export function isAuthorizedRequest(request, bridgeToken) {
  const expected = (bridgeToken || '').trim();
  if (!expected) return true;

  const authHeader = request.headers?.authorization || '';
  if (authHeader === `Bearer ${expected}`) return true;

  try {
    const url = new URL(request.url || '/', 'http://localhost');
    return url.searchParams.get('token') === expected;
  } catch {
    return false;
  }
}

export function buildUnauthorizedEnvelope() {
  return {
    type: 'bridge.chat.error',
    requestId: 'unauthorized',
    payload: { message: 'Unauthorized' }
  };
}
EOF

cat > src/protocol.js <<'EOF'
export function buildBridgeConnectAck(requestId, sessionKey) {
  return {
    type: 'bridge.chat.ack',
    requestId,
    sessionKey,
    payload: { stage: 'connected' }
  };
}

export function buildBridgeAck(requestId, sessionKey, stage = 'accepted') {
  return {
    type: 'bridge.chat.ack',
    requestId,
    sessionKey,
    payload: { stage }
  };
}

export function buildBridgeDelta(requestId, sessionKey, runId, text) {
  return {
    type: 'bridge.chat.delta',
    requestId,
    sessionKey,
    runId,
    payload: { text }
  };
}

export function buildBridgeDone(requestId, sessionKey, runId) {
  return {
    type: 'bridge.chat.done',
    requestId,
    sessionKey,
    runId,
    payload: { reason: 'completed' }
  };
}

export function buildBridgeError(requestId, sessionKey, message) {
  return {
    type: 'bridge.chat.error',
    requestId,
    sessionKey,
    payload: { message }
  };
}

export function mapBridgeEnvelopeToGatewayRequest(envelope) {
  if (envelope.type === 'bridge.chat.send') {
    return {
      id: envelope.requestId,
      method: 'chat.send',
      params: {
        sessionKey: envelope.sessionKey,
        text: envelope.payload?.text || '',
        agentId: envelope.payload?.agentId || null,
        idempotencyKey: envelope.requestId
      }
    };
  }

  if (envelope.type === 'bridge.chat.abort') {
    return {
      id: envelope.requestId,
      method: 'chat.abort',
      params: {
        sessionKey: envelope.sessionKey,
        runId: envelope.runId || envelope.payload?.runId || null
      }
    };
  }

  if (envelope.type === 'bridge.chat.history') {
    return {
      id: envelope.requestId,
      method: 'chat.history',
      params: { sessionKey: envelope.sessionKey }
    };
  }

  return null;
}

export function mapGatewayEventToBridgeEnvelope(message, requestId) {
  const data = message?.data || {};
  if (message?.event !== 'chat') return null;
  if (data.type === 'delta') return buildBridgeDelta(requestId, data.sessionKey, data.runId, data.text || '');
  if (data.type === 'done') return buildBridgeDone(requestId, data.sessionKey, data.runId);
  if (data.type === 'error') return buildBridgeError(requestId, data.sessionKey, data.message || 'Gateway error');
  return null;
}
EOF

cat > src/config.js <<'EOF'
import { normalizeBridgePath } from './security.js';

export function loadBridgeConfig(env = process.env) {
  return {
    port: Number(env.OPENCLAW_BRIDGE_PORT || 8787),
    host: env.OPENCLAW_BRIDGE_HOST || '0.0.0.0',
    bridgePath: normalizeBridgePath(env.OPENCLAW_BRIDGE_PATH || '/ws'),
    healthPath: normalizeBridgePath(env.OPENCLAW_BRIDGE_HEALTH_PATH || '/health'),
    bridgeToken: env.OPENCLAW_BRIDGE_TOKEN || '',
    gatewayUrl: env.OPENCLAW_GATEWAY_URL || 'ws://127.0.0.1:18789',
    gatewayToken: env.OPENCLAW_GATEWAY_TOKEN || '',
    clientPingIntervalMs: Number(env.OPENCLAW_BRIDGE_CLIENT_PING_INTERVAL_MS || 30000),
    gatewayReconnectDelayMs: Number(env.OPENCLAW_BRIDGE_GATEWAY_RECONNECT_DELAY_MS || 2000),
    shutdownGraceMs: Number(env.OPENCLAW_BRIDGE_SHUTDOWN_GRACE_MS || 5000)
  };
}
EOF

cat > src/gateway-adapter.js <<'EOF'
import {
  buildBridgeAck,
  buildBridgeConnectAck,
  buildBridgeError,
  mapBridgeEnvelopeToGatewayRequest,
  mapGatewayEventToBridgeEnvelope
} from './protocol.js';
import { createLogger } from './logger.js';

const OPEN_STATE = 1;

export class OpenClawGatewayAdapter {
  constructor({ gatewayUrl, gatewayToken, webSocketFactory, logger = createLogger('gateway-adapter') }) {
    this.gatewayUrl = gatewayUrl;
    this.gatewayToken = gatewayToken;
    this.webSocketFactory = webSocketFactory;
    this.logger = logger;
    this.gatewaySocket = null;
    this.pendingMessages = [];
    this.gatewayListenersBound = false;
  }

  connect() {
    if (this.gatewaySocket && this.gatewaySocket.readyState !== undefined && this.gatewaySocket.readyState !== 3) {
      return this.gatewaySocket;
    }

    this.gatewaySocket = this.webSocketFactory(this.gatewayUrl, {
      headers: this.gatewayToken ? { Authorization: `Bearer ${this.gatewayToken}` } : undefined
    });
    this.gatewayListenersBound = false;
    this.bindGatewayLifecycle();
    this.logger.info('gateway socket created', { gatewayUrl: this.gatewayUrl });
    return this.gatewaySocket;
  }

  bindGatewayLifecycle() {
    if (!this.gatewaySocket || this.gatewayListenersBound || typeof this.gatewaySocket.on !== 'function') return;

    this.gatewayListenersBound = true;
    this.gatewaySocket.on('open', () => {
      this.logger.info('gateway socket opened');
      this.flushPendingMessages();
    });
    this.gatewaySocket.on('close', () => {
      this.logger.warn('gateway socket closed');
      this.gatewaySocket = null;
      this.gatewayListenersBound = false;
    });
    this.gatewaySocket.on('error', (error) => {
      this.logger.error('gateway socket error', { message: error?.message || 'unknown' });
    });
  }

  sendGatewayPayload(payload) {
    const encoded = JSON.stringify(payload);
    const socket = this.connect();
    if (socket.readyState === OPEN_STATE) {
      socket.send(encoded);
      return;
    }
    this.pendingMessages.push(encoded);
  }

  flushPendingMessages() {
    if (!this.gatewaySocket || this.gatewaySocket.readyState !== OPEN_STATE) return;
    while (this.pendingMessages.length > 0) {
      this.gatewaySocket.send(this.pendingMessages.shift());
    }
  }

  handleBridgeEnvelope(envelope, sendToClient) {
    if (envelope.type === 'bridge.connect') {
      sendToClient(buildBridgeConnectAck(envelope.requestId, envelope.sessionKey));
      return;
    }

    const gatewayRequest = mapBridgeEnvelopeToGatewayRequest(envelope);
    if (!gatewayRequest) {
      sendToClient(buildBridgeError(envelope.requestId, envelope.sessionKey, `Unsupported bridge message: ${envelope.type}`));
      return;
    }

    sendToClient(buildBridgeAck(envelope.requestId, envelope.sessionKey, 'accepted'));
    this.sendGatewayPayload(gatewayRequest);
  }

  handleGatewayMessage(rawMessage, requestId, sendToClient) {
    const parsed = JSON.parse(rawMessage);
    const envelope = mapGatewayEventToBridgeEnvelope(parsed, requestId);
    if (envelope) sendToClient(envelope);
  }
}
EOF

cat > src/server.js <<'EOF'
import http from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import { loadBridgeConfig } from './config.js';
import { OpenClawGatewayAdapter } from './gateway-adapter.js';
import { createLogger } from './logger.js';
import {
  buildUnauthorizedEnvelope,
  isAuthorizedRequest,
  shouldHandleBridgePath
} from './security.js';

function createNodeWebSocket(url, options = {}) {
  return new WebSocket(url, options);
}

export function startBridgeServer({
  config = loadBridgeConfig(),
  webSocketFactory = createNodeWebSocket,
  logger = createLogger()
} = {}) {
  const server = http.createServer((req, res) => {
    const path = (req.url || '/').split('?')[0];
    if (path === config.healthPath) {
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ ok: true, service: 'openclaw-bridge' }));
      return;
    }

    res.writeHead(404, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ ok: false, message: 'Not Found' }));
  });

  const wss = new WebSocketServer({ noServer: true });
  const clientHeartbeat = new Map();

  const pingTimer = setInterval(() => {
    for (const client of wss.clients) {
      const alive = clientHeartbeat.get(client);
      if (alive === false) {
        logger.warn('terminating stale client');
        client.terminate();
        clientHeartbeat.delete(client);
        continue;
      }
      clientHeartbeat.set(client, false);
      try {
        client.ping();
      } catch (error) {
        logger.warn('client ping failed', { message: error?.message || 'unknown' });
      }
    }
  }, config.clientPingIntervalMs);

  server.on('upgrade', (request, socket, head) => {
    if (!shouldHandleBridgePath(request.url || '/', config.bridgePath)) {
      socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
      socket.destroy();
      return;
    }

    if (!isAuthorizedRequest(request, config.bridgeToken)) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      socket.destroy();
      return;
    }

    wss.handleUpgrade(request, socket, head, (client) => {
      wss.emit('connection', client, request);
    });
  });

  wss.on('connection', (client, request) => {
    clientHeartbeat.set(client, true);
    client.on('pong', () => clientHeartbeat.set(client, true));
    client.on('close', () => clientHeartbeat.delete(client));

    const adapter = new OpenClawGatewayAdapter({
      gatewayUrl: config.gatewayUrl,
      gatewayToken: config.gatewayToken,
      webSocketFactory,
      logger
    });

    logger.info('client connected', { remoteAddress: request.socket.remoteAddress });

    client.on('message', (raw) => {
      try {
        const envelope = JSON.parse(raw.toString());
        const gatewaySocket = adapter.connect();
        gatewaySocket.onmessage = (event) => {
          adapter.handleGatewayMessage(event.data.toString(), envelope.requestId, (payload) => {
            client.send(JSON.stringify(payload));
          });
        };
        adapter.handleBridgeEnvelope(envelope, (payload) => {
          client.send(JSON.stringify(payload));
        });
      } catch (error) {
        logger.error('bridge message handling failed', { message: error?.message || 'unknown' });
        client.send(JSON.stringify({
          ...buildUnauthorizedEnvelope(),
          requestId: 'unknown',
          payload: { message: error.message || 'Bridge failure' }
        }));
      }
    });
  });

  server.listen(config.port, config.host, () => {
    logger.info('bridge server listening', {
      host: config.host,
      port: config.port,
      bridgePath: config.bridgePath,
      healthPath: config.healthPath
    });
  });

  const shutdown = () => {
    logger.warn('shutdown signal received');
    clearInterval(pingTimer);
    for (const client of wss.clients) {
      client.close(1001, 'Server shutdown');
    }
    setTimeout(() => {
      wss.close(() => server.close(() => process.exit(0)));
    }, config.shutdownGraceMs).unref();
  };

  process.once('SIGINT', shutdown);
  process.once('SIGTERM', shutdown);

  return server;
}

startBridgeServer();
EOF

cat > .env <<EOF
NODE_ENV=production
OPENCLAW_BRIDGE_HOST=$BRIDGE_HOST
OPENCLAW_BRIDGE_PORT=$BRIDGE_PORT
OPENCLAW_BRIDGE_PATH=$BRIDGE_PATH
OPENCLAW_BRIDGE_HEALTH_PATH=$HEALTH_PATH
OPENCLAW_BRIDGE_TOKEN=$BRIDGE_TOKEN
OPENCLAW_GATEWAY_URL=$GATEWAY_URL
OPENCLAW_GATEWAY_TOKEN=$GATEWAY_TOKEN
OPENCLAW_BRIDGE_CLIENT_PING_INTERVAL_MS=$PING_MS
OPENCLAW_BRIDGE_GATEWAY_RECONNECT_DELAY_MS=$RECONNECT_MS
OPENCLAW_BRIDGE_SHUTDOWN_GRACE_MS=$SHUTDOWN_MS
EOF

echo "[INFO] 安装依赖..."
npm install --omit=dev

if [[ "$USE_SYSTEMD" == "1" ]] && command -v systemctl >/dev/null 2>&1 && [[ $(id -u) -eq 0 ]]; then
  cat > /etc/systemd/system/openclaw-bridge.service <<EOF
[Unit]
Description=OpenClaw Bridge
After=network.target

[Service]
Type=simple
WorkingDirectory=$APP_DIR
EnvironmentFile=$APP_DIR/.env
ExecStart=$(command -v node) src/server.js
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable openclaw-bridge >/dev/null 2>&1 || true
  systemctl restart openclaw-bridge
  START_MODE="systemd"
else
  pkill -f "node src/server.js" >/dev/null 2>&1 || true
  nohup env $(tr '\n' ' ' < .env) node src/server.js > bridge.log 2>&1 &
  echo $! > bridge.pid
  START_MODE="nohup"
fi

sleep 2

WS_URL="ws://$PUBLIC_HOST:$BRIDGE_PORT$BRIDGE_PATH"
HEALTH_URL="http://$PUBLIC_HOST:$BRIDGE_PORT$HEALTH_PATH"

cat <<EOF

========================================
 OpenClaw Bridge 远程部署完成
========================================
部署目录: $APP_DIR
启动方式: $START_MODE

ET 端请填写：
地址: $WS_URL
Key: $BRIDGE_TOKEN

健康检查：
$HEALTH_URL

说明：
1. 已自动探测公网 IP 并生成 ET 可直接填写地址
2. 现在对外暴露的是 Bridge，不是 OpenClaw Gateway
3. 你后续如果要做反代，只反代 $BRIDGE_PORT 即可
4. Gateway 仍然只走 VPS 本机: $GATEWAY_URL
========================================
EOF
