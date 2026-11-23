import 'fake-indexeddb/auto'
import { afterEach, describe, expect, test } from 'bun:test'
import { SyncStore } from '../src/sync-store'
import { syncKey, type StoredRecord } from '../src/types'

const stores: SyncStore[] = []
const record = (entity: StoredRecord['entity'], id: string, payload: Record<string, unknown>, deleted = false): StoredRecord => ({ key: syncKey(entity, id), entity, id, payload, deleted })

afterEach(() => { for (const store of stores.splice(0)) store.close() })

describe('IndexedDB 同步事务', () => {
  test('密钥代次更新后，手动重试不能重发旧密文；重新编辑后才恢复上传', async () => {
    const store = await SyncStore.open(`secrets-${crypto.randomUUID()}`); stores.push(store)
    const id = crypto.randomUUID()
    await store.setState({ epoch: 0, secretsEpoch: 0 })
    const oldValue = { apiKey: { $enc: 1, keyVersion: 1 } }
    await store.saveLocal([record('user_settings', id, { kind: 'api_configs', key: 'model', value: oldValue, clientModifiedAt: 1 })])
    expect(await store.applyPage({ changes: [], next_cursor: 0, has_more: false, epoch: 0, secrets_epoch: 1 })).toBe('reset-secrets')
    await store.retryRejected()
    const blocked = await store.snapshot()
    expect(blocked.pending[0].failed).toBe('SECRETS_EPOCH_CHANGED')
    expect(blocked.records[0].payload.value).toEqual(oldValue)
    expect(blocked.state.secretsEpoch).toBe(1)

    // 用户重新保存时使用新密钥；同一条配置替换旧队列项，而不是丢弃本地内容。
    const newValue = { apiKey: { $enc: 1, keyVersion: 2 } }
    await store.saveLocal([record('user_settings', id, { kind: 'api_configs', key: 'model', value: newValue, clientModifiedAt: 2 })])
    const edited = await store.snapshot()
    expect(edited.pending).toHaveLength(1)
    expect(edited.pending[0].failed).toBeUndefined()
    expect(edited.pending[0].payload.value).toEqual(newValue)
  })

  test('同一页先收到子消息再收到会话墓碑时，子消息不会复活', async () => {
    const store = await SyncStore.open(`cascade-${crypto.randomUUID()}`); stores.push(store)
    const sessionId = '11111111-1111-4111-8111-111111111111'
    // 本机已排队的子消息删除也会被远端会话删除覆盖，无需再提交一次。
    await store.saveLocal([record('messages', crypto.randomUUID(), { sessionId }, true)])
    const result = await store.applyPage({
      changes: [
        { entity: 'messages', id: crypto.randomUUID(), payload: { sessionId, text: '旧消息' }, deleted: false, server_seq: 1 },
        { entity: 'sessions', id: sessionId, payload: { clientModifiedAt: 2, title: '已删除' }, deleted: true, server_seq: 2 },
      ], next_cursor: 2, has_more: false, epoch: 0, secrets_epoch: 0,
    })
    expect(result).toBe('applied')
    const snapshot = await store.snapshot()
    expect(snapshot.records.every(item => item.deleted)).toBe(true)
    expect(snapshot.pending).toHaveLength(0)
  })

  test('确认旧版本时，网络等待期间的新编辑仍然留在队列', async () => {
    const store = await SyncStore.open(`race-${crypto.randomUUID()}`); stores.push(store)
    const id = '22222222-2222-4222-8222-222222222222'
    await store.saveLocal([record('sessions', id, { clientModifiedAt: 1, title: '第一次' })])
    const first = (await store.snapshot()).pending[0]
    await store.saveLocal([record('sessions', id, { clientModifiedAt: 2, title: '第二次' })])
    await store.acknowledge([first], [{ index: 0, entity: 'sessions', id, status: 'accepted', server_seq: 3 }])
    const snapshot = await store.snapshot()
    expect(snapshot.pending).toHaveLength(1)
    expect(snapshot.pending[0].payload.title).toBe('第二次')
  })

  test('远端删除会话后，未送达消息停止重试', async () => {
    const store = await SyncStore.open(`rejected-${crypto.randomUUID()}`); stores.push(store)
    const sessionId = '33333333-3333-4333-8333-333333333333', messageId = crypto.randomUUID()
    await store.saveLocal([record('messages', messageId, { sessionId, text: '不能再发送' })])
    const batch = (await store.snapshot()).pending
    await store.acknowledge(batch, [{ index: 0, entity: 'messages', id: messageId, status: 'rejected', error: { code: 'session_deleted', message: 'deleted' } }])
    const snapshot = await store.snapshot()
    expect(snapshot.pending).toHaveLength(0)
    expect(snapshot.records.find(item => item.id === messageId)?.deleted).toBe(true)
  })

  test('state() 轻量读取同步状态，cacheAttachment 按容量淘汰最旧条目', async () => {
    const store = await SyncStore.open(`lru-${crypto.randomUUID()}`); stores.push(store)
    await store.setState({ cursor: 12, secretsEpoch: 3 })
    expect(await store.state()).toEqual({ cursor: 12, secretsEpoch: 3 })
    await store.cacheAttachment('a', new Blob(['1']), 2)
    await store.cacheAttachment('b', new Blob(['2']), 2)
    await store.cacheAttachment('c', new Blob(['3']), 2)
    expect(await store.cachedAttachment('a')).toBeUndefined()
    expect(await store.cachedAttachment('b')).toBeDefined()
    expect(await store.cachedAttachment('c')).toBeDefined()
  })

  test('大量记录下 saveLocal 不再退化为 O(N^2)，且删除会话仍正确级联', async () => {
    const store = await SyncStore.open(`perf-${crypto.randomUUID()}`); stores.push(store)
    const sessionId = crypto.randomUUID()
    const batch: StoredRecord[] = [record('sessions', sessionId, { clientModifiedAt: 1, title: '长会话' })]
    for (let i = 0; i < 600; i++) {
      batch.push(record('messages', crypto.randomUUID(), { sessionId, text: `msg-${i}`, timestamp: i }))
    }
    await store.saveLocal(batch)
    const start = performance.now()
    for (let i = 0; i < 10; i++) {
      await store.saveLocal([record('messages', crypto.randomUUID(), { sessionId, text: `extra-${i}`, timestamp: 1000 + i })])
    }
    expect(performance.now() - start).toBeLessThan(1500)
    await store.saveLocal([record('sessions', sessionId, { clientModifiedAt: 2000, title: '长会话' }, true)])
    const afterDelete = await store.snapshot()
    expect(afterDelete.records.every(item => item.deleted)).toBe(true)
  })
})
