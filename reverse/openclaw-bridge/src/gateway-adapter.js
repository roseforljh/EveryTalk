import {
  buildBridgeAck,
  buildBridgeConnectAck,
  buildBridgeError,
  mapBridgeEnvelopeToGatewayRequest,
  mapGatewayEventToBridgeEnvelope,
} from './protocol.js';
import { createLogger } from './logger.js';

const OPEN_STATE = 1;

export class OpenClawGatewayAdapter {
  constructor({
    gatewayUrl,
    gatewayToken,
    webSocketFactory,
    logger = createLogger('gateway-adapter'),
    createRequestId = () => crypto.randomUUID(),
  }) {
    this.gatewayUrl = gatewayUrl;
    this.gatewayToken = gatewayToken;
    this.webSocketFactory = webSocketFactory;
    this.logger = logger;
    this.createRequestId = createRequestId;
    this.gatewaySocket = null;
    this.pendingMessages = [];
    this.gatewayListenersBound = false;
  }

  connect() {
    if (this.gatewaySocket && this.gatewaySocket.readyState !== undefined && this.gatewaySocket.readyState !== 3) {
      return this.gatewaySocket;
    }

    this.gatewaySocket = this.webSocketFactory(this.gatewayUrl, {
      headers: this.gatewayToken ? {
        Authorization: `Bearer ${this.gatewayToken}`,
        'User-Agent': 'OpenClaw-Bridge/1.0',
      } : {
        'User-Agent': 'OpenClaw-Bridge/1.0',
      },
      protocol: 'openclaw-rpc',
    });
    this.gatewayListenersBound = false;
    this.bindGatewayLifecycle();
    this.logger.info('gateway socket created', { gatewayUrl: this.gatewayUrl });
    return this.gatewaySocket;
  }

  bindGatewayLifecycle() {
    if (!this.gatewaySocket || this.gatewayListenersBound || typeof this.gatewaySocket.on !== 'function') {
      return;
    }

    this.gatewayListenersBound = true;
    this.gatewaySocket.on('open', () => {
      this.logger.info('gateway socket opened');
      this.sendConnectPayload();
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

  sendConnectPayload() {
    if (!this.gatewaySocket || this.gatewaySocket.readyState !== OPEN_STATE) {
      return;
    }

    const payload = {
      id: this.createRequestId(),
      method: 'connect',
      params: {
        auth: {
          token: this.gatewayToken || '',
        },
      },
    };
    this.gatewaySocket.send(JSON.stringify(payload));
  }

  flushPendingMessages() {
    if (!this.gatewaySocket || this.gatewaySocket.readyState !== OPEN_STATE) {
      return;
    }
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
    if (envelope) {
      sendToClient(envelope);
    }
  }
}
