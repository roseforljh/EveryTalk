'use client'

import { Image, FileText, Headphones, Video, ArrowDownLeft, ArrowUpRight, RefreshCw } from 'lucide-react'
import { imageProtocol } from './model'
import { ModelIcon } from './ModelIcon'
import { findCatalogModel, imageParameterKeys, modalityLabels, modelCapabilities, parameterProtocol, withCatalogModel, type CatalogModel } from './model-capabilities'
import { isObject } from './sync-core'
import type { JsonObject } from './types'

const modalityIcons = { text: FileText, image: Image, audio: Headphones, video: Video }
const imageFields: Record<string, { label: string; placeholder: string; numeric?: boolean }> = {
  imageSize: { label: '图片尺寸', placeholder: '1024x1024 / 1K / 2K' }, aspectRatio: { label: '宽高比', placeholder: '1:1 / 16:9 / 9:16' },
  inferenceSteps: { label: '推理步数', placeholder: '30', numeric: true }, guidanceScale: { label: '引导系数', placeholder: '7.5', numeric: true },
  quality: { label: '图片质量', placeholder: 'auto / low / medium / high' }, batchSize: { label: '生成数量', placeholder: '1', numeric: true },
}

/** 能力与接口格式分区展示；隐藏参数保留草稿，最终只由请求适配器发送对应字段。 */
export function ModelParametersFields({ config, onChange, catalog, loading, error, onRefresh }: {
  config: JsonObject; onChange: (config: JsonObject) => void; catalog: CatalogModel[]; loading: boolean; error: string; onRefresh: () => void
}) {
  const parameters = isObject(config.modelParameters) ? config.modelParameters : {}
  const capability = modelCapabilities(config)
  const protocol = parameterProtocol(config)
  const format = imageProtocol(config)
  const matched = findCatalogModel(catalog, String(config.model ?? ''), String(config.provider ?? ''))
  const imageOutput = capability.output.includes('image')
  const textOutput = capability.output.includes('text')
  const customParameters = Array.isArray(parameters.customParameters) ? parameters.customParameters : []
  const customEffort = customParameters.find(item => isObject(item) && item.name === 'reasoning_effort')
  const effort = protocol === 'OPENAI_COMPATIBLE' && isObject(customEffort) ? customEffort.enabled === false ? 'none' : String(customEffort.value) : String(parameters.reasoningMode === 'DISABLED' ? 'none' : parameters.reasoningEffort ?? 'medium')
  const patch = (values: JsonObject): void => onChange({ ...config, ...values })
  const patchParameters = (values: JsonObject): void => patch({ modelParameters: { ...parameters, ...values } })
  const imageParameterNames: Record<string, string> = { aspectRatio: 'imageAspectRatio', quality: 'imageQuality', batchSize: 'imageBatchSize' }
  const ranges: Record<string, [number, number, number | 'any']> = { outputLimit: [1, 9999999, 1], inferenceSteps: [1, 1000, 1], guidanceScale: [0, 100, 'any'], batchSize: [1, 10, 1] }
  const field = (key: string, label: string, placeholder: string, numeric = true) => <label key={key}>{label}<input type={numeric ? 'number' : 'text'} min={ranges[key]?.[0]} max={ranges[key]?.[1]} step={ranges[key]?.[2]} value={String(parameters[imageParameterNames[key]] ?? config[key] ?? '')} placeholder={placeholder} onChange={event => {
    const value = numeric ? event.target.value === '' ? null : Number(event.target.value) : event.target.value
    patch({ [key]: value, ...(imageParameterNames[key] ? { modelParameters: { ...parameters, [imageParameterNames[key]]: value } } : {}) })
  }} /></label>
  const imageChoice = (key: string, label: string, options: string[]) => <label key={key}>{label}<select value={String(parameters[imageParameterNames[key]] ?? config[key] ?? '')} onChange={event => {
    const value = event.target.value || null
    patch({ [key]: value, ...(imageParameterNames[key] ? { modelParameters: { ...parameters, [imageParameterNames[key]]: value } } : {}) })
  }}><option value="">默认</option>{options.map(value => <option value={value} key={value}>{value}</option>)}</select></label>
  return <>
    <section className="et-model-capabilities">
      <div className="et-model-summary"><ModelIcon model={String(config.model ?? '')} /><div><strong>模型能力</strong><small>{matched ? 'models.dev 标准 · 可手动调整' : '自定义能力 · 可从目录匹配'}</small></div><button type="button" className="et-outline" disabled={loading} onClick={onRefresh} aria-label="刷新 models.dev 模型目录"><RefreshCw size={14} />{loading ? '加载中' : '刷新目录'}</button></div>
      {error && <p role="status" className="et-muted">{error}</p>}
      {matched && <button type="button" className="et-catalog-apply" onClick={() => onChange(withCatalogModel({ ...config, modelParameters: { ...parameters, inputModalities: null, outputModalities: null, supportsReasoning: null } }, matched))}>使用 {matched.name} 的目录能力与限制</button>}
      {(['input', 'output'] as const).map(direction => <div className="et-capability-line" key={direction}><span>{direction === 'input' ? <ArrowDownLeft size={15} /> : <ArrowUpRight size={15} />}{direction === 'input' ? '输入' : '输出'}</span><div className="et-capability-options">{Object.entries(modalityLabels).map(([id, label]) => {
        const Icon = modalityIcons[id as keyof typeof modalityIcons]
        const selected = capability[direction].includes(id)
        return <button key={id} type="button" data-modality={id} aria-pressed={selected} onClick={() => {
          const values = selected ? capability[direction].filter(value => value !== id) : [...capability[direction], id]
          if (!values.length) return
          patch({ ...(direction === 'output' ? { imageConfig: values.includes('image'), modality: values.includes('image') ? 'IMAGE' : 'TEXT' } : {}), modelParameters: { ...parameters, [`${direction}Modalities`]: values } })
        }}><Icon size={14} />{label}</button>
      })}</div></div>)}
      <p className="et-muted">输入代表模型可理解的内容，输出代表可生成的内容。切换能力会显示相应参数。</p>
    </section>
    <section className="et-parameter-section"><h4>API 配置</h4><label>API 协议<select value={protocol} onChange={event => patchParameters({ protocolOverride: event.target.value })}><option value="OPENAI_COMPATIBLE">OpenAI · Chat Completions</option><option value="CODEX">OpenAI · Responses</option><option value="ANTHROPIC">Anthropic · Messages</option><option value="GEMINI">Google · Generate Content</option></select></label>
    </section>
    {textOutput && <section className="et-parameter-section"><h4>文本生成</h4><div className="et-parameter-grid">
      {field('outputLimit', '最大输出 tokens', '4096')}
      <label>上下文窗口 tokens<input type="number" min="2" max="10000000" value={String(parameters.contextLimit ?? 128000)} onChange={event => patchParameters({ contextLimit: Number(event.target.value) })} /></label>
      <label>思考程度<select value={effort} onChange={event => {
        const value = event.target.value
        // 与安卓兼容接口的自定义参数同步更新，只替换思考字段，保留其他参数。
        const next = customParameters.filter(item => !isObject(item) || item.name !== 'reasoning_effort')
        patchParameters({ reasoningEffort: value, reasoningMode: value === 'none' ? 'DISABLED' : 'EFFORT', ...(protocol === 'OPENAI_COMPATIBLE' ? { customParameters: [...next, { name: 'reasoning_effort', value, type: 'STRING', enabled: true }] } : {}) })
      }}>{(protocol === 'ANTHROPIC' ? ['none', 'low', 'medium', 'high', 'max'] : protocol === 'GEMINI' ? ['none', 'minimal', 'low', 'medium', 'high'] : ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max']).concat(effort).filter((value, index, values) => values.indexOf(value) === index).map(value => <option key={value} value={value}>{value}</option>)}</select></label>
      {['ANTHROPIC', 'GEMINI'].includes(protocol) && <label>思考预算 tokens<input type="number" min="1024" max="10000000" step="1" value={String(parameters.thoughtBudget ?? 2048)} onChange={event => patchParameters({ thoughtBudget: Number(event.target.value), reasoningMode: 'BUDGET' })} /></label>}
    </div></section>}
    {imageOutput && <section className="et-parameter-section"><h4>图片生成 <small>{format === 'qwen' ? '需上传参考图' : ''}</small></h4>{format === 'unsupported' && <p>当前 API 协议未接入图片生成，请在 API 配置中选择支持图片生成的接口。</p>}<div className="et-parameter-grid">{imageParameterKeys(format).map(key => {
      const item = imageFields[key]
      if (format !== 'openai' && key === 'imageSize') return imageChoice(key, item.label, format === 'gemini' ? ['1K', '2K', '4K'] : ['HD', '2K'])
      if (format !== 'openai' && key === 'aspectRatio') return imageChoice(key, item.label, format === 'modal' ? ['1:1', '16:9', '9:16', ...(String(config.imageSize).toUpperCase() === '2K' ? ['4:3'] : [])] : ['1:1', '2:3', '3:2', '3:4', '4:3', '4:5', '5:4', '9:16', '16:9', '21:9'])
      return field(key, item.label, item.placeholder, item.numeric ?? false)
    })}</div></section>}
    {capability.input.includes('audio') && <p className="et-parameter-note">音频输入能力已记录。聊天附件仍受当前 API 支持的上传格式限制。</p>}
    {capability.input.includes('video') && <p className="et-parameter-note">视频输入能力已记录。当前聊天适配器暂不发送视频文件。</p>}
    {capability.output.some(item => item === 'audio' || item === 'video') && <p role="status" className="et-parameter-note">音频／视频输出能力可以保存；当前聊天尚未接入这些生成接口，因此没有可用的生成参数。</p>}
  </>
}
