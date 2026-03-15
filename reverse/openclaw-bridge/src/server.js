import http from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import { loadBridgeConfig } from './config.js';
import { OpenClawGatewayAdapter } from './gateway-adapter.js';
import { createLogger } from './logger.js';
import {
  buildUnauthorizedEnvelope,
  isAuthorizedRequest,
  shouldHandleBridgePath,
} from './security.js';

function createNodeWebSocket(url, options = {}) {
  return new WebSocket(url, options);
}

export function startBridgeServer({
  config = loadBridgeConfig(),
  webSocketFactory = createNodeWebSocket,
  logger = createLogger(),
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
      logger,
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
          payload: { message: error.message || 'Bridge failure' },
        }));
      }
    });
  });

  server.listen(config.port, config.host, () => {
    logger.info('bridge server listening', {
      host: config.host,
      port: config.port,
      bridgePath: config.bridgePath,
      healthPath: config.healthPath,
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

if (process.argv[1] && process.argv[1].endsWith('server.js')) {
  const server = startBridgeServer();
  const address = server.address();
  if (address && typeof address === 'object') {
    console.log(`OpenClaw Bridge listening on http://${address.address}:${address.port}`);
    console.log(`Bridge WS path: ${loadBridgeConfig().bridgePath}`);
    console.log(`Health check: ${loadBridgeConfig().healthPath}`);
  }
}
