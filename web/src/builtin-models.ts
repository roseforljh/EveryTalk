import { isObject } from './sync-core'
import { syncKey, type JsonObject, type StoredRecord } from './types'

/** 与安卓共用稳定身份；服务地址和密钥由服务端解析，不能进入浏览器或同步记录。 */
export const builtinModels: JsonObject[] = [
  { id: 'builtin-z-image-turbo', name: 'Z-Image-Turbo (Modal)', model: 'z-image-turbo-modal', inferenceSteps: 4 },
  { id: 'builtin-qwen-image-edit', name: 'Qwen Image Edit', model: 'qwen-image-edit-modal', inferenceSteps: 30 },
  { id: 'builtin-kolors', name: 'Kwai-Kolors/Kolors', model: 'Kwai-Kolors/Kolors' },
].map(model => ({ ...model, provider: '默认', address: '', apiKey: '', imageConfig: true, modality: 'IMAGE', channel: '' }))

/** 兼容安卓旧版随机 ID 的默认项，不能靠前端按钮隐藏来限制删除。 */
export function isBuiltinModel(value: JsonObject, key?: string): boolean {
  return builtinModels.some(model => model.id === key ||
    (['默认', 'default'].includes(String(value.provider ?? '').trim().toLowerCase()) && model.model === value.model))
}

/** 本地投影始终补齐默认项；不向同步服务写入虚构记录，也不复活远端删除的自定义配置。 */
export function withBuiltinModels(records: StoredRecord[]): StoredRecord[] {
  const result = [...records]
  for (const model of builtinModels) {
    if (records.some(record => record.entity === 'user_settings' && !record.deleted && record.payload.kind === 'api_configs' &&
      isObject(record.payload.value) && isBuiltinModel(record.payload.value) && record.payload.value.model === model.model)) continue
    const id = String(model.id)
    result.push({ id, key: syncKey('user_settings', id), entity: 'user_settings', deleted: false, payload: { kind: 'api_configs', key: id, value: model } })
  }
  return result
}
