import test from 'node:test';
import assert from 'node:assert/strict';
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

test('gateway adapter queues messages before gateway socket opens', () => {
  const socket = {
    readyState: 0,
    sent: [],
    handlers: new Map(),
    on(event, handler) {
      this.handlers.set(event, handler);
    },
    send(payload) {
      this.sent.push(payload);
    },
    emit(event, value) {
      const handler = this.handlers.get(event);
      if (handler) handler(value);
    },
    ping() {},
    terminate() {},
  };

  const adapter = new OpenClawGatewayAdapter({
    gatewayUrl: 'ws://127.0.0.1:18789',
    gatewayToken: 'token',
    webSocketFactory: () => socket,
    logger: { info() {}, warn() {}, error() {}, debug() {} },
  });

  adapter.connect();
  adapter.sendGatewayPayload({ id: 'req-1', method: 'chat.send', params: { text: 'hello' } });
  assert.equal(socket.sent.length, 0);

  socket.readyState = 1;
  socket.emit('open');

  assert.equal(socket.sent.length, 1);
  assert.equal(socket.sent[0], JSON.stringify({ id: 'req-1', method: 'chat.send', params: { text: 'hello' } }));
});
