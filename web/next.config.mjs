import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('.', import.meta.url))
// 本机复用安卓的三个公开配置；不读取、复制或公开其余密钥。
const properties = new Map()
try {
  for (const line of readFileSync(new URL('../app1/local.properties', import.meta.url), 'utf8').split(/\r?\n/)) {
    const match = /^(SUPABASE_URL|SUPABASE_PUBLISHABLE_KEY|EVERYTALK_SYNC_BASE_URL)\s*=\s*(.*)$/.exec(line)
    if (match) properties.set(match[1], match[2].trim().replace(/^"|"$/g, ''))
  }
} catch { /* 部署环境使用环境变量，不依赖本机 Android 配置文件。 */ }
const publicKey = process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY ?? properties.get('SUPABASE_PUBLISHABLE_KEY') ?? ''
if (publicKey) {
  let anonymous = false
  try { anonymous = JSON.parse(Buffer.from(publicKey.split('.')[1], 'base64url').toString()).role === 'anon' } catch {}
  if (!publicKey.startsWith('sb_publishable_') && !anonymous) throw new Error('网页只能使用 Supabase publishable 或 anon Key')
}

export default {
  reactStrictMode: true,
  devIndicators: false,
  agentRules: false,
  turbopack: { root },
  outputFileTracingRoot: root,
  env: {
    NEXT_PUBLIC_SUPABASE_URL: process.env.NEXT_PUBLIC_SUPABASE_URL ?? properties.get('SUPABASE_URL') ?? '',
    NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY: publicKey,
    // 网页是纯前端，所有接口直接请求独立部署的后端。
    NEXT_PUBLIC_API_BASE_URL: process.env.NEXT_PUBLIC_API_BASE_URL ?? properties.get('EVERYTALK_SYNC_BASE_URL') ?? '',
  },
  images: { formats: ['image/avif', 'image/webp'], remotePatterns: [{ protocol: 'https', hostname: 'qone.kuz7.com' }] },
}
