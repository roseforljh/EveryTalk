import { describe, expect, test } from 'bun:test'
import { builtinModels, isBuiltinModel, withBuiltinModels } from '../src/builtin-models'
import { imageRequest } from '../src/model'
import type { StoredRecord } from '../src/types'

describe('统一聊天的内置模型', () => {
  test('空账号和删除过的默认配置都能使用三个内置模型', () => {
    const defaults = withBuiltinModels([])
    expect(defaults).toHaveLength(3)
    expect(withBuiltinModels(defaults)).toHaveLength(3)
    expect(withBuiltinModels(defaults.map(record => ({ ...record, deleted: true }))).filter(record => !record.deleted)).toHaveLength(3)
  })

  test('安卓旧随机 ID 保持原引用，补齐其余默认模型', () => {
    const old = withBuiltinModels([])[0]!
    old.id = 'legacy-id'; old.key = 'user_settings:legacy-id'; old.payload.key = 'legacy-id'
    const models = withBuiltinModels([old])
    expect(models).toHaveLength(3)
    expect(models[0]?.payload.key).toBe('legacy-id')
    expect(isBuiltinModel(builtinModels[0]!, 'legacy-id')).toBe(true)
    expect(isBuiltinModel({ provider: '我的服务', model: 'z-image-turbo-modal' })).toBe(false)
  })

  test('同步合并新旧默认ID只保留一项并解析旧选择，不改写原记录', () => {
    const defaults = withBuiltinModels([])
    const legacy = defaults.map(record => ({ ...record, id: `legacy-${record.id}`, key: `user_settings:legacy-${record.id}`,
      payload: { ...record.payload, key: `legacy-${record.payload.key}`, value: { ...(record.payload.value as object), provider: ' Default ' } } }))
    const custom = { ...legacy[0]!, id: 'custom', key: 'user_settings:custom',
      payload: { ...legacy[0]!.payload, key: 'custom', value: { ...(legacy[0]!.payload.value as object), provider: '我的服务' } } }
    const selection: StoredRecord = { entity: 'user_settings', id: 'selection', key: 'user_settings:selection', deleted: false,
      payload: { kind: 'selected_models', key: 'image', value: { configId: legacy[1]!.payload.key } } }
    const conversation: StoredRecord = { ...selection, id: 'conversation', key: 'user_settings:conversation',
      payload: { kind: 'conversation_settings', key: 'chat-id', value: { modelId: legacy[2]!.payload.key, webSearchEnabled: true } } }
    const records = [...legacy, ...defaults, custom, selection, conversation]
    const original = JSON.stringify(records)
    for (const input of [records, [...records].reverse(), withBuiltinModels(records)]) {
      const result = withBuiltinModels(input)
      const models = result.filter(record => record.payload.kind === 'api_configs' && !record.deleted)
      expect(models).toHaveLength(4)
      expect(models.map(record => record.payload.key).sort()).toEqual([...defaults.map(record => record.payload.key), 'custom'].sort())
      expect(result.find(record => record.id === selection.id)?.payload.value).toEqual({ configId: defaults[1]!.payload.key })
      expect(result.find(record => record.id === conversation.id)?.payload.value).toEqual({ modelId: defaults[2]!.payload.key, webSearchEnabled: true })
    }
    expect(JSON.stringify(records)).toBe(original)
    const oldDevices = [legacy[1]!, { ...legacy[1]!, id: 'device-b', key: 'user_settings:device-b', payload: { ...legacy[1]!.payload, key: 'device-b' } }]
    expect(withBuiltinModels(oldDevices).map(record => record.payload.key).sort()).toEqual(withBuiltinModels([...oldDevices].reverse()).map(record => record.payload.key).sort())
  })

  test('内置请求只发送模型身份，不从浏览器发送服务地址和密钥', () => {
    const request = imageRequest(builtinModels[0]!, '画一只猫')
    expect(request.builtinModel).toBe('z-image-turbo-modal')
    expect(request.target).toBe('')
    expect(request.apiKey).toBe('')
    expect(request.body.numInferenceSteps).toBe(4)
    expect(() => imageRequest(builtinModels[1]!, '修改图片')).toThrow('参考图片')
    const edit = imageRequest(builtinModels[1]!, '修改图片', [{ mime: 'image/png', dataUrl: 'data:image/png;base64,AQID' }])
    expect(edit.body.image_base64).toBe('AQID')
    expect(edit.builtinModel).toBe('qwen-image-edit-modal')
  })
})
