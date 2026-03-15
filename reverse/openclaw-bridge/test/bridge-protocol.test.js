import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildBridgeAck,
  buildBridgeConnectAck,
  buildBridgeDelta,
  buildBridgeDone,
  buildBridgeError,
  mapBridgeEnvelopeToGatewayRequest,
  mapGatewayEventToBridgeEnvelope,
} from '../src/protocol.js';

test('maps bridge chat send envelope to gateway request', () => {
  const request = mapBridgeEnvelopeToGatewayRequest({
    type: 'bridge.chat.send',
    requestId: 'req-1',
    sessionKey: 'et:conv-1',
    payload: {
      text: 'hello',
      agentId: 'main',
    },
  });

  assert.deepEqual(request, {
    id: 'req-1',
    method: 'chat.send',
    params: {
      sessionKey: 'et:conv-1',
      text: 'hello',
      agentId: 'main',
      idempotencyKey: 'req-1',
    },
  });
});

test('maps bridge abort envelope to gateway request', () => {
  const request = mapBridgeEnvelopeToGatewayRequest({
    type: 'bridge.chat.abort',
    requestId: 'req-2',
    sessionKey: 'et:conv-1',
    runId: 'run-1',
    payload: {
      runId: 'run-1',
    },
  });

  assert.deepEqual(request, {
    id: 'req-2',
    method: 'chat.abort',
    params: {
      sessionKey: 'et:conv-1',
      runId: 'run-1',
    },
  });
});

test('maps gateway delta event to bridge delta envelope', () => {
  const envelope = mapGatewayEventToBridgeEnvelope({
    event: 'chat',
    data: {
      type: 'delta',
      sessionKey: 'et:conv-1',
      runId: 'run-1',
      text: 'partial',
    },
  }, 'req-1');

  assert.deepEqual(envelope, {
    type: 'bridge.chat.delta',
    requestId: 'req-1',
    sessionKey: 'et:conv-1',
    runId: 'run-1',
    payload: {
      text: 'partial',
    },
  });
});

test('maps gateway done event to bridge done envelope', () => {
  const envelope = mapGatewayEventToBridgeEnvelope({
    event: 'chat',
    data: {
      type: 'done',
      sessionKey: 'et:conv-1',
      runId: 'run-1',
    },
  }, 'req-1');

  assert.deepEqual(envelope, {
    type: 'bridge.chat.done',
    requestId: 'req-1',
    sessionKey: 'et:conv-1',
    runId: 'run-1',
    payload: {
      reason: 'completed',
    },
  });
});

test('builds bridge ack envelopes', () => {
  assert.deepEqual(buildBridgeConnectAck('req-1', 'et:conv-1'), {
    type: 'bridge.chat.ack',
    requestId: 'req-1',
    sessionKey: 'et:conv-1',
    payload: {
      stage: 'connected',
    },
  });

  assert.deepEqual(buildBridgeAck('req-2', 'et:conv-1', 'accepted'), {
    type: 'bridge.chat.ack',
    requestId: 'req-2',
    sessionKey: 'et:conv-1',
    payload: {
      stage: 'accepted',
    },
  });
});

test('builds bridge helper envelopes', () => {
  assert.deepEqual(buildBridgeDelta('req-1', 'et:conv-1', 'run-1', 'text'), {
    type: 'bridge.chat.delta',
    requestId: 'req-1',
    sessionKey: 'et:conv-1',
    runId: 'run-1',
    payload: {
      text: 'text',
    },
  });

  assert.deepEqual(buildBridgeDone('req-1', 'et:conv-1', 'run-1'), {
    type: 'bridge.chat.done',
    requestId: 'req-1',
    sessionKey: 'et:conv-1',
    runId: 'run-1',
    payload: {
      reason: 'completed',
    },
  });

  assert.deepEqual(buildBridgeError('req-1', 'et:conv-1', 'boom'), {
    type: 'bridge.chat.error',
    requestId: 'req-1',
    sessionKey: 'et:conv-1',
    payload: {
      message: 'boom',
    },
  });
});
