import { lookup } from 'node:dns/promises'
import { request } from 'node:https'
import { Readable } from 'node:stream'

/** 模型地址来自用户配置：只允许公网 HTTPS，DNS 解析后固定连接 IP，防止访问内网和 DNS 重绑定。 */
export function publicIPv4(ip: string): boolean {
  const parts = ip.split('.').map(Number)
  if (parts.length !== 4 || parts.some(n => !Number.isInteger(n) || n < 0 || n > 255)) return false
  const [a, b, c] = parts
  return !(a === 0 || a === 10 || a === 127 || a >= 224 || (a === 100 && b >= 64 && b <= 127) ||
    (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && (b === 168 || b === 0 || b === 2)) || (a === 198 && (b === 18 || b === 19 || (b === 51 && c === 100))) ||
    (a === 203 && b === 0 && c === 113))
}

export async function modelUpstream(target: URL, headers: Record<string, string>, body: string | Uint8Array, signal: AbortSignal, contentType = 'application/json', idleMs = 120_000): Promise<Response> {
  if (target.protocol !== 'https:' || target.username || target.password || target.hash || (target.port && target.port !== '443')) throw new Error('只支持标准 HTTPS 公网模型地址')
  const addresses = await lookup(target.hostname, { family: 4, all: true })
  if (!addresses.length || addresses.some(item => !publicIPv4(item.address))) throw new Error('模型地址必须解析到公网')
  return new Promise((resolve, reject) => {
    const bytes = typeof body === 'string' ? Buffer.from(body) : Buffer.from(body)
    const upstream = request(target, { method: 'POST', agent: false, headers: { ...headers, 'Content-Type': contentType, 'Content-Length': bytes.byteLength },
      signal, lookup: (_host, _options, callback) => callback(null, addresses[0].address, 4), timeout: idleMs }, incoming => {
      const responseHeaders = new Headers({ 'Cache-Control': 'private,no-store', 'X-Content-Type-Options': 'nosniff', 'X-Accel-Buffering': 'no' })
      responseHeaders.set('Content-Type', incoming.headers['content-type'] ?? 'application/json')
      // 仅转发状态和正文，不把上游 Cookie、重定向地址或认证信息带回网页。
      resolve(new Response(Readable.toWeb(incoming) as unknown as ReadableStream<Uint8Array>, { status: incoming.statusCode ?? 502, headers: responseHeaders }))
    })
    upstream.on('timeout', () => upstream.destroy(new Error('模型响应超时')))
    upstream.on('error', reject)
    upstream.end(bytes)
  })
}

/**
 * 下载供应商返回的图片并转成 data URL。
 * 图片生成接口常返回短期 URL；服务端先固定公网 IP、禁止重定向并限制大小，
 * 才能把结果安全地交给附件同步链路，避免浏览器直接访问未知地址。
 */
export async function publicImageData(url: string, signal: AbortSignal, maxBytes = 32 * 1024 * 1024, idleMs = 120_000): Promise<string> {
  const target = new URL(url)
  if (target.protocol !== 'https:' || target.username || target.password || target.hash || (target.port && target.port !== '443')) throw new Error('图片地址必须是标准 HTTPS 公网地址')
  const addresses = await lookup(target.hostname, { family: 4, all: true })
  if (!addresses.length || addresses.some(item => !publicIPv4(item.address))) throw new Error('图片地址必须解析到公网')
  return new Promise((resolve, reject) => {
    const incomingRequest = request(target, { method: 'GET', agent: false, signal, lookup: (_host, _options, callback) => callback(null, addresses[0].address, 4), timeout: idleMs }, incoming => {
      const contentType = incoming.headers['content-type']?.split(';', 1)[0]?.trim().toLowerCase() ?? ''
      if (!/^image\/[a-z0-9.+-]+$/.test(contentType)) { incoming.resume(); reject(new Error('供应商返回的内容不是图片')); return }
      const declared = Number(incoming.headers['content-length'] ?? 0)
      if (declared > maxBytes) { incoming.resume(); reject(new Error('生成图片超过 32 MiB 限制')); return }
      const chunks: Buffer[] = []; let size = 0
      incoming.on('data', chunk => { size += chunk.length; if (size > maxBytes) incoming.destroy(new Error('生成图片超过 32 MiB 限制')); else chunks.push(chunk) })
      incoming.on('end', () => { if (size < 1 || size > maxBytes) reject(new Error('生成图片为空或超过大小限制')); else resolve(`data:${contentType};base64,${Buffer.concat(chunks).toString('base64')}`) })
      incoming.on('error', reject)
    })
    incomingRequest.on('timeout', () => incomingRequest.destroy(new Error('图片下载超时')))
    incomingRequest.on('error', reject)
    incomingRequest.end()
  })
}
