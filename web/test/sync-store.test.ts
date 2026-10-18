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
    const result = await store.applyPage({
      changes: [
        { entity: 'messages', id: crypto.randomUUID(), payload: { sessionId: '11111111-1111-4111-8111-111111111111', text: '旧消息' }, deleted: false, server_seq: 1 },
        { entity: 'sessions', id: '11111111-1111-4111-8111-111111111111', payload: { clientModifiedAt: 2, title: '已删除' }, deleted: true, server_seq: 2 },
      ], next_cursor: 2, has_more: false, epoch: 0, secrets_epoch: 0,
    })
    expect(result).toBe('applied')
    expect((await store.snapshot()).records.every(item => item.deleted)).toBe(true)
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
})
