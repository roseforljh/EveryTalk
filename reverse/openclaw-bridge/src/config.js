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
    shutdownGraceMs: Number(env.OPENCLAW_BRIDGE_SHUTDOWN_GRACE_MS || 5000),
  };
}
