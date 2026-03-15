import test from 'node:test';
import assert from 'node:assert/strict';
import { loadBridgeConfig } from '../src/config.js';
import {
  buildUnauthorizedEnvelope,
  isAuthorizedRequest,
  normalizeBridgePath,
  shouldHandleBridgePath,
} from '../src/security.js';

test('loadBridgeConfig exposes deployable defaults', () => {
  const config = loadBridgeConfig({});

  assert.equal(config.host, '0.0.0.0');
  assert.equal(config.port, 8787);
  assert.equal(config.bridgePath, '/ws');
  assert.equal(config.healthPath, '/health');
});

test('normalizeBridgePath keeps leading slash', () => {
  assert.equal(normalizeBridgePath('bridge/ws'), '/bridge/ws');
  assert.equal(normalizeBridgePath('/ws'), '/ws');
});

test('shouldHandleBridgePath matches exact configured path', () => {
  assert.equal(shouldHandleBridgePath('/ws', '/ws'), true);
  assert.equal(shouldHandleBridgePath('/bridge/ws', '/ws'), false);
});

test('isAuthorizedRequest accepts bearer token and query token', () => {
  const bridgeToken = 'secret';
  assert.equal(isAuthorizedRequest({ headers: { authorization: 'Bearer secret' }, url: '/ws' }, bridgeToken), true);
  assert.equal(isAuthorizedRequest({ headers: {}, url: '/ws?token=secret' }, bridgeToken), true);
  assert.equal(isAuthorizedRequest({ headers: {}, url: '/ws?token=wrong' }, bridgeToken), false);
});

test('buildUnauthorizedEnvelope returns bridge error payload', () => {
  assert.deepEqual(buildUnauthorizedEnvelope(), {
    type: 'bridge.chat.error',
    requestId: 'unauthorized',
    payload: { message: 'Unauthorized' },
  });
});
