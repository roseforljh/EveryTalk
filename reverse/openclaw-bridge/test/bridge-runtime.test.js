import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { loadBridgeConfig } from '../src/config.js';
import { OpenClawGatewayAdapter } from '../src/gateway-adapter.js';

test('loadBridgeConfig exposes runtime hardening defaults', () => {
  const config = loadBridgeConfig({});

  assert.equal(config.clientPingIntervalMs, 30000);
  assert.equal(config.gatewayReconnectDelayMs, 2000);
  assert.equal(config.shutdownGraceMs, 5000);
});

test('gateway adapter reconnects after socket close', () => {
  const sockets = [];
  const factory = () => {
    const listeners = new Map();
    const socket = {
      readyState: 0,
      sent: [],
      on(event, handler) {
        listeners.set(event, handler);
      },
      send(payload) {
        this.sent.push(payload);
      },
      emit(event, value) {
        const handler = listeners.get(event);
        if (handler) handler(value);
      },
      ping() {},
      terminate() {},
    };
    sockets.push(socket);
    return socket;
  };

  const adapter = new OpenClawGatewayAdapter({
    gatewayUrl: 'ws://127.0.0.1:18789',
    gatewayToken: 'token',
    webSocketFactory: factory,
    logger: { info() {}, warn() {}, error() {}, debug() {} },
  });

  const first = adapter.connect();
  first.emit('close');
  const second = adapter.connect();

  assert.equal(sockets.length, 2);
  assert.notEqual(first, second);
});

test('gateway adapter connects with openclaw rpc subprotocol and headers', () => {
  const calls = [];
  const adapter = new OpenClawGatewayAdapter({
    gatewayUrl: 'ws://127.0.0.1:18789',
    gatewayToken: 'token',
    webSocketFactory: (url, options) => {
      calls.push({ url, options });
      return {
        readyState: 0,
        on() {},
        send() {},
        ping() {},
        terminate() {},
      };
    },
    logger: { info() {}, warn() {}, error() {}, debug() {} },
  });

  adapter.connect();

  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, 'ws://127.0.0.1:18789');
  assert.deepEqual(calls[0].options, {
    headers: {
      Authorization: 'Bearer token',
      'User-Agent': 'OpenClaw-Bridge/1.0',
    },
    protocol: 'openclaw-rpc',
  });
});

