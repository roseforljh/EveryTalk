export function createLogger(scope = 'openclaw-bridge') {
  function log(level, message, extra) {
    const timestamp = new Date().toISOString();
    const suffix = extra ? ` ${JSON.stringify(extra)}` : '';
    console.log(`[${timestamp}] [${scope}] [${level}] ${message}${suffix}`);
  }

  return {
    debug(message, extra) { log('DEBUG', message, extra); },
    info(message, extra) { log('INFO', message, extra); },
    warn(message, extra) { log('WARN', message, extra); },
    error(message, extra) { log('ERROR', message, extra); },
  };
}
