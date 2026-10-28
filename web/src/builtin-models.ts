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
  return builtinModelId(value, key) !== undefined
}

/** 旧随机 ID 与新固定 ID 都归到同一内置身份，自定义服务中的同名模型保持独立。 */
function builtinModelId(value: JsonObject, key?: string): string | undefined {
  return builtinModels.find(model => model.id === key ||
    (['默认', 'default'].includes(String(value.provider ?? '').trim().toLowerCase()) && model.model === value.model))
    ?.id as string | undefined
}

/** 按内置身份合并默认项并解析旧引用；只修改展示投影，保留原同步记录及所有自定义配置。 */
export function withBuiltinModels(records: StoredRecord[]): StoredRecord[] {
  const retained = new Map<string, StoredRecord>()
  const aliases = new Map<string, string>()
  for (const record of records) {
    if (record.entity !== 'user_settings' || record.deleted || record.payload.kind !== 'api_configs' || !isObject(record.payload.value)) continue
    const key = String(record.payload.key)
    const builtinId = builtinModelId(record.payload.value, key)
    if (!builtinId) continue
    aliases.set(key, builtinId)
    const previous = retained.get(builtinId)
    const previousKey = String(previous?.payload.key)
    // 固定 ID 优先，其余按 ID 排序，避免云端记录返回顺序改变选中模型。
    if (!previous || key === builtinId || (previousKey !== builtinId && key < previousKey)) retained.set(builtinId, record)
  }
  const seen = new Set<string>()
  const result = records.filter(record => {
    if (record.entity !== 'user_settings' || record.deleted || record.payload.kind !== 'api_configs') return true
    const builtinId = aliases.get(String(record.payload.key))
    if (!builtinId) return true
    if (retained.get(builtinId) !== record || seen.has(builtinId)) return false
    seen.add(builtinId)
    return true
  }).map(record => {
    if (record.entity !== 'user_settings' || record.deleted || !isObject(record.payload.value)) return record
    const field = record.payload.kind === 'conversation_settings' ? 'modelId'
      : record.payload.kind === 'selected_models' && ['text', 'image'].includes(String(record.payload.key)) ? 'configId' : undefined
    if (!field) return record
    const value = record.payload.value
    const builtinId = aliases.get(String(value[field]))
    const retainedKey = builtinId ? retained.get(builtinId)?.payload.key : undefined
    return retainedKey && retainedKey !== value[field]
      ? { ...record, payload: { ...record.payload, value: { ...value, [field]: retainedKey } } } : record
  })
  for (const model of builtinModels) {
    if (retained.has(String(model.id))) continue
    const id = String(model.id)
    result.push({ id, key: syncKey('user_settings', id), entity: 'user_settings', deleted: false, payload: { kind: 'api_configs', key: id, value: model } })
  }
  return result
}
