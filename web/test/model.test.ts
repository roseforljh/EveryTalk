import { describe, expect, test } from 'bun:test'
import { imageProtocol, imageRequest, modalImageDimensions, modelProtocol, modelRequest, resolveModelEndpoint } from '../src/model'

describe('安卓模型端点解析兼容性', () => {
  test('OpenAI 兼容地址保留版本路径和查询参数', () => {
    expect(resolveModelEndpoint('openai', 'https://proxy.example.com/v1?tenant=demo', 'm')).toBe('https://proxy.example.com/v1/chat/completions?tenant=demo')
    expect(resolveModelEndpoint('openai', 'https://proxy.example.com/chat/completions', 'm')).toBe('https://proxy.example.com/v1/chat/completions')
  })
  test('Anthropic 和 Gemini 使用安卓端点规则', () => {
    expect(resolveModelEndpoint('anthropic', 'https://proxy.example.com/v1/messages', 'claude')).toBe('https://proxy.example.com/v1/messages')
    expect(resolveModelEndpoint('gemini', 'https://generativelanguage.googleapis.com', 'gemini-2.5-flash')).toBe('https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse')
  })

  test('旧模型配置缺少协议覆盖值时按供应商识别协议', () => {
    expect(modelProtocol({ provider: 'Google', model: 'gemini-2.5-flash' })).toBe('gemini')
    expect(modelProtocol({ provider: 'Claude', model: 'claude-3-7-sonnet' })).toBe('anthropic')
    expect(modelProtocol({ address: 'https://generativelanguage.googleapis.com', model: 'vision' })).toBe('gemini')
  })

  test('图片附件按三种协议放入最后一条用户消息', () => {
    const config = { address: 'https://proxy.example.com/v1', model: 'vision-model', apiKey: 'secret' }
    const messages = [{ id: '80000000-0000-4000-8000-000000000001', entity: 'messages' as const, key: 'messages:80000000-0000-4000-8000-000000000001', deleted: false, payload: { sender: 'User', text: '看看这张图' } }]
    const attachment = { mime: 'image/png', dataUrl: 'data:image/png;base64,AQID' }
    const openai = modelRequest(config, messages, '', [attachment])
    expect(openai.body.messages).toEqual([{ role: 'user', content: [{ type: 'text', text: '看看这张图' }, { type: 'image_url', image_url: { url: attachment.dataUrl } }] }])
    const anthropic = modelRequest({ ...config, provider: 'Anthropic' }, messages, '', [attachment])
    expect(anthropic.body.messages).toEqual([{ role: 'user', content: [{ type: 'text', text: '看看这张图' }, { type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'AQID' } }] }])
    const gemini = modelRequest({ ...config, provider: 'Gemini' }, messages, '', [attachment])
    expect(gemini.body.contents).toEqual([{ role: 'user', parts: [{ text: '看看这张图' }, { inline_data: { mime_type: 'image/png', data: 'AQID' } }] }])
  })

  test('图片生成请求沿用 Android 的 Gemini 和 OpenAI 地址合同', () => {
    const gemini = imageRequest({ address: 'https://generativelanguage.googleapis.com', model: 'gemini-2.5-flash-image', apiKey: 'secret', provider: 'Google' }, '画一只猫')
    expect(gemini.target).toBe('https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-image:generateContent')
    expect(gemini.body.generationConfig).toEqual({ responseModalities: ['IMAGE', 'TEXT'] })
    const openai = imageRequest({ address: 'https://api.example.com/v1', model: 'gpt-image-1', apiKey: 'secret' }, '画一只猫')
    expect(openai.target).toBe('https://api.example.com/v1/images/generations')
    // 安卓 GPT_IMAGE 家族未选比例时发送 size=auto。
    expect(openai.body).toEqual({ model: 'gpt-image-1', prompt: '画一只猫', size: 'auto' })
  })

  test('图片生成请求保留 Gemini 3 尺寸和宽高比参数', () => {
    const request = imageRequest({
      address: 'https://generativelanguage.googleapis.com', model: 'gemini-3-pro-image-preview', apiKey: 'secret', provider: 'Google',
      aspectRatio: '16:9', imageSize: '2K',
    }, '画一张横幅')
    expect(request.body.generationConfig).toEqual({
      responseModalities: ['IMAGE', 'TEXT'],
      imageConfig: { aspectRatio: '16:9', imageSize: '2K' },
    })
  })

  test('图片生成请求兼容 Seedream、Qwen 和 Modal 参数合同', () => {
    const attachment = { mime: 'image/png', dataUrl: 'data:image/png;base64,AQID' }
    const seedream = imageRequest({
      address: 'https://api.example.com/v1', model: 'doubao-seedream-4.0', apiKey: 'secret', provider: 'Seedream',
      imageSize: '2048x2048', batchSize: 2, quality: 'high', watermark: true,
    }, '改成水彩画', [attachment])
    expect(seedream.protocol).toBe('openai')
    // 安卓 Seedream 带参考图仍走 generations，不发送 n/quality。
    expect(seedream.target).toBe('https://api.example.com/v1/images/generations')
    expect(seedream.body).toEqual({
      model: 'doubao-seedream-4.0', prompt: '改成水彩画', size: '2048x2048',
      response_format: 'url', watermark: true, image: [attachment.dataUrl],
    })
    const seedreamRatio = imageRequest({ model: 'doubao-seedream-4.5', apiKey: 'secret', provider: 'Seedream', modelParameters: { imageAspectRatio: '16:9' } }, '海报', [attachment, attachment])
    expect(seedreamRatio.target).toBe('https://ark.cn-beijing.volces.com/api/v3/images/generations')
    expect(seedreamRatio.body).toMatchObject({ size: '2560x1440', image: [attachment.dataUrl, attachment.dataUrl] })

    const qwen = imageRequest({
      address: 'https://qwen.example.modal.run/generate', model: 'qwen-image-edit', apiKey: 'secret', provider: 'Qwen',
      numInferenceSteps: 20, guidanceScale: 4,
    }, '去掉背景', [attachment])
    expect(imageProtocol({ address: 'https://qwen.example.modal.run/generate', model: 'qwen-image-edit' })).toBe('qwen')
    expect(imageProtocol({ channel: 'OpenAI兼容', model: 'qwen-image-edit' })).toBe('openai')
    expect(qwen.body).toEqual({ image_base64: 'AQID', prompt: '去掉背景', steps: 20, guidance_scale: 4 })

    const modal = imageRequest({
      address: 'https://example.modal.run/generate', model: 'z-image-turbo', provider: 'Modal',
      imageSize: '768x1024', numInferenceSteps: 8, guidanceScale: 1.5,
    }, '生成海报')
    expect(imageProtocol({ address: 'https://example.modal.run/generate', model: 'z-image-turbo' })).toBe('modal')
    expect(modal.body).toEqual({ prompt: '生成海报', imageSize: '768x1024', numInferenceSteps: 8 })
  })

  test('OpenAI 图片参考图切换到 images/edits multipart 合同', () => {
    const request = imageRequest({ address: 'https://api.openai.com/v1', model: 'gpt-image-1', apiKey: 'key', provider: 'OpenAI' }, '修改这张图', [{ mime: 'image/png', dataUrl: 'data:image/png;base64,AA==' }])
    expect(request.target).toBe('https://api.openai.com/v1/images/edits')
    expect(request.body.image).toBe('data:image/png;base64,AA==')
    expect(request.body.prompt).toBe('修改这张图')
  })

  test('Modal 尺寸换算与 Android 的宽高比规则一致', () => {
    expect(modalImageDimensions('2K', '16:9')).toEqual({ width: 2048, height: 1152 })
    expect(modalImageDimensions('HD', 'HD 4:3')).toEqual({ width: 1024, height: 1024 })
    expect(modalImageDimensions('768x1024', '')).toEqual({ width: 768, height: 1024 })
  })
})
