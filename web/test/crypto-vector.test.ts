import { describe, expect, test } from 'bun:test'
import { AccountCrypto, base64, envelope } from '../src/crypto'
import { SyncClient } from '../src/sync'

const unlocked = async (epoch = 0): Promise<AccountCrypto> => {
  const account = new AccountCrypto('local-preview-user')
  await account.unlock(new SyncClient(''), { access_token: '', refresh_token: '', expires_at: 0, user: { id: 'local-preview-user' } }, epoch)
  return account
}

const hex = (value: Uint8Array): string => Array.from(value, byte => byte.toString(16).padStart(2, '0')).join('')
const bytes = (value: string): Uint8Array<ArrayBuffer> => Uint8Array.from(value.match(/../g) ?? [], item => Number.parseInt(item, 16))

describe('安卓同步 AES-GCM 向量', () => {
  test('字段加密输出与合同向量一致', async () => {
    const key = await crypto.subtle.importKey('raw', bytes('000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'), 'AES-GCM', false, ['encrypt'])
    const aad = new TextEncoder().encode('["everytalk-sync","0.2.0","00000000-0000-4000-8000-000000000001","api_configs","80000000-0000-4000-8000-000000000099","/apiKey",1,0]')
    const result = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: bytes('202122232425262728292a2b'), additionalData: aad }, key, new TextEncoder().encode('sk-example-secret')))
    expect(hex(result)).toBe('a1518b1514f9777e76196fbda47b869ca4ecfcf3dbe7ae706f6f9dd10ab53bcc02')
  })
})

describe('配置递归加密', () => {
  test('数组 primitive、根字符串及任意深度敏感值均加密且原样还原', async () => {
    const account = await unlocked()
    const secret = 'sk-example-secret'
    const source = { args: ['ordinary', secret, [null, false, 3, { 'a/b~c': ['Bearer abcdefghijk', secret] }]], prompt: `请使用 ${secret}` }
    const encoded = await account.encodeSettings(source, 'mcp_servers', 'cfg') as typeof source
    expect(encoded.args[0]).toBe('ordinary')
    expect(envelope(encoded.args[1])).toBe(true)
    expect(JSON.stringify(encoded)).not.toContain(secret)
    expect(JSON.stringify(encoded)).not.toContain('Bearer abcdefghijk')
    expect(await account.decodeSettings(encoded, 'mcp_servers', 'cfg')).toEqual(source)
    const nested = encoded.args[2] as unknown[]
    const escaped = nested[3] as { 'a/b~c': unknown[] }
    expect(await account.decrypt(escaped['a/b~c'][1], 'mcp_servers', 'cfg', '/args/2/3/a~1b~0c/1')).toBe(secret)
    const root = await account.encodeSettings(secret, 'conversation_settings', 'cfg')
    expect(envelope(root)).toBe(true)
    expect(await account.decodeSettings(root, 'conversation_settings', 'cfg')).toBe(secret)
    await expect(account.decrypt(escaped['a/b~c'][1], 'mcp_servers', 'cfg', '/args/2/3/a~1b~0c/0')).rejects.toThrow()
  })

  test('含凭据 URL 按查询参数与路径规则整串加密，解密保留原地址', async () => {
    const account = await unlocked()
    const urls = [
      'https://mcp.example/path?exaApiKey=opaque&tools=search#keep-fragment',
      'https://mcp.example/path?api%4Bey=opaque',
      'https://mcp.example/path?access_token=opaque',
      'https://mcp.example/path?X-Signature=opaque',
      'https://mcp.example/path?Authorization=opaque',
      'https://mcp.example/fc-0123456789abcdef0123456789abcdef/v2/mcp',
      'https://mcp.example/sk-0123456789abcdef0123456789abcdef/v2/mcp',
      'https://mcp.example/tvly-0123456789abcdef0123456789abcdef',
      'https://mcp.example/%66%63-0123456789abcdef0123456789abcdef/v2/mcp',
      'https://user:password@mcp.example/path?tools=search',
      '请连接 https://mcp.example/path?token=opaque 后继续',
    ]
    const source = { endpoint: urls[0], nested: { args: urls.slice(1) }, publicEndpoint: 'https://mcp.example/path?tools=search#keep' }
    const encoded = await account.encodeSettings(source, 'mcp_servers', 'cfg') as { endpoint: unknown; nested: { args: unknown[] }; publicEndpoint: string }
    expect(envelope(encoded.endpoint)).toBe(true)
    expect(encoded.nested.args.every(envelope)).toBe(true)
    expect(encoded.publicEndpoint).toBe(source.publicEndpoint)
    expect(await account.decodeSettings(encoded, 'mcp_servers', 'cfg')).toEqual(source)
    for (const url of urls) expect(JSON.stringify(encoded)).not.toContain(url)
  })

  test('敏感字段与自定义凭据仍按整个字段保护，合法旧信封保持不变', async () => {
    const account = await unlocked()
    const old = await account.encrypt('old-secret', 'api_configs', 'cfg', '/apiKey')
    const source = { apiKey: old, headers: { Authorization: 'opaque' }, params: [{ name: 'API_KEY', value: 'opaque' }], active: true }
    const encoded = await account.encodeSettings(source, 'api_configs', 'cfg') as typeof source
    expect(encoded.apiKey).toBe(old)
    expect(envelope(encoded.headers)).toBe(true)
    expect(envelope(encoded.params[0]?.value)).toBe(true)
    expect(await account.decodeSettings(encoded, 'api_configs', 'cfg')).toEqual({ ...source, apiKey: 'old-secret' })
    const current = await unlocked(1)
    expect(await current.encodeSettings(old, 'api_configs', 'cfg', '/apiKey')).toBe(old)
  })

  test('坏信封在根、数组和敏感容器内直接失败，不得被重新包装', async () => {
    const account = await unlocked()
    const valid = await account.encrypt('secret', 'api_configs', 'cfg', '/apiKey')
    const missing = { ...valid }
    delete missing.ct
    const invalid = [
      { ...valid, $enc: 2 }, { ...valid, alg: 'AES-GCM' }, missing,
      { ...valid, extra: 'plaintext' }, { ...valid, nonce: 'not-base64' },
      { ...valid, nonce: base64(new Uint8Array(11)) }, { ...valid, ct: base64(new Uint8Array(15)) },
      { ...valid, ct: `${'A'.repeat(20)}AB==` }, { ...valid, ct: base64(new Uint8Array(8192)) },
      { ...valid, keyVersion: 0 }, { ...valid, keyVersion: 1.5 }, { ...valid, keyVersion: '1' },
    ]
    for (const broken of invalid) {
      expect(envelope(broken)).toBe(false)
      await expect(account.decrypt(broken, 'api_configs', 'cfg', '/apiKey')).rejects.toThrow('配置密文无效')
      await expect(account.decodeSettings({ apiKey: broken }, 'api_configs', 'cfg')).rejects.toThrow('配置密文无效')
      for (const input of [broken, { args: [broken] }, { apiKey: broken }, { headers: { nested: [broken] } }]) {
        await expect(account.encodeSettings(input, 'api_configs', 'cfg')).rejects.toThrow('配置密文无效')
      }
    }
  })
})
