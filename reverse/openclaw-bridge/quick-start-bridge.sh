#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

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
PUBLIC_HOST="${OPENCLAW_PUBLIC_HOST:-}"
ENV_FILE=".env.bridge"
PID_FILE=".openclaw-bridge.pid"
LOG_FILE="openclaw-bridge.log"

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[ERROR] 缺少命令: $1" >&2
    exit 1
  fi
}

random_token() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 24
  else
    tr -dc 'A-Za-z0-9' </dev/urandom | head -c 48
  fi
}

detect_host() {
  if [[ -n "$PUBLIC_HOST" ]]; then
    echo "$PUBLIC_HOST"
    return
  fi

  if command -v curl >/dev/null 2>&1; then
    local ip
    ip="$(curl -4 -fsS --max-time 3 https://api.ipify.org 2>/dev/null || true)"
    if [[ -n "$ip" ]]; then
      echo "$ip"
      return
    fi
  fi

  if command -v hostname >/dev/null 2>&1; then
    local ip
    ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
    if [[ -n "$ip" ]]; then
      echo "$ip"
      return
    fi
  fi

  echo "<你的服务器IP或域名>"
}

ensure_token() {
  local current="$1"
  if [[ -n "$current" ]]; then
    echo "$current"
  else
    random_token
  fi
}

stop_existing() {
  if [[ -f "$PID_FILE" ]]; then
    local pid
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then
      echo "[INFO] 检测到旧 bridge 进程，正在停止 PID=$pid"
      kill "$pid" >/dev/null 2>&1 || true
      sleep 1
    fi
    rm -f "$PID_FILE"
  fi
}

need_cmd node
need_cmd npm
need_cmd bash

if [[ -z "$GATEWAY_TOKEN" ]]; then
  echo "[ERROR] 请先设置 OPENCLAW_GATEWAY_TOKEN，再运行本脚本。" >&2
  echo "示例: OPENCLAW_GATEWAY_TOKEN=你的网关token bash quick-start-bridge.sh" >&2
  exit 1
fi

BRIDGE_TOKEN="$(ensure_token "$BRIDGE_TOKEN")"
PUBLIC_HOST="$(detect_host)"

cat > "$ENV_FILE" <<EOF
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

echo "[INFO] 安装依赖中..."
npm install --omit=dev >/dev/null

stop_existing

echo "[INFO] 正在启动 OpenClaw Bridge..."
set -a
source "$ENV_FILE"
set +a
nohup node src/server.js > "$LOG_FILE" 2>&1 &
BRIDGE_PID=$!
echo "$BRIDGE_PID" > "$PID_FILE"
sleep 2

if ! kill -0 "$BRIDGE_PID" >/dev/null 2>&1; then
  echo "[ERROR] Bridge 启动失败，请查看日志: $ROOT_DIR/$LOG_FILE" >&2
  tail -n 50 "$LOG_FILE" || true
  exit 1
fi

WS_BASE="ws://$PUBLIC_HOST:$BRIDGE_PORT$BRIDGE_PATH"
HEALTH_URL="http://$PUBLIC_HOST:$BRIDGE_PORT$HEALTH_PATH"

cat <<EOF

========================================
 OpenClaw Bridge 已启动
========================================
PID: $BRIDGE_PID
日志: $ROOT_DIR/$LOG_FILE
环境文件: $ROOT_DIR/$ENV_FILE

ET 端请填写：
地址: $WS_BASE
Key: $BRIDGE_TOKEN

健康检查：
$HEALTH_URL

注意：
1. 你暴露给外部的是 Bridge，不是 OpenClaw Gateway
2. 如果后续自己做反代，只需要把 $BRIDGE_PORT 转出去
3. 若想固定域名，重新执行时带上：
   OPENCLAW_PUBLIC_HOST=你的域名 bash quick-start-bridge.sh
========================================
EOF
