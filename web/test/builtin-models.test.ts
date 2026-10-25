import { afterEach, describe, expect, test } from 'bun:test'
import { builtinModels, isBuiltinModel, withBuiltinModels } from '../src/builtin-models'
import { imageRequest } from '../src/model'
import { POST } from '../app/api/image/route'

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

describe('内置图片服务端边界', () => {
  const originalFetch = globalThis.fetch
  const originalUrls = process.env.VITE_API_URLS
  afterEach(() => {
    globalThis.fetch = originalFetch
    if (originalUrls === undefined) delete process.env.VITE_API_URLS
    else process.env.VITE_API_URLS = originalUrls
  })

  test('客户端不能替内置模型指定上游；缺少服务配置明确返回 503', async () => {
    delete process.env.VITE_API_URLS
    globalThis.fetch = Object.assign(async () => Response.json({ id: 'user' }), { preconnect: originalFetch.preconnect }) as typeof fetch
    const input = { ...imageRequest(builtinModels[0]!, '画猫'), target: 'https://attacker.example', apiKey: 'client-key' }
    const response = await POST(new Request('https://everytalk.test/api/image', {
      method: 'POST', headers: { origin: 'https://everytalk.test', authorization: 'Bearer test' }, body: JSON.stringify(input),
    }))
    expect(response.status).toBe(503)
    expect(await response.json()).toEqual({ error: '服务端尚未配置此默认图片服务' })
  })
})
