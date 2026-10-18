import { describe, expect, test } from 'bun:test'

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
