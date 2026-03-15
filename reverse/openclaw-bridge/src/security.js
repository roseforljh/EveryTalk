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
  if (authHeader === `Bearer ${expected}`) {
    return true;
  }

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
    payload: { message: 'Unauthorized' },
  };
}
