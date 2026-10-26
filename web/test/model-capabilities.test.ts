import { describe, expect, test } from 'bun:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { ModelParametersFields } from '../src/ModelParametersFields'
import { findCatalogModel, imageParameterKeys, modelCapabilities, normalizeModalities, parseModelsDevCatalog, withCatalogModel, withModelId } from '../src/model-capabilities'
import { generate, imageProtocol, imageRequest, modelProtocol, modelRequest } from '../src/model'
import type { JsonObject } from '../src/types'

const config = { address: 'https://api.example/v1', apiKey: 'test-key', model: 'model-a' }

describe('models.dev 能力与参数', () => {
  test('解析原始目录和 MIME 模态，保留未知模型能力', () => {
    const catalog = parseModelsDevCatalog({ vendor: { models: { 'model-a': { modalities: { input: ['text', 'image/png', 'audio/wav', 'application/pdf'], output: ['text'] }, reasoning: false, temperature: false, tool_call: true, limit: { context: 32000, output: 8000 }, reasoning_options: [{ type: 'effort', values: ['low', 'high'] }] } } } })
    expect(catalog).toHaveLength(1)
    expect(catalog[0]).toMatchObject({ input: ['text', 'image', 'audio', 'pdf'], output: ['text'], reasoning: false, temperature: false, context: 32000, maxOutput: 8000, efforts: ['low', 'high'] })
    expect(normalizeModalities(['video/mp4', 'video', 'decision'])).toEqual(['video', 'decision'])
    const manual = withCatalogModel({ ...config, modelParameters: { inputModalities: ['text'], outputModalities: ['image'] } }, catalog[0])
    expect(modelCapabilities(manual)).toMatchObject({ input: ['text'], output: ['image'], reasoning: false })
    expect(findCatalogModel(catalog, 'vendor/model-a')?.id).toBe('model-a')
    const unknown = withModelId(manual, 'unknown-model', catalog)
    expect(unknown.modelParameters).toMatchObject({ resolvedCapability: null, inputModalities: ['text'], outputModalities: ['image'] })
    expect(modelCapabilities(unknown).reasoning).toBeUndefined()
  })

  test('手动协议优先于模型名，禁用能力不发送不支持的参数', () => {
    const openai = modelRequest({ ...config, model: 'claude-test', temperature: 0.7, topP: 0.9, modelParameters: { protocolOverride: 'OPENAI_COMPATIBLE', resolvedCapability: { supportsReasoning: false, supportsTemperature: false }, reasoningEffort: 'high' } }, [], '')
    expect(openai.protocol).toBe('openai')
    expect(openai.body).not.toHaveProperty('temperature')
    expect(openai.body).not.toHaveProperty('top_p')
    expect(openai.body).not.toHaveProperty('reasoning_effort')
    const synced = modelRequest({ ...config, modelParameters: { customParameters: [{ name: 'reasoning_effort', value: 'high', enabled: true }], reasoningEffort: 'medium' } }, [], '')
    expect(synced.body.reasoning_effort).toBe('high')
    expect(modelProtocol({ ...config, modelParameters: { protocolOverride: 'CODEX' } })).toBe('responses')
    expect(() => modelRequest({ ...config, modelParameters: { inputModalities: ['text'] } }, [], '', [{ mime: 'image/png', dataUrl: 'data:image/png;base64,AQID' }])).toThrow('图片输入')
  })

  test('Responses、Anthropic、Gemini 的思考与输出参数分别编码', () => {
    const responses = modelRequest({ ...config, outputLimit: 1024, modelParameters: { protocolOverride: 'CODEX', reasoningEffort: 'high' } }, [], 'system')
    expect(responses.target).toBe('https://api.example/v1/responses')
    expect(responses.body).toMatchObject({ max_output_tokens: 1024, reasoning: { effort: 'high', summary: 'auto' }, instructions: 'system', input: [] })
    expect(responses.body).not.toHaveProperty('messages')
    // 安卓 AnthropicDirectClient：程度模式走 adaptive，预算模式才发 budget_tokens。
    const anthropic = modelRequest({ ...config, modelParameters: { protocolOverride: 'ANTHROPIC', reasoningEffort: 'high', thoughtBudget: 2048 } }, [], '')
    expect(anthropic.body).toMatchObject({ max_tokens: 4096, thinking: { type: 'adaptive' }, output_config: { effort: 'high' } })
    const budget = modelRequest({ ...config, modelParameters: { protocolOverride: 'ANTHROPIC', reasoningMode: 'BUDGET', thoughtBudget: 2048 } }, [], '')
    expect(budget.body.thinking).toEqual({ type: 'enabled', budget_tokens: 2048 })
    const gemini = modelRequest({ ...config, model: 'gemini-3-pro', modelParameters: { protocolOverride: 'GEMINI', reasoningEffort: 'high' } }, [], '')
    expect(gemini.body.generationConfig).toEqual({ maxOutputTokens: 4096, thinkingConfig: { includeThoughts: true, thinkingLevel: 'high' } })
    const legacyGemini = modelRequest({ ...config, model: 'gemini-2.5-flash', modelParameters: { protocolOverride: 'GEMINI', reasoningEffort: 'high' } }, [], '')
    expect(legacyGemini.body.generationConfig).toEqual({ maxOutputTokens: 4096, thinkingConfig: { includeThoughts: true, thinkingBudget: 24576 } })
  })

  test('OpenAI 兼容接口按安卓规则发送输出上限和自定义参数', () => {
    const fallback = modelRequest(config, [], '')
    expect(fallback.body).toMatchObject({ max_completion_tokens: 4096, reasoning_effort: 'medium', stream_options: { include_usage: true } })
    expect(modelRequest({ ...config, address: 'https://api.deepseek.com' }, [], '').body).toHaveProperty('max_tokens', 4096)
    const custom = modelRequest({ ...config, modelParameters: { customParameters: [
      { name: 'reasoning_effort', value: 'high', type: 'STRING', enabled: true }, { name: 'seed', value: '7', type: 'NUMBER', enabled: true },
      { name: 'enable_thinking', value: 'TRUE', type: 'BOOLEAN', enabled: true }, { name: 'extra', value: '{"a":1}', type: 'JSON', enabled: true },
      { name: 'off', value: 'x', type: 'STRING', enabled: false },
    ] } }, [], '')
    expect(custom.body).toMatchObject({ reasoning_effort: 'high', seed: 7, enable_thinking: true, extra: { a: 1 } })
    expect(custom.body).not.toHaveProperty('off')
    expect(() => modelRequest({ ...config, modelParameters: { customParameters: [{ name: 'max_tokens', value: '1', type: 'NUMBER', enabled: true }] } }, [], '')).toThrow('由应用管理')
  })

  test('所有文本接口忽略历史采样覆盖并使用模型接口默认值', () => {
    for (const protocolOverride of ['OPENAI_COMPATIBLE', 'CODEX', 'ANTHROPIC', 'GEMINI']) {
      const request = modelRequest({ ...config, outputLimit: 2048, temperature: 1.8, topP: 0.2,
        modelParameters: { protocolOverride, resolvedCapability: { supportsTemperature: true }, customParameters: [{ name: 'temperature', value: '1.9', enabled: true }, { name: 'top_p', value: '0.1', enabled: true }] },
      }, [], '')
      for (const body of [request.body, request.body.generationConfig ?? {}]) {
        expect(body).not.toHaveProperty('temperature')
        expect(body).not.toHaveProperty('top_p')
        expect(body).not.toHaveProperty('topP')
      }
    }
  })

  test('能力编辑仅提供源码中的四类且不提供采样设置', () => {
    const html = renderToStaticMarkup(createElement(ModelParametersFields, { config: { ...config, temperature: 1.8, topP: 0.2 }, catalog: [], loading: false, error: '', onChange: () => {}, onRefresh: () => {} }))
    for (const label of ['文本', '图片', '视频', '音频']) expect(html.match(new RegExp(`</svg>${label}</button>`, 'g'))).toHaveLength(2)
    expect(html).not.toContain('PDF')
    expect(html).not.toContain('温度')
    expect(html).not.toContain('Top P')
    expect(html).toContain('最大输出 tokens')
  })

  test('图片参数随接口切换，保留安卓同步的图片设置', () => {
    expect(imageParameterKeys('qwen')).toEqual(['inferenceSteps', 'guidanceScale'])
    const gemini = imageRequest({ ...config, model: 'gemini-3-pro-image', imageSize: '2K', modelParameters: { imageApiFormat: 'gemini', imageAspectRatio: '16:9' } }, '画图')
    expect((gemini.body.generationConfig as JsonObject).imageConfig).toEqual({ imageSize: '2K', aspectRatio: '16:9' })
    const openai = imageRequest({ ...config, model: 'gemini-proxy-image', channel: 'OpenAI兼容', modelParameters: { imageApiFormat: 'gemini', imageQuality: 'high', imageBatchSize: 2 } }, '画图')
    expect(openai.protocol).toBe('openai')
    expect(openai.body).toMatchObject({ quality: 'high', n: 2 })
    const hidden = imageRequest({ ...config, inferenceSteps: 30, guidanceScale: 7.5, modelParameters: { imageApiFormat: 'openai' } }, '画图')
    expect(hidden.body).not.toHaveProperty('num_inference_steps')
    expect(hidden.body).not.toHaveProperty('guidance_scale')
  })

  test('参数界面只显示当前能力与格式对应的控件，并带本地图标', () => {
    const html = renderToStaticMarkup(createElement(ModelParametersFields, { config: { ...config, address: 'https://qwen.example.modal.run', model: 'qwen-image-edit', imageConfig: true }, catalog: [], loading: false, error: '', onChange: () => {}, onRefresh: () => {} }))
    expect(html).toContain('/model-icons/qwen.svg')
    expect(html).toContain('推理步数')
    expect(html).not.toContain('最大输出 tokens')
    expect(html).not.toContain('图片质量')
    expect(html).toContain('aria-pressed="true"')
  })

  test('思考设置常驻且图片协议由同一个 API 配置决定', () => {
    const html = renderToStaticMarkup(createElement(ModelParametersFields, { config: { ...config, modelParameters: { supportsReasoning: false, resolvedCapability: { supportsReasoning: false } } }, catalog: [], loading: false, error: '', onChange: () => {}, onRefresh: () => {} }))
    expect(html).toContain('思考程度')
    expect(html).not.toContain('思考能力')
    expect(html).not.toContain('图片接口')
    const native = { ...config, model: 'image-model', channel: 'Gemini', imageConfig: true, modelParameters: { imageApiFormat: 'openai' } }
    expect(imageProtocol(native)).toBe('gemini')
    expect(imageProtocol({ ...native, channel: 'OpenAI兼容' })).toBe('openai')
    expect(imageProtocol({ ...native, channel: 'Anthropic' })).toBe('unsupported')
    expect(() => imageRequest({ ...native, channel: 'Anthropic' }, '画图')).toThrow('未接入图片生成')
    const nativeHtml = renderToStaticMarkup(createElement(ModelParametersFields, { config: native, catalog: [], loading: false, error: '', onChange: () => {}, onRefresh: () => {} }))
    expect(nativeHtml).toContain('<option value="2K">2K</option>')
    expect(nativeHtml).toContain('<option value="16:9">16:9</option>')
    expect(nativeHtml).not.toContain('图片质量')
    const request = imageRequest({ ...config, address: 'https://proxy.example/gateway/v1/chat/completions?tenant=demo', channel: 'OpenAI兼容', model: 'gemini-proxy-image' }, '画图')
    expect(request.target).toBe('https://proxy.example/gateway/v1/images/generations?tenant=demo')
  })

  test('Responses 流在 completed 后正确结束，不把完整结果当中断丢弃', async () => {
    const previous = globalThis.fetch
    globalThis.fetch = Object.assign(async () => new Response('data: {"type":"response.output_text.delta","delta":"完成"}\n\ndata: {"type":"response.completed"}\n\n', { headers: { 'Content-Type': 'text/event-stream' } }), { preconnect: previous.preconnect })
    try {
      const result = await generate({ access_token: 'token' } as Parameters<typeof generate>[0], modelRequest({ ...config, modelParameters: { protocolOverride: 'CODEX' } }, [], ''), new AbortController().signal, () => {})
      expect(result.text).toBe('完成')
    } finally { globalThis.fetch = previous }
  })
})
