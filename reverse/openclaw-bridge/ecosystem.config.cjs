module.exports = {
  apps: [
    {
      name: 'openclaw-bridge',
      script: 'src/server.js',
      cwd: __dirname,
      instances: 1,
      autorestart: true,
      max_restarts: 10,
      restart_delay: 2000,
      env: {
        NODE_ENV: 'production',
        OPENCLAW_BRIDGE_PORT: 8787,
        OPENCLAW_BRIDGE_HOST: '0.0.0.0',
        OPENCLAW_BRIDGE_PATH: '/ws',
        OPENCLAW_BRIDGE_HEALTH_PATH: '/health',
      },
    },
  ],
};
