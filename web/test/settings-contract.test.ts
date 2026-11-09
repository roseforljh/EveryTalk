import { describe, expect, test } from 'bun:test'
import {
  canonicalSettingKey,
  isCanonicalUuid,
  migrateLegacySettingRecords,
  settingContractToView,
  settingViewToContract,
} from '../src/settings-contract'
import type { SyncClient } from '../src/sync'
import type { StoredRecord } from '../src/types'

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

describe('MCP 配置合同映射', () => {
  test('网页字段写成安卓 mcp_servers 字段', () => {
    const { value, extras } = settingViewToContract('mcp_configs', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', {
      id: 'context7',
      name: 'Context7',
      url: 'https://mcp.context7.com/mcp',
      transportType: 'HTTP',
      preset: 'CONTEXT7',
      enabled: true,
      description: '文档',
      tools: [{ name: 'resolve-library-id' }],
      apiKey: 'sk-test-1234567890',
    })
    expect(value).toMatchObject({
      localId: 'context7',
      name: 'Context7',
      endpoint: 'https://mcp.context7.com/mcp',
      transport: 'HTTP',
      enabled: true,
    })
    expect(value.headers).toEqual([['Authorization', 'Bearer sk-test-1234567890']])
    expect(extras).toMatchObject({ preset: 'CONTEXT7', description: '文档' })
  })

  test('安卓记录还原成网页形状并保留敏感信封', () => {
    const headers = { $enc: 1, alg: 'AES-256-GCM', nonce: 'AAAAAAAAAAAAAAAA', ct: 'AAAAAAAAAAAAAAAAAAAAAA==', keyVersion: 1 }
    const view = settingContractToView('mcp_configs', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', {
      localId: 'context7',
      name: 'Context7',
      endpoint: 'https://mcp.context7.com/mcp',
      transport: 'SSE',
      enabled: false,
      headers,
    }, { preset: 'CONTEXT7', description: '文档', tools: [] })
    expect(view).toMatchObject({
      id: 'context7',
      name: 'Context7',
      url: 'https://mcp.context7.com/mcp',
      transportType: 'SSE',
      enabled: false,
      status: 'idle',
    })
    expect(view.headers).toEqual(headers)
  })

  test('端点被加密时仍按原信封保存，不会被空 URL 覆盖', () => {
    const endpoint = { $enc: 1, alg: 'AES-256-GCM', nonce: 'AAAAAAAAAAAAAAAA', ct: 'AAAAAAAAAAAAAAAAAAAAAA==', keyVersion: 1 }
    const view = settingContractToView('mcp_configs', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', { endpoint, transport: 'HTTP', enabled: true }, {})
    expect(view.url).toBe('')
    const { value } = settingViewToContract('mcp_configs', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', view)
    expect(value.endpoint).toEqual(endpoint)
  })
})

