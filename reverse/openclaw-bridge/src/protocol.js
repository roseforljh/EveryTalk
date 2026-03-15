export function buildBridgeConnectAck(requestId, sessionKey) {
  return {
    type: 'bridge.chat.ack',
    requestId,
    sessionKey,
    payload: { stage: 'connected' },
  };
}

export function buildBridgeAck(requestId, sessionKey, stage = 'accepted') {
  return {
    type: 'bridge.chat.ack',
    requestId,
    sessionKey,
    payload: { stage },
  };
}

export function buildBridgeDelta(requestId, sessionKey, runId, text) {
  return {
    type: 'bridge.chat.delta',
    requestId,
    sessionKey,
    runId,
    payload: { text },
  };
}

export function buildBridgeDone(requestId, sessionKey, runId) {
  return {
    type: 'bridge.chat.done',
    requestId,
    sessionKey,
    runId,
    payload: { reason: 'completed' },
  };
}

export function buildBridgeError(requestId, sessionKey, message) {
  return {
    type: 'bridge.chat.error',
    requestId,
    sessionKey,
    payload: { message },
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
        idempotencyKey: envelope.requestId,
      },
    };
  }

  if (envelope.type === 'bridge.chat.abort') {
    return {
      id: envelope.requestId,
      method: 'chat.abort',
      params: {
        sessionKey: envelope.sessionKey,
        runId: envelope.runId || envelope.payload?.runId || null,
      },
    };
  }

  if (envelope.type === 'bridge.chat.history') {
    return {
      id: envelope.requestId,
      method: 'chat.history',
      params: {
        sessionKey: envelope.sessionKey,
      },
    };
  }

  return null;
}

export function mapGatewayEventToBridgeEnvelope(message, requestId) {
  const data = message?.data || {};
  if (message?.event !== 'chat') {
    return null;
  }

  if (data.type === 'delta') {
    return buildBridgeDelta(requestId, data.sessionKey, data.runId, data.text || '');
  }

  if (data.type === 'done') {
    return buildBridgeDone(requestId, data.sessionKey, data.runId);
  }

  if (data.type === 'error') {
    return buildBridgeError(requestId, data.sessionKey, data.message || 'Gateway error');
  }

  return null;
}