describe('技能 / 电脑配置合同映射', () => {
  test('技能只同步元数据，网页文件与规则留在本机 extras', () => {
    const { value, extras } = settingViewToContract('skill_packages', 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', {
      packageId: 'vercel-labs-nextjs-react',
      packageName: 'vercel-labs/nextjs-react-skills',
      name: 'nextjs-react-skills',
      sourceType: 'REMOTE',
      sourceRepository: 'https://github.com/vercel-labs/nextjs-react-skills',
      currentHash: 'a8f4c291e0',
      enabled: true,
      description: 'React 规范',
      markdown: '# 规则',
      files: [{ path: 'SKILL.md', size: 1, sha256: 'a8f4c291e0' }],
      children: [{ skillId: 'react-19-patterns' }],
    })
    expect(value).toMatchObject({
      packageId: 'vercel-labs-nextjs-react',
      packageName: 'vercel-labs/nextjs-react-skills',
      name: 'nextjs-react-skills',
      sourceType: 'REMOTE',
      contentHash: 'a8f4c291e0',
      enabled: true,
      invocationMode: 'AUTO',
      description: 'React 规范',
    })
    expect(value.files).toBeUndefined()
    expect(value.markdown).toBeUndefined()
    expect(extras).toMatchObject({ markdown: '# 规则', files: [{ path: 'SKILL.md', size: 1, sha256: 'a8f4c291e0' }] })
  })

  test('电脑字段在 displayName/allowPrivateNetwork 与网页字段间往返', () => {
    const { value } = settingViewToContract('computer_servers', 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', {
      id: 'server-hk-docker',
      name: '香港节点',
      provider: 'SSH',
      host: '43.154.88.120',
      port: 22,
      username: 'ubuntu',
      authKind: 'PRIVATE_KEY',
      runMode: 'CONTAINER',
      permissionMode: 'AUTO',
      privateNetworkAllowed: true,
      workspaces: [{ id: 'ws-1' }],
    })
    expect(value).toMatchObject({
      displayName: '香港节点',
      provider: 'SSH',
      host: '43.154.88.120',
      port: 22,
      username: 'ubuntu',
      authKind: 'PRIVATE_KEY',
      runMode: 'CONTAINER',
      allowPrivateNetwork: true,
      permissionMode: 'FULL',
    })
    expect(value.workspaces).toBeUndefined()
    const view = settingContractToView('computer_servers', 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', value, { workspaces: [{ id: 'ws-1' }] })
    expect(view).toMatchObject({ id: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', name: '香港节点', privateNetworkAllowed: true, permissionMode: 'AUTO' })
    expect(view.workspaces).toEqual([{ id: 'ws-1' }])
  })

  test('Cloudflare 电脑在安卓端使用 PASSWORD 认证', () => {
    const { value } = settingViewToContract('computer_servers', 'ffffffff-ffff-4fff-8fff-ffffffffffff', {
      name: 'CF',
      provider: 'CLOUDFLARE',
      host: 'sandbox.everytalk.workers.dev',
      authKind: 'OAUTH',
    })
    expect(value.authKind).toBe('PASSWORD')
  })
})

describe('UUID key 与旧 kind 迁移', () => {
  test('UUID 原样小写，非 UUID 按安卓 UUID v3 规则派生', async () => {
    expect(await canonicalSettingKey('mcp_servers', 'AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA')).toBe('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
    // 固定向量：uuid.nameUUIDFromBytes("EveryTalk:mcp_servers:github") 的 Java 输出。
    expect(await canonicalSettingKey('mcp_servers', 'github')).toBe('8a98284f-80cb-32c0-a50c-751a89a0fae3')
    expect(await canonicalSettingKey('computers', 'server-hk-docker')).toBe('17913f87-8b5f-3cdf-996d-f6ee625be54c')
    const first = await canonicalSettingKey('mcp_servers', 'context7')
    const second = await canonicalSettingKey('mcp_servers', 'context7')
    expect(first).toBe(second)
    expect(first).toBe('77c04861-126a-3796-ac23-57f85fd607db')
    expect(first).toMatch(uuid)
    expect(isCanonicalUuid(first)).toBe(true)
  })

  test('旧 mcp_configs 记录迁移成 mcp_servers（UUID key、合同字段）', async () => {
    const id = '11111111-1111-4111-8111-111111111111'
    const old: StoredRecord = {
      key: `user_settings:${id}`,
      entity: 'user_settings',
      id,
      deleted: false,
      payload: {
        clientModifiedAt: 5,
        kind: 'mcp_configs',
        key: 'context7',
        value: { id: 'context7', name: 'Context7', url: 'https://mcp.context7.com/mcp', transportType: 'HTTP', enabled: true, description: '文档', tools: [{ name: 'x' }] },
      },
    }
    let saved: StoredRecord[] = []
    const client = {
      snapshot: async () => ({ records: [old], pending: [], state: { cursor: 0 } }),
      saveRecords: async (records: StoredRecord[]) => { saved = records },
    } as unknown as SyncClient

    expect(await migrateLegacySettingRecords(client, 'user-1')).toBe(1)
    const migrated = saved[0]
    expect(migrated.id).toBe(id)
    expect(migrated.payload.kind).toBe('mcp_servers')
    expect(String(migrated.payload.key)).toMatch(uuid)
    expect(migrated.payload.value).toMatchObject({ localId: 'context7', endpoint: 'https://mcp.context7.com/mcp', transport: 'HTTP' })
  })

  test('旧 computer_servers / skill_packages 迁移后 key 均为 UUID', async () => {
    const computerId = '33333333-3333-4333-8333-333333333333'
    const skillId = '44444444-4444-4444-8444-444444444444'
    const records: StoredRecord[] = [
      {
        key: `user_settings:${computerId}`, entity: 'user_settings', id: computerId, deleted: false,
        payload: { clientModifiedAt: 5, kind: 'computer_servers', key: 'server-hk-docker', value: { id: 'server-hk-docker', name: '香港节点', provider: 'SSH', host: '1.2.3.4' } },
      },
      {
        key: `user_settings:${skillId}`, entity: 'user_settings', id: skillId, deleted: false,
        payload: { clientModifiedAt: 5, kind: 'skill_packages', key: 'vercel-labs-nextjs-react', value: { packageId: 'vercel-labs-nextjs-react', name: 'nextjs', sourceType: 'REMOTE', currentHash: 'h' } },
      },
    ]
    let saved: StoredRecord[] = []
    const client = {
      snapshot: async () => ({ records, pending: [], state: { cursor: 0 } }),
      saveRecords: async (next: StoredRecord[]) => { saved = next },
    } as unknown as SyncClient

    expect(await migrateLegacySettingRecords(client, 'user-1')).toBe(2)
    expect(saved.map(record => record.payload.kind).sort()).toEqual(['computers', 'skills'])
    for (const record of saved) expect(String(record.payload.key)).toMatch(uuid)
  })

  test('已是合同 kind 的记录不会被迁移', async () => {
    const record: StoredRecord = {
      key: 'user_settings:22222222-2222-4222-8222-222222222222',
      entity: 'user_settings',
      id: '22222222-2222-4222-8222-222222222222',
      deleted: false,
      payload: { clientModifiedAt: 5, kind: 'mcp_servers', key: '22222222-2222-4222-8222-222222222222', value: {} },
    }
    let saved: StoredRecord[] = []
    const client = {
      snapshot: async () => ({ records: [record], pending: [], state: { cursor: 0 } }),
      saveRecords: async (records: StoredRecord[]) => { saved = records },
    } as unknown as SyncClient
    expect(await migrateLegacySettingRecords(client, 'user-1')).toBe(0)
    expect(saved).toEqual([])
  })
})
